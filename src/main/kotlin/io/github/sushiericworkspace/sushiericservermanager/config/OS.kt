package io.github.sushiericworkspace.sushiericservermanager.config

import java.nio.file.Path

/**
 * 実行環境のオペレーティングシステム（OS）を判別し、
 * OS固有のパスやサポート状況を管理するユーティリティオブジェクト。
 */
object OS {
    /** 小文字に変換されたシステムプロパティ os.name */
    val name: String = System.getProperty("os.name").lowercase()

    /** 実行環境がWindowsであるか */
    val isWindows: Boolean = name.contains("win")

    /** 実行環境がmacOSであるか */
    val isMac: Boolean = name.contains("mac")

    /**
     * 既存仕様と同じく、GUIアプリの正式対応OSはWindowsとmacOSです。
     * Linux用パスはテスト可能なフォールバックとして解決できますが、Launcherでは起動対象外です。
     */
    val dataConfigBase: String by lazy {
        AppDataDirectoryResolver.resolveBaseDirectory(
            osName = System.getProperty("os.name"),
            userHome = Path.of(System.getProperty("user.home")),
            environment = System.getenv()
        ).toString()
    }

    fun isSupportedOs(): Boolean = isSupportedOs(System.getProperty("os.name"))

    internal fun isSupportedOs(osName: String): Boolean {
        val normalized = osName.lowercase()
        return normalized.contains("win") || normalized.contains("mac")
    }
}
