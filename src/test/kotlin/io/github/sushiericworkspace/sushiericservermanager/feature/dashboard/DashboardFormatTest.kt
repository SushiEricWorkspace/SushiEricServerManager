package io.github.sushiericworkspace.sushiericservermanager.feature.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Dashboardへ表示する値の書式を検証する。
 */
class DashboardFormatTest {

    @Test
    fun `値が無い項目は未取得と表す`() {
        assertEquals(DashboardFormat.MISSING, DashboardFormat.ticksPerSecond(null))
        assertEquals(DashboardFormat.MISSING, DashboardFormat.millisPerTick(null))
        assertEquals(DashboardFormat.MISSING, DashboardFormat.percent(null))
        assertEquals(DashboardFormat.MISSING, DashboardFormat.players(null, null))
        assertEquals(DashboardFormat.MISSING, DashboardFormat.uptime(null))
        assertEquals(DashboardFormat.MISSING, DashboardFormat.bytes(null))
        assertEquals(DashboardFormat.MISSING, DashboardFormat.bytesPair(null, null))
    }

    @Test
    fun `0は未取得と区別する`() {
        assertEquals("0.00", DashboardFormat.ticksPerSecond(0.0))
        assertEquals("0.0 %", DashboardFormat.percent(0.0))
        assertEquals("0 / 20", DashboardFormat.players(0, 20))
        assertEquals("0時間 0分 0秒", DashboardFormat.uptime(0))
    }

    @Test
    fun `ティック関連の値を小数第2位まで表す`() {
        assertEquals("19.98", DashboardFormat.ticksPerSecond(19.9812))
        assertEquals("4.20 ms", DashboardFormat.millisPerTick(4.2))
    }

    @Test
    fun `バイト数を単位付きで表す`() {
        assertEquals("512 B", DashboardFormat.bytes(512))
        assertEquals("1.0 KB", DashboardFormat.bytes(1_024))
        assertEquals("1.0 MB", DashboardFormat.bytes(1_024L * 1_024))
        assertEquals("1.5 GB", DashboardFormat.bytes(1_536L * 1_024 * 1_024))
    }

    @Test
    fun `使用量と総量を並べて表す`() {
        assertEquals(
            "1.0 GB / 4.0 GB",
            DashboardFormat.bytesPair(1_024L * 1_024 * 1_024, 4L * 1_024 * 1_024 * 1_024)
        )
    }

    @Test
    fun `総量だけ分からない場合は使用量を表す`() {
        assertEquals("1.0 KB", DashboardFormat.bytesPair(1_024, null))
    }

    @Test
    fun `使用量だけ分からない場合は総量を残す`() {
        assertEquals(
            "${DashboardFormat.MISSING} / 1.0 KB",
            DashboardFormat.bytesPair(null, 1_024)
        )
    }

    @Test
    fun `稼働時間を日と時間で表す`() {
        assertEquals("0時間 12分 34秒", DashboardFormat.uptime(754))
        assertEquals("2時間 0分 0秒", DashboardFormat.uptime(7_200))
        assertEquals("1日 2時間 3分", DashboardFormat.uptime(86_400 + 7_200 + 180))
    }

    @Test
    fun `負の値は未取得と同じ扱いにする`() {
        assertEquals(DashboardFormat.MISSING, DashboardFormat.uptime(-1))
        assertEquals(DashboardFormat.MISSING, DashboardFormat.bytes(-1))
    }

    @Test
    fun `人数は片方だけでは表さない`() {
        assertEquals(DashboardFormat.MISSING, DashboardFormat.players(3, null))
        assertEquals(DashboardFormat.MISSING, DashboardFormat.players(null, 20))
    }
}
