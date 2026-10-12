package io.github.sushiericworkspace.sushiericservermanager.communication.management

import java.time.Duration
import kotlin.test.*

class ManagementTransportTest {
    @Test fun `切断未確認では通信経路を保持し次の接続を拒否する`() {
        val client = ServerManagementClient()
        val first = Transport()
        assertTrue(client.connect(first))
        first.failClose = true
        assertFailsWith<IllegalStateException> { client.disconnect() }
        val next = Transport()
        assertFailsWith<IllegalStateException> { client.connect(next) }
        assertFalse(next.opened)
        first.failClose = false
        client.disconnect()
        assertTrue(client.connect(next))
        client.disconnect()
    }

    @Test fun `旧通信経路のcallbackを新しい接続へ届けない`() {
        val client = ServerManagementClient()
        val first = Transport()
        val next = Transport()
        assertTrue(client.connect(first))
        assertTrue(client.connect(next))
        first.listener.onClosed(1000, "old")
        first.listener.onError(IllegalStateException("old"))
        assertIs<ServerManagementState.Connected>(client.state)
        client.disconnect()
    }

    private class Transport : ManagementTransport {
        lateinit var listener: ServerManagementListener
        var opened = false
        var failClose = false
        override fun connect(listener: ServerManagementListener, timeout: Duration): Int {
            this.listener = listener
            opened = true
            return 25580
        }
        override fun send(request: ServerManagementRequest) = true
        override fun disconnect() { check(!failClose) { "終了未確認" } }
    }
}
