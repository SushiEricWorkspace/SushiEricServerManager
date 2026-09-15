package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.common.stats.player.StatsType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemStatMultiplierRulesTest {
    @Test
    fun `NaNと無限は既定値へ戻す`() {
        assertEquals(1.0, normalizeItemStatMultiplier(Double.NaN))
        assertEquals(1.0, normalizeItemStatMultiplier(Double.POSITIVE_INFINITY))
        assertEquals(1.0, normalizeItemStatMultiplier(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `範囲外の倍率は範囲内へ収める`() {
        assertEquals(0.0, normalizeItemStatMultiplier(-0.5))
        assertEquals(ITEM_STAT_MULTIPLIER_MAX, normalizeItemStatMultiplier(ITEM_STAT_MULTIPLIER_MAX + 1.0))
        assertEquals(1.5, normalizeItemStatMultiplier(1.5))
        assertEquals(0.0, normalizeItemStatMultiplier(0.0))
    }

    @Test
    fun `読めない入力は元の倍率へ戻す`() {
        assertEquals(1.5, parseItemStatMultiplier("abc", 1.5))
        assertEquals(1.5, parseItemStatMultiplier(null, 1.5))
        assertEquals(2.0, parseItemStatMultiplier(" 2 ", 1.5))
        assertEquals(0.0, parseItemStatMultiplier("-3", 1.5))
    }

    @Test
    fun `倍率の表示は値と同じ表記にする`() {
        assertEquals("1", formatItemStatMultiplier(1.0))
        assertEquals("1.5", formatItemStatMultiplier(1.5))
        assertEquals("0.25", formatItemStatMultiplier(0.25))
    }

    @Test
    fun `既定値の倍率はMapへ保持しない`() {
        val multipliers = mutableMapOf(StatsType.PHYSICS_DAMAGE to 1.5)

        applyItemStatMultiplier(multipliers, StatsType.PHYSICS_DAMAGE, 1.0)
        assertFalse(StatsType.PHYSICS_DAMAGE in multipliers)

        applyItemStatMultiplier(multipliers, StatsType.DEFENCE, Double.NaN)
        assertFalse(StatsType.DEFENCE in multipliers)

        applyItemStatMultiplier(multipliers, StatsType.MAX_HEALTH, 1.0)
        assertTrue(multipliers.isEmpty())
    }

    @Test
    fun `既定値以外の倍率はMapへ補正して保持する`() {
        val multipliers = mutableMapOf<StatsType, Double>()

        applyItemStatMultiplier(multipliers, StatsType.PHYSICS_DAMAGE, 1.5)
        applyItemStatMultiplier(multipliers, StatsType.DEFENCE, -1.0)

        assertEquals(
            mapOf(
                StatsType.PHYSICS_DAMAGE to 1.5,
                StatsType.DEFENCE to 0.0
            ),
            multipliers
        )
    }
}
