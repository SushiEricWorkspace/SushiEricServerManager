package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** ローカル監督へ本文をstdinで渡します。貸出の取得・更新は行いません。 */
class CliSupervisorClient(private val profile: ManagedProfile) : SupervisorClient {
    override fun request(command: String, payload: JsonObject, operationId: String): JsonObject {
        val text = payload.toString().toByteArray(Charsets.UTF_8)
        val envelope = buildJsonObject {
            put("version", 1); put("command", command); put("operationId", operationId)
            put("payload", payload); put("dryRun", false)
        }.toString().toByteArray(Charsets.UTF_8)
        if (envelope.size > IPC_LIMIT) throw SupervisorFailure("MESSAGE_TOO_LARGE", rejectedBeforeEffect = true)
        val process = ProcessBuilder(profile.python.toString(), "-B", profile.cli.toString(), command,
            "--root", profile.root.toString(), "--operation-id", operationId, "--payload-stdin", "--timeout-seconds", "15")
            .redirectError(ProcessBuilder.Redirect.DISCARD).apply { environment()["PYTHONUTF8"] = "1" }.start()
        val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(IPC_LIMIT + 1) } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        val input = CompletableFuture.runAsync { process.outputStream.use { it.write(text) } }
        try {
            input.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            if (!process.waitFor((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)) throw SupervisorFailure("RESULT_UNKNOWN")
            val bytes = output.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            if (bytes.size > IPC_LIMIT) throw SupervisorFailure("RESULT_UNKNOWN")
            val response = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            if (response["operationId"]?.jsonPrimitive?.content != operationId) throw SupervisorFailure("RESULT_UNKNOWN")
            if (response["ok"]?.jsonPrimitive?.boolean != true) {
                val detail = response["error"]?.jsonObject
                throw SupervisorFailure(detail?.get("code")?.jsonPrimitive?.content ?: "RESULT_UNKNOWN",
                    rejectedBeforeEffect = detail?.get("rejectedBeforeEffect")?.jsonPrimitive?.booleanOrNull == true)
            }
            return response.getValue("result").jsonObject
        } catch (error: SupervisorFailure) {
            throw error
        } catch (error: Exception) {
            // 期限切れでsupervisorの変更をkill・別ID再送しません。
            throw SupervisorFailure("RESULT_UNKNOWN", error)
        }
    }
    companion object { const val IPC_LIMIT = 65_536 }
}

/** テストでは同じ要求契約を合成監督へ接続します。 */
fun interface SupervisorClient {
    fun request(command: String, payload: JsonObject, operationId: String): JsonObject
}

class SupervisorFailure(val code: String, cause: Throwable? = null, val rejectedBeforeEffect: Boolean = false) : RuntimeException("管理接続: $code", cause)

/** 接続時だけ保持する貸出入力。SSH profileや設定JSONへ混ぜません。 */
data class ManagedProfile(val root: Path, val python: Path, val cli: Path,
                          val instanceId: String, val leaseId: String, val epoch: Long) {
    init {
        require(listOf(root, python, cli).all { it.isAbsolute }) { "管理root・Python・CLIは絶対パスで指定してください。" }
        require(Regex("[a-z0-9][a-z0-9-]{0,127}").matches(instanceId) && leaseId.isNotBlank() && epoch > 0)
    }
}

internal fun operationId(): String = UUID.randomUUID().toString()
