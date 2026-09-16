package io.github.sushiericworkspace.sushiericservermanager.editor.main.item.diff

import io.github.sushiericworkspace.common.data.item.model.ItemStatMultiplier
import io.github.sushiericworkspace.common.stats.player.StatsPart
import io.github.sushiericworkspace.common.stats.player.StatsPartTarget
import io.github.sushiericworkspace.common.stats.player.StatsType
import io.github.sushiericworkspace.sushiericservermanager.ui.format.ItemStatMultiplierFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

class ItemStatMultiplierDiffTest {
    private val self15 = ItemStatMultiplier(StatsPartTarget.Self, 1.5)
    private val all11 = ItemStatMultiplier(StatsPartTarget.All, 1.1)

    @Test
    fun `倍率のリストが異なるStatsTypeだけを差分にする`() {
        val original = mapOf(
            StatsType.PHYSICS_DAMAGE to listOf(self15),
            StatsType.DEFENCE to listOf(all11)
        )
        val server = mapOf(
            StatsType.PHYSICS_DAMAGE to listOf(self15),
            StatsType.DEFENCE to listOf(all11, self15)
        )

        assertEquals(setOf(StatsType.DEFENCE), statMultiplierDiffTypes(original, server))
    }

    @Test
    fun `片方にだけある倍率も差分にする`() {
        val original = mapOf(StatsType.PHYSICS_DAMAGE to listOf(self15))
        val server = mapOf(StatsType.MAX_HEALTH to listOf(all11))

        assertEquals(
            setOf(StatsType.PHYSICS_DAMAGE, StatsType.MAX_HEALTH),
            statMultiplierDiffTypes(original, server)
        )
    }

    @Test
    fun `同じ倍率と空リストは差分にしない`() {
        val multipliers = mapOf(StatsType.PHYSICS_DAMAGE to listOf(self15))

        assertEquals(emptySet(), statMultiplierDiffTypes(multipliers, multipliers.toMap()))
        assertEquals(emptySet(), statMultiplierDiffTypes(emptyMap(), emptyMap()))
        assertEquals(
            emptySet(),
            statMultiplierDiffTypes(mapOf(StatsType.PHYSICS_DAMAGE to emptyList()), emptyMap())
        )
    }

    @Test
    fun `倍率は対象と値を読みやすい文字列にする`() {
        assertEquals("自分 ×1.5", ItemStatMultiplierFormatter.format(self15))
        assertEquals("全体 ×1.1", ItemStatMultiplierFormatter.format(all11))
        assertEquals(
            "部位(頭, 足) ×0.5",
            ItemStatMultiplierFormatter.format(
                ItemStatMultiplier(StatsPartTarget.Parts(setOf(StatsPart.BOOTS, StatsPart.HELMET)), 0.5)
            )
        )
        assertEquals("自分 ×1.5 | 全体 ×1.1", ItemStatMultiplierFormatter.format(listOf(self15, all11)))
        assertEquals(ItemStatMultiplierFormatter.NONE, ItemStatMultiplierFormatter.format(null))
        assertEquals(ItemStatMultiplierFormatter.NONE, ItemStatMultiplierFormatter.format(emptyList()))
    }
}
