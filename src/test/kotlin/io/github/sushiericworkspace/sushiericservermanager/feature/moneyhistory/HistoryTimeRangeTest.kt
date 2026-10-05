package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class HistoryTimeRangeTest {
    private val date = LocalDate.of(2026, 10, 5)

    @Test
    fun `時刻なしでは指定した日全体を含む`() {
        val range = historyTimeRange(date, "", date, "")
        assertTrue(range.contains(date.atStartOfDay()))
        assertTrue(range.contains(date.atTime(23, 59, 59, 999_999_999)))
        assertFalse(range.contains(date.plusDays(1).atStartOfDay()))
    }

    @Test
    fun `分と秒を指定でき終了の分または秒の末尾も含む`() {
        val range = historyTimeRange(date, "10:30:15", date, "11:00")
        assertFalse(range.contains(date.atTime(10, 30, 14)))
        assertTrue(range.contains(date.atTime(10, 30, 15)))
        assertTrue(range.contains(date.atTime(11, 0, 59, 999_999_999)))
        assertFalse(range.contains(date.atTime(11, 1)))
        val seconds = historyTimeRange(date, "11:00:15", date, "11:00:15")
        assertTrue(seconds.contains(date.atTime(11, 0, 15, 999_999_999)))
        assertFalse(seconds.contains(date.atTime(11, 0, 16)))
    }

    @Test
    fun `不正な時刻と日付なしの時刻と逆順を拒否する`() {
        assertFailsWith<IllegalArgumentException> { historyTimeRange(date, "24:00", null, "") }
        assertFailsWith<IllegalArgumentException> { historyTimeRange(date, "10:60", null, "") }
        assertFailsWith<IllegalArgumentException> { historyTimeRange(null, "10:00", null, "") }
        assertFailsWith<IllegalArgumentException> { historyTimeRange(date, "11:00", date, "10:00") }
        assertTrue(historyTimeRange(null, "", null, "").contains(date.atStartOfDay()))
    }
}
