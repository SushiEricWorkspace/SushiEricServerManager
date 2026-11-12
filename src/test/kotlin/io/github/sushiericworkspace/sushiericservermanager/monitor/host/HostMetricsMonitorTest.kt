package io.github.sushiericworkspace.sushiericservermanager.monitor.host

import io.github.sushiericworkspace.sushiericservermanager.communication.SshCommandResult
import io.github.sushiericworkspace.sushiericservermanager.communication.SshCommandRunner
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import net.schmizz.sshj.SSHClient
import org.mockito.Mockito
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HostMetricsMonitorTest {
    @Test fun `停止後に完了した取得は未取得を上書きしない`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val monitor = monitor {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            result(1024)
        }
        val values = CopyOnWriteArrayList<HostMetrics>()
        monitor.addListener { if (it.hasValue) values += it }
        try {
            monitor.start(3600)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            monitor.stop()
            release.countDown()
            drain(monitor)
            assertFalse(monitor.isRunning)
            assertEquals(HostMetrics.EMPTY, monitor.metrics)
            assertTrue(values.isEmpty())
        } finally { release.countDown(); monitor.stop() }
    }

    @Test fun `再開始後の旧取得を拒否し開始の重複で取得を増やさない`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val current = CountDownLatch(1)
        val calls = AtomicInteger()
        val monitor = monitor {
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                result(1024)
            } else result(2048)
        }
        val values = CopyOnWriteArrayList<HostMetrics>()
        monitor.addListener { if (it.hasValue) { values += it; current.countDown() } }
        try {
            monitor.start(3600); monitor.start(3600)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            monitor.stop(); monitor.start(3600)
            release.countDown()
            assertTrue(current.await(5, TimeUnit.SECONDS))
            drain(monitor)
            assertEquals(2, calls.get())
            assertEquals(listOf(2048L * 1024), values.map { it.memoryTotalBytes })
        } finally { release.countDown(); monitor.stop() }
    }

    @Test fun `通知listenerの実行中も別threadから停止できる`() {
        val returned = CountDownLatch(1)
        val timedOut = AtomicBoolean()
        val monitor = monitor { result(1024) }
        monitor.addListener { metrics ->
            if (metrics.hasValue) {
                val stopped = CountDownLatch(1)
                thread { monitor.stop(); stopped.countDown() }
                timedOut.set(!stopped.await(5, TimeUnit.SECONDS))
                returned.countDown()
            }
        }
        try {
            monitor.start(3600)
            assertTrue(returned.await(6, TimeUnit.SECONDS))
            assertFalse(timedOut.get())
            assertEquals(HostMetrics.EMPTY, monitor.metrics)
        } finally { monitor.stop() }
    }

    private fun monitor(collect: () -> SshCommandResult): HostMetricsMonitor {
        val manager = Mockito.mock(SshManager::class.java)
        val client = Mockito.mock(SSHClient::class.java)
        val runner = Mockito.mock(SshCommandRunner::class.java)
        Mockito.`when`(manager.sshClient).thenReturn(client)
        Mockito.`when`(client.isConnected).thenReturn(true)
        Mockito.`when`(runner.run(client, HostMetricsCommands.build(HostMetricsPlatform.LINUX))).thenAnswer { collect() }
        return HostMetricsMonitor(manager, runner)
    }

    private fun result(total: Int) = SshCommandResult(0, "MemTotal:$total\nMemAvailable:0", "")

    private fun drain(monitor: HostMetricsMonitor) {
        val field = HostMetricsMonitor::class.java.getDeclaredField("scheduler").apply { isAccessible = true }
        (field.get(monitor) as ScheduledExecutorService).submit {}.get(5, TimeUnit.SECONDS)
    }
}
