package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.damage.kind.effect.PlayerToMobMeleeEffects
import io.github.sushiericworkspace.combatcore.combat.guard.PlayerGuardInput
import io.github.sushiericworkspace.combatcore.player.skill.effect.SkillLevelSnapshot
import io.github.sushiericworkspace.combatcore.combat.damage.preparation.*
import io.github.sushiericworkspace.combatcore.combat.damage.resource.DamageResourceSource
import io.github.sushiericworkspace.combatcore.item.effect.ItemSpecialEffectRole
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.LevelledItemUpgradeDefinition
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.NonLevelledItemUpgradeDefinition
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.enchantment.*
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.honing.Honing
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.reforge.*
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.*
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.detail.*
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.upgrade.ItemUpgradeCategory
import io.github.sushiericworkspace.sushiericdatamodel.stats.entity.EntityStatsType
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.*
import java.util.UUID
import kotlin.test.*

class SimulatorEquipmentAssemblerTest {
    private val position = SimulatorPosition(UUID(0, 1), "プレイヤー", "test")
    private val direct = SimulatorAttack.PlayerMelee(PlayerToMobMeleeEffects.Direct(null, null))
    private fun item(content: ItemDetailContent = SwordData(), stats: Map<StatsType, Double> = emptyMap(),
        rarity: SushiEricRarity = SushiEricRarity.COMMON) = ItemBaseData(id = "weapon", itemDetail = ItemDetail(content = content), stats = stats, rarity = rarity)
    private fun ready(items: Map<StatsPart, SimulatorEquippedItem>, skills: Map<SkillType, Int> = emptyMap()) = assertIs<SimulatorEquipmentResult.Ready>(
        SimulatorEquipmentAssembler.resolve(SimulatorEquipmentInput(items), SkillLevelSnapshot(skills)))
    private fun sword(levelled: Map<LevelledItemUpgradeDefinition, Int> = emptyMap(),
        nonLevelled: Map<ItemUpgradeCategory, NonLevelledItemUpgradeDefinition> = emptyMap()) =
        ready(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(item(), levelled, nonLevelled)))
    private fun base() = StatsType.entries.associateWith { when (it) {
        StatsType.PHYSICS_DAMAGE -> 95.0
        StatsType.STRENGTH -> 20.0
        StatsType.DEFENCE -> 100.0
        StatsType.ABILITY_DAMAGE -> 120.0
        else -> 0.0
    } }
    private fun input(equipment: SimulatorEquipmentResult.Ready, attack: SimulatorAttack = direct,
        stats: Map<StatsType, Double> = base(), stamina: Double = 0.0,
        cooldowns: Map<SimulatorEffectCooldownKey, Long> = emptyMap(),
        guardState: SimulatorGuardState = SimulatorGuardState(), currentStamina: Double = 100.0) = equipment.singleHitInput(
        position, stats, DynamicStatsType.entries.associateWith { if (it == DynamicStatsType.STAMINA) currentStamina else 100.0 },
        SimulatorMobInput(SimulatorPosition(UUID(0, 2), "Mob", "test"), EntityStatsType.entries.associateWith {
            when (it) { EntityStatsType.DEFENCE -> 100.0; EntityStatsType.ATTACK_DAMAGE -> 120.0; else -> 0.0 }
        }, emptyMap(), 500.0), attack,
        DamagePreparationValues(100, if (attack.playerIsSource) DamageResourceSource.Player(stamina, 0.0) else DamageResourceSource.NonPlayer, 1.0, 0.0),
        guardState = guardState, cooldowns = cooldowns)
    private fun calculate(input: SimulatorInput) = assertIs<SingleHitResult.Expected>(
        SingleHitSimulator.calculate(SingleHitRequest(1, input, SingleHitMode.Expected))).result

    @Test fun `Self済み固定値とAllとPartsと技能を二重適用せず合成する`() {
        val weapon = item(stats = mapOf(StatsType.PHYSICS_DAMAGE to 10.0)).copy(statMultipliers = mapOf(
            StatsType.PHYSICS_DAMAGE to listOf(ItemStatMultiplier(StatsPartTarget.Self, 1.5),
                ItemStatMultiplier(StatsPartTarget.All, 1.1), ItemStatMultiplier(StatsPartTarget.Parts(setOf(StatsPart.MAIN_HAND)), 1.2))))
        val equipment = ready(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(weapon)), mapOf(SkillType.COMBAT to 1))
        val stats = input(equipment, stats = base() + (StatsType.PHYSICS_DAMAGE to 100.0))
            .player.snapshot.requirePlayerDamageStats()
        // BASE=100*1.12、MAIN_HAND=(10*1.5)*1.32。
        assertEquals(131.8, stats.getTotalStat(StatsType.PHYSICS_DAMAGE), 1e-10)
        assertEquals(112.0, stats.getPartStat(StatsPart.BASE, StatsType.PHYSICS_DAMAGE), 1e-10)
        assertEquals(19.8, stats.getPartStat(StatsPart.MAIN_HAND, StatsType.PHYSICS_DAMAGE), 1e-10)
        val source = equipment.equipmentSources.getValue(StatsPart.MAIN_HAND).getValue("equipment").stats.getValue(StatsType.PHYSICS_DAMAGE)
        assertEquals(15.0, source.flat)
        assertTrue(source.multipliers.none { it.target == StatsPartTarget.Self })
    }

    @Test fun `SHARPNESSのlevel1と5は準備完了前だけで最終値を増加する`() {
        for ((level, literal) in listOf(1 to 63.0, 5 to 75.0)) {
            val equipment = sword(mapOf(MeleeSushiEricEnchantment.SHARPNESS to level))
            val result = calculate(input(equipment)).branches.single().result
            assertEquals(60.0, result.formula.finalDamage)
            assertEquals(literal, result.finalDamage, 1e-10)
            val trace = result.preparation.phaseTrace.last()
            assertEquals(DamagePreparationPhase.BEFORE_COMPLETE, trace.phase)
            assertEquals(literal, trace.damageAfter)
        }
    }

    @Test fun `BRAWNは筋力を変えSHARPNESSはSnapshotを変えない`() {
        val brawn = input(sword(mapOf(MeleeSushiEricEnchantment.BRAWN to 1)))
        val sharp = input(sword(mapOf(MeleeSushiEricEnchantment.SHARPNESS to 1)))
        assertEquals(25.0, brawn.player.snapshot.requirePlayerStats()[StatsType.STRENGTH])
        assertEquals(20.0, sharp.player.snapshot.requirePlayerStats()[StatsType.STRENGTH])
        assertEquals(62.5, calculate(brawn).meanDamage, 1e-10)
        assertEquals(63.0, calculate(sharp).meanDamage, 1e-10)
        assertTrue(brawn.preparation.specialEffects.isEmpty())
        assertEquals(1, sharp.preparation.specialEffects.size)
    }

    @Test fun `SOURCEとTARGETの役割と期限と登録順を保持しCoreが条件判定する`() {
        val equipment = sword(linkedMapOf(MeleeSushiEricEnchantment.BRAWN to 1, MeleeSushiEricEnchantment.SHARPNESS to 1))
        val key = SimulatorEffectCooldownKey(ItemSpecialEffectRole.SOURCE, MeleeSushiEricEnchantment.SHARPNESS.providerKey, "damage_multiplier")
        val source = input(equipment, cooldowns = mapOf(key to 101L))
        val effect = source.preparation.specialEffects.single()
        assertEquals(ItemSpecialEffectRole.SOURCE, effect.role)
        assertEquals(StatsPart.MAIN_HAND, effect.part)
        assertEquals(1, effect.registrationIndex)
        assertEquals(101L, effect.cooldownUntilTick)
        assertEquals(62.5, calculate(source).meanDamage, 1e-10)
        val target = input(equipment, SimulatorAttack.MobMelee)
        assertEquals(ItemSpecialEffectRole.TARGET, target.preparation.specialEffects.single().role)
        assertEquals(60.0, calculate(target).meanDamage, 1e-10)
    }

    @Test fun `SHARPNESSはCoreのMAIN_HAND条件を守り独自のKind制限を加えない`() {
        val main = sword(mapOf(MeleeSushiEricEnchantment.SHARPNESS to 1))
        assertEquals(63.0, calculate(input(main, SimulatorAttack.PlayerMagic())).meanDamage, 1e-10)
        val offhand = ready(mapOf(StatsPart.OFF_HAND to SimulatorEquippedItem(item(ShortSwordData()),
            mapOf(MeleeSushiEricEnchantment.SHARPNESS to 5))))
        assertEquals(60.0, calculate(input(offhand)).meanDamage, 1e-10)
        assertEquals(StatsPart.OFF_HAND, input(offhand).preparation.specialEffects.single().part)
    }

    @Test fun `有効レベルと対象外をModと同じ規則で解決し保存指定を変えない`() {
        val levels = linkedMapOf<LevelledItemUpgradeDefinition, Int>(MeleeSushiEricEnchantment.BRAWN to 99,
            MeleeSushiEricEnchantment.SHARPNESS to 0, ArmorCustomEnchantment.PROTECTION to 3)
        val equipment = sword(levels)
        levels.clear()
        assertEquals(listOf(MeleeSushiEricEnchantment.BRAWN), equipment.activeUpgrades.map { it.definition })
        assertEquals(5, equipment.activeUpgrades.single().level)
        assertEquals(listOf(5, 0, 0), equipment.adjustments.map { it.effectiveLevel })
        assertEquals(99, equipment.input.items.getValue(StatsPart.MAIN_HAND).levelled[MeleeSushiEricEnchantment.BRAWN])
        assertEquals(45.0, input(equipment).player.snapshot.requirePlayerStats()[StatsType.STRENGTH])
        val negative = sword(mapOf(MeleeSushiEricEnchantment.LIFE_STEAL to -1))
        assertTrue(negative.activeUpgrades.isEmpty())
        val bow = ready(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(item(BowData()),
            mapOf(MeleeSushiEricEnchantment.LIFE_STEAL to 1))))
        assertTrue(bow.activeUpgrades.isEmpty())
    }

    @Test fun `Honingと各Reforge群は独立した最低最高レアリティ基準と一致する`() {
        val honed = sword(nonLevelled = mapOf(ItemUpgradeCategory.HONING to Honing))
        val stats = input(honed).player.snapshot.requirePlayerStats()
        assertEquals(100.0, stats[StatsType.PHYSICS_DAMAGE])
        assertEquals(10.0, stats[StatsType.PHYSICS_CRIT_CHANCE])
        val cases = listOf(Triple(SwordData(), MeleeReforge.POWERFUL, 20.0 to 80.0),
            Triple(BowData(), BowReforge.HUNTER, 16.0 to 64.0), Triple(CrossbowData(), CrossbowReforge.HEAVY, 24.0 to 90.0))
        for ((content, definition, expected) in cases) {
            for ((rarity, literal) in listOf(SushiEricRarity.COMMON to expected.first, SushiEricRarity.ULTIMATE to expected.second)) {
                val equipment = ready(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(item(content, rarity = rarity),
                    nonLevelled = mapOf(ItemUpgradeCategory.REFORGE to definition))))
                assertEquals(95.0 + literal, input(equipment).player.snapshot.requirePlayerStats()[StatsType.PHYSICS_DAMAGE], 1e-10)
            }
        }
        for ((rarity, literal) in listOf(SushiEricRarity.COMMON to 110.0, SushiEricRarity.ULTIMATE to 145.0)) {
            val armor = ready(mapOf(StatsPart.HELMET to SimulatorEquippedItem(item(HelmetData(), rarity = rarity),
                nonLevelled = mapOf(ItemUpgradeCategory.REFORGE to ArmorReforge.IMMOVABLE))))
            assertEquals(literal, input(armor).player.snapshot.requirePlayerStats()[StatsType.DEFENCE])
        }
    }

    @Test fun `防具強化と対象部位を反映する`() {
        val equipment = ready(mapOf(StatsPart.HELMET to SimulatorEquippedItem(item(HelmetData()), mapOf(ArmorCustomEnchantment.PROTECTION to 5)),
            StatsPart.BOOTS to SimulatorEquippedItem(item(BootsData()), mapOf(ArmorCustomEnchantment.RUSH to 3))))
        val player = input(equipment).player.snapshot.requirePlayerStats()
        assertEquals(120.0, player[StatsType.DEFENCE])
        assertEquals(15.0, player[StatsType.SPEED])
        assertIs<SimulatorEquipmentResult.Unsupported>(SimulatorEquipmentAssembler.resolve(SimulatorEquipmentInput(
            mapOf(StatsPart.HELMET to SimulatorEquippedItem(item()))), SkillLevelSnapshot.EMPTY))
    }

    @Test fun `非対応効果を持つ装備では部分的なSnapshotを返さない`() {
        for (definition in listOf(MeleeSushiEricEnchantment.LIFE_STEAL, MeleeSushiEricEnchantment.FROZEN_EDGE,
            MeleeSushiEricEnchantment.FIRE_ASPECT, MeleeSushiEricEnchantment.KNOCKBACK)) {
            val request = SimulatorEquipmentInput(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(item(), mapOf(definition to 1))))
            val result = assertIs<SimulatorEquipmentResult.Unsupported>(SimulatorEquipmentAssembler.resolve(request, SkillLevelSnapshot.EMPTY))
            assertSame(request, result.input)
            assertEquals(definition, result.problems.single().definition)
            assertTrue(result.problems.single().reason.isNotBlank())
        }
    }

    @Test fun `オフハンド短剣は通常能力から除外して予約入力にだけ半減する`() {
        val equipment = ready(mapOf(StatsPart.OFF_HAND to SimulatorEquippedItem(item(ShortSwordData(), mapOf(
            StatsType.PHYSICS_DAMAGE to 40.0, StatsType.PHYSICS_CRIT_CHANCE to 30.0)), mapOf(MeleeSushiEricEnchantment.BRAWN to 1))))
        assertFalse(StatsPart.OFF_HAND in equipment.equipmentSources)
        assertEquals(95.0, input(equipment).player.snapshot.requirePlayerStats()[StatsType.PHYSICS_DAMAGE])
        val followUp = assertNotNull(equipment.offhandFollowUp)
        assertEquals(20.0, followUp.stats.getTotalStat(StatsType.PHYSICS_DAMAGE))
        assertEquals(30.0, followUp.stats.getTotalStat(StatsType.PHYSICS_CRIT_CHANCE))
        assertEquals(2.5, followUp.stats.getTotalStat(StatsType.STRENGTH))
        val output = assertIs<DamagePreparationPipelineResult.Completed>(calculate(input(equipment)).branches.single().result.preparation).output
        assertEquals(105L, output.followUp?.executeTick)
        assertNotNull(output.followUp?.input?.resourceCost)
        assertEquals(100.0, input(equipment).sourceHp)
    }

    @Test fun `メインハンドの近接costはCatalogの固定仕様から作る`() {
        val equipment = sword()
        val rich = calculate(input(equipment, stamina = 20.0)).branches.single().result
        assertEquals(75.0, rich.finalDamage, 1e-10)
        assertEquals(8.0, rich.resourceCosts.single().amount)
        assertEquals(60.0, calculate(input(equipment, stamina = 0.0)).meanDamage)
        assertEquals("weapon", assertIs<SimulatorAttack.PlayerMelee>(input(equipment).attackSpec).effect.itemDataId)
    }

    @Test fun `元装備Mapと強化と倍率対象集合の変更が固定入力へ漏れない`() {
        val parts = mutableSetOf(StatsPart.MAIN_HAND)
        val multipliers = mutableListOf(ItemStatMultiplier(StatsPartTarget.Parts(parts), 1.1))
        val levels = mutableMapOf<LevelledItemUpgradeDefinition, Int>(MeleeSushiEricEnchantment.BRAWN to 1)
        val raw = item(stats = mapOf(StatsType.PHYSICS_DAMAGE to 10.0)).copy(statMultipliers = mapOf(StatsType.PHYSICS_DAMAGE to multipliers))
        val slots = mutableMapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(raw, levels))
        val fixed = SimulatorEquipmentInput(slots)
        val before = input(assertIs<SimulatorEquipmentResult.Ready>(SimulatorEquipmentAssembler.resolve(fixed, SkillLevelSnapshot.EMPTY)))
        slots.clear(); levels.clear(); parts.clear(); multipliers.clear()
        val after = input(assertIs<SimulatorEquipmentResult.Ready>(SimulatorEquipmentAssembler.resolve(fixed, SkillLevelSnapshot.EMPTY)))
        assertEquals(calculate(before).meanDamage, calculate(after).meanDamage)
        assertEquals(1, fixed.items.getValue(StatsPart.MAIN_HAND).levelled[MeleeSushiEricEnchantment.BRAWN])
        assertEquals(10.0, raw.stats[StatsType.PHYSICS_DAMAGE])
    }

    @Test fun `装備と技能の集計筋力がガードへ反映され構えなしでもパリィする`() {
        val equipment = ready(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(item(), mapOf(MeleeSushiEricEnchantment.BRAWN to 1)),
            StatsPart.OFF_HAND to SimulatorEquippedItem(item(ShieldData(parryCooldown = 20)))), mapOf(SkillType.COMBAT to 1))
        val fixed = input(equipment, SimulatorAttack.MobMelee)
        val guard = assertNotNull(fixed.preparation.playerGuard)
        assertFalse(guard.raising)
        assertEquals(27.0, guard.strength)
        assertEquals(100.0, guard.currentStamina)
        val result = calculate(fixed).branches.single().result
        assertEquals(46.5, result.finalDamage, 1e-10)
        val effect = assertIs<DamagePreparationEffect.Guard>(result.preparation.preparationEffects.single())
        assertEquals(6.0, effect.outcome.consumedStamina)
        assertEquals(120L, effect.cooldownUntilTick)
    }

    @Test fun `両手盾はメインを優先し構えとCD期限を保持する`() {
        val equipment = ready(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(item(ShieldData(blockCooldown = 11, parryCooldown = 21))),
            StatsPart.OFF_HAND to SimulatorEquippedItem(item(ShieldData(blockCooldown = 33, parryCooldown = 44)))))
        val state = SimulatorGuardState(raising = true, blockUntilTick = 100, parryUntilTick = 99)
        val fixed = input(equipment, SimulatorAttack.MobMelee, guardState = state)
        val guard = assertNotNull(fixed.preparation.playerGuard)
        assertEquals(11L, guard.shield.blockCooldown)
        assertTrue(guard.raising)
        assertEquals(100L, guard.blockUntilTick)
        assertEquals(99L, guard.parryUntilTick)
        val blocked = calculate(fixed).branches.single().result
        assertEquals(0.0, blocked.finalDamage)
        val effect = assertIs<DamagePreparationEffect.Guard>(blocked.preparation.preparationEffects.single())
        assertEquals(111L, effect.cooldownUntilTick)
        val cooling = calculate(input(equipment, SimulatorAttack.MobMelee, guardState = state.copy(blockUntilTick = 101)))
        assertEquals(60.0, cooling.meanDamage)
        assertTrue(cooling.branches.single().result.preparation.preparationEffects.isEmpty())
    }

    @Test fun `盾とスタミナは装備と同じ現在値から解決し盾なしではガードしない`() {
        val noShield = input(sword(), SimulatorAttack.MobMelee, guardState = SimulatorGuardState(raising = true))
        assertNull(noShield.preparation.playerGuard)
        assertEquals(60.0, calculate(noShield).meanDamage)
        val equipment = ready(mapOf(StatsPart.OFF_HAND to SimulatorEquippedItem(item(ShieldData()))))
        val empty = input(equipment, SimulatorAttack.MobMelee, guardState = SimulatorGuardState(raising = true), currentStamina = 0.0)
        assertEquals(0.0, empty.preparation.playerGuard?.currentStamina)
        assertEquals(60.0, calculate(empty).meanDamage)
        assertNull(input(equipment).preparation.playerGuard)
        assertFailsWith<IllegalArgumentException> {
            equipment.singleHitInput(position, base(), DynamicStatsType.entries.associateWith { 100.0 }, noShield.mob,
                SimulatorAttack.MobMelee, DamagePreparationValues(100, DamageResourceSource.NonPlayer, 0.0, 0.0,
                    playerGuard = PlayerGuardInput(ShieldData(), true, 999.0, 999.0)))
        }
    }

    @Test fun `技能上昇と低下で装備sourceと効果とコストを同時に切り替える`() {
        val weapon = item(stats = mapOf(StatsType.PHYSICS_DAMAGE to 10.0)).copy(skillLevelRequirements = mapOf(SkillType.COMBAT to 2))
        val raw = SimulatorEquipmentInput(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(weapon,
            mapOf(MeleeSushiEricEnchantment.SHARPNESS to 1))))
        fun resolve(level: Int) = assertIs<SimulatorEquipmentResult.Ready>(SimulatorEquipmentAssembler.resolve(raw,
            SkillLevelSnapshot(mapOf(SkillType.COMBAT to level))))
        for (level in listOf(0, 2, 0)) {
            val equipment = resolve(level)
            val fixed = input(equipment, stamina = 100.0)
            val melee = assertIs<SimulatorAttack.PlayerMelee>(fixed.attackSpec)
            if (level == 0) {
                assertEquals(StatsPart.MAIN_HAND, equipment.inactiveEquipment.single().part)
                assertTrue(equipment.inactiveEquipment.single().reason.isNotBlank())
                assertTrue(equipment.equipmentSources.isEmpty())
                assertTrue(fixed.preparation.specialEffects.isEmpty())
                assertNull(fixed.preparation.sourceItemData)
                assertNull(melee.effect.itemDataId)
                assertEquals(24.0, assertNotNull(melee.effect.resourceCost).fixedAmount())
                val result = calculate(fixed).branches.single().result
                assertEquals(75.0, result.finalDamage)
                assertEquals(24.0, result.resourceCosts.single().amount)
            } else {
                assertTrue(equipment.inactiveEquipment.isEmpty())
                assertEquals(1, equipment.equipmentSources.size)
                assertEquals(1, fixed.preparation.specialEffects.size)
                assertNotNull(fixed.preparation.sourceItemData)
                assertNotNull(melee.effect.resourceCost)
                assertEquals(2, fixed.player.snapshot.skillLevels?.level(SkillType.COMBAT))
            }
        }
        assertEquals(2, raw.items.getValue(StatsPart.MAIN_HAND).baseData.skillLevelRequirements[SkillType.COMBAT])
    }

    @Test fun `素手と技能未達の既定コスト24は充足時だけ強化して成功後消費する`() {
        val bare = ready(emptyMap())
        val gated = ready(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(item().copy(
            skillLevelRequirements = mapOf(SkillType.COMBAT to 1)))))
        for (equipment in listOf(bare, gated)) {
            val enough = calculate(input(equipment, stamina = 24.0)).branches.single().result
            assertEquals(75.0, enough.finalDamage)
            assertEquals(24.0, enough.resourceCosts.single().amount)
            val poor = calculate(input(equipment, stamina = 23.9)).branches.single().result
            assertEquals(60.0, poor.finalDamage)
            assertTrue(poor.resourceCosts.isEmpty())
            assertNull(assertIs<SimulatorAttack.PlayerMelee>(input(equipment).attackSpec).effect.itemDataId)
        }
    }

    @Test fun `技能未達の盾を飛ばして利用可能な副手を選び未達の短剣は予約しない`() {
        val gatedShield = item(ShieldData(parryCooldown = 21)).copy(skillLevelRequirements = mapOf(SkillType.COMBAT to 2))
        val availableShield = item(ShieldData(parryCooldown = 44))
        for ((level, cooldown) in listOf(1 to 44L, 2 to 21L, 1 to 44L)) {
            val equipment = ready(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(gatedShield),
                StatsPart.OFF_HAND to SimulatorEquippedItem(availableShield)), mapOf(SkillType.COMBAT to level))
            assertEquals(cooldown, input(equipment, SimulatorAttack.MobMelee).preparation.playerGuard?.shield?.parryCooldown)
        }
        val gated = ready(mapOf(StatsPart.OFF_HAND to SimulatorEquippedItem(gatedShield)))
        assertNull(input(gated, SimulatorAttack.MobMelee).preparation.playerGuard)
        val dagger = item(ShortSwordData()).copy(skillLevelRequirements = mapOf(SkillType.MINING to 1))
        for (level in listOf(0, 1, 0)) {
            val equipment = ready(mapOf(StatsPart.OFF_HAND to SimulatorEquippedItem(dagger)), mapOf(SkillType.MINING to level))
            if (level == 0) {
                assertNull(equipment.offhandFollowUp)
                assertNull(input(equipment).preparation.offhandFollowUp)
                assertEquals(StatsPart.OFF_HAND, equipment.inactiveEquipment.single().part)
            } else assertNotNull(equipment.offhandFollowUp)
        }
    }

    @Test fun `技能未達装備の非対応効果は無効で条件達成後は非対応になる`() {
        val weapon = item().copy(skillLevelRequirements = mapOf(SkillType.COMBAT to 2, SkillType.MINING to 1))
        val fixed = SimulatorEquipmentInput(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(weapon,
            mapOf(MeleeSushiEricEnchantment.LIFE_STEAL to 1))))
        val partial = assertIs<SimulatorEquipmentResult.Ready>(SimulatorEquipmentAssembler.resolve(fixed,
            SkillLevelSnapshot(mapOf(SkillType.COMBAT to 2))))
        assertTrue(partial.activeUpgrades.isEmpty())
        assertEquals(1, partial.inactiveEquipment.size)
        assertIs<SimulatorEquipmentResult.Unsupported>(SimulatorEquipmentAssembler.resolve(fixed,
            SkillLevelSnapshot(mapOf(SkillType.COMBAT to 2, SkillType.MINING to 1))))
    }

    @Test fun `非レベル強化はカテゴリごと一件で内部level1を使う`() {
        val equipment = sword(nonLevelled = mapOf(ItemUpgradeCategory.HONING to Honing, ItemUpgradeCategory.REFORGE to MeleeReforge.POWERFUL))
        assertTrue(equipment.activeUpgrades.all { it.level == 1 })
        assertEquals(listOf(MeleeReforge.POWERFUL, Honing), equipment.activeUpgrades.map { it.definition })
        assertFailsWith<IllegalArgumentException> { SimulatorEquippedItem(item(), nonLevelled = mapOf(ItemUpgradeCategory.REFORGE to Honing)) }
    }
}
