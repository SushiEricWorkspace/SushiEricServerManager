package io.github.sushiericworkspace.sushiericservermanager.monitor

import io.github.sushiericworkspace.sushiericservermanager.communication.management.JvmStatus
import io.github.sushiericworkspace.sushiericservermanager.communication.management.MinecraftServerStatus
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementClient
import io.github.sushiericworkspace.sushiericservermanager.config.AppSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 監視情報の保持と、未接続時の扱いを検証する。
 *
 * 購読の送受信は実サーバーでの確認に委ね、
 * ここでは接続していない状態の契約と取得元の区別を確認する。
 */
class ServerMonitorTest {

    @Test
    fun `初期状態は値を持たない`() {
        val monitor = ServerMonitor(ServerManagementClient())

        assertFalse(monitor.snapshot.hasValue)
        assertNull(monitor.snapshot.server)
        assertNull(monitor.snapshot.jvm)
        assertFalse(monitor.isSubscribed)
        assertNull(monitor.appliedIntervalTicks)
    }

    @Test
    fun `未接続では購読を開始しない`() {
        val monitor = ServerMonitor(ServerManagementClient())

        assertFalse(monitor.start())
        assertFalse(monitor.start(intervalTicks = 40))
        assertFalse(monitor.isSubscribed)
    }

    @Test
    fun `未接続でも購読停止を呼び出せる`() {
        val monitor = ServerMonitor(ServerManagementClient())

        assertFalse(monitor.stop())
        assertFalse(monitor.isSubscribed)
        assertNull(monitor.appliedIntervalTicks)
    }

    @Test
    fun `取得元はManagement APIである`() {
        assertEquals(
            ServerMonitorSource.MANAGEMENT_API,
            ServerMonitorSnapshot.EMPTY.source
        )
    }

    @Test
    fun `値がそろって初めて受信済みとみなす`() {
        assertFalse(ServerMonitorSnapshot.EMPTY.hasValue)

        assertFalse(
            ServerMonitorSnapshot(server = serverStatus(), jvm = null).hasValue
        )

        assertFalse(
            ServerMonitorSnapshot(server = null, jvm = jvmStatus()).hasValue
        )

        assertTrue(
            ServerMonitorSnapshot(
                server = serverStatus(),
                jvm = jvmStatus()
            ).hasValue
        )
    }

    @Test
    fun `更新間隔の既定値と範囲はサーバー側と一致する`() {
        assertEquals(20, AppSettings.DEFAULT_MONITOR_INTERVAL_TICKS)
        assertEquals(1..1200, AppSettings.MONITOR_INTERVAL_TICKS_RANGE)
    }

    @Test
    fun `範囲外の更新間隔は丸める`() {
        assertEquals(
            1,
            AppSettings(monitorIntervalTicks = 0).resolvedMonitorIntervalTicks()
        )

        assertEquals(
            1200,
            AppSettings(monitorIntervalTicks = 99999)
                .resolvedMonitorIntervalTicks()
        )

        assertEquals(
            40,
            AppSettings(monitorIntervalTicks = 40).resolvedMonitorIntervalTicks()
        )
    }

    private fun serverStatus(): MinecraftServerStatus =
        MinecraftServerStatus(
            ticksPerSecond = 20.0,
            millisPerTick = 1.5,
            currentTick = 100,
            uptimeSeconds = 5,
            onlinePlayerCount = 1,
            maxPlayerCount = 20
        )

    private fun jvmStatus(): JvmStatus =
        JvmStatus(
            heapUsedBytes = 1,
            heapCommittedBytes = 2,
            nonHeapUsedBytes = 3,
            totalMemoryBytes = 4,
            freeMemoryBytes = 5,
            threadCount = 6
        )
}
