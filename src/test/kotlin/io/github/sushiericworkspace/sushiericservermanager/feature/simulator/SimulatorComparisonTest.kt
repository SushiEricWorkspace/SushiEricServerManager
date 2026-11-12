package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.attack.sequence.AttackRecord
import io.github.sushiericworkspace.combatcore.combat.damage.preparation.DamagePreparationPhaseTrace
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.*
import io.github.sushiericworkspace.combatcore.combat.damage.state.DamageResources
import io.github.sushiericworkspace.combatcore.combat.guard.MobParryInput
import io.github.sushiericworkspace.combatcore.combat.random.*
import io.github.sushiericworkspace.combatcore.stats.StatModifier
import io.github.sushiericworkspace.combatcore.item.upgrade.effect.ActiveItemUpgrade
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.enchantment.MeleeSushiEricEnchantment
import io.github.sushiericworkspace.combatcore.item.effect.ItemSpecialEffectRole
import io.github.sushiericworkspace.combatcore.player.skill.effect.SkillLevelSnapshot
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.DisplayInfo
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.LoreSection
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.PlainTextLoreSection
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemStatMultiplier
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.detail.SwordData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.detail.BowData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.detail.ItemDetail
import io.github.sushiericworkspace.sushiericdatamodel.stats.entity.EntityStatsType
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.*
import java.util.UUID
import kotlin.test.*

class SimulatorComparisonTest {
    private val version = SimulatorComparisonVersion("core-fixed", "datamodel-fixed", "manager-calculation-1")
    private val position = SimulatorPosition(UUID(0, 1), "Player", "test")
    private val arrow = SimulatorAttack.PlayerProjectile(null, null, null)
    private fun rows(vararg ids: String) = ids.map { SimulatorHit(arrow, hitId = it) }
    private fun base(damage: Double = 95.0, chance: Double = 0.0) = StatsType.entries.associateWith { when (it) {
        StatsType.PHYSICS_DAMAGE -> damage
        StatsType.PHYSICS_CRIT_CHANCE -> chance
        StatsType.PHYSICS_CRIT_DAMAGE -> 50.0
        else -> 0.0
    } }
    private fun state(hp: Double = 1000.0, overHeal: Double = 0.0) = SameTickDamageState(
        SameTickCombatantState(1000.0, resources = DamageResources(100.0, 100.0)), SameTickCombatantState(hp, overHeal))
    private fun input(hits: List<SimulatorHit> = rows("a", "b"), stats: Map<StatsType, Double> = base(), defence: Double = 0.0,
        initial: SameTickDamageState = state(), parry: MobParryInput? = null,
        equipment: SimulatorEquipmentResult.Ready = assertIs<SimulatorEquipmentResult.Ready>(SimulatorEquipmentAssembler.resolve(
            SimulatorEquipmentInput(emptyMap()), SkillLevelSnapshot.EMPTY))): SimulatorMultipleHitInput.Ready {
        val mob = SimulatorMobInput(SimulatorPosition(UUID(0, 2), "Mob", "test"), EntityStatsType.entries.associateWith {
            when (it) { EntityStatsType.DEFENCE -> defence; EntityStatsType.MAX_HEALTH -> 1000.0; else -> 0.0 }
        }, emptyMap(), 1000.0)
        return assertIs(equipment.multipleHitInput(position, stats, mob, 100, initial, hits, 1.0, 0.0, parry))
    }
    private fun capture(input: SimulatorMultipleHitInput, generation: Long = 1, mode: SimulatorMultipleHitMode = SimulatorMultipleHitMode.Expected,
        identity: SimulatorComparisonVersion = version): SimulatorComparisonSnapshot {
        val request = SimulatorMultipleHitRequest(generation, input, mode)
        return SimulatorComparisonSnapshot.capture(request, SimulatorMultipleHitSimulator.calculate(request), identity)
    }
    private fun compare(before: SimulatorComparisonSnapshot, current: SimulatorComparisonSnapshot) = SimulatorComparisonSession().apply {
        setBaseline(before)
    }.compare(current, current.generation)

