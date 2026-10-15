package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.damage.simulation.SameTickDamageHitResult
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.SameTickDamageRowExpectation
import io.github.sushiericworkspace.combatcore.combat.random.DamageRollKind
import io.github.sushiericworkspace.combatcore.combat.random.DamageRollRecord
import io.github.sushiericworkspace.combatcore.combat.random.DamageRollTrace

/**
 * セッション内に基準を一つ保持します。保持/更新/解除は明示操作で、比較は基準を書き換えません。
 * ExpectedとSampleは同じモード同士だけを比較します。両方の基準が必要な呼出し側は別セッションで保持します。
 * スレッド生成・戦闘再計算・保存は行いません。セッションの操作は呼出し側で直列化してください。
 */
class SimulatorComparisonSession {
    var baseline: SimulatorComparisonSnapshot? = null
        private set

    fun setBaseline(snapshot: SimulatorComparisonSnapshot) { baseline = snapshot }
    fun clearBaseline() { baseline = null }

    /** latestGenerationは呼出し側の現在要求世代です。過去の基準世代は拒否しません。 */
    fun compare(current: SimulatorComparisonSnapshot, latestGeneration: Long): SimulatorComparisonResult {
        if (current.generation != latestGeneration) return SimulatorComparisonResult.Stale(current.generation, latestGeneration)
        val previous = baseline ?: return SimulatorComparisonResult.NoBaseline
        if (previous.version != current.version) return SimulatorComparisonResult.Incompatible("Core・DataModel・計算仕様の版が一致しません。")
        val sameMode = (previous.mode is SimulatorMultipleHitMode.Sample && current.mode is SimulatorMultipleHitMode.Sample) ||
            (previous.mode == SimulatorMultipleHitMode.Expected && current.mode == SimulatorMultipleHitMode.Expected)
        if (!sameMode) return SimulatorComparisonResult.Incompatible("期待値とサンプルは別に比較してください。")
        if (previous.randomVersion != null && current.randomVersion != null && previous.randomVersion != current.randomVersion) return SimulatorComparisonResult.Incompatible("乱数アルゴリズムの版が一致しません。")
        val before = previous.result
        val after = current.result
        return when {
            before is SimulatorMultipleHitResult.Expected && after is SimulatorMultipleHitResult.Expected ->
                SimulatorComparisonResult.Expected(previous, current, SimulatorDamageChanges(
                    SimulatorDamageChange(before.result.meanCalculatedDamage, after.result.meanCalculatedDamage),
                    SimulatorDamageChange(before.result.meanHealthDamage, after.result.meanHealthDamage),
                    SimulatorDamageChange(before.result.meanOverHealDamage, after.result.meanOverHealDamage),
                    SimulatorDamageChange(before.result.meanAppliedDamage, after.result.meanAppliedDamage)),
                    align(before.result.rows, after.result.rows, { it.hitId }, ::expectedChanges))
            before is SimulatorMultipleHitResult.Sample && after is SimulatorMultipleHitResult.Sample -> {
                val rows = align(before.result.rows, after.result.rows, { it.hitId }, ::sampleChanges)
                val draws = rows.filterIsInstance<SimulatorRowComparison.Matched<SameTickDamageHitResult>>()
                    .associate { it.hitId to compareDraws(it.baseline.trace, it.current.trace) }
                SimulatorComparisonResult.Sample(previous, current, SimulatorDamageChanges(
                    SimulatorDamageChange(before.result.calculatedDamage, after.result.calculatedDamage),
                    SimulatorDamageChange(before.result.healthDamage, after.result.healthDamage),
                    SimulatorDamageChange(before.result.overHealDamage, after.result.overHealDamage),
                    SimulatorDamageChange(before.result.appliedDamage, after.result.appliedDamage)), rows, draws)
            }
            else -> SimulatorComparisonResult.Incomplete(before, after)
        }
    }

    private fun expectedChanges(before: SameTickDamageRowExpectation, after: SameTickDamageRowExpectation) = SimulatorDamageChanges(
        SimulatorDamageChange(before.meanCalculatedDamage, after.meanCalculatedDamage),
        SimulatorDamageChange(before.meanHealthDamage, after.meanHealthDamage),
        SimulatorDamageChange(before.meanOverHealDamage, after.meanOverHealDamage),
        SimulatorDamageChange(before.meanAppliedDamage, after.meanAppliedDamage))

    private fun sampleChanges(before: SameTickDamageHitResult, after: SameTickDamageHitResult) = SimulatorDamageChanges(
        SimulatorDamageChange(before.transition.calculatedDamage, after.transition.calculatedDamage),
        SimulatorDamageChange(before.transition.healthDamage, after.transition.healthDamage),
        SimulatorDamageChange(before.transition.overHealDamage, after.transition.overHealDamage),
        SimulatorDamageChange(before.transition.appliedDamage, after.transition.appliedDamage))

    private fun <T> align(before: List<T>, after: List<T>, id: (T) -> String,
        change: (T, T) -> SimulatorDamageChanges): List<SimulatorRowComparison<T>> {
        val old = before.withIndex().associateBy { id(it.value) }
        val current = after.withIndex().associateBy { id(it.value) }
        return (current.keys + old.keys).map { hitId ->
            val left = old[hitId]
            val right = current[hitId]
            when {
                left != null && right != null -> SimulatorRowComparison.Matched(hitId, left.index, right.index,
                    left.value, right.value, change(left.value, right.value))
                right != null -> SimulatorRowComparison.Added(hitId, right.index, right.value)
                else -> SimulatorRowComparison.Removed(hitId, checkNotNull(left).index, left.value)
            }
        }
    }

    private fun compareDraws(before: DamageRollTrace, after: DamageRollTrace): List<SimulatorDrawComparison> =
        DamageRollKind.entries.flatMap { kind ->
            val left = before.records.filter { it.kind == kind }
            val right = after.records.filter { it.kind == kind }
            val occurrences = (left + right).filterIsInstance<DamageRollRecord.Reached>().map { it.occurrence }.distinct().sorted()
            if (occurrences.isEmpty()) listOf(SimulatorDrawComparison(kind, null, left.singleOrNull(), right.singleOrNull()))
            else occurrences.map { occurrence ->
                fun record(records: List<DamageRollRecord>): DamageRollRecord? = records.firstOrNull {
                    when (it) { is DamageRollRecord.Reached -> it.occurrence == occurrence; is DamageRollRecord.NotReached -> occurrence == 0 }
                }
                SimulatorDrawComparison(kind, occurrence, record(left), record(right))
            }
        }
}
