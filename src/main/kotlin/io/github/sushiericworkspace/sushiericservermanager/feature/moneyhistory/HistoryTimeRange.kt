package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** 両端を含む端末のローカル日時範囲です。nullの端には制限がありません。 */
internal data class HistoryTimeRange(val start: LocalDateTime?, val end: LocalDateTime?) {
    fun contains(value: LocalDateTime): Boolean =
        (start == null || !value.isBefore(start)) && (end == null || !value.isAfter(end))
}

/** 時刻省略時は日全体、終了時刻は指定した分または秒の末尾までを対象にします。 */
internal fun historyTimeRange(
    fromDate: LocalDate?, fromTime: String, toDate: LocalDate?, toTime: String
): HistoryTimeRange {
    val start = historyTimeBoundary(fromDate, fromTime, false)
    val end = historyTimeBoundary(toDate, toTime, true)
    require(start == null || end == null || !start.isAfter(end)) { "開始日時が終了日時より後です。" }
    return HistoryTimeRange(start, end)
}

private fun historyTimeBoundary(date: LocalDate?, text: String, end: Boolean): LocalDateTime? {
    val input = text.trim()
    require(date != null || input.isEmpty()) { "時刻を指定する場合は日付も選択してください。" }
    if (date == null) return null
    if (input.isEmpty()) return date.atTime(if (end) LocalTime.MAX else LocalTime.MIN)
    require(Regex("\\d{2}:\\d{2}(:\\d{2})?").matches(input)) { "時刻はHH:mmまたはHH:mm:ssで入力してください。" }
    val time = runCatching { LocalTime.parse(input) }.getOrElse {
        throw IllegalArgumentException("時刻は00:00:00～23:59:59で入力してください。")
    }
    return date.atTime(if (end) time.withSecond(if (input.length == 5) 59 else time.second).withNano(999_999_999) else time)
}