    @Test fun `同一入力の期待値差は全指標と行で0になる`() {
        val fixed = input()
        val before = capture(fixed)
        val current = capture(fixed, 2)
        assertSame(fixed, before.input)
        val result = assertIs<SimulatorComparisonResult.Expected>(compare(before, current))
        assertEquals(200.0, result.totals.calculated.baseline)
        assertEquals(0.0, result.totals.calculated.difference)
        assertEquals(0.0, result.totals.health.changePercent)
        assertTrue(result.rows.all { assertIs<SimulatorRowComparison.Matched<SameTickDamageRowExpectation>>(it).changes.calculated.difference == 0.0 })
    }

    @Test fun `装備と防御と距離の変更を再計算せず評価済み期待値から比較する`() {
        val baseline = capture(input(rows("a")))
        val equipment = assertIs<SimulatorEquipmentResult.Ready>(SimulatorEquipmentAssembler.resolve(SimulatorEquipmentInput(mapOf(
            StatsPart.MAIN_HAND to SimulatorEquippedItem(ItemBaseData(itemDetail = ItemDetail(content = BowData()),
                stats = mapOf(StatsType.PHYSICS_DAMAGE to 100.0))))), SkillLevelSnapshot.EMPTY))
        val geared = assertIs<SimulatorComparisonResult.Expected>(compare(baseline, capture(input(rows("a"), equipment = equipment))))
        assertEquals(100.0, geared.totals.calculated.difference)
        assertEquals(100.0, geared.totals.calculated.changePercent)
        val defended = assertIs<SimulatorComparisonResult.Expected>(compare(baseline, capture(input(rows("a"), defence = 100.0))))
        assertEquals(-50.0, defended.totals.calculated.difference)
        val distant = rows("a").map { it.copy(attack = SimulatorAttack.PlayerProjectile(null, null, 4.0)) }
        val distance = assertIs<SimulatorComparisonResult.Expected>(compare(baseline, capture(input(distant))))
        assertEquals(-11.0, distance.totals.calculated.difference, 1e-10)
    }

    @Test fun `基準0の比率は0同士でもなく数値の差だけ返す`() {
        val zero = capture(input(rows("a").map { it.copy(matched = false) }))
        for (current in listOf(zero, capture(input(rows("a"))))) {
            val result = assertIs<SimulatorComparisonResult.Expected>(compare(zero, current))
            assertNull(result.totals.calculated.changePercent)
            assertNull(result.totals.health.changePercent)
        }
        assertEquals(100.0, assertIs<SimulatorComparisonResult.Expected>(compare(zero, capture(input(rows("a"))))).totals.health.difference)
    }

    @Test fun `過剰ダメージとOverHealを純HPの差へ混ぜない`() {
        val before = capture(input(rows("a"), initial = state(50.0, 20.0)))
        val after = capture(input(rows("a"), stats = base(195.0), initial = state(50.0, 20.0)))
        val result = assertIs<SimulatorComparisonResult.Expected>(compare(before, after))
        assertEquals(100.0, result.totals.calculated.difference)
        assertEquals(50.0, result.totals.health.current)
        assertEquals(0.0, result.totals.health.difference)
        assertEquals(20.0, result.totals.overHeal.current)
        assertEquals(70.0, result.totals.applied.current)
    }

    @Test fun `追加削除と順序変更をhitIdで区別し0行を作らない`() {
        val before = capture(input(rows("a", "b", "c")))
        val current = capture(input(rows("new", "b", "a")), 2)
        val result = assertIs<SimulatorComparisonResult.Expected>(compare(before, current))
        assertEquals(listOf("new", "b", "a", "c"), result.rows.map { it.hitId })
        assertIs<SimulatorRowComparison.Added<SameTickDamageRowExpectation>>(result.rows[0])
        assertFalse(assertIs<SimulatorRowComparison.Matched<SameTickDamageRowExpectation>>(result.rows[1]).moved)
        val moved = assertIs<SimulatorRowComparison.Matched<SameTickDamageRowExpectation>>(result.rows[2])
        assertEquals(0, moved.baselineIndex)
        assertEquals(2, moved.currentIndex)
        assertTrue(moved.moved)
        assertIs<SimulatorRowComparison.Removed<SameTickDamageRowExpectation>>(result.rows[3])
    }

