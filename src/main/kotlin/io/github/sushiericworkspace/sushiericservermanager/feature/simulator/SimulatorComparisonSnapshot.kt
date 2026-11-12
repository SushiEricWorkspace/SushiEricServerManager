package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.attack.snapshot.AttackSnapshot
import io.github.sushiericworkspace.combatcore.combat.damage.preparation.*
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.*
import io.github.sushiericworkspace.combatcore.combat.random.DamageRollTrace

/** 比較呼出し側が明示するCore・DataModel・Manager計算仕様の固定識別子。ファイルを読みません。 */
data class SimulatorComparisonVersion(val combatCore: String, val dataModel: String, val calculation: String)

/**
 * 基準または現在値の評価記録です。固定Readyは再利用し、取消token・乱数器は保持しません。
 * Core結果のcollectionをcapture時と公開取得時に隔離します。コピーは戦闘計算を実行しません。
 */
class SimulatorComparisonSnapshot private constructor(
    val generation: Long,
    val input: SimulatorMultipleHitInput,
    val mode: SimulatorMultipleHitMode,
    val version: SimulatorComparisonVersion,
    result: SimulatorMultipleHitResult
) {
    private val fixedResult = result.comparisonCopy()
    val randomVersion: String? = (result as? SimulatorMultipleHitResult.Sample)?.randomVersion
    val result: SimulatorMultipleHitResult get() = fixedResult.comparisonCopy()

    companion object {
        /** 結果と要求の世代・完了モード・seedが不一致の場合はIllegalArgumentExceptionです。 */
        fun capture(request: SimulatorMultipleHitRequest, result: SimulatorMultipleHitResult,
            version: SimulatorComparisonVersion): SimulatorComparisonSnapshot {
            require(request.generation == result.generation) { "要求と結果の世代が一致しません。" }
            when (result) {
                is SimulatorMultipleHitResult.Sample -> require(request.mode == SimulatorMultipleHitMode.Sample(result.seed)) {
                    "サンプルのモードまたはseedが要求と一致しません。"
                }
                is SimulatorMultipleHitResult.Expected -> require(request.mode == SimulatorMultipleHitMode.Expected) {
                    "期待値のモードが要求と一致しません。"
                }
                else -> Unit
            }
            return SimulatorComparisonSnapshot(request.generation, request.input, request.mode, version, result)
        }
    }
}

private fun SimulatorMultipleHitResult.comparisonCopy(): SimulatorMultipleHitResult = when (this) {
    is SimulatorMultipleHitResult.Sample -> copy(result = SameTickDamageResult.Completed(result.rows.map { row ->
        row.copy(transition = row.transition.let { transition -> transition.copy(
            before = transition.before.comparisonCopy(), after = transition.after.comparisonCopy(),
            simulation = transition.simulation?.let { it.copy(preparation = it.preparation.comparisonCopy()) },
            appliedEffects = transition.appliedEffects.let { effects -> SameTickDamageAppliedEffects(
                effects.resourceCosts, effects.attackRecord, effects.swordCycle, effects.breakChange) }
        ) }, trace = DamageRollTrace(row.trace.seed, row.trace.hitId, row.trace.records))
    }, result.finalState.comparisonCopy()))
    is SimulatorMultipleHitResult.Expected -> copy(result = SameTickDamageExpectationResult.Completed(
        result.rows.map { row -> SameTickDamageRowExpectation(row.hitId, row.meanCalculatedDamage, row.meanHealthDamage,
            row.meanOverHealDamage, row.statusProbabilities, row.rejectionProbabilities, row.evaluatedHits) },
        result.branches.map { it.copy(finalState = it.finalState.comparisonCopy()) }, result.evaluatedHits))
    is SimulatorMultipleHitResult.Cancelled -> copy()
    is SimulatorMultipleHitResult.LimitExceeded -> copy()
    is SimulatorMultipleHitResult.Unsupported -> copy()
}

private fun SameTickDamageState.comparisonCopy() = SameTickDamageState(source, target, targetBreak,
    SameTickDamageIntervals(intervals.effectTicks, intervals.directTick, intervals.projectileTick),
    itemCooldowns, sourceAttackRecords, nextRecordId, targetBreakActive)

private fun AttackSnapshot.comparisonCopy() = copy(
    attacker = attacker?.let { it.copy(attackDamages = it.attackDamages.toMap()) },
    target = target.copy(attackDamages = target.attackDamages.toMap())
)

private fun DamagePreparationPipelineResult.comparisonCopy(): DamagePreparationPipelineResult = when (this) {
    is DamagePreparationPipelineResult.Completed -> DamagePreparationPipelineResult.Completed(output.let {
        DamagePreparationOutput(it.calculationResult, it.resourceCosts, it.attackSnapshot?.comparisonCopy(),
            it.knockback, it.preparationEffects, it.swordCycle, it.followUp?.let { follow ->
                follow.copy(snapshot = follow.snapshot.comparisonCopy()) }, it.phaseTrace, it.knockbackModifier)
    })
    is DamagePreparationPipelineResult.Rejected -> DamagePreparationPipelineResult.Rejected(reason, detail, phase,
        recordDamageInterval, preparationEffects, guardedFollowUp?.let { it.copy(snapshot = it.snapshot.comparisonCopy()) },
        phaseTrace, criticalType, knockbackModifier)
}
