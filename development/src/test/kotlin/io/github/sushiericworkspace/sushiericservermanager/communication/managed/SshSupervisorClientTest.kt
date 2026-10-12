package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.*

class SshSupervisorClientTest {
    @Test fun `応答ID不一致と上限違反を再利用可能な接続へ変換しない`() {
        for (mode in listOf("id", "size", "eof")) withChild(mode) { client ->
            assertFailsWith<SupervisorFailure> { client.request("status", buildJsonObject {}, "request-one") }
            assertFailsWith<IllegalStateException> { client.request("status", buildJsonObject {}, "request-two") }
        }
    }

    @Test fun `既知の作用前拒否だけは同じ接続を継続できる`() {
        withChild("reject") { client ->
            val error = assertFailsWith<SupervisorFailure> { client.request("io", buildJsonObject {}, "request-one") }
            assertTrue(error.rejectedBeforeEffect)
            assertEquals("request-two", client.request("status", buildJsonObject {}, "request-two")["echo"]?.jsonPrimitive?.content)
        }
    }

    @Test fun `作用前情報のないエラーは保存再送を受け付けない`() {
        withChild("unknown") { client ->
            assertFailsWith<SupervisorFailure> { client.request("io", buildJsonObject {}, "request-one") }
            assertFailsWith<IllegalStateException> { client.request("io", buildJsonObject {}, "new-operation") }
        }
    }

    @Test fun `応答が止まっても期限内で返り同じ接続へ要求を再送しない`() {
        val started = System.nanoTime()
        withChild("silence") { client ->
            assertFailsWith<SupervisorFailure> { client.request("io", buildJsonObject {}, "request-one") }
            assertFailsWith<IllegalStateException> { client.request("io", buildJsonObject {}, "request-two") }
        }
        assertTrue(System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(5))
    }

    private fun withChild(mode: String, action: (SshSupervisorClient) -> Unit) {
        val python = System.getenv("MANAGED_PYTHON")
        assumeTrue(python != null && Files.isRegularFile(Path.of(python)))
        val script = Files.createTempFile("manager-197-frame-", ".py")
        Files.writeString(script, """
            import json, sys
            mode=sys.argv[1]
            number=0
            while True:
                prefix=sys.stdin.buffer.read(4)
                if not prefix: break
                request=json.loads(sys.stdin.buffer.read(int.from_bytes(prefix,'big')))
                number+=1
                if mode=='eof': break
                if mode=='silence': continue
                if mode=='size':
                    sys.stdout.buffer.write((65537).to_bytes(4,'big')); sys.stdout.buffer.flush(); break
                if number==1 and mode in ('reject','unknown'):
                    response={'ok':False,'operationId':request['operationId'],'error':{'code':'TEST_REJECTED','rejectedBeforeEffect':mode=='reject'}}
                else:
                    response={'ok':True,'operationId':'old-request' if mode=='id' else request['operationId'],'result':{'echo':request['operationId']}}
                body=json.dumps(response).encode()
                sys.stdout.buffer.write(len(body).to_bytes(4,'big')+body); sys.stdout.buffer.flush()
        """.trimIndent())
        val process = ProcessBuilder(python, "-B", script.toString(), mode).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try { action(SshSupervisorClient(process, timeoutSeconds = 1)) }
        finally { process.outputStream.close(); check(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)); Files.delete(script) }
    }
}
