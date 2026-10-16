package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import javafx.application.Platform
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 画面の実行状態。Finishedは要求と生の結果を保持し、詳細表示でも再計算しません。 */
internal sealed interface SimulatorRunState {
    data object Idle : SimulatorRunState
    data class Running(val request: SimulatorMultipleHitRequest) : SimulatorRunState
    data class Finished(val request: SimulatorMultipleHitRequest, val result: SimulatorMultipleHitResult) : SimulatorRunState
    data class Failed(val error: Throwable) : SimulatorRunState
}

/**
 * 一画面の要求世代と背景計算を管理します。start/cancel/disposeと通知はFXスレッドで扱います。
 * 入力の保存や接続操作は行わず、取消後・終了後・接続切替後の完了通知を破棄します。
 */
internal class SimulatorWorkspace(
    private val isSessionCurrent: () -> Boolean,
    private val onChanged: (SimulatorRunState) -> Unit,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task ->
        Thread(task, "simulator-calculation").apply { isDaemon = true }
    },
    private val dispatch: (() -> Unit) -> Unit = { Platform.runLater(it) },
    private val calculate: (SimulatorMultipleHitRequest) -> SimulatorMultipleHitResult = SimulatorMultipleHitSimulator::calculate
) {
    var generation: Long = 0L
        private set
    var state: SimulatorRunState = SimulatorRunState.Idle
        private set
    private var active: SimulatorMultipleHitRequest? = null
    private var disposed = false

    fun start(input: SimulatorMultipleHitInput, mode: SimulatorMultipleHitMode) {
        check(!disposed && isSessionCurrent()) { "終了済みの画面または接続では計算できません。" }
        active?.cancel()
        val request = SimulatorMultipleHitRequest(++generation, input, mode)
        active = request
        update(SimulatorRunState.Running(request))
        executor.execute {
            val completed = runCatching { calculate(request) }
            dispatch {
                if (!isSessionCurrent()) dispose()
                if (!disposed && active === request && request.generation == generation) {
                    active = null
                    completed.fold({ update(SimulatorRunState.Finished(request, it)) },
                        { update(SimulatorRunState.Failed(it)) })
                }
            }
        }
    }

    fun cancel() {
        val request = active ?: return
        request.cancel()
        active = null
        update(SimulatorRunState.Finished(request, SimulatorMultipleHitResult.Cancelled(request.generation)))
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        active?.cancel()
        active = null
        state = SimulatorRunState.Idle
        executor.shutdownNow()
    }

    private fun update(value: SimulatorRunState) {
        state = value
        onChanged(value)
    }
}

/** Coreが返した合計だけを表示し、未完了を0や途中値へ変換しません。 */
internal fun SimulatorMultipleHitResult.summary(): String = when (this) {
    is SimulatorMultipleHitResult.Expected -> "厳密期待値  算出: ${result.meanCalculatedDamage} / 純HP減少: ${result.meanHealthDamage} / OH吸収: ${result.meanOverHealDamage} / 適用量: ${result.meanAppliedDamage}"
    is SimulatorMultipleHitResult.Sample -> "サンプル seed=$seed  算出: ${result.calculatedDamage} / 純HP減少: ${result.healthDamage} / OH吸収: ${result.overHealDamage} / 適用量: ${result.appliedDamage}"
    is SimulatorMultipleHitResult.Cancelled -> "取消済み（ダメージ値なし）"
    is SimulatorMultipleHitResult.LimitExceeded -> "厳密計算上限超過: $reason / 評価回数: $evaluatedHits（ダメージ値なし）"
    is SimulatorMultipleHitResult.Unsupported -> "非対応: $reason${hitId?.let { " / hitId=$it" }.orEmpty()}（ダメージ値なし）"
}
