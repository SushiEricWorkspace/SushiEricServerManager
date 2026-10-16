package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.attack.snapshot.AttackSnapshot
import io.github.sushiericworkspace.combatcore.combat.attack.snapshot.EntityStatsSnapshot
import io.github.sushiericworkspace.combatcore.combat.attack.sequence.AttackRecord
import io.github.sushiericworkspace.combatcore.combat.damage.kind.effect.PlayerToMobMeleeEffects
import io.github.sushiericworkspace.combatcore.combat.damage.preparation.*
import io.github.sushiericworkspace.combatcore.combat.damage.preparation.rule.DamagePreparationRuleResult
import io.github.sushiericworkspace.combatcore.combat.damage.resource.*
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.DamageSimulator
import io.github.sushiericworkspace.combatcore.combat.guard.*
import io.github.sushiericworkspace.combatcore.entity.definition.MobAttackMeans
import io.github.sushiericworkspace.combatcore.item.custom.base.itemtype.arrow.ArrowType
import io.github.sushiericworkspace.combatcore.item.effect.*
import io.github.sushiericworkspace.combatcore.item.effect.cost.*
import io.github.sushiericworkspace.combatcore.stats.*
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemType
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.detail.ShieldData
import io.github.sushiericworkspace.sushiericdatamodel.stats.entity.EntityStatsType
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.*
import java.util.UUID
import kotlin.random.Random
import kotlin.test.*

class SingleHitSimulatorTest {
    private val melee = SimulatorAttack.PlayerMelee(PlayerToMobMeleeEffects.Direct(null, null))
    private fun base(chance: Double = 0.0) = StatsType.entries.associateWith { type ->
        when (type) {
            StatsType.PHYSICS_DAMAGE -> 95.0
            StatsType.STRENGTH -> 20.0
            StatsType.ABILITY_DAMAGE -> 60.0
            StatsType.AILMENT_DAMAGE -> 10.0
            StatsType.INTELLIGENCE -> 100.0
            StatsType.PHYSICS_CRIT_CHANCE, StatsType.ABILITY_CRIT_CHANCE -> chance
            StatsType.PHYSICS_CRIT_DAMAGE -> 50.0
            StatsType.ABILITY_CRIT_DAMAGE -> 60.0
            StatsType.DEFENCE -> 100.0
            else -> 0.0
        }
    }
    private fun player(chance: Double = 0.0, level: Int = 0) = SimulatorPlayerInput(
        SimulatorPosition(UUID(0, 1), "プレイヤー", "test"), base(chance), mapOf(SkillType.COMBAT to level),
        emptyMap(), mapOf(StatsPart.MAIN_HAND to ItemType.SWORD), DynamicStatsType.entries.associateWith { 100.0 })
    private fun mob() = SimulatorMobInput(SimulatorPosition(UUID(0, 2), "Mob", "test", x = 1.0),
        EntityStatsType.entries.associateWith { when (it) {
            EntityStatsType.DEFENCE -> 100.0
            EntityStatsType.ATTACK_DAMAGE -> 120.0
            else -> 0.0
        } }, mapOf(MobAttackMeans.PROJECTILE to 80.0, MobAttackMeans.MAGIC to 60.0), 500.0)
    private fun values(parry: MobParryInput? = null, guard: PlayerGuardInput? = null,
        effects: List<ResolvedPreparationEffect> = emptyList()) = DamagePreparationValues(
        100, DamageResourceSource.Player(20.0, 10.0), 1.0, 0.0, mobParry = parry, playerGuard = guard, specialEffects = effects)
    private fun expected(input: SimulatorInput) = assertIs<SingleHitResult.Expected>(
        SingleHitSimulator.calculate(SingleHitRequest(7, input, SingleHitMode.Expected))).result
    private fun resolved(effect: ItemPreparationEffect) = ResolvedPreparationEffect(
        "test", effect, ItemSpecialEffectRole.SOURCE, StatsPart.MAIN_HAND, 1, 0, 0, 0)

