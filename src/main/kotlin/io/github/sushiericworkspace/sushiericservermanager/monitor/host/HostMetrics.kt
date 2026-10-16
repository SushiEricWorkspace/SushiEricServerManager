package io.github.sushiericworkspace.sushiericservermanager.monitor.host

import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem

/**
 * SSH経由で取得したホストOSの状態です。
 *
 * 取得できなかった項目は`null`とし、値が0であることと区別します。
 * 画面では未取得であることを利用者へ示します。
 *
 * @property cpuUsagePercent CPU使用率。0.0から100.0の範囲。
 * @property memoryUsedBytes 使用中の物理メモリ。
 * @property memoryTotalBytes 物理メモリの総量。
 */
data class HostMetrics(
    val cpuUsagePercent: Double? = null,
    val memoryUsedBytes: Long? = null,
    val memoryTotalBytes: Long? = null
) {
    /** いずれかの値を取得できているかを返します。 */
    val hasValue: Boolean
        get() = cpuUsagePercent != null || memoryTotalBytes != null

    companion object {
        /** 何も取得できていない状態です。 */
        val EMPTY = HostMetrics()
    }
}

/**
 * ホストOSの状態を取得する方法の区分です。
 *
 * [RemoteOperatingSystem.Family]はWindowsとUnix系の2種類ですが、
 * LinuxとmacOSでは取得コマンドが異なるため、ここでは別の区分として扱います。
 */
internal enum class HostMetricsPlatform {
    WINDOWS,
    LINUX,
    MACOS;

    companion object {

        /** 接続先OSに対応する区分を返します。 */
        fun of(
            operatingSystem: RemoteOperatingSystem
        ): HostMetricsPlatform =
            when (operatingSystem) {
                RemoteOperatingSystem.WINDOWS -> WINDOWS
                RemoteOperatingSystem.MACOS -> MACOS
                RemoteOperatingSystem.UBUNTU_DESKTOP,
                RemoteOperatingSystem.UBUNTU_SERVER -> LINUX
            }
    }
}
