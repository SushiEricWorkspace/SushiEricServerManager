package io.github.sushiericworkspace.sushiericservermanager

import io.github.sushiericworkspace.combatcore.stats.StatsPartCalculator
import io.github.sushiericworkspace.combatcore.stats.StatsPartSource
import io.github.sushiericworkspace.combatcore.stats.StatModifier
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.StatsPart
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.StatsType
import kotlin.test.Test
import kotlin.test.assertEquals

class CombatCoreIntegrationTest {
    @Test
    fun `UIを介さずCombatCoreのステータス計算を呼び出せる`() {
        val result = StatsPartCalculator.calculate(
            parts = mapOf(
                StatsPart.BASE to mapOf(
                    "direct" to StatsPartSource(
                        stats = mapOf(StatsType.PHYSICS_DAMAGE to StatModifier(flat = 12.0))
                    )
                )
            ),
            type = StatsType.PHYSICS_DAMAGE
        )

        assertEquals(12.0, result.value)
    }
}
