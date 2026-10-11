package io.github.sushiericworkspace.sushiericservermanager.editor.session

import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ManagementReconnectPolicy
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementClient
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementState
import io.github.sushiericworkspace.sushiericservermanager.config.AppSettingsManager
import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfile
import io.github.sushiericworkspace.sushiericservermanager.monitor.ServerMonitor
import io.github.sushiericworkspace.sushiericservermanager.monitor.host.HostMetricsMonitor
import io.github.sushiericworkspace.sushiericservermanager.app.AppMode
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.ManagedEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.communication.managed.ManagedProfile
import io.github.sushiericworkspace.sushiericservermanager.communication.managed.ManagedSession
import io.github.sushiericworkspace.sushiericservermanager.communication.managed.SupervisorFailure
import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import io.github.sushiericworkspace.sushiericservermanager.update.evaluateManagerCompatibility
import io.github.sushiericworkspace.common.path.SushiEricDataDirectory
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.properties.ReadOnlyProperty

object EditorSession {
    private val logger = LoggerFactory.getLogger(EditorSession::class.java)

    var sshManager = SshManager()

    /**
     * Management APIのクライアントです。
     *
     * SSH接続の確立後に非同期で接続します。接続できなくてもSSHとSFTPは
     * 利用できるため、失敗しても以降の処理を止めません。
     */
    val managementClient = ServerManagementClient()

    /**
     * 監視情報の購読と保持を行います。
     *
     * Management APIへ接続できた場合だけ購読を開始します。
     */
    val serverMonitor = ServerMonitor(managementClient)

    /**
     * ホストOSの状態をSSH経由で取得します。
     *
     * Management APIでは取得できないCPUとシステムメモリを扱います。
     * 取得はDashboardを開いている間だけ行います。
     */
    val hostMetricsMonitor = HostMetricsMonitor(sshManager)

    var dataService: EditorDataService? = null
        private set
    var managedSession: ManagedSession? = null
        private set
    var managedCompatibility: ManagerCompatibility = ManagerCompatibility.Unchecked
        private set

    var mode: AppMode? = null
        private set

    private val reconnectPolicy = ManagementReconnectPolicy()