    @Test fun `追加削除や並べ替えで既存行の抽選値がずれない`() {
        val mode = SimulatorMultipleHitMode.Sample(42)
        val result = assertIs<SimulatorComparisonResult.Sample>(compare(capture(input(rows("a", "b", "c")), mode = mode),
            capture(input(rows("new", "c", "b")), 2, mode)))
        assertEquals(setOf("b", "c"), result.draws.keys)
        for (draws in result.draws.values) {
            val critical = draws.single { it.kind == DamageRollKind.CRITICAL }
            assertEquals(assertIs<DamageRollRecord.Reached>(critical.baseline).unitValue,
                assertIs<DamageRollRecord.Reached>(critical.current).unitValue)
        }
    }

    @Test fun `同じ抽選値でも確率変更による成立差を区別する`() {
        val mode = SimulatorMultipleHitMode.Sample(5)
        val result = assertIs<SimulatorComparisonResult.Sample>(compare(capture(input(rows("a"), stats = base(chance = 0.0)), mode = mode),
            capture(input(rows("a"), stats = base(chance = 100.0)), 2, mode)))
        val draw = result.draws.getValue("a").single { it.kind == DamageRollKind.CRITICAL }
        val before = assertIs<DamageRollRecord.Reached>(draw.baseline)
        val after = assertIs<DamageRollRecord.Reached>(draw.current)
        assertEquals(before.unitValue, after.unitValue)
        assertEquals(0.0, before.probability)
        assertEquals(1.0, after.probability)
        assertFalse(before.succeeded)
        assertTrue(after.succeeded)
        assertEquals(50.0, result.totals.calculated.difference)
    }

    @Test fun `前のパリィCD差で後行の到達状態と拒否と数値が変わる`() {
        val before = input(parry = MobParryInput(1.0, 1.0, 20))
        val current = input(parry = MobParryInput(1.0, 1.0, 0))
        val mode = SimulatorMultipleHitMode.Sample(5)
        val result = assertIs<SimulatorComparisonResult.Sample>(compare(capture(before, mode = mode), capture(current, 2, mode)))
        val parry = result.draws.getValue("b").single { it.kind == DamageRollKind.MOB_PARRY }
        assertIs<DamageRollRecord.NotReached>(parry.baseline)
        assertIs<DamageRollRecord.Reached>(parry.current)
        val second = assertIs<SimulatorRowComparison.Matched<SameTickDamageHitResult>>(result.rows[1])
        assertEquals(SameTickDamageStatus.APPLIED, second.baseline.transition.status)
        assertEquals(SameTickDamageStatus.REJECTED, second.current.transition.status)
        assertEquals(120L, second.baseline.transition.before.target.parryUntilTick)
        assertEquals(0L, second.current.transition.before.target.parryUntilTick)
        assertEquals(-100.0, second.changes.health.difference)
        val expected = assertIs<SimulatorComparisonResult.Expected>(compare(capture(before), capture(current, 2)))
        val row = assertIs<SimulatorRowComparison.Matched<SameTickDamageRowExpectation>>(expected.rows[1])
        assertEquals(1.0, row.baseline.statusProbabilities[SameTickDamageStatus.APPLIED])
        assertEquals(1.0, row.current.statusProbabilities[SameTickDamageStatus.REJECTED])
    }

    @Test fun `死亡後行と期待値の終端枝をそのまま保持する`() {
        val result = assertIs<SimulatorComparisonResult.Sample>(compare(capture(input(initial = state(1000.0)), mode = SimulatorMultipleHitMode.Sample(1)),
            capture(input(initial = state(40.0)), 2, SimulatorMultipleHitMode.Sample(1))))
        val second = assertIs<SimulatorRowComparison.Matched<SameTickDamageHitResult>>(result.rows[1])
        assertEquals(SameTickDamageStatus.TARGET_DEAD, second.current.transition.status)
        assertEquals(0.0, second.current.transition.after.target.health)
        val expected = capture(input(parry = MobParryInput(0.5, 1.0, 20)))
        val branches = assertIs<SimulatorMultipleHitResult.Expected>(expected.result).result.branches
        assertEquals(1.0, branches.sumOf { it.probability })
        assertTrue(branches.map { it.finalState.target.health }.distinct().size > 1)
    }

