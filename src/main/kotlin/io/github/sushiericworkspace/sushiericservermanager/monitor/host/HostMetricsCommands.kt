package io.github.sushiericworkspace.sushiericservermanager.monitor.host

import io.github.sushiericworkspace.sushiericservermanager.communication.WindowsPowerShellCommand

/**
 * ホストOSの状態を取得するコマンドを組み立てます。
 *
 * 出力の形式はOSごとに異なるため、[HostMetricsParser]と対で使用します。
 * 解析しやすいよう、可能な範囲で`キー=値`の形へ整えて出力させます。
 */
internal object HostMetricsCommands {

    /** CPU使用率の標本を取る間隔です。1回目と2回目の差から使用率を求めます。 */
    private const val SAMPLE_INTERVAL_SECONDS = 1

    /**
     * 指定した区分向けのコマンド行を返します。
     */
    fun build(
        platform: HostMetricsPlatform
    ): String =
        when (platform) {
            HostMetricsPlatform.WINDOWS -> WindowsPowerShellCommand.build(WINDOWS_SCRIPT)
            HostMetricsPlatform.LINUX -> LINUX_COMMAND
            HostMetricsPlatform.MACOS -> MACOS_COMMAND
        }

    /**
     * Windowsでは取得値をそのまま出力できるため、キーと値の形で書き出します。
     *
     * CPU使用率は論理プロセッサの平均を使用します。
     */
    private val WINDOWS_SCRIPT =
        """
        ${'$'}ErrorActionPreference = 'SilentlyContinue'
        ${'$'}cpu = (Get-CimInstance Win32_Processor | Measure-Object -Property LoadPercentage -Average).Average
        ${'$'}os = Get-CimInstance Win32_OperatingSystem
        Write-Output "CPU_PERCENT=${'$'}cpu"
        Write-Output "MEMORY_TOTAL_KB=${'$'}(${'$'}os.TotalVisibleMemorySize)"
        Write-Output "MEMORY_FREE_KB=${'$'}(${'$'}os.FreePhysicalMemory)"
        """.trimIndent()

    /**
     * Linuxでは`/proc`から取得します。
     *
     * CPUは瞬間値を取れないため、[SAMPLE_INTERVAL_SECONDS]をあけて2回読み取ります。
     */
    private val LINUX_COMMAND =
        listOf(
            "awk '/^cpu /{print \"CPU_SAMPLE=\"\$0}' /proc/stat",
            "sleep $SAMPLE_INTERVAL_SECONDS",
            "awk '/^cpu /{print \"CPU_SAMPLE=\"\$0}' /proc/stat",
            "awk '/^MemTotal:|^MemAvailable:/{print \$1 \$2}' /proc/meminfo"
        ).joinToString("; ")

    /**
     * macOSでは`top`と`vm_stat`から取得します。
     *
     * `top`の1回目の標本は起動からの平均になるため、2回取得して後ろの行を使用します。
     */
    private val MACOS_COMMAND =
        listOf(
            "top -l 2 -n 0 -s $SAMPLE_INTERVAL_SECONDS | awk '/^CPU usage/{print \"CPU_LINE=\"\$0}'",
            "sysctl -n hw.memsize | awk '{print \"MEMORY_TOTAL_BYTES=\"\$0}'",
            "vm_stat"
        ).joinToString("; ")
}