    @Test fun `COMBATの0と1と最大レベルを独立した基準値で集計する`() {
        for (level in listOf(0, 1, SkillType.MAX_LEVEL)) {
            val p = player(level = level)
            val stats = p.snapshot.requirePlayerDamageStats()
            assertEquals(20.0 + level * 2.0, stats.getTotalStat(StatsType.STRENGTH))
            assertEquals(95.0 * (1.0 + level * 0.02), stats.getTotalStat(StatsType.PHYSICS_DAMAGE), 1e-10)
            assertEquals(60.0 * (1.0 + level * 0.02), stats.getTotalStat(StatsType.ABILITY_DAMAGE), 1e-10)
            assertEquals(10.0 * (1.0 + level * 0.02), stats.getTotalStat(StatsType.AILMENT_DAMAGE), 1e-10)
            assertEquals(stats.getTotalStat(StatsType.STRENGTH), p.snapshot.requirePlayerStats()[StatsType.STRENGTH])
        }
    }

    @Test fun `装備とスキルの倍率は加算合成して一度だけ適用する`() {
        val source = StatsPartSource<StatsPart, StatsType>(stats = mapOf(StatsType.PHYSICS_DAMAGE to
            StatModifier(flat = 5.0, multipliers = listOf(StatMultiplier(1.5, StatsPartTarget.All)))))
        val p = SimulatorPlayerInput(SimulatorPosition(UUID(0, 1), "p", "test"), base(), mapOf(SkillType.COMBAT to 1),
            mapOf(StatsPart.MAIN_HAND to mapOf("equipment" to source)), emptyMap(), DynamicStatsType.entries.associateWith { 100.0 })
        assertEquals(152.0, p.snapshot.requirePlayerDamageStats().getTotalStat(StatsType.PHYSICS_DAMAGE), 1e-10)
        assertFailsWith<IllegalArgumentException> {
            SimulatorPlayerInput(SimulatorPosition(UUID(0, 1), "p", "test"), base(), emptyMap(),
                mapOf(StatsPart.SKILL to mapOf("duplicate" to source)), emptyMap(), DynamicStatsType.entries.associateWith { 100.0 })
        }
    }

    @Test fun `両方向の3種類を独立fixtureとCore直接呼出しで確認する`() {
        // Mod基準式: 近接と投射物=(5+95)*1.2/2、魔法=60*2/2。Mobは手段別攻撃力/2。
        val cases = listOf(melee to 60.0, SimulatorAttack.PlayerProjectile(null, null, null) to 60.0,
            SimulatorAttack.PlayerMagic() to 60.0, SimulatorAttack.MobMelee to 60.0,
            SimulatorAttack.MobProjectile to 40.0, SimulatorAttack.MobMagic to 30.0)
        for ((attack, literal) in cases) {
            val input = SimulatorInput(player(), mob(), attack, values())
            val actual = expected(input)
            assertEquals(literal, actual.meanDamage, 1e-10, attack.toString())
            val sample = assertIs<SingleHitResult.Sample>(SingleHitSimulator.calculate(
                SingleHitRequest(7, input, SingleHitMode.Sample(42)))).result
            assertEquals(literal, sample.finalDamage, 1e-10)
            assertEquals(DamageSimulator.expected(input.attack, input.preparation).meanDamage, actual.meanDamage)
        }
    }

    @Test fun `矢rankと距離とBREAKをCoreへ渡す`() {
        fun run(attack: SimulatorAttack, breaking: Boolean = false) = expected(SimulatorInput(player(), mob(), attack,
            DamagePreparationValues(100, DamageResourceSource.Player(20.0, 10.0), 1.0, 0.0, targetIsBreaking = breaking))).meanDamage
        assertEquals(66.0, run(SimulatorAttack.PlayerProjectile(ArrowType.NORMAL, 5, null)), 1e-10)
        assertEquals(53.4, run(SimulatorAttack.PlayerProjectile(null, null, 4.0)), 1e-10)
        assertEquals(120.0 / 1.85, run(SimulatorAttack.PlayerProjectile(ArrowType.PENETRATION, 10, null)), 1e-10)
        assertEquals(144.0, run(melee, true), 1e-10)
    }

