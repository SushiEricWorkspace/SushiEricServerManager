package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import org.mockito.Mockito.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SshHistorySourceTest {
    @Test fun `固定CLIのstdinをEOFで閉じ同じsnapshotを全ページへ渡す`() {
        val requests = CopyOnWriteArrayList<HostAdapterRequest>()
        val client = mock(SSHClient::class.java)
        val commands = CopyOnWriteArrayList<Session.Command>()
        val sessions = CopyOnWriteArrayList<Session>()
        `when`(client.startSession()).thenAnswer {
            val session = mock(Session::class.java)
            val command = mock(Session.Command::class.java)
            sessions += session; commands += command
            val eof = CountDownLatch(1)
            val sent = object : ByteArrayOutputStream() { override fun close() { eof.countDown() } }
            val input = object : InputStream() {
                private var response: ByteArrayInputStream? = null
                private fun response(): ByteArrayInputStream {
                    response?.let { return it }
                    assertTrue(eof.await(3, TimeUnit.SECONDS))
                    val request = HostAdapterCodec.decodeRequest(sent.toByteArray())
                    requests += request
                    val result = when (val action = request.action) {
                        HostAdapterAction.HistoryStart -> HostAdapterResult.Snapshot(HostHistorySnapshot(5, listOf(3)))
                        is HostAdapterAction.HistoryPage -> {
                            assertEquals(HostHistorySnapshot(5, listOf(3)), action.snapshot)
                            HostAdapterResult.History(if (action.offset == 0) listOf(historyRecord()) else emptyList())
                        }
                        else -> error("検索以外の操作です。")
                    }
                    return ByteArrayInputStream(HostAdapterCodec.encodeResponse(HostAdapterResponse(requestId = request.requestId,
                        status = HostAdapterStatus.SUCCESS, result = result))).also { response = it }
                }
                override fun read() = response().read()
                override fun read(bytes: ByteArray, offset: Int, size: Int) = response().read(bytes, offset, size)
            }
            `when`(session.exec("fixed-host-adapter")).thenReturn(command)
            `when`(command.outputStream).thenReturn(sent)
            `when`(command.inputStream).thenReturn(input)
            `when`(command.errorStream).thenReturn(ByteArrayInputStream("診断".toByteArray()))
            `when`(command.exitStatus).thenReturn(0)
            session
        }
        var connection = client
        val source = SshHistorySource({ connection }, "fixed-host-adapter", UUID.randomUUID().toString())
        assertEquals(1, source.readAll().size)
        assertEquals(3, requests.size)
        commands.forEach { verify(it).close() }
        sessions.forEach { verify(it).close() }
        connection = mock(SSHClient::class.java)
        assertFailsWith<IllegalStateException> { source.open() }
        verifyNoInteractions(connection)
    }

    @Test fun `起動失敗認証失敗とtimeoutでsessionとstream workerを終了する`() {
        val denied = SshHistorySource({ throw java.io.IOException("認証失敗") }, "fixed", UUID.randomUUID().toString())
        assertFails { denied.open() }
        val client = mock(SSHClient::class.java)
        val session = mock(Session::class.java)
        val command = mock(Session.Command::class.java)
        `when`(client.startSession()).thenReturn(session)
        `when`(session.exec("fixed")).thenReturn(command)
        `when`(command.outputStream).thenReturn(ByteArrayOutputStream())
        `when`(command.errorStream).thenReturn(ByteArrayInputStream(byteArrayOf()))
        val stopped = CountDownLatch(1)
        `when`(command.inputStream).thenReturn(object : InputStream() {
            override fun read(): Int = try { CountDownLatch(1).await(); -1 } finally { stopped.countDown() }
        })
        assertFails { SshHistorySource({ client }, "fixed", UUID.randomUUID().toString(), 30).open() }
        assertTrue(stopped.await(1, TimeUnit.SECONDS))
        verify(command).close(); verify(session).close()
    }
}