    @Test fun `未完了と版違いと異モードには数値差を返さない`() {
        val fixed = input()
        val complete = capture(fixed)
        val request = SimulatorMultipleHitRequest(2, fixed, SimulatorMultipleHitMode.Expected)
        val failures = listOf(SimulatorMultipleHitResult.Cancelled(2),
            SimulatorMultipleHitResult.LimitExceeded(2, 65_536, "厳密期待値の計算上限超過"),
            SimulatorMultipleHitResult.Unsupported(2, "非対応効果", "a"))
        for (failure in failures) {
            val result = assertIs<SimulatorComparisonResult.Incomplete>(compare(complete,
                SimulatorComparisonSnapshot.capture(request, failure, version)))
            assertEquals(failure, result.current)
        }
        assertIs<SimulatorComparisonResult.Incompatible>(compare(complete,
            capture(fixed, 2, identity = version.copy(combatCore = "other-core"))))
        assertIs<SimulatorComparisonResult.Incompatible>(compare(complete, capture(fixed, 2, SimulatorMultipleHitMode.Sample(1))))
        val sampleFailure = SimulatorComparisonSnapshot.capture(SimulatorMultipleHitRequest(2, fixed, SimulatorMultipleHitMode.Sample(1)), failures.last(), version)
        assertIs<SimulatorComparisonResult.Incomplete>(compare(capture(fixed, mode = SimulatorMultipleHitMode.Sample(1)), sampleFailure))
    }

    @Test fun `captureの世代モードseed不一致を拒否し現在のstaleだけを除く`() {
        val fixed = input()
        val expected = assertIs<SimulatorMultipleHitResult.Expected>(capture(fixed).result)
        assertFailsWith<IllegalArgumentException> { SimulatorComparisonSnapshot.capture(
            SimulatorMultipleHitRequest(2, fixed, SimulatorMultipleHitMode.Expected), expected, version) }
        assertFailsWith<IllegalArgumentException> { SimulatorComparisonSnapshot.capture(
            SimulatorMultipleHitRequest(1, fixed, SimulatorMultipleHitMode.Sample(1)), expected, version) }
        val sample = assertIs<SimulatorMultipleHitResult.Sample>(capture(fixed, mode = SimulatorMultipleHitMode.Sample(1)).result)
        assertFailsWith<IllegalArgumentException> { SimulatorComparisonSnapshot.capture(
            SimulatorMultipleHitRequest(1, fixed, SimulatorMultipleHitMode.Sample(2)), sample, version) }
        val session = SimulatorComparisonSession()
        assertEquals(SimulatorComparisonResult.NoBaseline, session.compare(capture(fixed), 1))
        val baseline = capture(fixed)
        session.setBaseline(baseline)
        assertIs<SimulatorComparisonResult.Expected>(session.compare(capture(fixed, 8), 8))
        assertEquals(SimulatorComparisonResult.Stale(7, 8), session.compare(capture(fixed, 7), 8))
        assertSame(baseline, session.baseline)
        session.clearBaseline()
        assertNull(session.baseline)
    }

    @Test fun `基準更新は明示操作だけでseed変更は期待値と別に記録する`() {
        val fixed = input(stats = base(chance = 50.0))
        val session = SimulatorComparisonSession()
        val first = capture(fixed, mode = SimulatorMultipleHitMode.Sample(1))
        val current = capture(fixed, 2, SimulatorMultipleHitMode.Sample(2))
        session.setBaseline(first)
        val result = assertIs<SimulatorComparisonResult.Sample>(session.compare(current, 2))
        assertEquals(1L, assertIs<SimulatorMultipleHitMode.Sample>(result.baseline.mode).seed)
        assertEquals(2L, assertIs<SimulatorMultipleHitMode.Sample>(result.current.mode).seed)
        assertEquals(StableDamageRandom.VERSION, first.randomVersion)
        assertSame(first, session.baseline)
        session.setBaseline(current)
        assertEquals(0.0, assertIs<SimulatorComparisonResult.Sample>(session.compare(current, 2)).totals.calculated.difference)
        assertEquals(0.0, assertIs<SimulatorComparisonResult.Expected>(compare(capture(fixed), capture(fixed, 2))).totals.calculated.difference)
    }

