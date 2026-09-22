package io.github.sushiericworkspace.sushiericservermanager.config

import kotlinx.serialization.Serializable

/**
 * アプリケーション全体の設定です。
 *
 * 追加項目はすべてデフォルト値を持たせ、既存のconfig.jsonをそのまま読み込めるようにします。
 *
 * @property monitorIntervalTicks 監視情報の更新間隔。サーバー側のtick数で指定します。
 */
@Serializable
data class AppSettings(
    val monitorIntervalTicks: Int = DEFAULT_MONITOR_INTERVAL_TICKS,
    val sidebarDirectoryExpanded: Map<String, Map<String, Boolean>> = emptyMap()
) {
    /**
     * 指定できる範囲へ丸めた更新間隔を返します。
     *
     * 範囲外の値が保存されていても、サーバーへそのまま送らないようにします。
     */
    fun resolvedMonitorIntervalTicks(): Int =
        monitorIntervalTicks.coerceIn(MONITOR_INTERVAL_TICKS_RANGE)

    companion object {
        /**
         * 監視情報の更新間隔の既定値です。
         *
         * サーバーは1秒あたり20tickで動作するため、20tickで1秒間隔になります。
         */
        const val DEFAULT_MONITOR_INTERVAL_TICKS: Int = 20

        /**
         * 指定できる更新間隔の範囲です。
         *
         * サーバー側が受け付ける範囲と一致させます。
         */
        val MONITOR_INTERVAL_TICKS_RANGE: IntRange = 1..1200
    }
}

/**
 * config.jsonへアプリケーション設定を読み書きします。
 */
object AppSettingsManager : JsonFileHandler<AppSettings>(
    FilePath.SETTINGS,
    { AppSettings() },
    AppSettings.serializer()
)
