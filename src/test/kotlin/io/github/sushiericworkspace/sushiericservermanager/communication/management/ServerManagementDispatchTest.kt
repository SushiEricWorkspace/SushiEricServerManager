package io.github.sushiericworkspace.sushiericservermanager.communication.management

import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerManagementDispatchTest {
    @Test fun `世代照合後に遅れた旧frameを再接続後の新listenerへ通知しない`() {
        val client = ServerManagementClient()
        val old = Transport()
        assertTrue(client.connect(old))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val callbacks = object : CopyOnWriteArrayList<(ServerManagementResponse) -> Unit>() {
            override fun iterator(): MutableIterator<(ServerManagementResponse) -> Unit> {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                return super.iterator()
            }
        }
        val field = ServerManagementClient::class.java.getDeclaredField("messageListeners")
        assertEquals(CopyOnWriteArrayList::class.java, field.type)
        field.isAccessible = true
        field.set(client, callbacks)
        val failed = AtomicReference<Throwable?>()
        val worker = thread {
            try { old.listener.onMessage(ServerManagementResponse.Pong("old")) }
            catch (error: Throwable) { failed.set(error) }
        }
        val received = mutableListOf<ServerManagementResponse>()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val current = Transport()
            assertTrue(client.connect(current))
            client.addMessageListener { received.add(it) }
            release.countDown()
            worker.join(5000)
            assertFalse(worker.isAlive)
            failed.get()?.let { throw it }
            assertTrue(received.isEmpty())
            current.listener.onMessage(ServerManagementResponse.Pong("current"))
            assertEquals<ServerManagementResponse>(ServerManagementResponse.Pong("current"), received.single())
        } finally {
            release.countDown()
            worker.join(5000)
            assertFalse(worker.isAlive)
            client.disconnect()
        }
    }

    private class Transport : ManagementTransport {
        lateinit var listener: ServerManagementListener
        override fun connect(listener: ServerManagementListener, timeout: Duration): Int {
            this.listener = listener
            return 12345
        }
        override fun send(request: ServerManagementRequest) = true
        override fun disconnect() = Unit
    }
}
