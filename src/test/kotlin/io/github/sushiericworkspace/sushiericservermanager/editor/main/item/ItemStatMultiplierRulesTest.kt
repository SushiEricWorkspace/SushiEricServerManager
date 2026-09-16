package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.common.data.item.model.ItemStatMultiplier
import io.github.sushiericworkspace.common.data.item.model.ItemType
import io.github.sushiericworkspace.common.stats.player.StatsPart
import io.github.sushiericworkspace.common.stats.player.StatsPartTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun `追加時の初期要素は自分を対象にした既定値の倍率である`() {
        assertEquals(ItemStatMultiplier(StatsPartTarget.Self, 1.0), initialItemStatMultiplier())
    }

    @Test
    fun `対象と選択肢は相互に変換できる`() {
        assertEquals(ItemStatMultiplierTargetOption.SELF, ItemStatMultiplierTargetOption.of(StatsPartTarget.Self))
        assertEquals(ItemStatMultiplierTargetOption.ALL, ItemStatMultiplierTargetOption.of(StatsPartTarget.All))
        assertEquals(
            ItemStatMultiplierTargetOption.PARTS,
            ItemStatMultiplierTargetOption.of(StatsPartTarget.Parts(setOf(StatsPart.HELMET)))
        )

        assertEquals(StatsPartTarget.Self, resolveItemStatMultiplierTarget(ItemStatMultiplierTargetOption.SELF, emptySet()))
        assertEquals(StatsPartTarget.All, resolveItemStatMultiplierTarget(ItemStatMultiplierTargetOption.ALL, setOf(StatsPart.HELMET)))
        assertEquals(
            StatsPartTarget.Parts(setOf(StatsPart.HELMET, StatsPart.BOOTS)),
            resolveItemStatMultiplierTarget(ItemStatMultiplierTargetOption.PARTS, setOf(StatsPart.HELMET, StatsPart.BOOTS))
        )
    }

    @Test
    fun `部位を選んでいない部位指定は対象を作れない`() {
        assertNull(resolveItemStatMultiplierTarget(ItemStatMultiplierTargetOption.PARTS, emptySet()))
    }

    @Test
    fun `部位指定の対象からだけ部位を取り出す`() {
        assertEquals(
            setOf(StatsPart.CHESTPLATE),
            selectedItemStatMultiplierParts(StatsPartTarget.Parts(setOf(StatsPart.CHESTPLATE)))
        )
        assertEquals(emptySet(), selectedItemStatMultiplierParts(StatsPartTarget.Self))
        assertEquals(emptySet(), selectedItemStatMultiplierParts(StatsPartTarget.All))
    }

    @Test
    fun `部位指定へ切り替えたときはアイテムの種類に合う部位を1つ選ぶ`() {
        assertEquals(setOf(StatsPart.HELMET), defaultItemStatMultiplierParts(ItemType.HELMET))
        assertEquals(setOf(StatsPart.BOOTS), defaultItemStatMultiplierParts(ItemType.BOOTS))
        assertEquals(setOf(StatsPart.MAIN_HAND), defaultItemStatMultiplierParts(ItemType.SWORD))
        assertEquals(setOf(StatsPart.MAIN_HAND), defaultItemStatMultiplierParts(ItemType.SHIELD))
    }
}
