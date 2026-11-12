package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.damage.simulation.*
import io.github.sushiericworkspace.combatcore.combat.damage.preparation.DamagePreparationValues
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** 単発サンプルのseedは同じ固定入力で再現します。厳密期待値はseedを使用しません。 */
sealed interface SingleHitMode {
    data class Sample(val seed: Long) : SingleHitMode
    data object Expected : SingleHitMode
}

/**
 * 呼出し側が採番する[generation]を結果へそのまま返します。古い世代の表示抑止は呼出し側の責務です。
 * [cancel]はスレッド間で利用可能です。開始前とCore呼出し後に確認し、取消済みなら数値を返しません。
 * Core内の単発評価は中断せず、評価中に取消された結果も破棄します。実行スレッドは生成しません。
 */
class SingleHitRequest(val generation: Long, val input: SimulatorInput, val mode: SingleHitMode) {
    private val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }
    internal fun isCancelled() = cancelled.get()
}

/**
 * formulaは会心・防御適用済み、preparationのphaseTraceはその後の処理記録です。
 * Core結果の成功後cost、拒否理由、拒否前の効果、追撃/連鎖等の予約要求を保持し、適用・予約しません。
 * [Unsupported]と[Cancelled]は数値を持たず、部分結果を期待値として扱いません。
 */
sealed interface SingleHitResult {
    val generation: Long
    data class Sample(override val generation: Long, val result: DamageSimulationResult) : SingleHitResult
    data class Expected(override val generation: Long, val result: DamageExpectationResult) : SingleHitResult
    data class Unsupported(override val generation: Long, val reason: String) : SingleHitResult
    data class Cancelled(override val generation: Long) : SingleHitResult
}

/** UI・保存先・実Entityの状態を変更せず、Coreの単発計算を同期的に呼び出します。 */
object SingleHitSimulator {
    fun calculate(request: SingleHitRequest): SingleHitResult {
        if (request.isCancelled()) return SingleHitResult.Cancelled(request.generation)
        val input = request.input
        val values = input.preparation
        // 結果が保持するItemBaseDataを要求の固定入力から分離します。
        val preparation = DamagePreparationValues(values.currentTick, values.sourceResource, values.targetX, values.targetZ,
            values.sourceItemData?.deepCopy(), values.sourceAttackRecords, values.targetIsBreaking,
            values.playerGuard, values.mobParry, values.offhandFollowUp, values.specialEffects)
        val result = try {
            when (val mode = request.mode) {
                is SingleHitMode.Sample -> {
                    val random = Random(mode.seed)
                    SingleHitResult.Sample(request.generation, DamageSimulator.simulate(
                        input.attack, preparation, { random.nextDouble(it) }, input.launchSnapshot))
                }
                SingleHitMode.Expected -> SingleHitResult.Expected(request.generation,
                    DamageSimulator.expected(input.attack, preparation, input.launchSnapshot))
            }
        } catch (exception: UnsupportedOperationException) {
            SingleHitResult.Unsupported(request.generation, exception.message ?: "対応外の乱数要求です。")
        }
        return if (request.isCancelled()) SingleHitResult.Cancelled(request.generation) else result
    }
}