    @Test fun `元入力Map変更と期待値結果Mapや枝変更が基準へ漏れない`() {
        val stats = base(chance = 50.0).toMutableMap()
        val fixed = input(stats = stats)
        val request = SimulatorMultipleHitRequest(1, fixed, SimulatorMultipleHitMode.Expected)
        val raw = assertIs<SimulatorMultipleHitResult.Expected>(SimulatorMultipleHitSimulator.calculate(request))
        val snapshot = SimulatorComparisonSnapshot.capture(request, raw, version)
        stats.clear()
        assertIs<MutableMap<SameTickDamageStatus, Double>>(raw.result.rows.first().statusProbabilities).clear()
        assertIs<MutableList<SameTickDamageRowExpectation>>(raw.result.rows).clear()
        assertIs<MutableList<SameTickDamageExpectationBranch>>(raw.result.branches).clear()
        val exposed = assertIs<SimulatorMultipleHitResult.Expected>(snapshot.result)
        assertEquals(2, exposed.result.rows.size)
        assertEquals(5, exposed.result.rows.first().statusProbabilities.size)
        assertTrue(exposed.result.branches.isNotEmpty())
        assertIs<MutableMap<SameTickDamageStatus, Double>>(exposed.result.rows.first().statusProbabilities).clear()
        assertIs<MutableList<SameTickDamageExpectationBranch>>(exposed.result.branches).clear()
        assertEquals(5, assertIs<SimulatorMultipleHitResult.Expected>(snapshot.result).result.rows.first().statusProbabilities.size)
        assertEquals(0.0, assertIs<SimulatorComparisonResult.Expected>(compare(snapshot, capture(fixed, 2))).totals.calculated.difference)
    }

