package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.breaking.*
import io.github.sushiericworkspace.combatcore.combat.attack.snapshot.AttackSnapshot
import io.github.sushiericworkspace.combatcore.combat.damage.preparation.*
import io.github.sushiericworkspace.combatcore.combat.damage.preparation.rule.DamagePreparationRuleResult
import io.github.sushiericworkspace.combatcore.item.custom.base.itemtype.arrow.ArrowType
import io.github.sushiericworkspace.combatcore.combat.damage.kind.effect.PlayerToMobMeleeEffects
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.*
import io.github.sushiericworkspace.combatcore.combat.damage.state.DamageResources
import io.github.sushiericworkspace.combatcore.combat.guard.MobParryInput
import io.github.sushiericworkspace.combatcore.combat.random.DamageRollRecord
import io.github.sushiericworkspace.combatcore.item.effect.*
import io.github.sushiericworkspace.combatcore.item.upgrade.effect.DamageMultiplierEffect
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.enchantment.MeleeSushiEricEnchantment
import io.github.sushiericworkspace.combatcore.player.skill.effect.SkillLevelSnapshot
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.*
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.detail.*
import io.github.sushiericworkspace.sushiericdatamodel.stats.entity.EntityStatsType
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.*
import io.github.sushiericworkspace.combatcore.entity.definition.MobAttackMeans
import java.util.UUID
import kotlin.test.*

class SimulatorMultipleHitTest {
    private val position = SimulatorPosition(UUID(0, 1), "Player", "test")
    private val mob = SimulatorMobInput(SimulatorPosition(UUID(0, 2), "Mob", "test"),
        EntityStatsType.entries.associateWith { when (it) {
            EntityStatsType.ATTACK_DAMAGE -> 100.0
            EntityStatsType.MAX_HEALTH -> 1000.0
            else -> 0.0
        } }, emptyMap(), 1000.0)
    private val base = StatsType.entries.associateWith { when (it) {
        StatsType.PHYSICS_DAMAGE -> 95.0
        StatsType.PHYSICS_CRIT_DAMAGE -> 50.0
        else -> 0.0
    } }
    private val arrow = SimulatorAttack.PlayerProjectile(null, null, null)
    private fun rows(count: Int = 2) = List(count) { SimulatorHit(arrow, hitId = "hit-$it") }
    private fun equipment(items: Map<StatsPart, SimulatorEquippedItem> = emptyMap()) =
        assertIs<SimulatorEquipmentResult.Ready>(SimulatorEquipmentAssembler.resolve(SimulatorEquipmentInput(items), SkillLevelSnapshot.EMPTY))
    private fun state(health: Double = 1000.0, overHeal: Double = 0.0, stamina: Double = 0.0) =
        SameTickDamageState(SameTickCombatantState(1000.0, resources = DamageResources(stamina, 10.0)),
            SameTickCombatantState(health, overHeal))
    private fun input(hits: List<SimulatorHit> = rows(), initial: SameTickDamageState = state(),
        parry: MobParryInput? = null, stats: Map<StatsType, Double> = base,
        gear: SimulatorEquipmentResult.Ready = equipment(), effects: List<ResolvedPreparationEffect> = emptyList(),
        plan: SimulatorShotPlan? = null): SimulatorMultipleHitInput =
        gear.multipleHitInput(position, stats, mob, 100L, initial, hits, 1.0, 0.0, parry, effects, plan)
    private fun sample(input: SimulatorMultipleHitInput, seed: Long = 0) = assertIs<SimulatorMultipleHitResult.Sample>(
        SimulatorMultipleHitSimulator.calculate(SimulatorMultipleHitRequest(7, input, SimulatorMultipleHitMode.Sample(seed))))
    private fun expected(input: SimulatorMultipleHitInput) = assertIs<SimulatorMultipleHitResult.Expected>(
        SimulatorMultipleHitSimulator.calculate(SimulatorMultipleHitRequest(7, input, SimulatorMultipleHitMode.Expected)))
    private fun effect(role: ItemSpecialEffectRole = ItemSpecialEffectRole.SOURCE, until: Long = 0,
        condition: ItemSpecialEffectCondition = ItemSpecialEffectCondition.ALWAYS) = ResolvedPreparationEffect(
        "extra", DamageMultiplierEffect(0.1, condition = condition), role, StatsPart.MAIN_HAND, 1, 0, 0, 0, until)

