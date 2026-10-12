package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import kotlinx.serialization.json.*
import java.nio.file.Path
import java.nio.file.Files

/** Git外の形式1プロファイル。秘密と実行時の貸出識別は含めません。 */
class ConnectionProfile private constructor(val file: Path, val json: JsonObject, val pendingManagerOperation: Boolean = false) {
    val id = json.getValue("profileId").jsonPrimitive.content
    val name = json.getValue("displayName").jsonPrimitive.content
    val root: Path = Path.of(json.getValue("managementRoot").jsonPrimitive.content)
    val python: Path = Path.of(json.getValue("python").jsonPrimitive.content)
    val cli: Path = Path.of(json.getValue("cli").jsonPrimitive.content)
    val operator = json.getValue("approval").jsonObject.getValue("operator").jsonPrimitive.content
    val instances = json.getValue("instances").jsonArray.map { it.jsonObject }
    val description = "$name / ${json.getValue("host").jsonPrimitive.content} / $root"
    private val directory = file.parent.parent

    internal fun bridge(command: String, payload: JsonObject = buildJsonObject {}, operation: String = operationId()): JsonObject {
        val input = buildJsonObject {
            put("command", command); put("cli", cli.toString()); put("directory", directory.toString())
            put("profileId", id); put("operationId", operation); put("expectedProfile", json)
            payload.forEach { (key, value) -> put(key, value) }
        }
        val resource = ConnectionProfile::class.java.getResourceAsStream("/managed/connection-bridge.py")!!.use { it.readBytes() }
        // スクリプトはstdinと分離して非秘密の一時ファイルへ置きます。
        val script = Files.createTempFile("manager-profile-", ".py")
        return try {
            Files.write(script, resource)
            invokePython(python, listOf(script.toString()), input, operation)
        } finally { Files.deleteIfExists(script) }
    }

    internal fun client(): SupervisorClient = CliSupervisorClient(root, python, cli)

    /** 選択されたJSONのパスを足掛かりに、正本のprivate権限・形式・ホスト検査を実行します。 */
    companion object {
        fun load(file: Path): ConnectionProfile {
            require(file.isAbsolute && file.parent.fileName.toString() == "profiles") { "Git外のprofiles/<ID>.jsonを選択してください。" }
            val bytes = Files.newInputStream(file).use { it.readNBytes(CliSupervisorClient.IPC_LIMIT + 1) }
            require(bytes.size <= CliSupervisorClient.IPC_LIMIT)
            val candidate = ConnectionProfile(file, Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject)
            val checked = candidate.bridge("profile")
            return ConnectionProfile(file, checked.getValue("profile").jsonObject, checked.getValue("pendingManagerOperation").jsonPrimitive.boolean)
        }
    }
}
