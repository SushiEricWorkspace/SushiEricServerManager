package io.github.sushiericworkspace.sushiericservermanager.config

import kotlinx.serialization.Serializable

/**
 * アプリケーション全体の設定です。
 *
 * 追加項目はすべてデフォルト値を持たせ、既存のconfig.jsonをそのまま読み込めるようにします。
 *
 * @property monitorIntervalTicks 監視情報の更新間隔。サーバー側のtick数で指定します。
 * @property showPlayerUuidInHistory 入出金履歴のプレイヤー選択欄でUUIDを表示するか。
 * @property windowSizes 画面種別ごとの装飾込み通常外寸と最大化状態。
 */
@Serializable
data class AppSettings(
    val monitorIntervalTicks: Int = DEFAULT_MONITOR_INTERVAL_TICKS,
    val sidebarDirectoryExpanded: Map<String, Map<String, Boolean>> = emptyMap(),
    val showPlayerUuidInHistory: Boolean = false,
    val windowSizes: Map<String, WindowSize> = emptyMap()
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

/** 装飾込みの通常外寸と最大化状態。位置と最小化状態は保存しません。 */
@Serializable
data class WindowSize(val width: Double, val height: Double, val maximized: Boolean = false) {
    /** 不正な保存寸法は共通初期値で開くために除外します。 */
    fun isValid(): Boolean = width.isFinite() && height.isFinite() && width > 0 && height > 0
}
