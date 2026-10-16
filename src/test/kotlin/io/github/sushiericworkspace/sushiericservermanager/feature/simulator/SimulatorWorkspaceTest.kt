package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*

class SimulatorWorkspaceTest {
    private val input = SimulatorMultipleHitInput.Unsupported("fixture")

    @Test fun `旧世代の遅い完了は新世代の表示を上書きせず計算は呼出元スレッドで動かない`() {
        val callbacks = LinkedBlockingQueue<() -> Unit>()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val caller = Thread.currentThread()
        val workerThreads = mutableListOf<Thread>()
        val states = mutableListOf<SimulatorRunState>()
        val requests = mutableListOf<SimulatorMultipleHitRequest>()
        val workspace = SimulatorWorkspace({ true }, states::add, Executors.newSingleThreadExecutor(), { callbacks.add(it) }) { request ->
            workerThreads += Thread.currentThread()
            requests += request
            if (request.generation == 1L) { started.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
            SimulatorMultipleHitSimulator.calculate(request)
        }
        try {
            workspace.start(input, SimulatorMultipleHitMode.Expected)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            workspace.start(input, SimulatorMultipleHitMode.Sample(42))
            release.countDown()
            assertNotNull(callbacks.poll(5, TimeUnit.SECONDS)).invoke()
            assertIs<SimulatorRunState.Running>(workspace.state)
            assertNotNull(callbacks.poll(5, TimeUnit.SECONDS)).invoke()
            val finished = assertIs<SimulatorRunState.Finished>(workspace.state)
            assertEquals(2L, finished.request.generation)
            assertIs<SimulatorMultipleHitResult.Unsupported>(finished.result)
            assertTrue(requests.first().isCancelled())
            assertTrue(workerThreads.all { it !== caller })
            assertEquals(3, states.size)
        } finally { release.countDown(); workspace.dispose() }
    }

    @Test fun `取消は値なしを即時通知して既に到着した計算完了を破棄する`() {
        val callbacks = LinkedBlockingQueue<() -> Unit>()
        val workspace = SimulatorWorkspace({ true }, {}, dispatch = { callbacks.add(it) })
        try {
            workspace.start(input, SimulatorMultipleHitMode.Expected)
            val completion = assertNotNull(callbacks.poll(5, TimeUnit.SECONDS))
            workspace.cancel()
            val cancelled = assertIs<SimulatorRunState.Finished>(workspace.state)
            assertIs<SimulatorMultipleHitResult.Cancelled>(cancelled.result)
            assertTrue(cancelled.request.isCancelled())
            completion()
            assertSame(cancelled, workspace.state)
        } finally { workspace.dispose() }
    }

    @Test fun `画面終了と接続切替の後に完了通知と参照を残さない`() {
        for (dispose in listOf(false, true)) {
            var connected = true
            val callbacks = LinkedBlockingQueue<() -> Unit>()
            val states = mutableListOf<SimulatorRunState>()
            val workspace = SimulatorWorkspace({ connected }, states::add, dispatch = { callbacks.add(it) })
            try {
                workspace.start(input, SimulatorMultipleHitMode.Expected)
                val completion = assertNotNull(callbacks.poll(5, TimeUnit.SECONDS))
                if (dispose) workspace.dispose() else connected = false
                completion()
                assertEquals(1, states.size)
                assertIs<SimulatorRunState.Idle>(workspace.state)
                assertFailsWith<IllegalStateException> { workspace.start(input, SimulatorMultipleHitMode.Expected) }
            } finally { workspace.dispose() }
        }
    }

    @Test fun `計算例外は完了値へ変換せず失敗として通知する`() {
        val callbacks = LinkedBlockingQueue<() -> Unit>()
        val error = IllegalStateException("fixture failure")
        val workspace = SimulatorWorkspace({ true }, {}, dispatch = { callbacks.add(it) }, calculate = { throw error })
        try {
            workspace.start(input, SimulatorMultipleHitMode.Expected)
            assertNotNull(callbacks.poll(5, TimeUnit.SECONDS)).invoke()
            assertSame(error, assertIs<SimulatorRunState.Failed>(workspace.state).error)
        } finally { workspace.dispose() }
    }

    @Test fun `失敗型をゼロに偽装しない合計表示`() {
        assertTrue(SimulatorMultipleHitResult.Unsupported(1, "非対応効果").summary().contains("非対応効果"))
        assertTrue(SimulatorMultipleHitResult.LimitExceeded(1, 65536, "上限").summary().contains("65536"))
        assertTrue(SimulatorMultipleHitResult.Cancelled(1).summary().contains("ダメージ値なし"))
    }
}
