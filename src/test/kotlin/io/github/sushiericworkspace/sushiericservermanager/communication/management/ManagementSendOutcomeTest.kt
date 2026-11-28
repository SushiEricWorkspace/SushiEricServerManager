package io.github.sushiericworkspace.sushiericservermanager.communication.management

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericservermanager.communication.database.postgresReference
import io.github.sushiericworkspace.sushiericservermanager.editor.shop.ManagementShopTransport
import io.github.sushiericworkspace.sushiericservermanager.editor.shop.ShopSaveSession
import io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory.ManagementHistorySource
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.*
import kotlin.test.*

class ManagementSendOutcomeTest {
    @TempDir lateinit var root: Path
    private val reference = postgresReference()
    private val binding = managementBinding(reference.status.state.binding.worldUuid).copy(database = reference.status.state.binding)
    private val edit = HostAdapterAction.Edit(listOf(HostProductChange.Save(
        "tab.apple", null, HostDefinition("item", 10), HostStockEdit.Register(1, null))))
    private enum class Outcome { ACCEPT, FALSE, THROW }

    private class Wire(val binding: ManagementDatabaseBinding) : ManagementTransport {
        lateinit var listener: ServerManagementListener
        val sent = CopyOnWriteArrayList<ServerManagementRequest>()
        var outcome = Outcome.ACCEPT
        var replyStart = true
        var beforeDatabase: () -> Unit = {}
        override fun connect(listener: ServerManagementListener, timeout: Duration): Int {
            this.listener = listener
            return 25580
        }
        override fun disconnect() = Unit
        override fun send(request: ServerManagementRequest): Boolean {
            sent += request
            when (request) {
                is ServerManagementRequest.DatabaseContextRequest ->
                    listener.onMessage(ServerManagementResponse.DatabaseContext(true, binding, nonce = request.nonce))
                is ServerManagementRequest.DatabaseHistoryEnd ->
                    listener.onMessage(ServerManagementResponse.DatabaseHistory(true, request.handle, nonce = request.nonce))
                is ServerManagementRequest.DatabaseHistoryStart, is ServerManagementRequest.DatabaseProductRequest -> {
                    beforeDatabase()
                    when (outcome) {
                        Outcome.FALSE -> return false
                        Outcome.THROW -> throw IllegalStateException("送信受付結果不明")
                        Outcome.ACCEPT -> if (request !is ServerManagementRequest.DatabaseHistoryStart || replyStart) reply(request)
                    }
                }
                else -> error("未到達")
            }
            return true
        }
        fun reply(request: ServerManagementRequest) {
            when (request) {
                is ServerManagementRequest.DatabaseHistoryStart ->
                    listener.onMessage(ServerManagementResponse.DatabaseHistory(true, "handle", nonce = request.nonce))
                is ServerManagementRequest.DatabaseProductRequest ->
                    listener.onMessage(ServerManagementResponse.DatabaseProductResult(HostAdapterResponse(
                        requestId = request.request.requestId, status = HostAdapterStatus.SUCCESS,
                        result = HostAdapterResult.Edited(emptyList())), request.nonce))
                else -> error("未到達")
            }
        }
    }

    private fun product(nonce: String): ServerManagementRequest.DatabaseProductRequest =
        ServerManagementRequest.DatabaseProductRequest(binding, HostAdapterRequest(requestId = UUID.randomUUID().toString(),
            worldUuid = binding.worldUuid, action = edit), nonce)

    @Test fun falseRetiresReplyButKeepsNonceForHistoryAndProduct() {
        val client = ServerManagementClient()
        val wire = Wire(binding).apply { outcome = Outcome.FALSE }
        val received = CopyOnWriteArrayList<ServerManagementResponse>()
        client.addMessageListener { received += it }
        assertTrue(client.connect(wire))
        try {
            for (request in listOf(ServerManagementRequest.DatabaseHistoryStart(binding, nonce = "history"), product("product"))) {
                assertFalse(client.send(request))
                wire.reply(request)
                assertTrue(received.isEmpty())
                val count = wire.sent.size
                assertFalse(client.send(request))
                assertEquals(count, wire.sent.size)
            }
        } finally { client.disconnect() }
    }

    @Test fun exceptionKeepsReplyAndNonceForHistoryAndProduct() {
        val client = ServerManagementClient()
        val wire = Wire(binding).apply { outcome = Outcome.THROW }
        val received = CopyOnWriteArrayList<ServerManagementResponse>()
        client.addMessageListener { received += it }
        assertTrue(client.connect(wire))
        try {
            for (request in listOf(ServerManagementRequest.DatabaseHistoryStart(binding, nonce = "history"), product("product"))) {
                assertFailsWith<IllegalStateException> { client.send(request) }
                val count = received.size
                wire.reply(request)
                assertEquals(count + 1, received.size)
                wire.reply(request)
                assertEquals(count + 1, received.size)
                assertFalse(client.send(request))
            }
        } finally { client.disconnect() }
    }

