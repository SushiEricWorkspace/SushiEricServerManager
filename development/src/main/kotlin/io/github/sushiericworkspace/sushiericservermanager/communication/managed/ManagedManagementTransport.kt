package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import io.github.sushiericworkspace.sushiericservermanager.communication.management.*
import io.github.sushiericworkspace.sushiericservermanager.communication.management.codec.ServerManagementMessageCodec
import io.github.sushiericworkspace.sushiericservermanager.communication.management.codec.ServerManagementDecodeResult
import kotlinx.serialization.json.*
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 固定endpointの監視と、監督経由の変更要求を分離します。再接続は行いません。 */
class ManagedManagementTransport(private val session: ManagedSession) : ManagementTransport {
    private lateinit var listener: ServerManagementListener
    private var connection: ServerManagementConnection? = null
    private var sshMonitor: SshManagedMonitor? = null
    private val managedSender = Executors.newSingleThreadExecutor {
        Thread(it, "managed-api-write").apply { isDaemon = true }
    }

    override fun connect(listener: ServerManagementListener, timeout: Duration): Int {
        this.listener = listener
        if (session.ssh) {
            val monitor = SshManagedMonitor(session, listener)
            sshMonitor = monitor
            monitor.connect()
            return session.endpointPort
        }
        val guarded = object : ServerManagementListener {
            override fun onMessage(message: ServerManagementResponse) = listener.onMessage(message)
            override fun onClosed(statusCode: Int, reason: String) {
                if (session.state == ManagedSession.State.OPEN) session.holdUnknown()
                listener.onClosed(statusCode, reason)
            }
            override fun onError(error: Throwable) { session.holdUnknown(); listener.onError(error) }
        }
        val opened = ServerManagementConnection(guarded)
        connection = opened
        try {
            opened.connect(session.endpointPort, timeout)
            return session.endpointPort
        } catch (_: Exception) {
            session.holdUnknown()
            error("管理監視socketへ接続できません。writer登録は保留されています。")
        }
    }

    override fun disconnect() {
        managedSender.shutdown()
        check(managedSender.awaitTermination(if (session.ssh) 45 else 20, TimeUnit.SECONDS)) { "管理APIのキューが終了していません。" }
        sshMonitor?.disconnect()
        sshMonitor = null
        connection?.closeVerified()
        connection = null
    }

    override fun send(request: ServerManagementRequest): Boolean {
        if (session.state != ManagedSession.State.OPEN) return false
        return when (request) {
            is ServerManagementRequest.CommandExecute,
            is ServerManagementRequest.MoneyHistorySave,
            is ServerManagementRequest.AchievementSave,
            is ServerManagementRequest.AchievementHistoryRequest,
            is ServerManagementRequest.Ping,
            is ServerManagementRequest.CommandComplete -> if (session.ssh || request !is ServerManagementRequest.Ping && request !is ServerManagementRequest.CommandComplete) session.enqueueIo(buildJsonObject {
                put("action", "api"); put("message", Json.parseToJsonElement(ServerManagementMessageCodec.encode(request)))
                put("timeoutSeconds", 5)
            }, managedSender, result = { result ->
                    try {
                        check(result["completed"]?.jsonPrimitive?.booleanOrNull == true)
                        val response = result["apiResponse"] ?: error("管理API応答がありません。")
                        val decoded = ServerManagementMessageCodec.decode(response.toString())
                        check(decoded is ServerManagementDecodeResult.Success) { "管理API応答を解釈できません。" }
                        listener.onMessage(decoded.message)
                    } catch (_: Exception) {
                        session.holdUnknown()
                        listener.onError(IllegalStateException("管理APIの完了が不明です。writer登録は保留されています。"))
                    }
            }, failure = { error ->
                if (error !is SupervisorFailure || !error.rejectedBeforeEffect) session.holdUnknown()
                listener.onError(IllegalStateException("管理API要求を完了できませんでした。監督statusを確認してください。"))
            }) else connection?.send(request) == true
            // 監視socketでは状態を変更する要求を送信しません。
            is ServerManagementRequest.MonitorSubscribe,
            is ServerManagementRequest.MonitorUnsubscribe,
            ServerManagementRequest.ConsoleSubscribe,
            ServerManagementRequest.ConsoleUnsubscribe -> sshMonitor?.send(request) ?: (connection?.send(request) == true)
        }
    }

}
