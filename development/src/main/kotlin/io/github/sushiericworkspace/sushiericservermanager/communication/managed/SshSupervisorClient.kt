package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import kotlinx.serialization.json.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 正本SshConnectionへの上限付きstdin/stdout経路。一度結果不明になった子プロセスへ再送しません。 */
internal class SshSupervisorClient internal constructor(private val process: Process, private val timeoutSeconds: Long = 45) : SupervisorClient {
    private val input = DataInputStream(process.inputStream)
    private val output = DataOutputStream(process.outputStream)
    private val reader = Executors.newSingleThreadExecutor { Thread(it, "managed-ssh-response").apply { isDaemon = true } }
    private val sender = Executors.newSingleThreadExecutor { Thread(it, "managed-ssh-request").apply { isDaemon = true } }
    @Volatile private var unavailable = false

    constructor(profile: ConnectionProfile) : this(start(profile)) {
        request("configure", profile.configuration, operationId())
    }

    @Synchronized override fun request(command: String, payload: JsonObject, operationId: String): JsonObject {
        check(!unavailable) { "SSH接続は終了済みまたは結果不明です。" }
        val bytes = buildJsonObject { put("command", command); put("payload", payload); put("operationId", operationId) }
            .toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > CliSupervisorClient.IPC_LIMIT) throw SupervisorFailure("MESSAGE_TOO_LARGE", rejectedBeforeEffect = true)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        try {
            val received = reader.submit<ByteArray> {
                val size = input.readInt()
                check(size in 1..CliSupervisorClient.IPC_LIMIT)
                ByteArray(size).also { input.readFully(it) }
            }
            sender.submit { output.writeInt(bytes.size); output.write(bytes); output.flush() }
                .get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            val response = Json.parseToJsonElement(received.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS).toString(Charsets.UTF_8)).jsonObject
            check(!unavailable)
            check(response["operationId"]?.jsonPrimitive?.content == operationId)
            if (response.getValue("ok").jsonPrimitive.boolean) return response.getValue("result").jsonObject
            val error = response.getValue("error").jsonObject
            throw SupervisorFailure(error.getValue("code").jsonPrimitive.content,
                rejectedBeforeEffect = error["rejectedBeforeEffect"]?.jsonPrimitive?.booleanOrNull == true)
        } catch (error: SupervisorFailure) {
            if (!error.rejectedBeforeEffect) abandon()
            throw error
        } catch (_: Exception) {
            abandon()
            throw SupervisorFailure("RESULT_UNKNOWN")
        }
    }

    internal fun abandon() {
        unavailable = true
        reader.shutdown(); sender.shutdown()
        Thread({ runCatching { output.close() } }, "managed-ssh-abandon").apply { isDaemon = true }.start()
    }

    /** writerのない候補・貸出用仲介だけを終了します。貸出の返却とは別操作です。 */
    @Synchronized fun finish() {
        if (unavailable) return
        try {
            request("finish-client", buildJsonObject {}, operationId())
            output.close()
            check(process.waitFor(45, TimeUnit.SECONDS) && process.exitValue() == 0) { "SSH仲介の正常終了が未確認です。" }
            unavailable = true
            reader.shutdown(); sender.shutdown()
        } catch (error: Exception) { abandon(); throw error }
    }

    companion object {
        private fun start(profile: ConnectionProfile): Process {
            val tools = profile.tools!!
            val directory = Files.createTempDirectory("manager-ssh-")
            val scripts = listOf("ssh-bridge.py", "connection-bridge.py")
            scripts.forEach { name ->
                val body = SshSupervisorClient::class.java.getResourceAsStream("/managed/$name")!!.use { it.readBytes() }
                Files.write(directory.resolve(name), body)
            }
            val child = try {
                ProcessBuilder(tools.python.toString(), "-B", directory.resolve("ssh-bridge.py").toString(), tools.cli.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).apply { environment()["PYTHONUTF8"] = "1" }.start()
            } catch (_: Exception) {
                scripts.forEach { Files.deleteIfExists(directory.resolve(it)) }
                Files.deleteIfExists(directory)
                throw SupervisorFailure("CLI_UNAVAILABLE", rejectedBeforeEffect = true)
            }
            Thread({
                child.waitFor()
                scripts.forEach { Files.deleteIfExists(directory.resolve(it)) }
                Files.deleteIfExists(directory)
            }, "managed-ssh-script-cleanup").apply { isDaemon = true }.start()
            return child
        }
    }
}
