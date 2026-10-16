package io.github.sushiericworkspace.sushiericservermanager.feature.dashboard

import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementState
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.monitor.ServerMonitorSnapshot
import io.github.sushiericworkspace.sushiericservermanager.monitor.host.HostMetrics
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import java.net.URL
import java.util.ResourceBundle

/**
 * サーバーの状態を一覧するDashboard画面です。
 *
 * Management API経由で取得するMinecraftの情報と、SSH経由で取得するホストOSの情報を、
 * 取得元ごとに分けて表示します。値を受け取れていない項目は「未取得」と示し、
 * 0であることや前回の値と区別できるようにします。
 */
class DashboardController : Initializable {

    @FXML private lateinit var rootPane: ScrollPane
    @FXML private lateinit var managementConnectionValue: Label
    @FXML private lateinit var sshConnectionValue: Label
    @FXML private lateinit var serverStateValue: Label
    @FXML private lateinit var tpsValue: Label
    @FXML private lateinit var msptValue: Label
    @FXML private lateinit var playersValue: Label
    @FXML private lateinit var uptimeValue: Label
    @FXML private lateinit var heapValue: Label
    @FXML private lateinit var cpuValue: Label
    @FXML private lateinit var memoryValue: Label

    private val client = EditorSession.managementClient
    private val serverMonitor = EditorSession.serverMonitor
    private val hostMetricsMonitor = EditorSession.hostMetricsMonitor

    private val stateListener: (ServerManagementState) -> Unit = { state ->
        runOnFxThread { applyConnectionState(state) }
    }

    private val snapshotListener: (ServerMonitorSnapshot) -> Unit = { snapshot ->
        runOnFxThread { applyServerSnapshot(snapshot) }
    }

    private val hostMetricsListener: (HostMetrics) -> Unit = { metrics ->
        runOnFxThread { applyHostMetrics(metrics) }
    }

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        client.addStateListener(stateListener)
        serverMonitor.addListener(snapshotListener)
        hostMetricsMonitor.addListener(hostMetricsListener)

        applyConnectionState(client.state)
        applyServerSnapshot(serverMonitor.snapshot)
        applyHostMetrics(hostMetricsMonitor.metrics)

        /*
         * ホストOSの取得はSSHのセッションを使うため、画面を開いている間だけ行う。
         */
        hostMetricsMonitor.start()
    }

    /** 画面が閉じられたとき、登録したリスナーと取得処理を解放します。 */
    fun dispose() {
        client.removeStateListener(stateListener)
        serverMonitor.removeListener(snapshotListener)
        hostMetricsMonitor.removeListener(hostMetricsListener)
        hostMetricsMonitor.stop()
    }

    private fun applyConnectionState(state: ServerManagementState) {
        val managementConnected = state is ServerManagementState.Connected

        applyConnection(
            label = managementConnectionValue,
            connected = managementConnected,
            connectedText = "接続済み",
            disconnectedText = when (state) {
                ServerManagementState.Connecting -> "接続中..."
                is ServerManagementState.Failed -> "未接続（${state.reason}）"
                else -> "未接続"
            }
        )

        /*
         * Management APIへ接続できていれば、Minecraftサーバーは動作している。
         */
        applyConnection(
            label = serverStateValue,
            connected = managementConnected,
            connectedText = "Online",
            disconnectedText = "Offline"
        )

        applySshConnection()
    }

    private fun applySshConnection() {
        applyConnection(
            label = sshConnectionValue,
            connected = EditorSession.sshManager.isConnected,
            connectedText = "接続済み",
            disconnectedText = "未接続"
        )
    }

    private fun applyServerSnapshot(snapshot: ServerMonitorSnapshot) {
        val server = snapshot.server
        val jvm = snapshot.jvm

        setValue(tpsValue, DashboardFormat.ticksPerSecond(server?.ticksPerSecond))
        setValue(msptValue, DashboardFormat.millisPerTick(server?.millisPerTick))
        setValue(
            playersValue,
            DashboardFormat.players(server?.onlinePlayerCount, server?.maxPlayerCount)
        )
        setValue(uptimeValue, DashboardFormat.uptime(server?.uptimeSeconds))
        setValue(
            heapValue,
            DashboardFormat.bytesPair(jvm?.heapUsedBytes, jvm?.heapMaxBytes ?: jvm?.heapCommittedBytes)
        )
    }

    private fun applyHostMetrics(metrics: HostMetrics) {
        setValue(cpuValue, DashboardFormat.percent(metrics.cpuUsagePercent))
        setValue(
            memoryValue,
            DashboardFormat.bytesPair(metrics.memoryUsedBytes, metrics.memoryTotalBytes)
        )

        /*
         * SSHの状態は通知されないため、取得の周期に合わせて見直す。
         */
        applySshConnection()
    }

    private fun setValue(label: Label, text: String) {
        label.text = text
        label.styleClass.removeAll(STATE_STYLE_CLASSES)

        if (text == DashboardFormat.MISSING) {
            label.styleClass.add(MISSING_STYLE_CLASS)
        }
    }

    private fun applyConnection(
        label: Label,
        connected: Boolean,
        connectedText: String,
        disconnectedText: String
    ) {
        label.text = if (connected) connectedText else disconnectedText
        label.styleClass.removeAll(STATE_STYLE_CLASSES)
        label.styleClass.add(if (connected) ONLINE_STYLE_CLASS else OFFLINE_STYLE_CLASS)
    }

    private fun runOnFxThread(action: () -> Unit) {
        if (Platform.isFxApplicationThread()) {
            action()
        } else {
            Platform.runLater(action)
        }
    }

    private companion object {
        const val MISSING_STYLE_CLASS = "dashboard-value-missing"
        const val ONLINE_STYLE_CLASS = "dashboard-value-online"
        const val OFFLINE_STYLE_CLASS = "dashboard-value-offline"

        val STATE_STYLE_CLASSES =
            listOf(MISSING_STYLE_CLASS, ONLINE_STYLE_CLASS, OFFLINE_STYLE_CLASS)
    }
}
