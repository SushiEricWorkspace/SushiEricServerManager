package io.github.sushiericworkspace.sushiericservermanager.editor.shop

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import org.mockito.Mockito.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.*

class SshShopTransportTest {
    @Test fun `固定CLIへ同じUTF8要求をstdinで渡し終了コードと応答IDを照合する`() {
        val request = HostAdapterRequest(requestId = UUID.randomUUID().toString(),
            worldUuid = UUID.randomUUID().toString(), action = HostAdapterAction.Products(listOf("tab.apple")))
        for (exit in listOf(0, 2)) {
            val client = mock(SSHClient::class.java)
            val session = mock(Session::class.java)
            val command = mock(Session.Command::class.java)
            val sent = ByteArrayOutputStream()
            val response = HostAdapterResponse(requestId = request.requestId, status = HostAdapterStatus.SUCCESS,
                result = HostAdapterResult.Products(emptyList()))
            `when`(client.startSession()).thenReturn(session)
            `when`(session.exec("fixed-host-adapter")).thenReturn(command)
            `when`(command.outputStream).thenReturn(sent)
            `when`(command.inputStream).thenReturn(ByteArrayInputStream(HostAdapterCodec.encodeResponse(response)))
            `when`(command.errorStream).thenReturn(ByteArrayInputStream("診断".toByteArray()))
            `when`(command.exitStatus).thenReturn(exit)
            val transport = SshShopTransport({ client }, "fixed-host-adapter", request.worldUuid)
            if (exit == 0) assertEquals(response, transport.execute(request))
            else assertFails { transport.execute(request) }
            assertContentEquals(HostAdapterCodec.encodeRequest(request), sent.toByteArray())
            verify(command).close()
            verify(session).close()
        }
    }
}
