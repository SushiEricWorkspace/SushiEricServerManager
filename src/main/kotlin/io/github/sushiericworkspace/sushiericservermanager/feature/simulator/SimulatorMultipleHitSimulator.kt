package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.damage.simulation.*
import io.github.sushiericworkspace.combatcore.combat.random.StableDamageRandom
import java.util.concurrent.atomic.AtomicBoolean

/** 厳密期待値はseedを使用せず、サンプルのseedは命中IDごとの抽選へ渡します。 */
sealed interface SimulatorMultipleHitMode {
    data class Sample(val seed: Long) : SimulatorMultipleHitMode
    data object Expected : SimulatorMultipleHitMode
}

/** 実行スレッドは生成しません。generationの採番と古い世代の表示抑止は利用側の責務です。 */
class SimulatorMultipleHitRequest(val generation: Long, val input: SimulatorMultipleHitInput, val mode: SimulatorMultipleHitMode) {
    private val cancelled = AtomicBoolean(false)
    fun cancel() { cancelled.set(true) }
    internal fun isCancelled() = cancelled.get()
}

/** 未完了結果はダメージ値を持ちません。Coreの行・状態・抽選・内訳を省略せず保持します。 */
sealed interface SimulatorMultipleHitResult {
    val generation: Long
    data class Sample(override val generation: Long, val seed: Long, val shotPlan: SimulatorShotPlan?,
        val result: SameTickDamageResult.Completed) : SimulatorMultipleHitResult {
        val randomVersion: String = StableDamageRandom.VERSION
    }
    data class Expected(override val generation: Long, val shotPlan: SimulatorShotPlan?,
        val result: SameTickDamageExpectationResult.Completed) : SimulatorMultipleHitResult
    data class Cancelled(override val generation: Long) : SimulatorMultipleHitResult
    data class LimitExceeded(override val generation: Long, val evaluatedHits: Int, val reason: String) : SimulatorMultipleHitResult
    data class Unsupported(override val generation: Long, val reason: String, val hitId: String? = null) : SimulatorMultipleHitResult
}

/** UI・保存・実Entityへ触れず、状態付き実行と厳密期待値を同期的にCoreへ委譲します。 */
object SimulatorMultipleHitSimulator {
    /**
     * ExpectedはCoreの各評価前の取消へ接続し、Sampleは一括呼出しの前後で取消を確認します。
     * 発射時消費はmetadataとして一度保持し、初期状態からの再消費や命中ごとの加算はしません。
     * @throws IllegalStateException CoreでHP適用後の確定コスト消費が失敗した場合。
     */
    fun calculate(request: SimulatorMultipleHitRequest): SimulatorMultipleHitResult {
        if (request.isCancelled()) return SimulatorMultipleHitResult.Cancelled(request.generation)
        val input = when (val assembled = request.input) {
            is SimulatorMultipleHitInput.Unsupported -> return SimulatorMultipleHitResult.Unsupported(request.generation, assembled.reason)
            is SimulatorMultipleHitInput.Ready -> assembled
        }
        val result = when (val mode = request.mode) {
            is SimulatorMultipleHitMode.Sample -> when (val sample = SameTickDamageSimulator.simulate(input.coreInput(), mode.seed)) {
                is SameTickDamageResult.Completed -> SimulatorMultipleHitResult.Sample(request.generation, mode.seed, input.shotPlan, sample)
                is SameTickDamageResult.Unsupported -> SimulatorMultipleHitResult.Unsupported(request.generation, sample.reason, sample.hitId)
            }
            SimulatorMultipleHitMode.Expected -> when (val expected = SameTickDamageExpectation.calculate(input.coreInput(), request::isCancelled)) {
                is SameTickDamageExpectationResult.Completed -> SimulatorMultipleHitResult.Expected(request.generation, input.shotPlan, expected)
                is SameTickDamageExpectationResult.Cancelled -> SimulatorMultipleHitResult.Cancelled(request.generation)
                is SameTickDamageExpectationResult.LimitExceeded -> SimulatorMultipleHitResult.LimitExceeded(request.generation, expected.evaluatedHits, expected.reason)
                is SameTickDamageExpectationResult.Unsupported -> SimulatorMultipleHitResult.Unsupported(request.generation, expected.reason, expected.hitId)
            }
        }
        return if (request.isCancelled()) SimulatorMultipleHitResult.Cancelled(request.generation) else result
    }
}