    @Test fun `公開装備のMapとsource変更で基準の入力説明値がドリフトしない`() {
        val targets = mutableSetOf(StatsPart.MAIN_HAND, StatsPart.HELMET)
        val lore = listOf(listOf(PlainTextLoreSection("a"), PlainTextLoreSection("b")),
            listOf(PlainTextLoreSection("c"), PlainTextLoreSection("d")))
        val item = ItemBaseData(itemDetail = ItemDetail(content = SwordData()), display = DisplayInfo(lore = lore),
            stats = mapOf(StatsType.PHYSICS_DAMAGE to 10.0, StatsType.STRENGTH to 2.0),
            skillLevelRequirements = mapOf(SkillType.COMBAT to 1, SkillType.MINING to 1),
            statMultipliers = mapOf(StatsType.PHYSICS_DAMAGE to listOf(
                ItemStatMultiplier(StatsPartTarget.Parts(targets), 1.1), ItemStatMultiplier(StatsPartTarget.All, 1.2)),
                StatsType.STRENGTH to listOf(ItemStatMultiplier(StatsPartTarget.All, 1.1))))
        val equipment = assertIs<SimulatorEquipmentResult.Ready>(SimulatorEquipmentAssembler.resolve(
            SimulatorEquipmentInput(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(item,
                mapOf(MeleeSushiEricEnchantment.BRAWN to 1, MeleeSushiEricEnchantment.SHARPNESS to 1)))),
            SkillLevelSnapshot(mapOf(SkillType.COMBAT to 2, SkillType.MINING to 2))))
        val fixed = input(equipment = equipment)
        val snapshot = capture(fixed)
        val publicItem = fixed.equipment.input.items.getValue(StatsPart.MAIN_HAND).baseData
        targets.clear()
        assertIs<MutableList<LoreSection>>(item.display.lore.first()).clear()
        assertIs<MutableList<List<LoreSection>>>(item.display.lore).clear()
        assertIs<MutableList<LoreSection>>(publicItem.display.lore.first()).clear()
        assertIs<MutableList<List<LoreSection>>>(publicItem.display.lore).clear()
        assertIs<MutableMap<StatsType, Double>>(publicItem.stats).clear()
        assertIs<MutableMap<SkillType, Int>>(publicItem.skillLevelRequirements).clear()
        assertIs<MutableList<ItemStatMultiplier>>(publicItem.statMultipliers.getValue(StatsType.PHYSICS_DAMAGE)).clear()
        assertIs<MutableMap<StatsType, List<ItemStatMultiplier>>>(publicItem.statMultipliers).clear()
        val source = fixed.equipment.equipmentSources.getValue(StatsPart.MAIN_HAND).getValue("equipment")
        assertIs<MutableMap<StatsType, StatModifier<StatsPart>>>(source.stats).clear()
        val playerRecord = fixed.preparedInputs.first().player.equipmentSources.getValue(StatsPart.MAIN_HAND).getValue("equipment")
        assertIs<MutableMap<StatsType, StatModifier<StatsPart>>>(playerRecord.stats).clear()
        assertFailsWith<UnsupportedOperationException> { assertIs<MutableList<ActiveItemUpgrade>>(equipment.activeUpgrades).clear() }
        val held = assertIs<SimulatorMultipleHitInput.Ready>(snapshot.input)
        val stable = held.equipment.input.items.getValue(StatsPart.MAIN_HAND).baseData
        assertEquals(lore, stable.display.lore)
        assertEquals(10.0, stable.stats[StatsType.PHYSICS_DAMAGE])
        assertEquals(1, stable.skillLevelRequirements[SkillType.MINING])
        assertEquals(2, stable.statMultipliers.getValue(StatsType.PHYSICS_DAMAGE).size)
        val target = assertIs<StatsPartTarget.Parts<StatsPart>>(stable.statMultipliers.getValue(StatsType.PHYSICS_DAMAGE).first().target)
        assertEquals(setOf(StatsPart.MAIN_HAND, StatsPart.HELMET), target.parts)
        assertTrue(held.equipment.equipmentSources.getValue(StatsPart.MAIN_HAND).getValue("equipment").stats.isNotEmpty())
        assertTrue(held.preparedInputs.first().player.equipmentSources.getValue(StatsPart.MAIN_HAND).getValue("equipment").stats.isNotEmpty())
        assertEquals(0.0, assertIs<SimulatorComparisonResult.Expected>(compare(snapshot, capture(fixed, 2))).totals.calculated.difference)
    }

    @Test fun `サンプルの公開状態履歴traceとphaseTrace変更が基準へ漏れない`() {
        val cooldowns = mapOf(DamageItemCooldownKey(ItemSpecialEffectRole.SOURCE, "one", "x") to 1L,
            DamageItemCooldownKey(ItemSpecialEffectRole.TARGET, "two", "y") to 2L)
        val intervals = SameTickDamageIntervals(mapOf(DamageEffectIntervalKey("old-a", "x") to 1L, DamageEffectIntervalKey("old-b", "y") to 2L))
        val history = listOf(AttackRecord(UUID(0, 2), "old-a", 1, recordId = 1), AttackRecord(UUID(0, 2), "old-b", 2, recordId = 2))
        val initial = SameTickDamageState(state().source, state().target, intervals = intervals, itemCooldowns = cooldowns, sourceAttackRecords = history)
        val fixed = input(initial = initial)
        val request = SimulatorMultipleHitRequest(1, fixed, SimulatorMultipleHitMode.Sample(1))
        val raw = assertIs<SimulatorMultipleHitResult.Sample>(SimulatorMultipleHitSimulator.calculate(request))
        val snapshot = SimulatorComparisonSnapshot.capture(request, raw, version)
        val row = raw.result.rows.first()
        assertIs<MutableMap<DamageItemCooldownKey, Long>>(row.transition.before.itemCooldowns).clear()
        assertIs<MutableList<AttackRecord>>(row.transition.before.sourceAttackRecords).clear()
        assertIs<MutableList<DamageRollRecord>>(row.trace.records).clear()
        assertIs<MutableList<DamagePreparationPhaseTrace>>(assertNotNull(row.transition.simulation).preparation.phaseTrace).clear()
        assertIs<MutableList<SameTickDamageHitResult>>(raw.result.rows).clear()
        val exposed = assertIs<SimulatorMultipleHitResult.Sample>(snapshot.result)
        assertEquals(2, exposed.result.rows.size)
        assertEquals(2, exposed.result.rows.first().transition.before.itemCooldowns.size)
        assertEquals(2, exposed.result.rows.first().trace.records.size)
        assertIs<MutableMap<DamageItemCooldownKey, Long>>(exposed.result.finalState.itemCooldowns).clear()
        assertIs<MutableMap<DamageEffectIntervalKey, Long>>(exposed.result.rows.first().transition.before.intervals.effectTicks).clear()
        assertEquals(2, assertIs<SimulatorMultipleHitResult.Sample>(snapshot.result).result.finalState.itemCooldowns.size)
        assertEquals(0.0, assertIs<SimulatorComparisonResult.Sample>(compare(snapshot, capture(fixed, 2, SimulatorMultipleHitMode.Sample(1)))).totals.calculated.difference)
    }
}
