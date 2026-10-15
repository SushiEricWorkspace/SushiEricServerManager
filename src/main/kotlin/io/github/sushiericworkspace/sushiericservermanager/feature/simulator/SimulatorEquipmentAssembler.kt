package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.attack.snapshot.AttackSnapshot
import io.github.sushiericworkspace.combatcore.combat.damage.kind.effect.PlayerToMobMeleeEffects
import io.github.sushiericworkspace.combatcore.combat.damage.preparation.DamagePreparationValues
import io.github.sushiericworkspace.combatcore.combat.guard.PlayerGuardInput
import io.github.sushiericworkspace.combatcore.player.skill.effect.SkillLevelSnapshot
import io.github.sushiericworkspace.combatcore.item.custom.base.itemtype.shortsword.ShortSwordFollowUpInput
import io.github.sushiericworkspace.combatcore.item.custom.base.itemtype.shortsword.ShortSwordFollowUpStats
import io.github.sushiericworkspace.combatcore.item.data.ItemStatsCalculation
import io.github.sushiericworkspace.combatcore.item.effect.ItemSpecialEffectRole
import io.github.sushiericworkspace.combatcore.item.effect.ResolvedPreparationEffect
import io.github.sushiericworkspace.combatcore.item.effect.cost.ItemResourceCostCatalog
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.ItemUpgradeDefinition
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.LevelledItemUpgradeDefinition
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.NonLevelledItemUpgradeDefinition
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.enchantment.ArmorCustomEnchantment
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.enchantment.MeleeSushiEricEnchantment
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.honing.Honing
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.reforge.ReforgeDefinition
import io.github.sushiericworkspace.combatcore.item.upgrade.effect.ActiveItemUpgrade
import io.github.sushiericworkspace.combatcore.item.upgrade.effect.ActiveItemUpgradeCollector
import io.github.sushiericworkspace.combatcore.stats.StatModifier
import io.github.sushiericworkspace.combatcore.stats.StatMultiplier
import io.github.sushiericworkspace.combatcore.stats.StatsPartSource
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemType
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.detail.ShieldData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.upgrade.ItemUpgradeCategory
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.*
import java.util.Collections

/** 無効化・上限補正の記録です。requestedLevelは入力値、effectiveLevelが0なら無効です。 */
data class SimulatorUpgradeAdjustment(
    val part: StatsPart, val definition: ItemUpgradeDefinition,
    val requestedLevel: Int, val effectiveLevel: Int, val reason: String
)

/** 非対応の部位または強化。definitionがnullなら装備部位とItemTypeの不一致です。 */
data class SimulatorEquipmentProblem(val part: StatsPart, val definition: ItemUpgradeDefinition?, val reason: String)

/** 技能条件を満たさず、能力・効果・盾・追撃の全経路から除いた装備です。 */
data class SimulatorInactiveEquipment(val part: StatsPart, val reason: String)

/** 特殊効果CDは部位を含めず、同じ役割・提供元・効果で共有します。 */
data class SimulatorEffectCooldownKey(val role: ItemSpecialEffectRole, val providerKey: String, val effectId: String)

/** 非対応の装備を含む場合はSnapshotや部分的な計算入力を公開しません。 */
sealed interface SimulatorEquipmentResult {
    /** 元の装備指定と全理由を表示できます。未対応効果を省いたReadyは生成しません。 */
    class Unsupported(val input: SimulatorEquipmentInput, val skillLevels: SkillLevelSnapshot, problems: List<SimulatorEquipmentProblem>) : SimulatorEquipmentResult {
        val problems: List<SimulatorEquipmentProblem> = problems.toList()
    }

