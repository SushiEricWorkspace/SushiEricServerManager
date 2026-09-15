package io.github.sushiericworkspace.sushiericservermanager.editor.main.item.diff

import io.github.sushiericworkspace.common.stats.player.StatsType
import kotlin.test.Test
import kotlin.test.assertEquals

class ItemStatMultiplierDiffTest {
    @Test
    fun `倍率が異なるStatsTypeだけを差分にする`() {
        val original = mapOf(
            StatsType.PHYSICS_DAMAGE to 1.5,
            StatsType.DEFENCE to 2.0
        )
        val server = mapOf(
            StatsType.PHYSICS_DAMAGE to 1.5,
            StatsType.DEFENCE to 0.5
        )

        assertEquals(setOf(StatsType.DEFENCE), statMultiplierDiffTypes(original, server))
    }

    @Test
    fun `片方にだけある倍率も差分にする`() {
        val original = mapOf(StatsType.PHYSICS_DAMAGE to 1.5)
        val server = mapOf(StatsType.MAX_HEALTH to 1.2)

        assertEquals(
            setOf(StatsType.PHYSICS_DAMAGE, StatsType.MAX_HEALTH),
            statMultiplierDiffTypes(original, server)
        )
    }

    @Test
    fun `同じ倍率なら差分はない`() {
        val multipliers = mapOf(StatsType.PHYSICS_DAMAGE to 1.5)

        assertEquals(emptySet(), statMultiplierDiffTypes(multipliers, multipliers.toMap()))
        assertEquals(emptySet(), statMultiplierDiffTypes(emptyMap(), emptyMap()))
    }
}
