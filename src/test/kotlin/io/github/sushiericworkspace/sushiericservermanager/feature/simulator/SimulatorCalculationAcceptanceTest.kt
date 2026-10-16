package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.damage.kind.effect.PlayerToMobMeleeEffects
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.*
import io.github.sushiericworkspace.combatcore.combat.damage.state.DamageResources
import io.github.sushiericworkspace.combatcore.combat.guard.MobParryInput
import io.github.sushiericworkspace.combatcore.combat.random.*
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.LevelledItemUpgradeDefinition
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.enchantment.MeleeSushiEricEnchantment
import io.github.sushiericworkspace.combatcore.player.skill.effect.SkillLevelSnapshot
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.*
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.detail.*
import io.github.sushiericworkspace.sushiericdatamodel.stats.entity.EntityStatsType
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.*
import java.util.UUID
import kotlin.test.*

/** 個別式を再検証せず、独立基準値が装備入力から比較記録まで届くことを受け入れます。 */
class SimulatorCalculationAcceptanceTest {
    private val version = SimulatorComparisonVersion("0c5b1a4cd431d3394ce01e9f118e1a7d7f4920ab",
        "93220ce834ab7957f9d480eaec27cfb76ee1052d", "manager267-8f43ee936d44225f383e4d76a3f5b27a3f7de92a")
    private val position = SimulatorPosition(UUID(0, 1), "Player", "acceptance")
    private val arrow = SimulatorAttack.PlayerProjectile(null, null, null)
    private fun rows(vararg ids: String) = ids.map { SimulatorHit(arrow, hitId = it) }
    private fun stats(chance: Double = 0.0) = StatsType.entries.associateWith { when (it) {
        StatsType.PHYSICS_DAMAGE -> 95.0
        StatsType.PHYSICS_CRIT_CHANCE -> chance
        StatsType.PHYSICS_CRIT_DAMAGE -> 50.0
        else -> 0.0
    } }
    private fun equipment(items: Map<StatsPart, SimulatorEquippedItem> = emptyMap(), level: Int = 0) =
        assertIs<SimulatorEquipmentResult.Ready>(SimulatorEquipmentAssembler.resolve(
            SimulatorEquipmentInput(items), SkillLevelSnapshot(mapOf(SkillType.COMBAT to level))))
    private fun state(hp: Double = 1000.0, oh: Double = 0.0) = SameTickDamageState(
        SameTickCombatantState(1000.0, resources = DamageResources(0.0, 0.0)), SameTickCombatantState(hp, oh))
    private fun input(gear: SimulatorEquipmentResult.Ready = equipment(), hits: List<SimulatorHit> = rows("a", "b"),
        initial: SameTickDamageState = state(), parry: MobParryInput? = null, base: Map<StatsType, Double> = stats(), mobAttack: Double = 0.0) =
        gear.multipleHitInput(position, base, SimulatorMobInput(SimulatorPosition(UUID(0, 2), "Mob", "acceptance"),
            EntityStatsType.entries.associateWith { when (it) { EntityStatsType.MAX_HEALTH -> 1000.0; EntityStatsType.ATTACK_DAMAGE -> mobAttack; else -> 0.0 } },
            emptyMap(), 1000.0), 100L, initial, hits, 1.0, 0.0, mobParry = parry)
    private fun capture(input: SimulatorMultipleHitInput, generation: Long = 1,
        mode: SimulatorMultipleHitMode = SimulatorMultipleHitMode.Expected): SimulatorComparisonSnapshot {
        val request = SimulatorMultipleHitRequest(generation, input, mode)
        return SimulatorComparisonSnapshot.capture(request, SimulatorMultipleHitSimulator.calculate(request), version)
    }
    private fun compare(baseline: SimulatorComparisonSnapshot, current: SimulatorComparisonSnapshot) =
        SimulatorComparisonSession().apply { setBaseline(baseline) }.compare(current, current.generation)