    /** Self適用済み固定値とAll/Parts未適用倍率を#264の入力へ渡します。 */
    class Ready internal constructor(
        val input: SimulatorEquipmentInput,
        val skillLevels: SkillLevelSnapshot,
        usableItems: Map<StatsPart, SimulatorEquippedItem>,
        inactive: List<SimulatorInactiveEquipment>,
        sources: Map<StatsPart, Map<String, StatsPartSource<StatsPart, StatsType>>>,
        itemTypes: Map<StatsPart, ItemType?>,
        active: List<ActiveItemUpgrade>,
        adjustments: List<SimulatorUpgradeAdjustment>,
        val offhandFollowUp: ShortSwordFollowUpInput?
    ) : SimulatorEquipmentResult {
        private val usableItems = Collections.unmodifiableMap(usableItems.toMap())
        val inactiveEquipment: List<SimulatorInactiveEquipment> = Collections.unmodifiableList(inactive.toList())
        private val fixedEquipmentSources = copyEquipmentSources(sources)
        /** sourceの公開取得値を変更しても、保持済み装備の記録は変わりません。 */
        val equipmentSources get() = copyEquipmentSources(fixedEquipmentSources)
        val itemTypes = Collections.unmodifiableMap(itemTypes.toMap())
        val activeUpgrades: List<ActiveItemUpgrade> = Collections.unmodifiableList(active.toList())
        val adjustments: List<SimulatorUpgradeAdjustment> = Collections.unmodifiableList(adjustments.toList())

        /** 役割・順序・期限を保持します。条件やCDの実判定、効果倍率の評価はCoreが行います。 */
        fun preparationEffects(
            role: ItemSpecialEffectRole,
            cooldowns: Map<SimulatorEffectCooldownKey, Long> = emptyMap()
        ): List<ResolvedPreparationEffect> = activeUpgrades.flatMapIndexed { index, upgrade ->
            upgrade.preparationEffects(role, index) { effect ->
                cooldowns[SimulatorEffectCooldownKey(role, upgrade.definition.providerKey, effect.id)] ?: 0L
            }
        }

        /**
         * 装備sourceと技能を一度だけ集計し、直接攻撃のcost・Preparationを組み立てます。
         * PlayerMeleeのitemDataId/costは固定したメインハンドから置き換えます。
         * preparationの現在値・履歴・既存特殊効果を保持し、装備効果を追加します。
         * sourceItemDataとoffhandFollowUpも利用可能な装備から確定し、HP更新や予約は行いません。
         * ガードの盾は利用可能なMAIN_HAND優先、次にOFF_HANDです。盾なしではガードを生成しません。
         * スタミナと筋力は組立済みplayerの現在値を使用し、構え・CD期限だけ[guardState]から取得します。
         * preparation.playerGuardはnullにしてください。明示ガード入力を使う場合は#264のSimulatorInputを使用します。
         */
        fun singleHitInput(
            position: SimulatorPosition,
            baseStats: Map<StatsType, Double>,
            dynamicStats: Map<DynamicStatsType, Double>,
            mob: SimulatorMobInput,
            attack: SimulatorAttack,
            preparation: DamagePreparationValues,
            guardState: SimulatorGuardState = SimulatorGuardState(),
            launchSnapshot: AttackSnapshot? = null,
            cooldowns: Map<SimulatorEffectCooldownKey, Long> = emptyMap()
        ): SimulatorInput {
            require(preparation.playerGuard == null) { "装備組立ではplayerGuardを手入力せず、guardStateを指定してください。" }
            val player = SimulatorPlayerInput(position, baseStats, SkillType.entries.associateWith(skillLevels::level), fixedEquipmentSources, itemTypes, dynamicStats)
            val role = if (attack.playerIsSource) ItemSpecialEffectRole.SOURCE else ItemSpecialEffectRole.TARGET
            val mainHand = usableItems[StatsPart.MAIN_HAND]?.baseData
            val resolvedAttack = if (attack is SimulatorAttack.PlayerMelee) SimulatorAttack.PlayerMelee(
                PlayerToMobMeleeEffects.Direct(mainHand?.id, ItemResourceCostCatalog.normalDirectMelee(mainHand)), attack.vanillaCritical
            ) else attack
            val shield = listOf(StatsPart.MAIN_HAND, StatsPart.OFF_HAND).firstNotNullOfOrNull { part ->
                usableItems[part]?.baseData?.itemDetail?.content as? ShieldData
            }
            val guard = if (attack.playerIsSource) null else shield?.let {
                PlayerGuardInput(it, guardState.raising, player.snapshot.requireDynamicStats()[DynamicStatsType.STAMINA],
                    player.snapshot.requirePlayerStats()[StatsType.STRENGTH], guardState.blockUntilTick, guardState.parryUntilTick)
            }
            val values = DamagePreparationValues(
                preparation.currentTick, preparation.sourceResource, preparation.targetX, preparation.targetZ,
                if (attack.playerIsSource) mainHand else preparation.sourceItemData,
                preparation.sourceAttackRecords, preparation.targetIsBreaking, guard, preparation.mobParry,
                if (attack.playerIsSource) offhandFollowUp else null,
                preparation.specialEffects + preparationEffects(role, cooldowns)
            )
            return SimulatorInput(player, mob, resolvedAttack, values, launchSnapshot)
        }
    }
}

