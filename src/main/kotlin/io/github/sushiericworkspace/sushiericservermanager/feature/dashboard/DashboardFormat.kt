package io.github.sushiericworkspace.sushiericservermanager.feature.dashboard

import java.util.Locale

/**
 * Dashboardへ表示する値の書式を組み立てます。
 *
 * 値を受け取れていない項目は[MISSING]を返し、0であることと区別できるようにします。
 */
internal object DashboardFormat {

    /** 値を取得できていないことを表す文字列です。 */
    const val MISSING = "未取得"

    private const val BYTES_PER_UNIT = 1024.0

    private val BYTE_UNITS = listOf("B", "KB", "MB", "GB", "TB")

    /** ティック毎秒を小数第2位まで表します。 */
    fun ticksPerSecond(value: Double?): String =
        value?.let { format("%.2f", it) } ?: MISSING

    /** 1ティックあたりの処理時間をミリ秒で表します。 */
    fun millisPerTick(value: Double?): String =
        value?.let { "${format("%.2f", it)} ms" } ?: MISSING

    /** 使用率を百分率で表します。 */
    fun percent(value: Double?): String =
        value?.let { "${format("%.1f", it)} %" } ?: MISSING

    /** 接続中の人数と上限を表します。 */
    fun players(online: Int?, maximum: Int?): String =
        if (online == null || maximum == null) MISSING else "$online / $maximum"

    /**
     * 使用量と総量を単位付きで表します。
     *
     * 総量が分からない場合は使用量だけを表します。
     */
    fun bytesPair(used: Long?, total: Long?): String =
        when {
            used == null && total == null -> MISSING
            used == null -> "$MISSING / ${bytes(total)}"
            total == null -> bytes(used)
            else -> "${bytes(used)} / ${bytes(total)}"
        }

    /**
     * バイト数を読みやすい単位へ換算します。
     *
     * 1024で割り切れる範囲で単位を上げ、小数第1位まで表します。
     */
    fun bytes(value: Long?): String {
        if (value == null) {
            return MISSING
        }

        if (value < 0) {
            return MISSING
        }

        var amount = value.toDouble()
        var unitIndex = 0

        while (amount >= BYTES_PER_UNIT && unitIndex < BYTE_UNITS.lastIndex) {
            amount /= BYTES_PER_UNIT
            unitIndex++
        }

        val text =
            if (unitIndex == 0) {
                amount.toLong().toString()
            } else {
                format("%.1f", amount)
            }

        return "$text ${BYTE_UNITS[unitIndex]}"
    }

    /**
     * 稼働時間を日、時間、分、秒で表します。
     *
     * 1日に満たない場合は日を省略します。
     */
    fun uptime(seconds: Long?): String {
        if (seconds == null || seconds < 0) {
            return MISSING
        }

        val days = seconds / 86_400
        val hours = seconds % 86_400 / 3_600
        val minutes = seconds % 3_600 / 60
        val remainingSeconds = seconds % 60

        return if (days > 0) {
            "${days}日 ${hours}時間 ${minutes}分"
        } else {
            "${hours}時間 ${minutes}分 ${remainingSeconds}秒"
        }
    }

    private fun format(pattern: String, value: Double): String =
        String.format(Locale.ROOT, pattern, value)
}
