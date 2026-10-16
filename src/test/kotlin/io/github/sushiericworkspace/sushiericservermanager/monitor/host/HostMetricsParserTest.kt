package io.github.sushiericworkspace.sushiericservermanager.monitor.host

import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ホストOSの状態を取得するコマンドの出力解析を検証する。
 */
class HostMetricsParserTest {

    @Test
    fun `接続先OSごとの取得区分を返す`() {
        assertEquals(
            HostMetricsPlatform.WINDOWS,
            HostMetricsPlatform.of(RemoteOperatingSystem.WINDOWS)
        )
        assertEquals(
            HostMetricsPlatform.MACOS,
            HostMetricsPlatform.of(RemoteOperatingSystem.MACOS)
        )
        assertEquals(
            HostMetricsPlatform.LINUX,
            HostMetricsPlatform.of(RemoteOperatingSystem.UBUNTU_SERVER)
        )
        assertEquals(
            HostMetricsPlatform.LINUX,
            HostMetricsPlatform.of(RemoteOperatingSystem.UBUNTU_DESKTOP)
        )
    }

    @Test
    fun `Windowsの出力を解析する`() {
        val metrics =
            HostMetricsParser.parse(HostMetricsPlatform.WINDOWS, WINDOWS_OUTPUT)

        assertEquals(12.0, metrics.cpuUsagePercent)
        assertEquals(16_613_464L * 1024, metrics.memoryTotalBytes)
        assertEquals((16_613_464L - 8_306_732L) * 1024, metrics.memoryUsedBytes)
    }

    @Test
    fun `Linuxの出力から2標本の差でCPU使用率を求める`() {
        val metrics =
            HostMetricsParser.parse(HostMetricsPlatform.LINUX, LINUX_OUTPUT)

        /*
         * 標本の差は total=200、idle+iowait=150 のため、使用率は25%となる。
         */
        val cpu = metrics.cpuUsagePercent
        assertTrue(cpu != null && abs(cpu - 25.0) < 0.001, "CPU使用率が想定と異なります: $cpu")

        assertEquals(16_384_000L * 1024, metrics.memoryTotalBytes)
        assertEquals((16_384_000L - 8_192_000L) * 1024, metrics.memoryUsedBytes)
    }

    @Test
    fun `macOSの出力を解析する`() {
        val metrics =
            HostMetricsParser.parse(HostMetricsPlatform.MACOS, MACOS_OUTPUT)

        val cpu = metrics.cpuUsagePercent
        assertTrue(cpu != null && abs(cpu - 15.37) < 0.001, "CPU使用率が想定と異なります: $cpu")

        assertEquals(17_179_869_184L, metrics.memoryTotalBytes)

        /*
         * 空きとして扱うページは free + inactive + speculative の合計であり、
         * 使用中は総量からその分を引いた値になる。
         */
        val available = (100_000L + 50_000L + 10_000L) * 16_384L
        assertEquals(17_179_869_184L - available, metrics.memoryUsedBytes)
    }

    @Test
    fun `想定と異なる出力では取得できた項目だけ返す`() {
        val metrics =
            HostMetricsParser.parse(
                HostMetricsPlatform.WINDOWS,
                "CPU_PERCENT=45\nMEMORY_TOTAL_KB=あ\n"
            )

        assertEquals(45.0, metrics.cpuUsagePercent)
        assertNull(metrics.memoryTotalBytes)
        assertNull(metrics.memoryUsedBytes)
    }

    @Test
    fun `標本が1つだけの場合はCPU使用率を求めない`() {
        val metrics =
            HostMetricsParser.parse(
                HostMetricsPlatform.LINUX,
                "CPU_SAMPLE=cpu  100 0 100 700 100 0 0 0 0 0\nMemTotal:1000\nMemAvailable:400\n"
            )

        assertNull(metrics.cpuUsagePercent)
        assertEquals(1000L * 1024, metrics.memoryTotalBytes)
    }

    @Test
    fun `空の出力では何も取得しない`() {
        HostMetricsPlatform.entries.forEach { platform ->
            val metrics = HostMetricsParser.parse(platform, "")

            assertEquals(HostMetrics.EMPTY, metrics, "$platform の解析結果が空ではありません")
            assertTrue(!metrics.hasValue)
        }
    }

    @Test
    fun `区分ごとのコマンドを組み立てられる`() {
        HostMetricsPlatform.entries.forEach { platform ->
            assertTrue(
                HostMetricsCommands.build(platform).isNotBlank(),
                "$platform のコマンドが空です"
            )
        }
    }

    private companion object {
        val WINDOWS_OUTPUT =
            """
            CPU_PERCENT=12
            MEMORY_TOTAL_KB=16613464
            MEMORY_FREE_KB=8306732
            """.trimIndent()

        val LINUX_OUTPUT =
            """
            CPU_SAMPLE=cpu  1000 0 500 8000 500 0 0 0 0 0
            CPU_SAMPLE=cpu  1050 0 500 8100 550 0 0 0 0 0
            MemTotal:16384000
            MemAvailable:8192000
            """.trimIndent()

        val MACOS_OUTPUT =
            """
            CPU_LINE=CPU usage: 5.12% user, 10.24% sys, 84.63% idle
            MEMORY_TOTAL_BYTES=17179869184
            Mach Virtual Memory Statistics: (page size of 16384 bytes)
            Pages free:                              100000.
            Pages active:                            234567.
            Pages inactive:                           50000.
            Pages speculative:                        10000.
            Pages wired down:                         99999.
            """.trimIndent()
    }
}
