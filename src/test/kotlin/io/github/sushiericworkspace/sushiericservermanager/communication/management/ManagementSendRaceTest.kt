package io.github.sushiericworkspace.sushiericservermanager.communication.management

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericservermanager.communication.database.postgresReference
import io.github.sushiericworkspace.sushiericservermanager.editor.shop.ManagementShopTransport
import io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory.ManagementHistorySource
import java.time.Duration
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.locks.LockSupport
import kotlin.test.*

class ManagementSendRaceTest {
    private val reference = postgresReference()
    private val binding = managementBinding(reference.status.state.binding.worldUuid).copy(database = reference.status.state.binding)

    private class Wire(val binding: ManagementDatabaseBinding, val answerContext: Boolean = false) : ManagementTransport {
        lateinit var listener: ServerManagementListener
        val sent = CopyOnWriteArrayList<ServerManagementRequest>()
        val contextSent = CountDownLatch(1)
        lateinit var context: ServerManagementRequest.DatabaseContextRequest
        var beforeStart: () -> Unit = {}
        @Volatile var closed = false
        override fun connect(listener: ServerManagementListener, timeout: Duration): Int {
            this.listener = listener
            return 25580
        }
        override fun disconnect() { closed = true }
        override fun send(request: ServerManagementRequest): Boolean {
            check(!closed)
            if (request is ServerManagementRequest.DatabaseHistoryStart) beforeStart()
            sent += request
            if (request is ServerManagementRequest.DatabaseContextRequest) {
                context = request
                contextSent.countDown()
                if (answerContext) replyContext()
            }
            return true
        }
        fun replyContext() = listener.onMessage(ServerManagementResponse.DatabaseContext(true, binding, nonce = context.nonce))
    }

    private fun gate(client: ServerManagementClient): Any = client.javaClass.getDeclaredField("databaseNonces")
        .apply { isAccessible = true }.get(client)

    private fun awaitBlocked(thread: Thread, method: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.BLOCKED ||
            thread.stackTrace.none { it.className == ServerManagementClient::class.java.name && it.methodName.substringBefore('$') == method }) {
            check(thread.isAlive && System.nanoTime() < deadline) { "指定した送信/切断境界で待機しませんでした。" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1))
        }
    }

    @Test fun oldHistoryStartCannotCrossReconnect() {
        val client = ServerManagementClient()
        val old = Wire(binding)
        val next = Wire(binding)
        assertTrue(client.connect(old))
        val source = ManagementHistorySource(client, 3_000, reference)
        val result = FutureTask { source.open().use { } }
        val worker = Thread(result, "history-reconnect-race")
        worker.start()
        try {
            assertTrue(old.contextSent.await(5, TimeUnit.SECONDS))
            synchronized(gate(client)) {
                old.replyContext()
                awaitBlocked(worker, "send")
                assertTrue(client.connect(next))
            }
            assertFailsWith<ExecutionException> { result.get(5, TimeUnit.SECONDS) }
            assertTrue(old.sent.none { it is ServerManagementRequest.DatabaseHistoryStart })
            assertTrue(next.sent.none { it is ServerManagementRequest.DatabaseHistoryStart || it is ServerManagementRequest.DatabaseHistoryEnd })
        } finally {
            worker.interrupt()
            worker.join(5_000)
            client.disconnect()
            assertFalse(worker.isAlive)
        }
    }

    @Test fun oldProductCannotCrossReconnect() {
        val client = ServerManagementClient()
        val old = Wire(binding, answerContext = true)
        val next = Wire(binding)
        assertTrue(client.connect(old))
        val transport = ManagementShopTransport(client, reference = reference)
        assertEquals(binding.worldUuid, transport.worldUuid())
        val request = HostAdapterRequest(requestId = UUID.randomUUID().toString(), worldUuid = binding.worldUuid,
            action = HostAdapterAction.Edit(listOf(HostProductChange.Save("tab.apple", null, HostDefinition("item", 10),
                HostStockEdit.Register(1, null)))))
        val result = FutureTask { transport.execute(request) }
        val worker = Thread(result, "product-reconnect-race")
        try {
            synchronized(gate(client)) {
                worker.start()
                awaitBlocked(worker, "send")
                assertTrue(client.connect(next))
            }
            assertFailsWith<ExecutionException> { result.get(5, TimeUnit.SECONDS) }
            assertTrue(old.sent.none { it is ServerManagementRequest.DatabaseProductRequest })
            assertTrue(next.sent.none { it is ServerManagementRequest.DatabaseProductRequest })
        } finally {
            worker.interrupt()
            worker.join(5_000)
            client.disconnect()
            assertFalse(worker.isAlive)
        }
    }

    @Test fun blockedSendAllowsResponsesOtherSendsAndDisconnectOnOldTarget() {
        val client = ServerManagementClient()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = Wire(binding).apply {
            beforeStart = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        val next = Wire(binding)
        val received = CopyOnWriteArrayList<ServerManagementResponse>()
        client.addMessageListener { received += it }
        assertTrue(client.connect(old))
        val generation = client.connectionGeneration
        val sent = FutureTask { client.send(ServerManagementRequest.DatabaseHistoryStart(binding, nonce = "accepted"), generation) }
        val response = FutureTask {
            old.listener.onMessage(ServerManagementResponse.DatabaseHistory(true, "old-handle", nonce = "accepted"))
        }
        val parallel = FutureTask {
            assertTrue(client.send(ServerManagementRequest.Ping("parallel")))
            assertTrue(client.send(ServerManagementRequest.DatabaseContextRequest("parallel-db")))
        }
        val switched = FutureTask { client.connect(next) }
        val sender = Thread(sent, "accepted-send-race")
        val responder = Thread(response, "response-during-send")
        val other = Thread(parallel, "parallel-send")
        val switcher = Thread(switched, "disconnect-send-race")
        sender.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            responder.start()
            response.get(5, TimeUnit.SECONDS)
            assertEquals("old-handle", assertIs<ServerManagementResponse.DatabaseHistory>(received.single()).handle)
            other.start()
            parallel.get(5, TimeUnit.SECONDS)
            assertFalse(sent.isDone)
            switcher.start()
            assertTrue(switched.get(5, TimeUnit.SECONDS))
            assertTrue(old.closed)
            assertFalse(sent.isDone)
            assertTrue(next.sent.isEmpty())
            release.countDown()
            assertTrue(sent.get(5, TimeUnit.SECONDS))
            assertEquals(1, old.sent.filterIsInstance<ServerManagementRequest.DatabaseHistoryStart>().size)
            assertTrue(next.sent.isEmpty())
            assertTrue(client.send(ServerManagementRequest.DatabaseContextRequest("accepted")))
        } finally {
            release.countDown()
            for (thread in listOf(sender, responder, other, switcher)) {
                thread.join(5_000)
                assertFalse(thread.isAlive)
            }
            client.disconnect()
        }
    }
}