    @Test fun falseStartHasNoDelayedCleanupAndDoesNotBlockNextSearch() {
        val client = ServerManagementClient()
        val wire = Wire(binding).apply { outcome = Outcome.FALSE }
        assertTrue(client.connect(wire))
        val source = ManagementHistorySource(client, 3_000, reference)
        try {
            assertFailsWith<IllegalStateException> { source.open() }
            assertNull(client.historyCleanup)
            wire.reply(wire.sent.filterIsInstance<ServerManagementRequest.DatabaseHistoryStart>().single())
            assertTrue(wire.sent.none { it is ServerManagementRequest.DatabaseHistoryEnd })
            wire.outcome = Outcome.ACCEPT
            source.open().close()
            assertEquals(2, wire.sent.filterIsInstance<ServerManagementRequest.DatabaseHistoryStart>().size)
            assertEquals(1, wire.sent.filterIsInstance<ServerManagementRequest.DatabaseHistoryEnd>().size)
        } finally { client.disconnect() }
    }

    @Test fun thrownStartFailsCallerButClosesDelayedHandleBeforeNextSearch() {
        val client = ServerManagementClient()
        val wire = Wire(binding).apply { outcome = Outcome.THROW }
        assertTrue(client.connect(wire))
        val source = ManagementHistorySource(client, 3_000, reference)
        try {
            assertFailsWith<IllegalStateException> { source.open() }
            val cleanup = assertNotNull(client.historyCleanup).second
            assertFalse(cleanup.isDone)
            wire.reply(wire.sent.filterIsInstance<ServerManagementRequest.DatabaseHistoryStart>().single())
            cleanup.get(5, TimeUnit.SECONDS)
            assertEquals("handle", wire.sent.filterIsInstance<ServerManagementRequest.DatabaseHistoryEnd>().single().handle)
            wire.outcome = Outcome.ACCEPT
            source.open().close()
            assertEquals(2, wire.sent.filterIsInstance<ServerManagementRequest.DatabaseHistoryStart>().size)
            assertEquals(2, wire.sent.filterIsInstance<ServerManagementRequest.DatabaseHistoryEnd>().size)
        } finally { client.disconnect() }
    }

    @Test fun productFailureKeepsOriginalPendingAndOnlyUnknownAcceptanceReceivesLateReply() {
        for (outcome in listOf(Outcome.FALSE, Outcome.THROW)) {
            val client = ServerManagementClient()
            val wire = Wire(binding).apply { this.outcome = outcome }
            val received = CopyOnWriteArrayList<ServerManagementResponse.DatabaseProductResult>()
            client.addMessageListener { if (it is ServerManagementResponse.DatabaseProductResult) received += it }
            assertTrue(client.connect(wire))
            val file = root.resolve("$outcome.json")
            val session = ShopSaveSession(ManagementShopTransport(client, reference = reference), file)
            try {
                val result = session.execute(edit)
                assertEquals(HostAdapterStatus.UNKNOWN, result.status)
                val pending = assertNotNull(session.pending())
                val bytes = Files.readAllBytes(file)
                val request = wire.sent.filterIsInstance<ServerManagementRequest.DatabaseProductRequest>().single()
                assertEquals(pending, request.request)
                wire.reply(request)
                assertEquals(if (outcome == Outcome.THROW) 1 else 0, received.size)
                assertContentEquals(bytes, Files.readAllBytes(file))
                assertEquals(pending, session.pending())
                assertEquals(1, wire.sent.filterIsInstance<ServerManagementRequest.DatabaseProductRequest>().size)
            } finally { session.close(); client.disconnect() }
        }
    }

    @Test fun oldFalseCompletionCannotRemoveNewConnectionReply() {
        val client = ServerManagementClient()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val old = Wire(binding).apply {
            outcome = Outcome.FALSE
            beforeDatabase = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        }
        val next = Wire(binding).apply { replyStart = false }
        val received = CopyOnWriteArrayList<ServerManagementResponse>()
        client.addMessageListener { received += it }
        assertTrue(client.connect(old))
        val request = ServerManagementRequest.DatabaseHistoryStart(binding, nonce = "same")
        val result = FutureTask { client.send(request) }
        val worker = Thread(result, "false-completion-race")
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(client.connect(next))
            assertTrue(client.send(request))
            release.countDown()
            assertFalse(result.get(5, TimeUnit.SECONDS))
            next.reply(request)
            assertEquals(1, received.size)
        } finally {
            release.countDown()
            worker.join(5_000)
            client.disconnect()
            assertFalse(worker.isAlive)
        }
    }
}
