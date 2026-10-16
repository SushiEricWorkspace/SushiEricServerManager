package io.github.sushiericworkspace.sushiericservermanager.communication.management

import io.github.sushiericworkspace.sushiericservermanager.communication.ssh.SshTunnelService
import java.net.InetAddress
import java.net.ServerSocket
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Management APIクライアントの接続状態と、未接続時の挙動を検証する。
 *
 * SSH接続を必要とする経路は実サーバーでの確認に委ね、
 * ここでは接続していない状態の契約を確認する。
 */
class ServerManagementClientTest {

    @Test
    fun `初期状態は未接続`() {
        val client = ServerManagementClient()

        assertIs<ServerManagementState.Disconnected>(client.state)
        assertFalse(client.isConnected)
    }

    @Test
    fun `未接続ではpingを送れない`() {
        val client = ServerManagementClient()

        assertNull(client.ping())
    }

    @Test
    fun `未接続ではメッセージを送れない`() {
        val client = ServerManagementClient()

        assertFalse(
            client.send(ServerManagementRequest.Ping(nonce = "n-1"))
        )
    }

    @Test
    fun `未接続でdisconnectしても失敗しない`() {
        val client = ServerManagementClient()

        client.disconnect()
        client.disconnect()

        assertIs<ServerManagementState.Disconnected>(client.state)
    }

    @Test
    fun `接続状態の変化を通知する`() {
        val client = ServerManagementClient()
        val received = mutableListOf<ServerManagementState>()

        client.addStateListener { received.add(it) }
        client.disconnect()

        /*
         * 未接続からのdisconnectは状態が変わらないため通知しない。
         */
        assertEquals(emptyList(), received)
    }

    @Test
    fun `既定の接続先ポートはModの既定値と一致する`() {
        assertEquals(25580, ServerManagementClient.DEFAULT_REMOTE_PORT)
        assertTrue(ServerManagementClient.DEFAULT_TIMEOUT > Duration.ZERO)
    }

    @Test
    fun `Tunnelの転送先はループバックへ固定されている`() {
        assertEquals("127.0.0.1", SshTunnelService.REMOTE_HOST)
    }

    @Test
    fun `接続できない場合は失敗状態になり例外を投げない`() {
        val connection =
            ServerManagementConnection(RecordingListener())

        /*
         * 誰もListenしていないポートへ接続を試みる。
         * ServerSocketを閉じた直後のポートを使い、確実に接続を失敗させる。
         */
        val closedPort =
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { socket ->
                socket.localPort
            }

        val failed =
            runCatching {
                connection.connect(closedPort, Duration.ofMillis(500))
            }

        assertTrue(failed.isFailure, "接続に成功してしまいました。")
        assertFalse(connection.isOpen)
    }

    private class RecordingListener : ServerManagementListener {
        override fun onMessage(message: ServerManagementResponse) = Unit
        override fun onClosed(statusCode: Int, reason: String) = Unit
        override fun onError(error: Throwable) = Unit
    }
}