    private val reconnectScheduler =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "management-api-reconnect").apply {
                isDaemon = true
            }
        }

    /**
     * 自動再接続を行ってよいかを表します。
     *
     * 利用者が切断を選んだ場合は再試行しないよう`false`にします。
     */
    @Volatile
    private var autoReconnectEnabled = false

    /**
     * 接続処理の実行中を表します。
     *
     * 接続処理の内部で切断が通知されるため、
     * その状態変化で再試行を重ねて予約しないよう使用します。
     */
    @Volatile
    private var connecting = false

    init {
        managementClient.addStateListener(::onManagementStateChanged)
    }

    fun prepareOnlineMode() {
        check(managedSession == null) { "管理writerを終了する前にSSH profileへ切り替えられません。" }
        mode = AppMode.ONLINE
        dataService = null
    }

    fun startOnlineSession() {
        check(managedSession == null)
        mode = AppMode.ONLINE
        dataService = EditorDataService(sshManager)

        autoReconnectEnabled = true
        reconnectPolicy.reset()

        connectManagementApi()
    }

    /**
     * Management APIへ接続し直します。
     *
     * 自動の再試行を打ち切ったあとでも、この操作で改めて接続を試せます。
     * 接続済みの場合とSSH接続が無い場合は何も行いません。
     */
    fun reconnectManagementApi() {
        if (managedSession != null) return
        if (mode != AppMode.ONLINE || managementClient.isConnected) {
            return
        }

        autoReconnectEnabled = true
        reconnectPolicy.reset()

        connectManagementApi()
    }

    /**
     * Management APIへ非同期で接続します。
     *
     * 接続には待ち時間があるため、UIスレッドを止めないよう別スレッドで行います。
     * 結果は[ServerManagementClient.state]と状態リスナーで受け取ります。
     */
    private fun connectManagementApi() {
        if (sshManager.sshClient == null) {
            return
        }

        Thread({
            if (!runConnect()) {
                scheduleReconnect()
            }
        }, "management-api-connect").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Management APIへ接続し、成功した場合は監視の購読を開始します。
     *
     * 呼び出したスレッドで完了まで待つため、UIスレッドから直接呼び出しません。
     *
     * @return 接続できた場合は`true`。
     */
    private fun runConnect(): Boolean {
        val client = sshManager.sshClient
            ?: return false

        val profile = sshManager.currentProfile

        val remotePort =
            profile
                ?.resolvedManagementPort()
                ?: ServerProfile.DEFAULT_MANAGEMENT_PORT

        logger.info(
            "Management APIへ接続します: profile={} managementPort={}",
            profile?.name,
            remotePort
        )

        connecting = true

        return try {
            val connected = managementClient.connect(client, remotePort)

            if (connected) {
                serverMonitor.start(
                    AppSettingsManager.load().resolvedMonitorIntervalTicks()
                )
            }

            connected
        } finally {
            connecting = false
        }
    }

    /**
     * 接続状態の変化を受け取り、切断された場合は再接続を予約します。
     */
    private fun onManagementStateChanged(
        state: ServerManagementState
    ) {
        when (state) {
            is ServerManagementState.Connected -> reconnectPolicy.reset()

            ServerManagementState.Disconnected,
            is ServerManagementState.Failed -> scheduleReconnect()

            ServerManagementState.Connecting -> Unit
        }
    }

    /**
     * 次の再接続を予約します。
     *
     * 待機はデーモンスレッドで行うため、画面の操作を妨げません。
     * SSH接続が失われている場合は、Tunnelを張り直せないため予約しません。
     */
    private fun scheduleReconnect() {
        if (!autoReconnectEnabled || connecting) {
            return
        }

        if (sshManager.sshClient == null) {
            logger.info("SSH接続が無いため、Management APIの自動再接続は行いません。")
            return
        }

        val delaySeconds = reconnectPolicy.nextDelaySeconds()

        if (delaySeconds == null) {
            logger.warn(
                "Management APIの自動再接続を打ち切りました。再接続するには手動の操作が必要です。上限={}",
                reconnectPolicy.maxAttempts
            )
            return
        }

        logger.info(
            "Management APIへ{}秒後に再接続します。試行={}/{}",
            delaySeconds,
            reconnectPolicy.attemptCount,
            reconnectPolicy.maxAttempts
        )

        reconnectScheduler.schedule(
            ::attemptReconnect,
            delaySeconds,
            TimeUnit.SECONDS
        )
    }

    private fun attemptReconnect() {
        if (!autoReconnectEnabled) {
            return
        }

        if (!runConnect()) {
            scheduleReconnect()
        }
    }

    fun startOfflineSession(store: EditorDataStore) {
        check(managedSession == null)
        mode = AppMode.OFFLINE
        dataService = EditorDataService(store)
    }

    fun disconnect() {
        /*
         * 利用者が切断を選んだ場合は、以降の状態変化で再接続を予約しない。
         */
        autoReconnectEnabled = false
        val managed = managedSession
        if (managed != null) {
            // 失敗時は参照を残して、別profileへの切替を許可しません。
            managed.close { managementClient.disconnect() }
            serverMonitor.stop()
            managedSession = null
            managedCompatibility = ManagerCompatibility.Unchecked
            dataService = null
            return
        }

        if (mode != AppMode.OFFLINE) {
            /*
             * SSHを切ると取得できなくなるため、先に停止する。
             */
            hostMetricsMonitor.stop()

            /*
             * SSHを切るとTunnelも使用できなくなるため、
             * Management APIを先に閉じてTunnelを解放する。
             */
            /*
             * 購読を止めてから接続を閉じる。
             * 接続を先に閉じるとunsubscribeを送れなくなる。
             */
            serverMonitor.stop()
            managementClient.disconnect()
            sshManager.disconnect()
        }
        dataService = null
    }

    fun resetMode() {
        disconnect()
        mode = null
    }

    /** 接続・登録・互換性読込はUIスレッド外で実行します。 */
    fun startManagedSession(profile: ManagedProfile) {
        check(managedSession == null && dataService == null && !sshManager.isConnected)
        autoReconnectEnabled = false
        val session = ManagedSession.open(profile, FilePath.dataDirectory().toPath())
        managedSession = session
        val compatibility = try {
            val text = session.io(buildJsonObject {
                put("action", "read"); put("path", SushiEricDataDirectory.ManagerCompat().getRawPath())
            }).getValue("text").jsonPrimitive.content
            evaluateManagerCompatibility(text)
        } catch (error: SupervisorFailure) {
            if (error.code == "FILE_NOT_FOUND") ManagerCompatibility.Missing else throw error
        }
        managedCompatibility = compatibility
        val store = ManagedEditorDataStore(session, compatibility)
        check(managementClient.connectManaged(session)) { "管理監視接続を確立できません。writer登録は保全されています。" }
        mode = AppMode.ONLINE
        dataService = EditorDataService(store, session.autoSaveDirectory)
        serverMonitor.start(AppSettingsManager.load().resolvedMonitorIntervalTicks())
    }
}

fun <T : Any> sessionValue(provider: () -> T?): ReadOnlyProperty<Any, T?> =
    ReadOnlyProperty { _, _ ->
        provider() // null ならそのまま null を返す
    }
