package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import kotlinx.serialization.json.*
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 貸出の取得元を保持し、借用分だけrenewします。停止・返却は明示操作であり切断から呼びません。 */
class ManagedLease internal constructor(
    val source: Source, val owner: String, private val client: SupervisorClient,
    token: JsonObject, private var expiresAt: Double, private val binding: JsonObject?,
    private val journal: (String, JsonObject, String, JsonObject?) -> Unit,
    private val clock: () -> Double = { Instant.now().toEpochMilli() / 1000.0 }
) {
    var token = token
        private set
    enum class Source { MANAGER, HANDOFF }
    enum class State { ACTIVE, UNKNOWN, RELEASED, DETACHED }
    @Volatile var state = State.ACTIVE
        private set
    private val scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "managed-lease-watch").apply { isDaemon = true } }
    private var connectionTaken = false
    private var onFailure: () -> Unit = {}
    val instanceId = token.getValue("instanceId").jsonPrimitive.content
    val responsibility: String get() = if (source == Source.MANAGER)
        "取得元：Manager（$owner）。renewはこのアプリが担当。切断してもサーバー・貸出は残ります。終了前に停止・返却を確認してください。"
        else "取得元：引継ぎ元（$owner）。renew・サーバー・返却の責任は発行元に残ります。Managerは自身のwriterだけを終了します。"

    @Synchronized fun ensureActive() {
        if (state == State.ACTIVE && expiresAt <= clock()) holdUnknown()
        check(state == State.ACTIVE && expiresAt > clock()) { "貸出が期限切れまたは結果不明です。編集は保全してください。" }
    }

    @Synchronized fun checkLease(): JsonObject {
        ensureActive()
        try {
            val checked = client.request("lease-check", JsonObject(token + ("owner" to JsonPrimitive(owner))), operationId())
            check(checked["epoch"] == token["epoch"] && checked["generation"] == token["expectedGeneration"]
                && checked["owner"]?.jsonPrimitive?.content == owner && checked["instanceId"] == token["instanceId"])
            check(checked.getValue("expiresAt").jsonPrimitive.double > clock())
            if (binding != null) {
                check(checked["runId"] == binding["runId"] && checked["nonce"] == binding["nonce"])
                check(checked["state"]?.jsonPrimitive?.content == "RUNNING" && checked["generationBound"]?.jsonPrimitive?.boolean == true
                    && checked["pending"]?.jsonPrimitive?.int == 0
                    && checked["writerSessions"]?.jsonArray?.none { it.jsonObject["status"]?.jsonPrimitive?.content == "UNKNOWN" } == true)
            }
            expiresAt = checked.getValue("expiresAt").jsonPrimitive.double
            return checked
        } catch (error: Exception) { holdUnknown(); throw error }
    }

    /** schedulerを含む接続監視。引継ぎでは検査だけ行い発行元の期限を更新しません。 */
    fun startWatching() { scheduler.scheduleWithFixedDelay({ try { tick() } catch (_: Exception) { holdUnknown() } }, 10, 10, TimeUnit.SECONDS) }

    @Synchronized internal fun tick() {
        try {
            ensureActive()
            if (source == Source.MANAGER) {
                val payload = JsonObject(token + ("ttlSeconds" to JsonPrimitive(3600)))
                val operation = operationId()
                journal("renew", payload, operation, null)
                val response = client.request("renew", payload, operation)
                check(response["instanceId"] == token["instanceId"] && response.getValue("expiresAt").jsonPrimitive.double > clock())
                journal("renew", payload, operation, response)
                expiresAt = response.getValue("expiresAt").jsonPrimitive.double
            } else checkLease()
        } catch (error: Exception) { holdUnknown(); throw error }
    }

    /** RUNNINGかつ固定世代の正規lease-checkからwriterの識別を作ります。 */
    @Synchronized fun connectionProfile(profile: ConnectionProfile): ManagedProfile {
        check(source == Source.MANAGER || !connectionTaken) { "引継ぎsessionは一度だけ使用できます。" }
        val checked = checkLease()
        check(checked["state"]?.jsonPrimitive?.content == "RUNNING" && checked["generationBound"]?.jsonPrimitive?.boolean == true)
        val fixed = binding ?: buildJsonObject {
            put("expectedGeneration", token.getValue("expectedGeneration")); put("runId", checked.getValue("runId"))
            put("nonce", checked.getValue("nonce")); put("sessionId", operationId())
        }
        connectionTaken = true
        return ManagedProfile(profile.root, profile.python, profile.cli, instanceId, token.getValue("leaseId").jsonPrimitive.content,
            token.getValue("epoch").jsonPrimitive.long, fixed)
    }

    /** 引継ぎwriterが正常終了した後だけ接続権を終了します。貸出のreleaseではありません。 */
    @Synchronized fun writerClosed() {
        onFailure = {}
        connectionTaken = false
        if (source == Source.HANDOFF && state == State.ACTIVE) { state = State.DETACHED; scheduler.shutdown() }
    }

    @Synchronized fun watchFailure(callback: () -> Unit) { onFailure = callback; if (state != State.ACTIVE) callback() }
    @Synchronized fun holdUnknown() {
        if (state != State.ACTIVE) return
        state = State.UNKNOWN
        scheduler.shutdown()
        onFailure()
    }
    /** 接続と独立した明示の世代準備。戻された世代だけを自身のtokenへ固定します。 */
    @Synchronized fun prepareGeneration(command: String, options: JsonObject) {
        require(source == Source.MANAGER && command in setOf("prepare", "reset", "restore"))
        ensureActive()
        check(!connectionTaken) { "writerを終了してから世代を準備してください。" }
        val payload = JsonObject(token + options)
        val operation = operationId()
        try {
            journal(command, payload, operation, null)
            val response = client.request(command, payload, operation)
            check(response["prepared"]?.jsonPrimitive?.boolean == true && response["instanceId"] == token["instanceId"]
                && response["generation"] == options["generationId"])
            journal(command, payload, operation, response)
            token = JsonObject(token + ("expectedGeneration" to response.getValue("generation")))
        } catch (error: Exception) { holdUnknown(); throw error }
    }
    /** Manager取得分の起動・正常停止だけを明示実行します。writerを終了してからstopを選びます。 */
    @Synchronized fun runtime(command: String) {
        require(source == Source.MANAGER && command in setOf("start", "stop"))
        ensureActive()
        check(!connectionTaken) { "writerを終了してから起動・停止してください。" }
        val checked = checkLease()
        check(checked["generationBound"]?.jsonPrimitive?.boolean == true)
        val payload = JsonObject(token + ("timeoutSeconds" to JsonPrimitive(120)))
        val operation = operationId()
        try {
            journal(command, payload, operation, null)
            val response = client.request(command, payload, operation)
            check(response["instanceId"] == token["instanceId"] && response["generation"] == token["expectedGeneration"])
            if (command == "start") check(response["running"]?.jsonPrimitive?.boolean == true)
            else check(response["runtimeStopped"]?.jsonPrimitive?.boolean == true && response["modSaveCompleted"]?.jsonPrimitive?.boolean == true
                && response["jvmAndChildrenExited"]?.jsonPrimitive?.boolean == true && response["exitCode"]?.jsonPrimitive?.int == 0)
            journal(command, payload, operation, response)
        } catch (error: Exception) { holdUnknown(); throw error }
    }

    /** Manager取得分だけを、writer終了後の明示操作で返却します。監督が停止証明も検査します。 */
    @Synchronized fun release() {
        require(source == Source.MANAGER)
        ensureActive()
        check(!connectionTaken) { "writerを終了してから返却してください。" }
        val operation = operationId()
        try {
            journal("release", token, operation, null)
            val response = client.request("release", token, operation)
            check(response["released"]?.jsonPrimitive?.boolean == true)
            journal("release", token, operation, response)
            state = State.RELEASED
            scheduler.shutdown()
        } catch (error: Exception) { holdUnknown(); throw error }
    }
    /** アプリ異常終了ではClose/releaseを推測せず、private操作記録を残します。 */
    fun abort() { holdUnknown() }
}