    @Test fun `会心とパリィの厳密期待値と式とPreparationを区別する`() {
        val result = expected(SimulatorInput(player(25.0), mob(), melee, values(MobParryInput(0.4, 1.0, 20))))
        assertEquals(40.5, result.meanDamage, 1e-10)
        assertEquals(4, result.branches.size)
        val rejected = result.branches.filter { it.result.rejectionReason != null }
        assertEquals(2, rejected.size)
        assertTrue(rejected.all { it.result.resourceCosts.isEmpty() && it.result.preparation.preparationEffects.isNotEmpty() })
        val completed = assertIs<DamagePreparationPipelineResult.Completed>(result.branches.first().result.preparation)
        assertEquals(1.0, completed.output.calculationResult.criticalMultiplier)
        assertEquals(0.0, completed.output.calculationResult.rawDefence)
        assertTrue(completed.phaseTrace.isNotEmpty())
    }

    @Test fun `ガード拒否でも防御側消費とCDを保持する`() {
        val guard = PlayerGuardInput(ShieldData(blockCooldown = 20), true, 12.0, 120.0)
        val result = expected(SimulatorInput(player(), mob(), SimulatorAttack.MobMelee, values(guard = guard))).branches.single().result
        assertEquals(0.0, result.finalDamage)
        assertEquals(DamagePreparationFailureReason.GUARDED, result.rejectionReason)
        val effect = assertIs<DamagePreparationEffect.Guard>(result.preparation.preparationEffects.single())
        assertEquals(12.0, effect.outcome.consumedStamina)
        assertEquals(120L, effect.cooldownUntilTick)
        assertTrue(result.resourceCosts.isEmpty())
    }

    @Test fun `成功後costは実消費せず不足時は強化しない`() {
        val cost = ItemResourceCost.fixed(DamageResourceType.STAMINA, 10.0, ItemResourceRequirement.OPTIONAL)
        val attack = SimulatorAttack.PlayerMelee(PlayerToMobMeleeEffects.Direct("sword", cost))
        val p = player()
        val successful = expected(SimulatorInput(p, mob(), attack, values())).branches.single().result
        assertEquals(75.0, successful.finalDamage, 1e-10)
        assertEquals(10.0, successful.resourceCosts.single().amount)
        val poor = DamagePreparationValues(100, DamageResourceSource.Player(0.0, 0.0), 1.0, 0.0)
        assertEquals(60.0, expected(SimulatorInput(p, mob(), attack, poor)).meanDamage, 1e-10)
        assertEquals(100.0, p.snapshot.requireDynamicStats()[DynamicStatsType.STAMINA])
    }

    @Test fun `元Mapとsource変更はSnapshotと再実行に影響しない`() {
        val base = base().toMutableMap()
        val targetParts = mutableSetOf(StatsPart.MAIN_HAND)
        val multipliers = mutableListOf(StatMultiplier(1.5, StatsPartTarget.Parts(targetParts)))
        val equipment = mutableMapOf("item" to StatsPartSource(stats = mapOf(StatsType.PHYSICS_DAMAGE to StatModifier(5.0, multipliers))))
        val attacks = mutableMapOf(MobAttackMeans.MELEE to 120.0)
        val p = SimulatorPlayerInput(SimulatorPosition(UUID(0, 1), "p", "test"), base, emptyMap(),
            mapOf(StatsPart.MAIN_HAND to equipment), emptyMap(), DynamicStatsType.entries.associateWith { 100.0 })
        val m = SimulatorMobInput(SimulatorPosition(UUID(0, 2), "m", "test"), EntityStatsType.entries.associateWith { 0.0 }, attacks, 500.0)
        val input = SimulatorInput(p, m, melee, values())
        val before = expected(input).meanDamage
        base.clear(); multipliers.clear(); targetParts.clear(); equipment.clear(); attacks.clear()
        assertEquals(95.0, p.baseStats[StatsType.PHYSICS_DAMAGE])
        assertEquals(1.5, p.equipmentSources.getValue(StatsPart.MAIN_HAND).getValue("item").stats.getValue(StatsType.PHYSICS_DAMAGE).multipliers.single().value)
        assertEquals(before, expected(input).meanDamage)
        assertEquals(120.0, m.snapshot.attackDamages[MobAttackMeans.MELEE])
    }

