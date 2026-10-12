package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import kotlinx.serialization.json.*

/** 画面の明示操作だけを貸出へ変換します。UNKNOWNや保持中の貸出を再acquireしません。 */
class ManagedConnectionService(val profile: ConnectionProfile, private val client: SupervisorClient = profile.client()) {
    var lease: ManagedLease? = null
        private set
    private var handoffId: String? = null
    @Volatile var uncertain = profile.pendingManagerOperation
        private set
    /** プロファイルの候補だけを返し、貸出tokenは列挙しません。 */
    fun instances(): List<JsonObject> {
        val candidates = profile.instances.map { it.getValue("instanceId").jsonPrimitive.content }.toSet()
        return client.request("list", buildJsonObject {}, operationId()).getValue("instances").jsonArray.map { it.jsonObject }
            .filter { it["instanceId"]?.jsonPrimitive?.content in candidates }
    }
    /** 同じprivate保存先からmanager宛ての期限内OFFERED記録を取得します。秘密tokenは返しません。 */
    fun handoffs(rows: List<JsonObject> = instances()): List<JsonObject> =
        profile.bridge("offers").getValue("offers").jsonArray.map { it.jsonObject }.filter { offered ->
            rows.any { row ->
                row["instanceId"] == offered["instanceId"] && row["owner"] == offered["owner"]
                    && row["epoch"] == offered["epoch"] && row["generation"] == offered["expectedGeneration"]
                    && row["runId"] == offered["runId"] && row["nonce"] == offered["nonce"]
                    && row["state"]?.jsonPrimitive?.content == "RUNNING" && row["leaseExpired"]?.jsonPrimitive?.boolean == false
                    && row["generationBound"]?.jsonPrimitive?.boolean == true
                    && row["writerSessions"]?.jsonArray?.none { it.jsonObject["status"]?.jsonPrimitive?.content == "UNKNOWN" } == true
            }
        }

    /** 選んだ枠へ明示接続します。保持貸出を再利用し、正常切断済みの引継ぎは同じ貸出へ再発行します。 */
    fun connect(instance: String, handoff: JsonObject?): ManagedLease {
        check(!uncertain)
        lease?.let { current ->
            if (current.state != ManagedLease.State.DETACHED) {
                check(current.instanceId == instance)
                current.ensureActive()
                return current
            }
            if (current.instanceId == instance) {
                val payload = buildJsonObject { put("handoffId", checkNotNull(handoffId)); put("owner", current.owner) }
                val operation = operationId()
                try {
                    journal("reconnect", payload, operation, null)
                    val offered = profile.bridge("reoffer", payload, operation)
                    journal("reconnect", payload, operation, offered)
                    lease = null
                    return claim(offered.getValue("handoffId").jsonPrimitive.content, current.owner, "manager")
                } catch (error: Exception) { uncertain = true; throw error }
            }
            lease = null
            handoffId = null
        }
        require(handoff != null) { "起動側でManagerへの引継ぎを発行してください。" }
        val selected = handoffs().single { it["handoffId"] == handoff["handoffId"] && it["instanceId"]?.jsonPrimitive?.content == instance }
        return claim(selected.getValue("handoffId").jsonPrimitive.content, selected.getValue("owner").jsonPrimitive.content, "manager")
    }

    /** 起動の進捗は、環境照合・貸出確認・世代準備・起動の完了工程数です。所要時間を推定しません。 */
    fun start(instance: String, preset: ManagedServerPreset, onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }) {
        check(!uncertain)
        onProgress(0, 4, "環境と枠を照合しています。")
        val row = instances().single { it["instanceId"]?.jsonPrimitive?.content == instance }
        val current = lease
        onProgress(1, 4, if (current == null) "貸出を取得しています。" else "保持貸出を照合しています。")
        val loan = if (current == null) {
            check(row["state"]?.jsonPrimitive?.content == "AVAILABLE" && row["owner"] == JsonNull)
            val preparation = preset.preparation(row["generation"] != JsonNull)
            borrow(instance, preset.owner).also {
                onProgress(2, 4, "世代を初期化しています。完了まで数分かかります。")
                it.prepareGeneration(preparation.first, preparation.second)
            }
        } else {
            check(current.source == ManagedLease.Source.MANAGER && current.instanceId == instance)
            val checked = current.checkLease()
            check(checked["state"]?.jsonPrimitive?.content in setOf("READY", "LEASED_STOPPED"))
            onProgress(2, 4, "保持する世代を確認しています。")
            if (checked["generationBound"]?.jsonPrimitive?.boolean != true) {
                val preparation = preset.preparation(checked["generation"] != JsonNull)
                current.prepareGeneration(preparation.first, preparation.second)
            }
            current
        }
        onProgress(3, 4, "サーバーを起動しています。")
        loan.runtime("start")
        onProgress(4, 4, "起動が完了しました。")
    }

    /** 貸出照合・templateからの準備・新世代照合の完了工程数を通知します。起動は別操作です。 */
    fun reset(instance: String, preset: ManagedServerPreset, onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }) {
        check(!uncertain)
        val loan = checkNotNull(lease)
        check(loan.source == ManagedLease.Source.MANAGER && loan.instanceId == instance)
        val preparation = preset.preparation(true)
        onProgress(0, 3, "貸出を照合しています。")
        val checked = loan.checkLease()
        check(checked["state"]?.jsonPrimitive?.content == "LEASED_STOPPED")
        onProgress(1, 3, "テンプレートからリセットしています。完了まで数分かかります。")
        loan.prepareGeneration(preparation.first, preparation.second)
        onProgress(2, 3, "準備済み世代を照合しています。")
        check(loan.checkLease()["state"]?.jsonPrimitive?.content == "READY")
        onProgress(3, 3, "リセットが完了しました。起動ボタンから起動できます。")
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
        val identifier = Regex("[a-z0-9][a-z0-9-]{0,127}")
        require(identifier.matches(handoff)) { "引継ぎIDは発行元から受け取った識別子を入力してください。" }
        require(identifier.matches(owner)) { "貸出ownerは小文字の英数字・ハイフンで入力してください。" }
        require(identifier.matches(targetClient)) { "受取clientは小文字の英数字・ハイフンで、発行元と同じ値を入力してください。" }
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
                fixed, ::journal).also { lease = it; handoffId = handoff; it.startWatching() }
        } catch (error: Exception) { uncertain = true; throw error }
    }
    fun abort() { uncertain = true; lease?.abort(); (client as? SshSupervisorClient)?.abandon() }
}
