package io.github.sushiericworkspace.sushiericservermanager.monitor.host

import io.github.sushiericworkspace.sushiericservermanager.communication.SshCommandRunner
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * SSH経由でホストOSの状態を定期的に取得します。
 *
 * 取得はSSHのセッションを開いてコマンドを実行するため、
 * 画面を更新するスレッドではなく専用のスレッドで行います。
 *
 * SSHへ接続していない間は取得を行わず、値を持たない状態を通知します。
 * 古い値を最新の状態として表示し続けないためです。
 *
 * @param sshManager 接続中のSSHクライアントとプロファイルの取得元。
 * @param commandRunner コマンドの実行手段。
 */
class HostMetricsMonitor(
    private val sshManager: SshManager,
    private val commandRunner: SshCommandRunner = SshCommandRunner()
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val listeners =
        CopyOnWriteArrayList<(HostMetrics) -> Unit>()

    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "host-metrics-monitor").apply {
                isDaemon = true
            }
        }

    private var task: ScheduledFuture<*>? = null

    /** 最新の取得結果です。取得していない場合は値を持ちません。 */
    @Volatile
    var metrics: HostMetrics = HostMetrics.EMPTY
        private set

    /** 取得中かを返します。 */
    val isRunning: Boolean
        get() = task?.isCancelled == false

    /**
     * 取得結果の更新を受け取ります。
     *
     * 通知は取得用のスレッドから呼ばれます。
     * 画面を更新する場合は呼び出し側でJavaFXスレッドへ移してください。
     */
    fun addListener(
        listener: (HostMetrics) -> Unit
    ) {
        listeners.add(listener)
    }

    /** 取得結果のリスナーを解除します。 */
    fun removeListener(
        listener: (HostMetrics) -> Unit
    ) {
        listeners.remove(listener)
    }

    /**
     * 定期取得を開始します。
     *
     * 既に開始している場合は何も行いません。
     *
     * @param intervalSeconds 取得の間隔。
     */
    @Synchronized
    fun start(
        intervalSeconds: Long = DEFAULT_INTERVAL_SECONDS
    ) {
        if (isRunning) {
            return
        }

        task = scheduler.scheduleWithFixedDelay(
            ::collect,
            0,
            intervalSeconds,
            TimeUnit.SECONDS
        )
    }

    /**
     * 定期取得を停止し、保持している値を破棄します。
     */
    @Synchronized
    fun stop() {
        task?.cancel(false)
        task = null

        publish(HostMetrics.EMPTY)
    }

    private fun collect() {
        val client = sshManager.sshClient

        if (client == null || !client.isConnected) {
            publish(HostMetrics.EMPTY)
            return
        }

        val operatingSystem =
            sshManager.currentProfile?.resolvedRemoteOperatingSystem()
                ?: RemoteOperatingSystem.UBUNTU_SERVER

        val platform = HostMetricsPlatform.of(operatingSystem)

        val result =
            commandRunner.run(client, HostMetricsCommands.build(platform))

        if (result == null) {
            publish(HostMetrics.EMPTY)
            return
        }

        if (!result.isSuccess) {
            logger.warn(
                "ホストOSの状態を取得できませんでした: exitStatus={} error={}",
                result.exitStatus,
                result.errorOutput.trim().take(ERROR_SUMMARY_LENGTH)
            )
        }

        publish(HostMetricsParser.parse(platform, result.output))
    }

    private fun publish(
        next: HostMetrics
    ) {
        metrics = next

        listeners.forEach { listener ->
            runCatching { listener(next) }
                .onFailure {
                    logger.warn("ホストOSの状態の通知に失敗しました。", it)
                }
        }
    }

    companion object {
        /** 取得の既定の間隔です。 */
        const val DEFAULT_INTERVAL_SECONDS: Long = 5

        /** 失敗を記録するときに残すエラー出力の長さです。 */
        private const val ERROR_SUMMARY_LENGTH = 200
    }
}