    @Test fun `2矢100と完全パリィは状態を引き継ぎ0と100になる`() {
        val initial = state()
        val input = input(initial = initial, parry = MobParryInput(1.0, 1.0, 20))
        val result = sample(input).result
        assertEquals(listOf(0.0, 100.0), result.rows.map { it.transition.calculatedDamage })
        assertEquals(100.0, result.calculatedDamage)
        assertEquals(100.0, result.healthDamage)
        assertEquals(900.0, result.finalState.target.health)
        assertEquals(120L, result.finalState.target.parryUntilTick)
        assertSame(result.rows[0].transition.after, result.rows[1].transition.before)
        assertIs<DamageRollRecord.NotReached>(result.rows[1].trace.records.last())
        assertEquals(listOf(0.0, 100.0), expected(input).result.rows.map { it.meanCalculatedDamage })
        assertEquals(1000.0, initial.target.health)
    }

    @Test fun `周辺期待値は先行パリィのCD分岐を含み合計と一致する`() {
        val result = expected(input(parry = MobParryInput(0.5, 1.0, 20))).result
        assertEquals(listOf(50.0, 75.0), result.rows.map { it.meanCalculatedDamage })
        assertEquals(listOf(0.5, 0.75), result.rows.map { it.appliedProbability })
        assertEquals(125.0, result.meanCalculatedDamage)
        assertEquals(result.rows.sumOf { it.meanHealthDamage }, result.meanHealthDamage)
        assertEquals(1.0, result.branches.sumOf { it.probability })
        assertEquals(125.0, result.branches.sumOf { it.probability * it.healthDamage })
    }

    @Test fun `未命中と死亡後と過剰ダメージを区別する`() {
        val hits = rows(3).mapIndexed { i, hit -> hit.copy(matched = i != 0) }
        val result = sample(input(hits, state(40.0))).result
        assertEquals(listOf(SameTickDamageStatus.MISSED, SameTickDamageStatus.APPLIED, SameTickDamageStatus.TARGET_DEAD),
            result.rows.map { it.transition.status })
        assertEquals(100.0, result.calculatedDamage)
        assertEquals(40.0, result.healthDamage)
        assertEquals(40.0, expected(input(hits, state(40.0))).result.meanHealthDamage)
    }

    @Test fun `HPとOverHealと貫通を別々に保持する`() {
        for ((overHeal, pierce, healthDamage, absorption) in listOf(
            listOf(150.0, 0.0, 0.0, 100.0), listOf(40.0, 0.0, 60.0, 40.0), listOf(40.0, 1.0, 100.0, 0.0))) {
            val one = input(rows(1).map { it.copy(pierceOverHeal = pierce == 1.0) }, state(overHeal = overHeal))
            val result = sample(one).result
            assertEquals(healthDamage, result.healthDamage)
            assertEquals(absorption, result.overHealDamage)
            assertEquals(healthDamage + absorption, result.appliedDamage)
            val expected = expected(one).result
            assertEquals(healthDamage, expected.meanHealthDamage)
            assertEquals(absorption, expected.meanOverHealDamage)
        }
    }

    @Test fun `裸手の24STは最初の命中だけ消費し次行へ渡す`() {
        val melee = SimulatorAttack.PlayerMelee(PlayerToMobMeleeEffects.Direct("caller-id", null))
        val result = sample(input(rows().map { it.copy(attack = melee) }, state(stamina = 24.0))).result
        assertEquals(listOf(125.0, 100.0), result.rows.map { it.transition.calculatedDamage })
        assertEquals(0.0, result.finalState.source.resources.stamina)
        assertTrue(result.finalState.sourceAttackRecords.all { it.itemDataId == null })
        assertEquals(24.0, result.rows[0].transition.before.source.resources.stamina)
    }

