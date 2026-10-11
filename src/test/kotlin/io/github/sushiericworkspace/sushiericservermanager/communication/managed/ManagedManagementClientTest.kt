package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import io.github.sushiericworkspace.sushiericservermanager.communication.management.*
import kotlinx.serialization.json.*
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ManagedManagementClientTest {
    @Test fun `API success falseを変更せず届けwriterを通信完了後に閉じる`() {
        val storage = createTempDirectory("managed-api-")
        Peer().use { peer ->
            try {
                val fixture = ManagedSessionTest.Fixture(storage, peer.port)
                val response = CountDownLatch(1)
                fixture.onIo = { }
                val supervisor = SupervisorClient { command, payload, operation ->
                    if (command != "writer-io") fixture.request(command, payload, operation)
                    else {
                        fixture.commands += command to payload
                        buildJsonObject {
                            put("completed", true)
                            putJsonObject("apiResponse") { put("type", "money_history_save_result"); put("nonce", "save-1"); put("success", false) }
                        }
                    }
                }
                val session = ManagedSession.open(fixture.profile, storage, supervisor)
                val client = ServerManagementClient()
                val received = CopyOnWriteArrayList<ServerManagementResponse>()
                client.addMessageListener { received += it; response.countDown() }
                assertTrue(client.connectManaged(session))
                assertTrue(client.send(ServerManagementRequest.MoneyHistorySave(null, "save-1")))
                assertTrue(response.await(3, TimeUnit.SECONDS))
                assertFalse(assertIs<ServerManagementResponse.MoneyHistorySaveResult>(received.single()).success)
                assertEquals(ManagedSession.State.OPEN, session.state)
                session.close { client.disconnect() }
                assertEquals(ManagedSession.State.CLOSED, session.state)
                assertTrue(peer.received.isEmpty(), "変更要求はread-only socketへ送らない")
                assertTrue(peer.closed.await(3, TimeUnit.SECONDS))
                assertEquals("writer-close", fixture.commands.last().first)
            } finally { storage.toFile().deleteRecursively() }
        }
    }

    @Test fun `遅延handshakeで接続get期限を超えたsessionは終了申告しない`() {
        val storage = createTempDirectory("managed-late-handshake-")
        Peer(delayMillis = 400).use { peer ->
            try {
                val fixture = ManagedSessionTest.Fixture(storage, peer.port)
                val session = fixture.open()
                val client = ServerManagementClient()
                assertFalse(client.connectManaged(session, Duration.ofMillis(100)))
                assertEquals(ManagedSession.State.UNKNOWN, session.state)
                assertFailsWith<IllegalStateException> { session.close { client.disconnect() } }
                assertFalse(fixture.commands.any { it.first == "writer-close" })
                assertTrue(peer.finished.await(3, TimeUnit.SECONDS))
                assertFalse(client.send(ServerManagementRequest.MoneyHistorySave(null, "late")))
            } finally { storage.toFile().deleteRecursively() }
        }
    }

    private class Peer(private val delayMillis: Long = 0) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val received = CopyOnWriteArrayList<String>()
        val closed = CountDownLatch(1)
        val finished = CountDownLatch(1)
        private val executor = Executors.newSingleThreadExecutor { Thread(it, "managed-test-peer").apply { isDaemon = true } }
        private val work = executor.submit {
            try {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val next = input.read(); if (next < 0) throw EOFException()
                        header.append(next.toChar())
                    }
                    check(header.startsWith("GET /management HTTP/1.1\r\n"))
                    val key = header.lines().single { it.startsWith("Sec-WebSocket-Key:", true) }.substringAfter(':').trim()
                    if (delayMillis > 0) Thread.sleep(delayMillis)
                    val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                    output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
                    output.flush()
                    while (true) {
                        val first = input.read(); if (first < 0) break
                        val second = input.read(); if (second < 0) throw EOFException()
                        var size = second and 127
                        if (size == 126) size = (input.read() shl 8) or input.read()
                        val mask = input.readNBytes(4)
                        val body = input.readNBytes(size).mapIndexed { index, byte -> (byte.toInt() xor mask[index % 4].toInt()).toByte() }.toByteArray()
                        if (first and 15 == 8) {
                            output.write(byteArrayOf(0x88.toByte(), body.size.toByte()) + body); output.flush()
                            closed.countDown(); break
                        }
                        received += body.toString(Charsets.UTF_8)
                    }
                }
            } catch (error: java.io.IOException) {
                if (delayMillis == 0L) throw error
            } finally { finished.countDown() }
        }
        override fun close() {
            work.get(4, TimeUnit.SECONDS)
            server.close(); executor.shutdown(); check(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
}
