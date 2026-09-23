package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.common.stats.player.StatsType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ItemStatsAddDialogTest {
    @Test
    fun `種類と値がそろえば確定できる`() {
        assertEquals(StatsType.STRENGTH to 5.0, resolveStatToAdd(StatsType.STRENGTH, 5.0))
        assertEquals(StatsType.BREAK_EFFICIENCY to -2.5, resolveStatToAdd(StatsType.BREAK_EFFICIENCY, -2.5))
    }

    @Test
    fun `0は加算なしのステータスとして確定できる`() {
        assertEquals(StatsType.STRENGTH to 0.0, resolveStatToAdd(StatsType.STRENGTH, 0.0))
    }

    @Test
    fun `種類が未選択なら確定できない`() {
        assertNull(resolveStatToAdd(null, 5.0))
    }

    @Test
    fun `値が未確定なら確定できない`() {
        assertNull(resolveStatToAdd(StatsType.STRENGTH, null))
        assertNull(resolveStatToAdd(StatsType.STRENGTH, Double.NaN))
    }
}