    @Test fun `装備と追加効果を一度ずつ適用しroleと両CD経路を維持する`() {
        val gear = equipment(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(
            ItemBaseData(id = "sword", itemDetail = ItemDetail(content = SwordData())),
            mapOf(MeleeSushiEricEnchantment.SHARPNESS to 1))))
        val melee = rows(1).map { it.copy(attack = SimulatorAttack.PlayerMelee(PlayerToMobMeleeEffects.Direct(null, null))) }
        assertEquals(115.5, sample(input(melee, gear = gear, effects = listOf(effect()))).result.calculatedDamage, 1e-10)
        assertEquals(105.0, sample(input(melee, gear = gear, effects = listOf(effect(until = 101)))).result.calculatedDamage, 1e-10)
        assertEquals(105.0, sample(input(melee, gear = gear, effects = listOf(effect(ItemSpecialEffectRole.TARGET)))).result.calculatedDamage, 1e-10)
        val resolved = gear.preparationEffects(ItemSpecialEffectRole.SOURCE).single()
        val initial = state()
        val cooled = SameTickDamageState(initial.source, initial.target, itemCooldowns = mapOf(
            DamageItemCooldownKey(ItemSpecialEffectRole.SOURCE, resolved.providerKey, resolved.effect.id) to 101L))
        assertEquals(110.0, sample(input(melee, cooled, gear = gear, effects = listOf(effect()))).result.calculatedDamage, 1e-10)
    }

    @Test fun `行数と方向とID不整合は組立時に対応外とする`() {
        assertIs<SimulatorMultipleHitInput.Unsupported>(input(rows().map { it.copy(hitId = "duplicate") }))
        assertIs<SimulatorMultipleHitInput.Unsupported>(input(listOf(rows(1).single(), SimulatorHit(SimulatorAttack.MobProjectile))))
        assertIs<SimulatorMultipleHitInput.Unsupported>(input(plan = SimulatorShotPlan.bow(BowData(multi = 2))))
        val result = SimulatorMultipleHitSimulator.calculate(SimulatorMultipleHitRequest(3,
            input(plan = SimulatorShotPlan.bow(BowData(multi = 2))), SimulatorMultipleHitMode.Expected))
        assertIs<SimulatorMultipleHitResult.Unsupported>(result)
        assertEquals(3L, result.generation)
    }

    @Test fun `弓は発射5命中2消費1を別々に保持し能力値を分割しない`() {
        val plan = SimulatorShotPlan.bow(BowData(multi = 2))
        val assembled = assertIs<SimulatorMultipleHitInput.Ready>(input(rows(5).mapIndexed { i, h -> h.copy(matched = i < 2) }, plan = plan))
        assertEquals(5, plan.projectileCount)
        assertEquals(2, assembled.matchedCount)
        assertEquals(1, plan.arrowConsumption)
        assertEquals(200.0, sample(assembled).result.calculatedDamage)
        assertTrue(plan.resourceCosts.isEmpty())
        assertEquals(0, sample(assembled).result.rows.sumOf { it.transition.appliedEffects.resourceCosts.size })
    }

    @Test fun `標準クロスボウの消費は効率から切上げて射撃に一度記録する`() {
        for ((efficiency, consumption) in listOf(0.0 to 3, 0.5 to 2, 1.0 to 0)) {
            val plan = SimulatorShotPlan.standardCrossbow(CrossbowData(arrowCount = 3, arrowEfficiency = efficiency))
            assertEquals(consumption, plan.arrowConsumption)
            val result = sample(input(rows(3), plan = plan))
            assertSame(plan, result.shotPlan)
            assertEquals(300.0, result.result.calculatedDamage)
        }
        assertEquals("手入力の同tick3本", SimulatorShotPlan.manual(3, 2, "手入力の同tick3本").condition)
    }

    @Test fun `安定IDは再計算と複製と順序変更と追加削除で抽選値を保つ`() {
        val stats = base + (StatsType.PHYSICS_CRIT_CHANCE to 50.0)
        val original = rows(4)
        fun rolls(hits: List<SimulatorHit>) = sample(input(hits, stats = stats), 123).result.rows.associate {
            it.hitId to it.trace.records.filterIsInstance<DamageRollRecord.Reached>().first().unitValue }
        val values = rolls(original)
        assertEquals(values, rolls(original.reversed()))
        assertEquals(values, rolls(original.map { it.copy() }))
        assertEquals(values, rolls(original + SimulatorHit(arrow)).filterKeys { it in values })
        assertEquals(values.filterKeys { it != "hit-1" }, rolls(original.filter { it.hitId != "hit-1" }))
        assertEquals(values, rolls(original))
        val damage = sample(input(original, stats = stats), 123).result.rows.map { it.transition.calculatedDamage }
        assertTrue(100.0 in damage && 150.0 in damage)
    }

    @Test fun `単一命中の期待値は125でsampleとは別に集計する`() {
        val one = input(rows(1), stats = base + (StatsType.PHYSICS_CRIT_CHANCE to 50.0))
        assertEquals(125.0, expected(one).result.meanCalculatedDamage)
        assertTrue(sample(one).result.calculatedDamage in listOf(100.0, 150.0))
    }

    @Test fun `評価上限は数値結果を持たない型へ渡す`() {
        val many = input(rows(17), state(health = 1e9), stats = base + (StatsType.PHYSICS_CRIT_CHANCE to 50.0))
        val result = assertIs<SimulatorMultipleHitResult.LimitExceeded>(SimulatorMultipleHitSimulator.calculate(
            SimulatorMultipleHitRequest(5, many, SimulatorMultipleHitMode.Expected)))
        assertEquals(65536, result.evaluatedHits)
        assertEquals("厳密期待値の計算上限超過", result.reason)
    }

    @Test fun `取消は開始前とCore分岐中に数値を返さない`() {
        val before = SimulatorMultipleHitRequest(8, input(), SimulatorMultipleHitMode.Sample(0))
        before.cancel()
        assertEquals(SimulatorMultipleHitResult.Cancelled(8), SimulatorMultipleHitSimulator.calculate(before))
        lateinit var during: SimulatorMultipleHitRequest
        val effects = listOf(effect(condition = ItemSpecialEffectCondition { _, _ -> during.cancel(); true }))
        during = SimulatorMultipleHitRequest(9, input(effects = effects), SimulatorMultipleHitMode.Expected)
        assertEquals(SimulatorMultipleHitResult.Cancelled(9), SimulatorMultipleHitSimulator.calculate(during))
    }

    @Test fun `入力の行と能力値を外部変更しても固定結果を変更しない`() {
        val hits = rows().toMutableList()
        val stats = base.toMutableMap()
        val assembled = assertIs<SimulatorMultipleHitInput.Ready>(input(hits, stats = stats))
        hits.clear()
        stats[StatsType.PHYSICS_DAMAGE] = 999.0
        assertEquals(2, assembled.hits.size)
        assertEquals(95.0, assembled.baseStats[StatsType.PHYSICS_DAMAGE])
        assertEquals(200.0, sample(assembled).result.calculatedDamage)
        assertEquals(1000.0, assembled.initialState.target.health)
    }

    @Test fun `装備から盾とSTRを取り現在STとCDは遷移から取る`() {
        val shield = equipment(mapOf(StatsPart.OFF_HAND to SimulatorEquippedItem(ItemBaseData(
            id = "shield", itemDetail = ItemDetail(content = ShieldData(parryCooldown = 20))))))
        val initial = SameTickDamageState(SameTickCombatantState(1000.0),
            SameTickCombatantState(1000.0, resources = DamageResources(100.0, 10.0)))
        val assembled = input(rows().map { it.copy(attack = SimulatorAttack.MobProjectile) }, initial,
            stats = base + (StatsType.STRENGTH to 100.0), gear = shield)
        val result = sample(assembled).result
        assertEquals(listOf(0.0, 100.0), result.rows.map { it.transition.calculatedDamage })
        assertEquals(900.0, result.finalState.target.health)
        assertEquals(95.0, result.finalState.target.resources.stamina)
        assertEquals(120L, result.finalState.target.parryUntilTick)
        assertEquals(100.0, expected(assembled).result.meanHealthDamage)
    }

    @Test fun `各行の矢種rankと距離を保持し本数による割算をしない`() {
        val crossbow = equipment(mapOf(StatsPart.MAIN_HAND to SimulatorEquippedItem(ItemBaseData(
            id = "crossbow", itemDetail = ItemDetail(content = CrossbowData())))))
        val hits = listOf(SimulatorHit(SimulatorAttack.PlayerProjectile(ArrowType.NORMAL, 5, null), hitId = "rank5"),
            SimulatorHit(SimulatorAttack.PlayerProjectile(null, null, 4.0), hitId = "distance4"))
        val result = sample(input(hits, gear = crossbow)).result
        assertEquals(110.0, result.rows[0].transition.calculatedDamage, 1e-10)
        assertEquals(89.0, result.rows[1].transition.calculatedDamage, 1e-10)
        assertEquals(199.0, result.calculatedDamage, 1e-10)
    }

    @Test fun `BREAK有効状態をruntimeと独立に渡し致死時は有効解除する`() {
        val initial = state(40.0)
        val stale = SameTickDamageState(initial.source, initial.target,
            BreakState(100.0, BreakRuntime(90, 4)), targetBreakActive = false)
        val input = input(rows(2), stale)
        val result = sample(input).result
        assertEquals(100.0, result.calculatedDamage)
        assertEquals(40.0, result.healthDamage)
        assertFalse(result.finalState.targetBreakActive)
        assertEquals(100.0, result.finalState.targetBreak.value)
        assertEquals(BreakRuntime(90, 4), result.finalState.targetBreak.runtime)
        val branch = expected(input).result.branches.single()
        assertFalse(branch.finalState.targetBreakActive)
        assertEquals(BreakRuntime(90, 4), branch.finalState.targetBreak.runtime)
        val active = SameTickDamageState(initial.source, initial.target,
            BreakState(100.0, BreakRuntime(100, 0)), targetBreakActive = true)
        assertEquals(120.0, sample(input(rows(1), active)).result.calculatedDamage)
    }

    @Test fun `発射Snapshotを複製し現在HPや式の能力値と分離する`() {
        val prepared = assertIs<SimulatorMultipleHitInput.Ready>(input(rows(1))).preparedInputs.single()
        val damageMap = mutableMapOf<MobAttackMeans, Double>()
        val launch = AttackSnapshot(prepared.player.snapshot, mob.snapshot.copy(attackDamages = damageMap),
            "launch-item", 999.0, 10L, false)
        val assembled = assertIs<SimulatorMultipleHitInput.Ready>(input(
            rows(1).map { it.copy(launchSnapshot = launch) }, state(40.0)))
        damageMap[MobAttackMeans.MELEE] = 900.0
        assertTrue(assembled.hits.single().launchSnapshot!!.target.attackDamages.isEmpty())
        assertEquals(999.0, assembled.hits.single().launchSnapshot!!.baseDamage)
        assertEquals(100.0, sample(assembled).result.calculatedDamage)
        assertEquals(40.0, sample(assembled).result.healthDamage)
        assertIs<SimulatorMultipleHitInput.Unsupported>(input(rows(1).map { it.copy(
            launchSnapshot = launch.copy(target = launch.target.copy(entityUuid = UUID(0, 3)))) }))
    }

    @Test fun `後続状態に影響する未知効果と毒矢は全体対応外で数値を返さない`() {
        val unknown = object : ItemPreparationEffect {
            override val id = "unknown"
            override val phase = DamagePreparationPhase.BEFORE_COMPLETE
            override fun apply(session: DamagePreparationSession, resolved: ResolvedPreparationEffect) = DamagePreparationRuleResult.Continue
        }
        val effects = listOf(ResolvedPreparationEffect("unknown", unknown, ItemSpecialEffectRole.SOURCE,
            StatsPart.MAIN_HAND, 1, 0, 0, 0))
        for (assembled in listOf(input(effects = effects), input(rows(1).map {
            it.copy(attack = SimulatorAttack.PlayerProjectile(ArrowType.POISON, 1, null)) }))) {
            for (mode in listOf(SimulatorMultipleHitMode.Expected, SimulatorMultipleHitMode.Sample(0))) {
                val result = SimulatorMultipleHitSimulator.calculate(SimulatorMultipleHitRequest(1, assembled, mode))
                assertIs<SimulatorMultipleHitResult.Unsupported>(result)
                assertTrue(result.reason.isNotBlank())
            }
        }
    }

    @Test fun `有効な短剣の近接予約は対応外で非近接と技能未達は許容する`() {
        val dagger = ItemBaseData(id = "dagger", itemDetail = ItemDetail(content = ShortSwordData()))
        val gear = equipment(mapOf(StatsPart.OFF_HAND to SimulatorEquippedItem(dagger)))
        val melee = rows(1).map { it.copy(attack = SimulatorAttack.PlayerMelee(PlayerToMobMeleeEffects.Direct(null, null))) }
        val failure = assertIs<SimulatorMultipleHitInput.Unsupported>(input(melee, gear = gear))
        assertEquals("オフハンド短剣の追撃予約は未対応です。", failure.reason)
        assertEquals(200.0, sample(input(gear = gear)).result.calculatedDamage)
        val gated = equipment(mapOf(StatsPart.OFF_HAND to SimulatorEquippedItem(dagger.copy(
            skillLevelRequirements = mapOf(SkillType.COMBAT to 2)))))
        assertNull(gated.offhandFollowUp)
        assertEquals(100.0, sample(input(melee, gear = gated)).result.calculatedDamage)
    }

    @Test fun `初期stateと公開stateと返却stateのcollection変更は再計算へ漏れない`() {
        val cooldown1 = DamageItemCooldownKey(ItemSpecialEffectRole.SOURCE, "one", "one")
        val cooldown2 = DamageItemCooldownKey(ItemSpecialEffectRole.SOURCE, "two", "two")
        val interval1 = DamageEffectIntervalKey("one", "one")
        val interval2 = DamageEffectIntervalKey("two", "two")
        val records = listOf(io.github.sushiericworkspace.combatcore.combat.attack.sequence.AttackRecord(
            UUID(0, 2), "one", 1L, recordId = 1),
            io.github.sushiericworkspace.combatcore.combat.attack.sequence.AttackRecord(UUID(0, 2), "two", 2L, recordId = 2))
        val initial = SameTickDamageState(state().source, state().target,
            intervals = SameTickDamageIntervals(mapOf(interval1 to 1L, interval2 to 2L)),
            itemCooldowns = mapOf(cooldown1 to 1L, cooldown2 to 2L), sourceAttackRecords = records)
        val assembled = assertIs<SimulatorMultipleHitInput.Ready>(input(initial = initial))
        fun mutate(state: SameTickDamageState) {
            (state.itemCooldowns as MutableMap).clear()
            (state.sourceAttackRecords as MutableList).clear()
            (state.intervals.effectTicks as MutableMap).clear()
        }
        mutate(initial)
        mutate(assembled.initialState)
        mutate(assembled.coreInput().initialState)
        val first = sample(assembled).result
        mutate(first.rows.first().transition.before)
        mutate(first.finalState)
        val expected = expected(assembled).result
        mutate(expected.branches.single().finalState)
        assertEquals(2, assembled.initialState.itemCooldowns.size)
        assertEquals(2, assembled.initialState.sourceAttackRecords.size)
        assertEquals(2, assembled.initialState.intervals.effectTicks.size)
        val rerun = sample(assembled).result
        assertEquals(200.0, rerun.calculatedDamage)
        assertEquals(200.0, expected(assembled).result.meanCalculatedDamage)
        assertEquals(2, rerun.finalState.itemCooldowns.size)
        assertEquals(2, rerun.finalState.intervals.effectTicks.size)
        assertEquals(4, rerun.finalState.sourceAttackRecords.size)
    }
}
