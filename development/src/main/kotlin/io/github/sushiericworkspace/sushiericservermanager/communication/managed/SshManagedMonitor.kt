package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import io.github.sushiericworkspace.sushiericservermanager.communication.management.*
import io.github.sushiericworkspace.sushiericservermanager.communication.management.codec.ServerManagementMessageCodec
import io.github.sushiericworkspace.sushiericservermanager.communication.management.codec.ServerManagementDecodeResult
import kotlinx.serialization.json.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** SSH仲介の読み取り専用監視。遅延通知は自身のOPEN sessionだけへ渡します。 */
internal class SshManagedMonitor(private val session: ManagedSession, private val listener: ServerManagementListener) {
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "managed-ssh-monitor").apply { isDaemon = true } }
    @Volatile private var active = true
    private var subscribed = false
    private var interval = 20

    fun connect() {
        try {
            open(20)
            worker.scheduleWithFixedDelay({
                if (active && subscribed && session.state == ManagedSession.State.OPEN) {
                    try {
                        session.monitor("monitor-read", buildJsonObject { put("timeoutSeconds", 1) })
                            .getValue("events").jsonArray.forEach { notify(it.jsonObject) }
                    } catch (_: Exception) { failed() }
                }
            }, 200, 200, TimeUnit.MILLISECONDS)
        } catch (error: Exception) { failed(); throw error }
    }

    private fun open(ticks: Int) {
        val response = session.monitor("monitor-open", buildJsonObject { put("intervalTicks", ticks) })
        subscribed = true
        interval = ticks
        notify(response)
    }

    private fun closeSubscription() {
        if (!subscribed) return
        check(session.monitor("monitor-close", buildJsonObject {})["disconnected"]?.jsonPrimitive?.boolean == true)
        subscribed = false
    }

    fun send(request: ServerManagementRequest): Boolean {
        if (!active || session.state != ManagedSession.State.OPEN) return false
        if (request !is ServerManagementRequest.MonitorSubscribe && request !is ServerManagementRequest.MonitorUnsubscribe) return false
        if (request is ServerManagementRequest.MonitorSubscribe && request.intervalTicks != null && request.intervalTicks !in 1..1200) return false
        return try {
            worker.execute {
                if (!active || session.state != ManagedSession.State.OPEN) return@execute
                try {
                    closeSubscription()
                    when (request) {
                        is ServerManagementRequest.MonitorSubscribe -> open(request.intervalTicks ?: 20)
                        is ServerManagementRequest.MonitorUnsubscribe -> notify(buildJsonObject {
                            put("type", "monitor_subscription"); put("subscribed", false); put("intervalTicks", interval)
                            request.nonce?.let { put("nonce", it) }
                        })
                        else -> error("監視購読以外は送信できません。")
                    }
                } catch (_: Exception) { failed() }
            }
            true
        } catch (_: java.util.concurrent.RejectedExecutionException) { false }
    }

    private fun notify(response: JsonObject) {
        val decoded = ServerManagementMessageCodec.decode(response.toString())
        check(decoded is ServerManagementDecodeResult.Success)
        if (active && session.state == ManagedSession.State.OPEN) listener.onMessage(decoded.message)
    }

    private fun failed() {
        session.holdUnknown()
        if (active) { active = false; worker.shutdown(); listener.onError(IllegalStateException("SSH監視の終了が未確認です。未保存編集と診断を保全してください。")) }
    }

    fun disconnect() {
        active = false
        worker.shutdown()
        check(worker.awaitTermination(45, TimeUnit.SECONDS)) { "SSH監視の実行中要求が残っています。" }
        closeSubscription()
    }
}
