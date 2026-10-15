package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.item.upgrade.definition.LevelledItemUpgradeDefinition
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.NonLevelledItemUpgradeDefinition
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.upgrade.ItemUpgradeCategory
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.StatsPart
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.StatsPartTarget
import java.util.Collections

/**
 * 装備1件の基礎定義と保存値相当の強化指定を複製します。保存や取得は行いません。
 * レベル制強化は0以下を無効、上限超過をmaxLevelへ補正します。元の指定値は保持します。
 * 非レベル強化はカテゴリごとに1件だけ指定し、キーと定義のカテゴリを一致させてください。
 */
class SimulatorEquippedItem(
    baseData: ItemBaseData,
    levelled: Map<LevelledItemUpgradeDefinition, Int> = emptyMap(),
    nonLevelled: Map<ItemUpgradeCategory, NonLevelledItemUpgradeDefinition> = emptyMap()
) {
    val baseData = baseData.copy(statMultipliers = baseData.statMultipliers.mapValues { (_, values) ->
        values.map { multiplier -> multiplier.copy(target = when (val target = multiplier.target) {
            StatsPartTarget.Self -> StatsPartTarget.Self
            StatsPartTarget.All -> StatsPartTarget.All
            is StatsPartTarget.Parts -> StatsPartTarget.Parts(Collections.unmodifiableSet(target.parts.toSet()))
        }) }
    })
    val levelled: Map<LevelledItemUpgradeDefinition, Int> = Collections.unmodifiableMap(levelled.toMap())
    val nonLevelled: Map<ItemUpgradeCategory, NonLevelledItemUpgradeDefinition> = Collections.unmodifiableMap(nonLevelled.toMap())

    init {
        require(nonLevelled.all { (category, definition) -> category == definition.category }) {
            "非レベル強化のカテゴリと定義を一致させてください。"
        }
    }
}

/** 装備部位ごとの固定入力。オフハンド短剣は通常能力から除き、追撃用にだけ使用します。 */
class SimulatorEquipmentInput(items: Map<StatsPart, SimulatorEquippedItem>) {
    val items: Map<StatsPart, SimulatorEquippedItem> = Collections.unmodifiableMap(items.toMap())

    init {
        require(items.keys.all { it.isEquipment }) { "装備部位だけを指定してください。" }
    }
}

/** 構えと既存CD期限だけを手入力します。スタミナ・筋力・盾は組立済み入力から取得します。 */
data class SimulatorGuardState(
    val raising: Boolean = false,
    val blockUntilTick: Long = 0L,
    val parryUntilTick: Long = 0L
)
