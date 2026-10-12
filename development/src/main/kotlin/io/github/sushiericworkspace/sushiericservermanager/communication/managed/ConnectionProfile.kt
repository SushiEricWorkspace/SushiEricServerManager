package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import kotlinx.serialization.json.*
import java.nio.file.Path
import java.nio.file.Files

/** SSH仲介を動かすこのPCのツールと、別途確認済みのホスト鍵記録。対象ホストのパスとは分離します。 */
data class ManagedClientTools(val python: Path, val cli: Path, val knownHosts: Path) {
    init { require(listOf(python, cli, knownHosts).all { it.isAbsolute }) }
}

/** Git外の形式1プロファイル。秘密と実行時の貸出識別は含めません。 */
class ConnectionProfile private constructor(val file: Path, val json: JsonObject, internal val tools: ManagedClientTools?) {
    val id = json.getValue("profileId").jsonPrimitive.content
    val name = json.getValue("displayName").jsonPrimitive.content
    val ssh = json.getValue("transport").jsonPrimitive.content == "ssh"
    val root: Path get() { check(!ssh); return Path.of(json.getValue("managementRoot").jsonPrimitive.content) }
    val python: Path get() { check(!ssh); return Path.of(json.getValue("python").jsonPrimitive.content) }
    val cli: Path get() { check(!ssh); return Path.of(json.getValue("cli").jsonPrimitive.content) }
    val operator = json.getValue("approval").jsonObject.getValue("operator").jsonPrimitive.content
    val instances = json.getValue("instances").jsonArray.map { it.jsonObject }
    val description = "$name / ${json.getValue("transport").jsonPrimitive.content} / ${json.getValue("host").jsonPrimitive.content} / ${json.getValue("managementRoot").jsonPrimitive.content}"
    var pendingManagerOperation = false
        private set
    private val supervisor: SupervisorClient by lazy { if (ssh) SshSupervisorClient(this) else CliSupervisorClient(root, python, cli) }
    internal val configuration: JsonObject get() = buildJsonObject {
        put("cli", (if (ssh) tools!!.cli else cli).toString()); put("directory", file.parent.parent.toString())
        put("profileId", id); put("expectedProfile", json)
        tools?.let { put("knownHosts", it.knownHosts.toString()) }
    }

    internal fun bridge(command: String, payload: JsonObject = buildJsonObject {}, operation: String = operationId()): JsonObject {
        if (ssh) return client().request(command, payload, operation)
        val input = JsonObject(configuration + payload + mapOf("command" to JsonPrimitive(command), "operationId" to JsonPrimitive(operation)))
        val resource = ConnectionProfile::class.java.getResourceAsStream("/managed/connection-bridge.py")!!.use { it.readBytes() }
        val script = Files.createTempFile("manager-profile-", ".py")
        return try {
            Files.write(script, resource)
            invokePython(python, listOf(script.toString()), input, operation)
        } finally { Files.deleteIfExists(script) }
    }

    internal fun client(): SupervisorClient = supervisor
    internal fun closeUnused() { (supervisor as? SshSupervisorClient)?.finish() }

    companion object {
        /** 正本のprivate権限・形式を検査します。SSHではこのPCのツールを明示し、まだネットワーク接続しません。 */
        fun load(file: Path, tools: ManagedClientTools? = null): ConnectionProfile {
            require(file.isAbsolute && file.parent.fileName.toString() == "profiles") { "Git外のprofiles/<ID>.jsonを選択してください。" }
            val bytes = Files.newInputStream(file).use { it.readNBytes(CliSupervisorClient.IPC_LIMIT + 1) }
            require(bytes.size <= CliSupervisorClient.IPC_LIMIT)
            val candidate = ConnectionProfile(file, Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject, tools)
            require(!candidate.ssh || tools != null) { "SSHプロファイルにはこのPCのPython・CLIと確認済みknown_hostsを指定してください。" }
            val checked = candidate.bridge("profile")
            check(checked.getValue("profile") == candidate.json)
            candidate.pendingManagerOperation = checked.getValue("pendingManagerOperation").jsonPrimitive.boolean
            return candidate
        }
    }
}
