package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import kotlinx.serialization.json.*

/** 画面の明示操作だけを貸出へ変換します。UNKNOWNや保持中の貸出を再acquireしません。 */
class ManagedConnectionService(val profile: ConnectionProfile, private val client: SupervisorClient = profile.client()) {
    var lease: ManagedLease? = null
        private set
    @Volatile var uncertain = profile.pendingManagerOperation
        private set
    /** プロファイルの候補だけを返し、貸出tokenは列挙しません。 */
    fun instances(): List<JsonObject> {
        val candidates = profile.instances.map { it.getValue("instanceId").jsonPrimitive.content }.toSet()
        return client.request("list", buildJsonObject {}, operationId()).getValue("instances").jsonArray.map { it.jsonObject }
            .filter { it["instanceId"]?.jsonPrimitive?.content in candidates }
    }
    private fun journal(command: String, payload: JsonObject, operation: String, result: JsonObject?) {
        profile.bridge(if (result == null) "journal" else "finish", buildJsonObject {
            if (result == null) { put("operation", command); put("payload", payload) } else put("result", result)
        }, operation)
    }
    /** AVAILABLEを明示借用し、送信前にprivate記録を残します。起動は行いません。 */
    fun borrow(instance: String, owner: String): ManagedLease {
        check(lease == null && !uncertain)
        require(profile.instances.any { it["instanceId"]?.jsonPrimitive?.content == instance })
        require(Regex("[a-z0-9][a-z0-9-]{0,127}").matches(owner))
        val row = instances().single { it["instanceId"]?.jsonPrimitive?.content == instance }
        check(row["state"]?.jsonPrimitive?.content == "AVAILABLE" && row["owner"] == JsonNull)
        val payload = buildJsonObject { put("instanceId", instance); put("owner", owner); put("ttlSeconds", 3600) }
        val operation = operationId()
        try {
            journal("acquire", payload, operation, null)
            val response = client.request("acquire", payload, operation)
            check(!uncertain) { "キャンセル後の貸出応答は保全します。" }
            journal("acquire", payload, operation, response)
            check(response["instanceId"]?.jsonPrimitive?.content == instance)
            val token = buildJsonObject {
                put("instanceId", instance); put("leaseId", response.getValue("leaseId")); put("epoch", response.getValue("epoch"))
                put("expectedGeneration", response.getValue("generation"))
            }
            return ManagedLease(ManagedLease.Source.MANAGER, owner, client, token, response.getValue("expiresAt").jsonPrimitive.double,
                null, ::journal).also { lease = it; it.startWatching() }
        } catch (error: Exception) { uncertain = true; throw error }
    }
    /** 同一owner・受取clientの一記録を消費します。取得・更新・返却責任は発行元に残ります。 */
    fun claim(handoff: String, owner: String, targetClient: String): ManagedLease {
        check(lease == null && !uncertain)
        try {
            val claimed = profile.bridge("claim", buildJsonObject {
                put("handoffId", handoff); put("owner", owner); put("targetClient", targetClient)
            })
            check(!uncertain) { "キャンセル後の引継ぎ応答は保全します。" }
            val record = claimed.getValue("handoff").jsonObject
            val checked = claimed.getValue("checked").jsonObject
            val token = record.getValue("token").jsonObject
            val fixed = buildJsonObject {
                put("expectedGeneration", token.getValue("expectedGeneration")); put("runId", record.getValue("runId"))
                put("nonce", record.getValue("nonce")); put("sessionId", record.getValue("sessionId"))
            }
            return ManagedLease(ManagedLease.Source.HANDOFF, owner, client, token, checked.getValue("expiresAt").jsonPrimitive.double,
                fixed, ::journal).also { lease = it; it.startWatching() }
        } catch (error: Exception) { uncertain = true; throw error }
    }
    fun abort() { uncertain = true; lease?.abort(); (client as? SshSupervisorClient)?.abandon() }
}