    @Test fun `COMBAT端点の装備判定とSelfAllParts集計が比較まで二重適用されない`() {
        val raw = ItemBaseData(id = "bow", itemDetail = ItemDetail(content = BowData()),
            stats = mapOf(StatsType.PHYSICS_DAMAGE to 10.0), statMultipliers = mapOf(
                StatsType.PHYSICS_DAMAGE to listOf(ItemStatMultiplier(StatsPartTarget.Self, 1.5),
                    ItemStatMultiplier(StatsPartTarget.All, 1.1),
                    ItemStatMultiplier(StatsPartTarget.Parts(setOf(StatsPart.MAIN_HAND)), 1.2))))
        val before = capture(input(equipment(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(raw))), rows("a"),
            base = stats() + (StatsType.PHYSICS_DAMAGE to 100.0)))
        // Part取り分はlevel0でBASE110+HAND19.5、level1でBASE112+HAND19.8。
        for ((level, literal) in listOf(0 to 134.5, 1 to 139.536, SkillType.MAX_LEVEL to 1093.5)) {
            val gear = equipment(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(raw)), level)
            val now = capture(input(gear, rows("a"), base = stats() + (StatsType.PHYSICS_DAMAGE to 100.0)), 2)
            val delta = assertIs<SimulatorComparisonResult.Expected>(compare(before, now))
            assertEquals(literal, delta.totals.calculated.current, 1e-9)
            assertEquals(literal - 134.5, delta.totals.calculated.difference, 1e-9)
        }
        assertEquals(134.5, assertIs<SimulatorMultipleHitResult.Expected>(before.result).result.meanCalculatedDamage)
    }

    @Test fun `SHARPNESSの編集値を固定して1から5への変化を比較する`() {
        val raw = ItemBaseData(id = "sword", itemDetail = ItemDetail(content = SwordData()))
        val levels = mutableMapOf<LevelledItemUpgradeDefinition, Int>(MeleeSushiEricEnchantment.SHARPNESS to 1)
        val equipped = SimulatorEquippedItem(raw, levels)
        val melee = listOf(SimulatorHit(SimulatorAttack.PlayerMelee(PlayerToMobMeleeEffects.Direct(null, null)), hitId = "sword"))
        val before = capture(input(equipment(mapOf(StatsPart.MAIN_HAND to equipped)), melee))
        levels[MeleeSushiEricEnchantment.SHARPNESS] = 5
        val after = capture(input(equipment(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(raw, levels))), melee), 2)
        val session = SimulatorComparisonSession().apply { setBaseline(before) }
        val result = assertIs<SimulatorComparisonResult.Expected>(session.compare(after, 2))
        assertEquals(105.0, result.totals.calculated.baseline)
        assertEquals(125.0, result.totals.calculated.current)
        assertEquals(20.0, result.totals.calculated.difference)
        assertSame(before, session.baseline)
        assertEquals(105.0, assertIs<SimulatorMultipleHitResult.Expected>(before.result).result.meanCalculatedDamage)
    }

    @Test fun `状態付き期待値の枝と周辺値を比較へ運びsampleと混同しない`() {
        val before = capture(input(parry = MobParryInput(1.0, 1.0, 20)))
        val currentInput = input(parry = MobParryInput(0.5, 1.0, 20))
        val current = capture(currentInput, 2)
        val result = assertIs<SimulatorComparisonResult.Expected>(compare(before, current))
        assertEquals(100.0, result.totals.calculated.baseline)
        assertEquals(125.0, result.totals.calculated.current)
        assertEquals(25.0, result.totals.calculated.difference)
        val rows = result.rows.map { assertIs<SimulatorRowComparison.Matched<SameTickDamageRowExpectation>>(it) }
        assertEquals(listOf(0.0, 100.0), rows.map { it.baseline.meanCalculatedDamage })
        assertEquals(listOf(50.0, 75.0), rows.map { it.current.meanCalculatedDamage })
        assertEquals(listOf(0.5, 0.75), rows.map { it.current.appliedProbability })
        val branches = assertIs<SimulatorMultipleHitResult.Expected>(current.result).result.branches
        assertEquals(listOf(0.25, 0.25, 0.5), branches.map { it.probability }.sorted())
        assertEquals(125.0, branches.sumOf { it.probability * it.healthDamage })
        assertIs<SimulatorComparisonResult.Incompatible>(compare(before,
            capture(currentInput, 3, SimulatorMultipleHitMode.Sample(0))))
    }

    @Test fun `同tickの未命中とOH吸収と死亡後を比較の別指標に保つ`() {
        val hits = rows("miss", "hit", "dead").mapIndexed { i, hit -> hit.copy(matched = i != 0) }
        val baseline = capture(input(hits = hits, initial = state(40.0, 40.0)))
        val current = capture(input(hits = hits.map { it.copy(pierceOverHeal = true) }, initial = state(40.0, 40.0)), 2)
        val delta = assertIs<SimulatorComparisonResult.Expected>(compare(baseline, current))
        assertEquals(100.0, delta.totals.calculated.baseline)
        assertEquals(40.0, delta.totals.health.baseline)
        assertEquals(40.0, delta.totals.overHeal.baseline)
        assertEquals(80.0, delta.totals.applied.baseline)
        assertEquals(0.0, delta.totals.health.difference)
        assertEquals(-40.0, delta.totals.overHeal.difference)
        assertEquals(-40.0, delta.totals.applied.difference)
        val rows = delta.rows.map { assertIs<SimulatorRowComparison.Matched<SameTickDamageRowExpectation>>(it).current }
        assertEquals(1.0, rows[0].statusProbabilities.getValue(SameTickDamageStatus.MISSED))
        assertEquals(1.0, rows[2].statusProbabilities.getValue(SameTickDamageStatus.TARGET_DEAD))
    }

    @Test fun `装備付きsampleの追加削除と確率変更でも既存IDの抽選記録を保持する`() {
        val bow = equipment(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(ItemBaseData(
            id = "bow", itemDetail = ItemDetail(content = BowData())))))
        val mode = SimulatorMultipleHitMode.Sample(123)
        val before = capture(input(bow, rows("a", "b", "removed"), base = stats(50.0)), mode = mode)
        val now = capture(input(bow, rows("new", "b", "a"), base = stats(100.0)), 2, mode)
        val delta = assertIs<SimulatorComparisonResult.Sample>(compare(before, now))
        assertEquals(listOf("new", "b", "a", "removed"), delta.rows.map { it.hitId })
        assertIs<SimulatorRowComparison.Added<SameTickDamageHitResult>>(delta.rows.first())
        assertIs<SimulatorRowComparison.Removed<SameTickDamageHitResult>>(delta.rows.last())
        for (id in listOf("a", "b")) {
            val critical = delta.draws.getValue(id).single { it.kind == DamageRollKind.CRITICAL }
            val baseline = assertIs<DamageRollRecord.Reached>(critical.baseline)
            val current = assertIs<DamageRollRecord.Reached>(critical.current)
            assertEquals(baseline.unitValue, current.unitValue)
            assertEquals(0.5, baseline.probability)
            assertEquals(1.0, current.probability)
            assertTrue(current.succeeded)
        }
        assertEquals(StableDamageRandom.VERSION, before.randomVersion)
        assertEquals(mode, before.mode)
    }

    @Test fun `取消と上限と追撃非対応は比較の0へ変換せず基準と世代を保持する`() {
        val session = SimulatorComparisonSession()
        val baseline = capture(input())
        session.setBaseline(baseline)
        val cancelled = SimulatorMultipleHitRequest(2, input(), SimulatorMultipleHitMode.Expected).apply { cancel() }
        val cancelledRecord = SimulatorComparisonSnapshot.capture(cancelled, SimulatorMultipleHitSimulator.calculate(cancelled), version)
        val many = capture(input(hits = List(17) { SimulatorHit(arrow, hitId = "branch-$it") },
            initial = state(1e9), base = stats(50.0)), 3)
        assertIs<SimulatorMultipleHitResult.LimitExceeded>(many.result)
        val dagger = equipment(mapOf(StatsPart.OFF_HAND to SimulatorEquippedItem(ItemBaseData(
            id = "dagger", itemDetail = ItemDetail(content = ShortSwordData())))))
        val unsupported = capture(input(dagger, listOf(SimulatorHit(
            SimulatorAttack.PlayerMelee(PlayerToMobMeleeEffects.Direct(null, null)), hitId = "direct"))), 4)
        assertIs<SimulatorMultipleHitResult.Unsupported>(unsupported.result)
        for (current in listOf(cancelledRecord, many, unsupported)) {
            assertIs<SimulatorComparisonResult.Incomplete>(session.compare(current, current.generation))
            assertSame(baseline, session.baseline)
        }
        assertIs<SimulatorComparisonResult.Stale>(session.compare(capture(input(), 5), 6))
        assertSame(baseline, session.baseline)
    }

    @Test fun `Mod同入力の2矢パリィを装備組立から比較のHPSTCD記録まで接続する`() {
        val shield = equipment(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(ItemBaseData(
            id = "shield", itemDetail = ItemDetail(content = ShieldData(parryCooldown = 20))))))
        val hits = rows("mod-arrow-1", "mod-arrow-2").map { it.copy(attack = SimulatorAttack.MobProjectile) }
        val initial = SameTickDamageState(SameTickCombatantState(1000.0),
            SameTickCombatantState(1000.0, resources = DamageResources(100.0, 0.0)))
        val base = stats() + (StatsType.STRENGTH to 100.0)
        val mode = SimulatorMultipleHitMode.Sample(0)
        val before = capture(input(hits = hits, initial = initial, base = base, mobAttack = 100.0), mode = mode)
        val current = capture(input(shield, hits, initial, base = base, mobAttack = 100.0), 2, mode)
        val delta = assertIs<SimulatorComparisonResult.Sample>(compare(before, current))
        assertEquals(200.0, delta.totals.health.baseline)
        assertEquals(100.0, delta.totals.health.current)
        assertEquals(-100.0, delta.totals.health.difference)
        val result = assertIs<SimulatorMultipleHitResult.Sample>(current.result).result
        assertEquals(listOf(0.0, 100.0), result.rows.map { it.transition.calculatedDamage })
        assertEquals(1000.0, result.rows[0].transition.after.target.health)
        assertEquals(900.0, result.finalState.target.health)
        assertEquals(95.0, result.finalState.target.resources.stamina)
        assertEquals(120L, result.finalState.target.parryUntilTick)
        assertEquals(100.0, assertIs<SimulatorMultipleHitResult.Expected>(capture(input(shield, hits, initial,
            base = base, mobAttack = 100.0)).result).result.meanHealthDamage)
    }
}
