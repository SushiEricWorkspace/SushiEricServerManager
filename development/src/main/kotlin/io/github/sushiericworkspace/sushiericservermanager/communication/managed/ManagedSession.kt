package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.Executor
import kotlin.concurrent.withLock

/** 世代・runを固定したwriter。閉鎖後の古いキューを再接続先へ送信しません。 */
class ManagedSession private constructor(
    val profile: ManagedProfile, private val client: SupervisorClient,
    private val binding: JsonObject, val workspace: File, val endpointPort: Int
) {
    enum class State { OPEN, CLOSING, CLOSED, UNKNOWN }
    private val lock = ReentrantLock()
    private val admission = Any()
    private var queued = 0
    @Volatile var state = State.OPEN
        private set
    val identity: String = workspace.name
    val autoSaveDirectory: File = workspace.resolve("autosave")
    val offlineDirectory: File = workspace.resolve("offline")
    val cacheDirectory: File = workspace.resolve("cache")

    /** immutable識別で要求します。送信後の結果不明ではwriter終了証明を出しません。 */
    fun io(request: JsonObject): JsonObject = performIo(request, accepted = false)

    /** 受理済みAPIはCLOSINGでも実行します。executorの破棄をdrain完了と扱いません。 */
    fun enqueueIo(request: JsonObject, executor: Executor, result: (JsonObject) -> Unit, failure: (Exception) -> Unit): Boolean =
        synchronized(admission) {
            if (state != State.OPEN) return@synchronized false
            queued++
            try {
                executor.execute {
                    try { result(performIo(request, accepted = true)) } catch (error: Exception) { failure(error) }
                    finally { synchronized(admission) { queued-- } }
                }
                true
            } catch (_: java.util.concurrent.RejectedExecutionException) { queued--; false }
        }

    private fun performIo(request: JsonObject, accepted: Boolean): JsonObject = lock.withLock {
        check(state == State.OPEN || (accepted && state == State.CLOSING)) { "管理writerは終了済み・結果不明です。" }
        try {
            client.request("writer-io", JsonObject(binding + ("request" to request)), operationId())
        } catch (error: Exception) {
            if (error !is SupervisorFailure || !error.rejectedBeforeEffect) state = State.UNKNOWN
            throw error
        }
    }

    /** 接続・キューの終了を証明できないとき、以後の受付と自動解除を禁止します。 */
    fun holdUnknown() { state = State.UNKNOWN }

    /** 受付を閉じて実行中I/Oを待ち、read-only socketを終了してから登録を閉じます。 */
    fun close(disconnect: () -> Unit) {
        synchronized(admission) {
            if (state == State.CLOSED) return
            check(state == State.OPEN) { "結果不明のwriterは自動解除できません。" }
            state = State.CLOSING
        }
        try {
            disconnect()
            lock.withLock {
                check(state == State.CLOSING) { "実行中I/Oが結果不明になりました。" }
                check(synchronized(admission) { queued == 0 }) { "受理済み保存キューが終了していません。" }
                val response = client.request("writer-close", JsonObject(binding + mapOf(
                    "drained" to JsonPrimitive(true), "disconnected" to JsonPrimitive(true))), operationId())
                check(response["closed"]?.jsonPrimitive?.boolean == true)
                state = State.CLOSED
            }
        } catch (error: Exception) {
            state = State.UNKNOWN
            throw error
        }
    }

    companion object {
        /** 照合済みRUNNINGに参加します。自動acquire/renew・世代追従は行いません。 */
        fun open(profile: ManagedProfile, storage: Path, client: SupervisorClient = CliSupervisorClient(profile)): ManagedSession {
            val status = client.request("status", buildJsonObject { put("instanceId", profile.instanceId) }, operationId())
                .getValue("instances").jsonArray.single().jsonObject
            check(status.getValue("state").jsonPrimitive.content == "RUNNING"
                && status.getValue("epoch").jsonPrimitive.long == profile.epoch
                && !status.getValue("leaseExpired").jsonPrimitive.boolean
                && status.getValue("generationBound").jsonPrimitive.boolean) { "貸出・起動状態が一致しません。" }
            val endpoint = status.getValue("managementEndpoint").jsonObject
            check(endpoint["address"]?.jsonPrimitive?.content == "127.0.0.1" && endpoint["protocol"]?.jsonPrimitive?.content == "tcp")
            val binding = buildJsonObject {
                put("instanceId", profile.instanceId); put("leaseId", profile.leaseId); put("epoch", profile.epoch)
                put("expectedGeneration", status.getValue("generation")); put("runId", status.getValue("runId"))
                put("nonce", status.getValue("nonce")); put("sessionId", operationId())
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(
                (profile.root.toString() + "\u0000" + binding.filterKeys { it != "leaseId" }.toString()).toByteArray())
                .joinToString("") { "%02x".format(it) }
            val workspace = storage.resolve("managed").resolve(hash).toFile()
            check(workspace.mkdirs()) { "既存session領域は再利用しません。" }
            val process = ProcessHandle.current()
            val request = JsonObject(binding + mapOf("pid" to JsonPrimitive(process.pid()),
                "startMillis" to JsonPrimitive(process.info().startInstant().orElseThrow().toEpochMilli()),
                "cwd" to JsonPrimitive(Path.of("").toRealPath().toString())))
            val session = ManagedSession(profile, client, binding, workspace, endpoint.getValue("port").jsonPrimitive.int)
            try {
                client.request("writer-open", request, operationId())
            } catch (error: Exception) {
                if (error is SupervisorFailure && error.rejectedBeforeEffect) throw error
                session.holdUnknown()
            }
            return session
        }
    }
}
