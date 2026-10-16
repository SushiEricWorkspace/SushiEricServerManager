package io.github.sushiericworkspace.sushiericservermanager.monitor

import io.github.sushiericworkspace.sushiericservermanager.communication.management.JvmStatus
import io.github.sushiericworkspace.sushiericservermanager.communication.management.MinecraftServerStatus
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementClient
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementRequest
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementResponse
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementState
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Management APIから監視情報を購読し、最新値を保持します。
 *
 * 取得元は[ServerMonitorSource.MANAGEMENT_API]に限られます。
 * CPU使用率などOSレベルの情報は取得元が異なるため、ここでは扱いません。
 *
 * 画面を離れる場合や切断時は[stop]で購読を止めます。
 * Management APIへ接続していない状態では値を持たず、購読要求も送りません。
 */
class ServerMonitor(
    private val client: ServerManagementClient
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val listeners =
        CopyOnWriteArrayList<(ServerMonitorSnapshot) -> Unit>()

    /** 最新の監視情報です。受信していない場合は値を持ちません。 */
    @Volatile
    var snapshot: ServerMonitorSnapshot = ServerMonitorSnapshot.EMPTY
        private set

    /** 購読中かを返します。 */
    @Volatile
    var isSubscribed: Boolean = false
        private set

    /** サーバーが適用した更新間隔です。未購読の場合はnullです。 */
    @Volatile
    var appliedIntervalTicks: Int? = null
        private set

    init {
        client.addMessageListener(::onMessage)
        client.addStateListener(::onStateChanged)
    }

    /**
     * 監視情報の更新を受け取ります。
     *
     * 通知はWebSocketのスレッドから呼ばれます。
     * UIを更新する場合は呼び出し側でJavaFXスレッドへ移してください。
     */
    fun addListener(
        listener: (ServerMonitorSnapshot) -> Unit
    ) {
        listeners.add(listener)
    }

    /** 監視情報のリスナーを解除します。 */
    fun removeListener(
        listener: (ServerMonitorSnapshot) -> Unit
    ) {
        listeners.remove(listener)
    }

    /**
     * 購読を開始します。
     *
     * 既に購読している場合は更新間隔を変更します。
     * Management APIへ接続していない場合は何もしません。
     *
     * @param intervalTicks 更新間隔。nullの場合はサーバー側の既定値を使用します。
     * @return 要求を送れた場合はtrue。
     */
    fun start(
        intervalTicks: Int? = null
    ): Boolean {
        if (!client.isConnected) {
            logger.info("Management APIへ接続していないため、監視情報の購読を開始しません。")
            return false
        }

        val sent =
            client.send(
                ServerManagementRequest.MonitorSubscribe(
                    intervalTicks = intervalTicks
                )
            )

        if (!sent) {
            logger.warn("監視情報の購読要求を送信できませんでした。")
        }

        return sent
    }

    /**
     * 購読を停止します。
     *
     * 接続していない場合も内部状態は未購読へ戻します。
     *
     * @return 要求を送れた場合はtrue。
     */
    fun stop(): Boolean {
        val sent =
            client.isConnected &&
                    client.send(ServerManagementRequest.MonitorUnsubscribe())

        markUnsubscribed()

        return sent
    }

    private fun onMessage(
        message: ServerManagementResponse
    ) {
        when (message) {
            is ServerManagementResponse.MonitorSubscription -> {
                isSubscribed = message.subscribed
                appliedIntervalTicks =
                    message.intervalTicks.takeIf { message.subscribed }

                logger.info(
                    "監視情報の購読状態が変わりました: subscribed={} intervalTicks={}",
                    message.subscribed,
                    message.intervalTicks
                )
            }

            is ServerManagementResponse.MonitorUpdate ->
                publish(
                    ServerMonitorSnapshot(
                        server = message.server,
                        jvm = message.jvm
                    )
                )

            else -> Unit
        }
    }

    private fun onStateChanged(
        state: ServerManagementState
    ) {
        if (state is ServerManagementState.Connected) {
            return
        }

        /*
         * 切断や接続失敗では購読が失われる。
         * 古い値を表示し続けないよう、保持している値も破棄する。
         */
        markUnsubscribed()
        publish(ServerMonitorSnapshot.EMPTY)
    }

    private fun markUnsubscribed() {
        isSubscribed = false
        appliedIntervalTicks = null
    }

    private fun publish(
        next: ServerMonitorSnapshot
    ) {
        snapshot = next

        listeners.forEach { listener ->
            runCatching { listener(next) }
                .onFailure {
                    logger.warn("監視情報の通知に失敗しました。", it)
                }
        }
    }
}

/**
 * 受信した監視情報の一式です。
 *
 * 取得元はいずれも[ServerMonitorSource.MANAGEMENT_API]です。
 *
 * @property server Minecraft内部の状態。未受信の場合はnull。
 * @property jvm サーバーのJVM状態。未受信の場合はnull。
 */
data class ServerMonitorSnapshot(
    val server: MinecraftServerStatus?,
    val jvm: JvmStatus?
) {
    /** 値を受信しているかを返します。 */
    val hasValue: Boolean
        get() = server != null && jvm != null

    /** この監視情報の取得元です。 */
    val source: ServerMonitorSource
        get() = ServerMonitorSource.MANAGEMENT_API

    companion object {
        /** 値を受信していない状態です。 */
        val EMPTY = ServerMonitorSnapshot(server = null, jvm = null)
    }
}
