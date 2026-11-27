package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericservermanager.communication.database.*
import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import org.mockito.Mockito.*
import kotlin.test.*

class SshHistorySourceTest {
    @Test fun `固定新版CLIへ一接続のframeを送りCloseとEOFを確認する`() {
        val reference = postgresReference()
        val fixture = HostChannelFixture(reference)
        val client = mock(SSHClient::class.java)
        stubSshRunState(client, reference)
        val session = mock(Session::class.java)
        val command = mock(Session.Command::class.java)
        `when`(client.isConnected).thenReturn(true)
        `when`(client.startSession()).thenReturn(session)
        `when`(session.exec(anyString())).thenReturn(command)
        `when`(command.inputStream).thenReturn(fixture.input)
        `when`(command.outputStream).thenReturn(fixture.output)
        `when`(command.errorStream).thenReturn(fixture.error)
        `when`(command.exitStatus).thenReturn(0)
        var connection = client
        val source = SshHistorySource({ connection }, reference, RemoteOperatingSystem.UBUNTU_SERVER)
        assertTrue(source.readAll().isEmpty())
        verify(client, times(1)).startSession()
        verify(session).exec("'/fixed/java' '-cp' '/fixed/host/lib/*' " +
            "'io.github.sushiericworkspace.sushiericdatamodel.database.host.PostgresWriterCli' '--settings' '/protected/host.json'")
        verify(command).close()
        verify(session).close()
        assertTrue(fixture.eof)
        connection = mock(SSHClient::class.java)
        assertFails { source.open() }
        verifyNoInteractions(connection)
    }

    @Test fun `起動認証失敗を空履歴として扱わない`() {
        val source = SshHistorySource({ throw java.io.IOException("認証失敗") }, postgresReference(),
            RemoteOperatingSystem.UBUNTU_SERVER)
        assertFails { source.open() }
    }
}
