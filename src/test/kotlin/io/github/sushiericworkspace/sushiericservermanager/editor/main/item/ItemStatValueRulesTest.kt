package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.common.stats.player.StatsType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ItemStatValueRulesTest {
    @Test
    fun `範囲内で0でない既定値を初期値にする`() {
        assertEquals(1.0, initialItemStatValue(StatsType.BREAK_EFFICIENCY))
        assertEquals(100.0, initialItemStatValue(StatsType.SPEED))
        assertEquals(2.0, initialItemStatValue(StatsType.MANA_REGEN))
    }

    @Test
    fun `既定値が0のステータスは範囲内の小さな非0値を初期値にする`() {
        assertEquals(1.0, initialItemStatValue(StatsType.STRENGTH))
        assertEquals(1.0, initialItemStatValue(StatsType.DEFENCE))
    }

    @Test
    fun `初期値は範囲の最小値から算出しない`() {
        StatsType.entries.forEach { type ->
            val initial = initialItemStatValue(type)

            assertNotEquals(0.0, initial, type.name)
            assertNotEquals(-Double.MAX_VALUE, initial, type.name)
            assertEquals(initial.coerceIn(type.min, type.max), initial, type.name)
        }
    }

    @Test
    fun `0は加算なしとしてそのまま保持する`() {
        assertEquals(NO_FLAT_ITEM_STAT_VALUE, normalizeItemStatValue(StatsType.BREAK_EFFICIENCY, 0.0))
        assertEquals(NO_FLAT_ITEM_STAT_VALUE, normalizeItemStatValue(StatsType.STRENGTH, 0.0))
        assertEquals(NO_FLAT_ITEM_STAT_VALUE, normalizeItemStatValue(StatsType.SPEED, 0.0))
    }

    @Test
    fun `数値として読めない値は加算なしへ戻す`() {
        assertEquals(NO_FLAT_ITEM_STAT_VALUE, normalizeItemStatValue(StatsType.STRENGTH, Double.NaN))
    }

    @Test
    fun `加算値を持つかどうかを0で判定する`() {
        assertFalse(hasFlatItemStatValue(0.0))
        assertTrue(hasFlatItemStatValue(1.0))
        assertTrue(hasFlatItemStatValue(-1.0))
    }

    @Test
    fun `表示する種類は加算値と倍率の和集合を宣言順で返す`() {
        assertEquals(
            listOf(StatsType.STRENGTH, StatsType.DEFENCE, StatsType.SPEED),
            visibleItemStatTypes(
                flatTypes = setOf(StatsType.SPEED, StatsType.STRENGTH),
                multiplierTypes = setOf(StatsType.DEFENCE, StatsType.STRENGTH)
            )
        )
    }

    @Test
    fun `倍率だけの種類も表示対象にする`() {
        assertEquals(
            listOf(StatsType.DEFENCE),
            visibleItemStatTypes(
                flatTypes = emptySet(),
                multiplierTypes = setOf(StatsType.DEFENCE)
            )
        )
    }

    @Test
    fun `範囲外の値は範囲内へ収める`() {
        assertEquals(400.0, normalizeItemStatValue(StatsType.SPEED, 500.0))
        assertEquals(-2.5, normalizeItemStatValue(StatsType.BREAK_EFFICIENCY, -2.5))
        assertEquals(0.5, normalizeItemStatValue(StatsType.BREAK_EFFICIENCY, 0.5))
    }

    @Test
    fun `読めない入力は元の値へ戻す`() {
        assertEquals(3.0, parseItemStatValue(StatsType.STRENGTH, "abc", 3.0))
        assertEquals(3.0, parseItemStatValue(StatsType.STRENGTH, "", 3.0))
        assertEquals(7.0, parseItemStatValue(StatsType.STRENGTH, " 7 ", 3.0))
    }

    @Test
    fun `整数値はIntの範囲を超えても桁を失わない`() {
        assertEquals("2147483648", formatItemStatValue(2147483648.0))
        assertEquals("-2147483649", formatItemStatValue(-2147483649.0))
        assertEquals("10000000000", formatItemStatValue(1.0E10))
    }

    @Test
    fun `負数と小数はそのまま表示する`() {
        assertEquals("-3", formatItemStatValue(-3.0))
        assertEquals("-2.5", formatItemStatValue(-2.5))
        assertEquals("0.25", formatItemStatValue(0.25))
    }

    @Test
    fun `極端な値は表示から再入力できる`() {
        listOf(Double.MAX_VALUE, -Double.MAX_VALUE, 1.0E20, 12345678901234567890.0).forEach { value ->
            val text = formatItemStatValue(value)

            assertEquals(value, parseItemStatValue(StatsType.BREAK_EFFICIENCY, text, 0.0), text)
        }
    }
}
