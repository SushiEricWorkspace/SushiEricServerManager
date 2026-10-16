package io.github.sushiericworkspace.sushiericservermanager.monitor.host

/**
 * ホストOSの状態を取得するコマンドの出力を解析します。
 *
 * 解析できなかった項目は`null`とし、取得できていないことを表します。
 * 想定と異なる出力でも例外にせず、取得できた項目だけを返します。
 * OSやバージョンの違いで出力が変わっても、画面全体が止まらないようにするためです。
 */
internal object HostMetricsParser {

    private const val KILOBYTE = 1_024L

    /** 出力を解析します。 */
    fun parse(
        platform: HostMetricsPlatform,
        output: String
    ): HostMetrics =
        when (platform) {
            HostMetricsPlatform.WINDOWS -> parseWindows(output)
            HostMetricsPlatform.LINUX -> parseLinux(output)
            HostMetricsPlatform.MACOS -> parseMacOs(output)
        }

    /**
     * `キー=値`の形で出力されたWindowsの結果を解析します。
     *
     * メモリはキロバイト単位で得られるため、バイトへ換算します。
     */
    private fun parseWindows(output: String): HostMetrics {
        val values = keyValues(output)

        val totalKilobytes = values["MEMORY_TOTAL_KB"]?.toLongOrNull()
        val freeKilobytes = values["MEMORY_FREE_KB"]?.toLongOrNull()

        val used =
            if (totalKilobytes != null && freeKilobytes != null) {
                ((totalKilobytes - freeKilobytes) * KILOBYTE).coerceAtLeast(0)
            } else {
                null
            }

        return HostMetrics(
            cpuUsagePercent = values["CPU_PERCENT"]?.toDoubleOrNull()?.coerceIn(0.0, 100.0),
            memoryUsedBytes = used,
            memoryTotalBytes = totalKilobytes?.times(KILOBYTE)
        )
    }

    /**
     * `/proc`から取得したLinuxの結果を解析します。
     *
     * CPU使用率は2つの標本の差から求めます。
     * メモリは`MemAvailable`を空き容量として扱い、キャッシュを使用中に数えません。
     */
    private fun parseLinux(output: String): HostMetrics {
        val samples =
            output.lineSequence()
                .filter { it.startsWith("CPU_SAMPLE=") }
                .map { it.removePrefix("CPU_SAMPLE=") }
                .toList()

        val cpu =
            if (samples.size >= 2) {
                cpuUsageFromProcStat(samples[samples.size - 2], samples.last())
            } else {
                null
            }

        val totalKilobytes = linuxMemoryValue(output, "MemTotal")
        val availableKilobytes = linuxMemoryValue(output, "MemAvailable")

        val used =
            if (totalKilobytes != null && availableKilobytes != null) {
                ((totalKilobytes - availableKilobytes) * KILOBYTE).coerceAtLeast(0)
            } else {
                null
            }

        return HostMetrics(
            cpuUsagePercent = cpu,
            memoryUsedBytes = used,
            memoryTotalBytes = totalKilobytes?.times(KILOBYTE)
        )
    }

    /**
     * `top`と`vm_stat`から取得したmacOSの結果を解析します。
     *
     * CPU使用率は最後の標本の待機率から求めます。
     * 空き容量は空き、非アクティブ、投機的に確保されたページの合計として扱います。
     */
    private fun parseMacOs(output: String): HostMetrics {
        val cpu =
            output.lineSequence()
                .filter { it.startsWith("CPU_LINE=") }
                .lastOrNull()
                ?.let { line -> IDLE_PATTERN.find(line)?.groupValues?.get(1)?.toDoubleOrNull() }
                ?.let { idle -> (100.0 - idle).coerceIn(0.0, 100.0) }

        val total =
            output.lineSequence()
                .firstOrNull { it.startsWith("MEMORY_TOTAL_BYTES=") }
                ?.removePrefix("MEMORY_TOTAL_BYTES=")
                ?.trim()
                ?.toLongOrNull()

        val pageSize =
            PAGE_SIZE_PATTERN.find(output)?.groupValues?.get(1)?.toLongOrNull()

        val free = macOsPages(output, "free")
        val inactive = macOsPages(output, "inactive")
        val speculative = macOsPages(output, "speculative")

        val used =
            if (total != null && pageSize != null && free != null && inactive != null) {
                val available = (free + inactive + (speculative ?: 0L)) * pageSize
                (total - available).coerceIn(0L, total)
            } else {
                null
            }

        return HostMetrics(
            cpuUsagePercent = cpu,
            memoryUsedBytes = used,
            memoryTotalBytes = total
        )
    }

    /**
     * `/proc/stat`の2つの標本からCPU使用率を求めます。
     *
     * 待機時間には`idle`と`iowait`を含めます。
     */
    private fun cpuUsageFromProcStat(
        first: String,
        second: String
    ): Double? {
        val before = procStatValues(first) ?: return null
        val after = procStatValues(second) ?: return null

        if (before.size < IDLE_FIELD_COUNT || after.size < IDLE_FIELD_COUNT) {
            return null
        }

        val totalDelta = after.sum() - before.sum()
        if (totalDelta <= 0) {
            return null
        }

        val idleBefore = before[IDLE_INDEX] + before[IOWAIT_INDEX]
        val idleAfter = after[IDLE_INDEX] + after[IOWAIT_INDEX]
        val busyDelta = totalDelta - (idleAfter - idleBefore)

        return (busyDelta.toDouble() / totalDelta * 100).coerceIn(0.0, 100.0)
    }

    private fun procStatValues(line: String): List<Long>? =
        line.trim()
            .split(WHITESPACE)
            .drop(1)
            .map { it.toLongOrNull() ?: return null }
            .takeIf { it.isNotEmpty() }

    private fun linuxMemoryValue(
        output: String,
        key: String
    ): Long? =
        output.lineSequence()
            .firstOrNull { it.startsWith("$key:") }
            ?.removePrefix("$key:")
            ?.trim()
            ?.toLongOrNull()

    private fun macOsPages(
        output: String,
        name: String
    ): Long? =
        Regex("""Pages $name:\s+(\d+)\.""")
            .find(output)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()

    private fun keyValues(output: String): Map<String, String> =
        output.lineSequence()
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                }
            }
            .toMap()

    private val WHITESPACE = Regex("\\s+")
    private val IDLE_PATTERN = Regex("([0-9.]+)%\\s+idle")
    private val PAGE_SIZE_PATTERN = Regex("page size of (\\d+) bytes")

    /** `/proc/stat`で待機時間を取り出すために必要な最小の項目数です。 */
    private const val IDLE_FIELD_COUNT = 5
    private const val IDLE_INDEX = 3
    private const val IOWAIT_INDEX = 4
}