/** 保存先に触れず、Modの有効化規則とCoreの装備計算を接続します。 */
object SimulatorEquipmentAssembler {
    fun resolve(input: SimulatorEquipmentInput, skills: SkillLevelSnapshot): SimulatorEquipmentResult {
        val problems = mutableListOf<SimulatorEquipmentProblem>()
        val usableItems = linkedMapOf<StatsPart, SimulatorEquippedItem>()
        val inactive = mutableListOf<SimulatorInactiveEquipment>()
        val adjustments = mutableListOf<SimulatorUpgradeAdjustment>()
        val sources = linkedMapOf<StatsPart, Map<String, StatsPartSource<StatsPart, StatsType>>>()
        val itemTypes = linkedMapOf<StatsPart, ItemType?>()
        val upgrades = linkedMapOf<StatsPart, Map<ItemUpgradeDefinition, Int>>()
        var offhand: ShortSwordFollowUpInput? = null
        for (part in ActiveItemUpgradeCollector.EQUIPMENT_PARTS) {
            val item = input.items[part] ?: continue
            val type = item.baseData.itemDetail.itemType
            val offhandSword = part == StatsPart.OFF_HAND && type == ItemType.SHORT_SWORD
            if (type !in part.supportType && !offhandSword) {
                problems += SimulatorEquipmentProblem(part, null, "${part.display}に${type.name}は対応していません。")
                continue
            }
            if (!canUse(item.baseData, skills)) {
                inactive += SimulatorInactiveEquipment(part, "装備の技能条件を満たしていません。")
                continue
            }
            usableItems[part] = item
            val levelled = linkedMapOf<LevelledItemUpgradeDefinition, Int>()
            val nonLevelled = mutableListOf<NonLevelledItemUpgradeDefinition>()
            fun active(definition: ItemUpgradeDefinition, requested: Int, effective: Int): Boolean {
                if (effective == 0 || !definition.canApplyTo(type)) {
                    adjustments += SimulatorUpgradeAdjustment(part, definition, requested, 0,
                        if (effective == 0) "レベル0以下の強化は無効です。" else "ItemTypeが強化の対象外です。")
                    return false
                }
                if (!supported(definition)) {
                    problems += SimulatorEquipmentProblem(part, definition, "${definition.displayName}の適用後効果・属性変更は非対応です。")
                    return false
                }
                if (requested != effective) adjustments += SimulatorUpgradeAdjustment(part, definition, requested, effective,
                    "最大レベルへ補正しました。")
                return true
            }
            item.levelled.forEach { (definition, level) ->
                val effective = level.coerceIn(0, definition.maxLevel)
                if (active(definition, level, effective)) levelled[definition] = effective
            }
            ItemUpgradeCategory.entries.mapNotNull(item.nonLevelled::get).forEach { definition ->
                if (active(definition, 1, ActiveItemUpgrade.NON_LEVELLED_LEVEL)) nonLevelled += definition
            }
            upgrades[part] = buildMap {
                putAll(levelled)
                nonLevelled.forEach { put(it, ActiveItemUpgrade.NON_LEVELLED_LEVEL) }
            }
            val calculation = ItemStatsCalculation(item.baseData, levelled, nonLevelled)
            if (offhandSword) {
                offhand = ShortSwordFollowUpInput(ShortSwordFollowUpStats.fromStats(calculation.stats),
                    ItemResourceCostCatalog.shortSwordOffhandFollowUp(item.baseData))
            } else {
                sources[part] = mapOf("equipment" to StatsPartSource(stats = StatsType.entries.associateWith { stat ->
                    StatModifier(calculation.stats.getValue(stat), calculation.statMultipliers(stat).map {
                        StatMultiplier(it.value, it.target)
                    })
                }))
                itemTypes[part] = type
            }
        }
        return if (problems.isNotEmpty()) SimulatorEquipmentResult.Unsupported(input, skills, problems)
        else SimulatorEquipmentResult.Ready(input, skills, usableItems, inactive, sources, itemTypes, ActiveItemUpgradeCollector.collect(upgrades), adjustments, offhand)
    }

    private fun canUse(item: ItemBaseData, skills: SkillLevelSnapshot): Boolean =
        item.skillLevelRequirements.all { (skill, required) -> skills.level(skill) >= required }

    private fun supported(definition: ItemUpgradeDefinition): Boolean =
        definition == Honing || definition is ReforgeDefinition || definition is ArmorCustomEnchantment ||
            definition == MeleeSushiEricEnchantment.BRAWN || definition == MeleeSushiEricEnchantment.SHARPNESS
}
