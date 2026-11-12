package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.damage.simulation.SameTickDamageHitResult
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.SameTickDamageRowExpectation
import io.github.sushiericworkspace.combatcore.combat.random.DamageRollKind
import io.github.sushiericworkspace.combatcore.combat.random.DamageRollRecord

/** 符号付きの値の差と増減率です。基準0では0→0も含めchangePercentはnullです。 */
data class SimulatorDamageChange(val baseline: Double, val current: Double) {
    val difference: Double = current - baseline
    val changePercent: Double? = if (baseline == 0.0) null else difference / baseline * 100.0
}

/** 算出値・純HP・OverHeal・両方込みの適用量を混同せず比較します。 */
data class SimulatorDamageChanges(val calculated: SimulatorDamageChange, val health: SimulatorDamageChange,
    val overHeal: SimulatorDamageChange, val applied: SimulatorDamageChange)

/** 追加・削除行へ架空の0行を補わず、共通行では元の順序と数値差を保持します。 */
sealed interface SimulatorRowComparison<out T> {
    val hitId: String
    data class Matched<T>(override val hitId: String, val baselineIndex: Int, val currentIndex: Int,
        val baseline: T, val current: T, val changes: SimulatorDamageChanges) : SimulatorRowComparison<T> {
        val moved: Boolean get() = baselineIndex != currentIndex
    }
    data class Added<T>(override val hitId: String, val currentIndex: Int, val current: T) : SimulatorRowComparison<T>
    data class Removed<T>(override val hitId: String, val baselineIndex: Int, val baseline: T) : SimulatorRowComparison<T>
}

/**
 * 同じ行・種別・出現番号の記録です。NotReachedには抽選値/確率/成立を補完しません。
 * occurrenceはReachedがない組ではnull、片側の追加出現に対応する記録がない場合はその側がnullです。
 */
data class SimulatorDrawComparison(val kind: DamageRollKind, val occurrence: Int?,
    val baseline: DamageRollRecord?, val current: DamageRollRecord?)

/** 未完了・世代違い・版/モード違いでは数値差を返しません。主指標ExpectedとSampleは別型です。 */
sealed interface SimulatorComparisonResult {
    data object NoBaseline : SimulatorComparisonResult
    data class Stale(val generation: Long, val latestGeneration: Long) : SimulatorComparisonResult
    data class Incompatible(val reason: String) : SimulatorComparisonResult
    data class Incomplete(val baseline: SimulatorMultipleHitResult, val current: SimulatorMultipleHitResult) : SimulatorComparisonResult
    data class Expected(val baseline: SimulatorComparisonSnapshot, val current: SimulatorComparisonSnapshot,
        val totals: SimulatorDamageChanges,
        val rows: List<SimulatorRowComparison<SameTickDamageRowExpectation>>) : SimulatorComparisonResult
    data class Sample(val baseline: SimulatorComparisonSnapshot, val current: SimulatorComparisonSnapshot,
        val totals: SimulatorDamageChanges,
        val rows: List<SimulatorRowComparison<SameTickDamageHitResult>>,
        val draws: Map<String, List<SimulatorDrawComparison>>) : SimulatorComparisonResult
}
