package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** ローカル監督へ本文をstdinで渡します。秘密本文をargv・通常ログへ出しません。 */
class CliSupervisorClient(private val root: Path, private val python: Path, private val cli: Path) : SupervisorClient {
    constructor(profile: ManagedProfile) : this(profile.root, profile.localTarget.python, profile.localTarget.cli)
    override fun request(command: String, payload: JsonObject, operationId: String): JsonObject {
        val envelope = buildJsonObject {
            put("version", 1); put("command", command); put("operationId", operationId); put("payload", payload); put("dryRun", false)
        }.toString().toByteArray(Charsets.UTF_8)
        if (envelope.size > IPC_LIMIT) throw SupervisorFailure("MESSAGE_TOO_LARGE", rejectedBeforeEffect = true)
        val timeout = when (command) { "prepare", "reset", "restore" -> 600; "start", "stop" -> 180; else -> 15 }
        val arguments = listOf(cli.toString(), command, "--root", root.toString(),
            "--operation-id", operationId, "--payload-stdin", "--timeout-seconds", timeout.toString())
        return invokePython(python, arguments, payload, operationId, timeout + 5)
    }
    companion object { const val IPC_LIMIT = 65_536 }
}

internal fun invokePython(python: Path, arguments: List<String>, payload: JsonObject, operation: String, timeoutSeconds: Int = 20): JsonObject {
    val text = payload.toString().toByteArray(Charsets.UTF_8)
    if (text.size > CliSupervisorClient.IPC_LIMIT) throw SupervisorFailure("MESSAGE_TOO_LARGE", rejectedBeforeEffect = true)
    val process = try {
        ProcessBuilder(listOf(python.toString(), "-B") + arguments)
            .redirectError(ProcessBuilder.Redirect.DISCARD).apply { environment()["PYTHONUTF8"] = "1" }.start()
    } catch (_: Exception) { throw SupervisorFailure("CLI_UNAVAILABLE", rejectedBeforeEffect = true) }
    val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(CliSupervisorClient.IPC_LIMIT + 1) } }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong())
    val input = CompletableFuture.runAsync { process.outputStream.use { it.write(text) } }
    try {
        input.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        if (!process.waitFor((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)) throw SupervisorFailure("RESULT_UNKNOWN")
        val bytes = output.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
        if (bytes.size > CliSupervisorClient.IPC_LIMIT) throw SupervisorFailure("RESULT_UNKNOWN")
        val response = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        if (response["operationId"]?.jsonPrimitive?.content != operation) throw SupervisorFailure("RESULT_UNKNOWN")
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
        throw SupervisorFailure("RESULT_UNKNOWN")
    }
}

/** テストでは同じ要求契約を合成監督へ接続します。 */
fun interface SupervisorClient {
    fun request(command: String, payload: JsonObject, operationId: String): JsonObject
}

class SupervisorFailure(val code: String, cause: Throwable? = null, val rejectedBeforeEffect: Boolean = false) : RuntimeException("管理接続: $code", cause)

/** 接続先ホストの識別。SSHのパスをこのPCのPathとして解決しません。 */
sealed interface ManagedTarget {
    val identity: String
    val description: String
    data class Local(val root: Path, val python: Path, val cli: Path) : ManagedTarget {
        init { require(listOf(root, python, cli).all { it.isAbsolute }) }
        override val identity = "local:$root"
        override val description = root.toString()
    }
    data class Ssh(val profile: ConnectionProfile, val owner: String, val client: SupervisorClient) : ManagedTarget {
        override val identity = "ssh:" + profile.json.toString()
        override val description = profile.description
    }
}

/** 接続時だけ保持する貸出入力。SSH profileや設定JSONへ混ぜません。 */
data class ManagedProfile(val target: ManagedTarget, val instanceId: String, val leaseId: String, val epoch: Long,
                          val fixedBinding: JsonObject? = null) {
    constructor(root: Path, python: Path, cli: Path, instanceId: String, leaseId: String, epoch: Long,
                fixedBinding: JsonObject? = null) : this(ManagedTarget.Local(root, python, cli), instanceId, leaseId, epoch, fixedBinding)
    internal val localTarget get() = when (val active = target) {
        is ManagedTarget.Local -> active
        is ManagedTarget.Ssh -> error("SSH対象のパスをこのPCのPathとして使用できません。")
    }
    /** ローカル接続専用。SSH対象では使用できません。 */
    val root: Path get() = localTarget.root
    internal fun client(): SupervisorClient = when (val active = target) {
        is ManagedTarget.Local -> CliSupervisorClient(this)
        is ManagedTarget.Ssh -> active.client
    }
    init { require(Regex("[a-z0-9][a-z0-9-]{0,127}").matches(instanceId) && leaseId.isNotBlank() && epoch > 0) }
}

internal fun operationId(): String = UUID.randomUUID().toString()