    @Test fun `発射時と現在HPとtickと履歴を混同しない`() {
        val p = player()
        val m = mob()
        val launchDamages = mutableMapOf(MobAttackMeans.MELEE to 111.0)
        val launchTarget = m.snapshot.copy(attackDamages = launchDamages, entityStats = EntityStatsSnapshot.create { 0.0 })
        val launch = AttackSnapshot(p.snapshot, launchTarget, "launch-item", 777.0, 20, false)
        val history = mutableListOf(AttackRecord(m.snapshot.entityUuid, "old", 1))
        val input = SimulatorInput(p, m, SimulatorAttack.PlayerProjectile(null, null, 3.0),
            DamagePreparationValues(100, DamageResourceSource.Player(20.0, 10.0), 5.0, 6.0, sourceAttackRecords = history), launch)
        history.clear()
        launchDamages.clear()
        val result = expected(input).branches.single().result
        val output = assertIs<DamagePreparationPipelineResult.Completed>(result.preparation).output
        assertEquals(60.0, result.formula.finalDamage)
        assertEquals(111.0, output.attackSnapshot?.target?.attackDamages?.get(MobAttackMeans.MELEE))
        assertEquals(20L, output.attackSnapshot?.createdTick)
        assertEquals(777.0, output.attackSnapshot?.baseDamage)
        assertEquals(100L, input.preparation.currentTick)
        assertEquals(1, input.preparation.sourceAttackRecords.size)
        assertEquals(100.0, input.sourceHp)
        assertEquals(500.0, input.targetHp)
    }

    @Test fun `seedと要求世代を保持して再現する`() {
        val input = SimulatorInput(player(25.0), mob(), melee, values(MobParryInput(0.4, 0.5, 20)))
        fun run() = assertIs<SingleHitResult.Sample>(SingleHitSimulator.calculate(SingleHitRequest(18, input, SingleHitMode.Sample(42))))
        val first = run()
        val second = run()
        assertEquals(18L, first.generation)
        assertEquals(first.result.formula, second.result.formula)
        assertEquals(first.result.preparation.phaseTrace, second.result.preparation.phaseTrace)
        val random = Random(42)
        assertEquals(DamageSimulator.simulate(input.attack, input.preparation, { random.nextDouble(it) }).formula, first.result.formula)
    }

    @Test fun `期待値で対応外の乱数は数値を持たない`() {
        val effect = object : ItemPreparationEffect {
            override val id = "random"
            override val phase = DamagePreparationPhase.BEFORE_RESOURCE
            override fun apply(session: DamagePreparationSession, resolved: ResolvedPreparationEffect): DamagePreparationRuleResult {
                session.random.nextDouble(100.0)
                return DamagePreparationRuleResult.Continue
            }
        }
        val input = SimulatorInput(player(), mob(), melee, values(effects = listOf(resolved(effect))))
        assertIs<SingleHitResult.Unsupported>(SingleHitSimulator.calculate(SingleHitRequest(9, input, SingleHitMode.Expected)))
    }

    @Test fun `開始前と計算中の取消は数値を返さない`() {
        val early = SingleHitRequest(1, SimulatorInput(player(), mob(), melee, values()), SingleHitMode.Expected)
        early.cancel()
        assertEquals(SingleHitResult.Cancelled(1), SingleHitSimulator.calculate(early))
        lateinit var running: SingleHitRequest
        val effect = object : ItemPreparationEffect {
            override val id = "cancel"
            override val phase = DamagePreparationPhase.BEFORE_RESOURCE
            override fun apply(session: DamagePreparationSession, resolved: ResolvedPreparationEffect): DamagePreparationRuleResult {
                running.cancel()
                return DamagePreparationRuleResult.Continue
            }
        }
        running = SingleHitRequest(2, SimulatorInput(player(), mob(), melee, values(effects = listOf(resolved(effect)))), SingleHitMode.Expected)
        assertEquals(SingleHitResult.Cancelled(2), SingleHitSimulator.calculate(running))
    }
}
