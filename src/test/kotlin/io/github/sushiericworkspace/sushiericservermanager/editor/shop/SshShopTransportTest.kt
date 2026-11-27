package io.github.sushiericworkspace.sushiericservermanager.editor.shop

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericservermanager.communication.database.*
import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import org.mockito.Mockito.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SshShopTransportTest {
    @Test fun `timeoutはstdinをEOFにしchannelとworkerを終了して新しいHを起動しない`() {
        val reference = postgresReference()
        val client = mock(SSHClient::class.java)
        stubSshRunState(client, reference)
        val session = mock(Session::class.java)
        val command = mock(Session.Command::class.java)
        `when`(client.isConnected).thenReturn(true)
        `when`(client.startSession()).thenReturn(session)
        `when`(session.exec(anyString())).thenReturn(command)
        var eof = false
        `when`(command.outputStream).thenReturn(object : ByteArrayOutputStream() {
            override fun close() { eof = true }
        })
        `when`(command.errorStream).thenReturn(ByteArrayInputStream(byteArrayOf()))
        val stopped = CountDownLatch(1)
        `when`(command.inputStream).thenReturn(object : InputStream() {
            override fun read(): Int = try { CountDownLatch(1).await(); -1 } finally { stopped.countDown() }
        })
        `when`(command.exitStatus).thenReturn(0)
        val transport = SshShopTransport({ client }, reference, RemoteOperatingSystem.UBUNTU_SERVER, 30)
        val request = HostAdapterRequest(requestId = UUID.randomUUID().toString(),
            worldUuid = reference.status.state.binding.worldUuid, action = HostAdapterAction.Edit(emptyList()))
        assertFails { transport.execute(request) }
        assertTrue(stopped.await(1, TimeUnit.SECONDS))
        assertTrue(eof)
        assertFails { transport.execute(request) }
        verify(client, times(1)).startSession()
        verify(command).close()
        verify(session).close()
    }

    @Test fun `Windows shell展開と相対配置はCLI起動前に拒否する`() {
        val client = mock(SSHClient::class.java)
        `when`(client.isConnected).thenReturn(true)
        for (reference in listOf(
            postgresReference().copy(javaExecutable = "C:/java%OTHER%/java.exe", runtimeDirectory = "C:/host",
                settingsFile = "C:/protected/settings.json"),
            postgresReference().copy(javaExecutable = "java.exe"))) {
            assertFails { sshHostChannel(client, reference, RemoteOperatingSystem.WINDOWS, false) }
        }
        verify(client, never()).startSession()
    }
}
