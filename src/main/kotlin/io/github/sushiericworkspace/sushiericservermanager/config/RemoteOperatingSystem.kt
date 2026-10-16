package io.github.sushiericworkspace.sushiericservermanager.config

/**
 * SSH接続先として正式に扱うOS。
 *
 * 接続元GUIアプリの対応OSとは別の概念です。接続元は既存仕様どおりWindowsとmacOSです。
 */
enum class RemoteOperatingSystem(
    val storedValue: String,
    val displayName: String,
    val family: Family
) {
    WINDOWS(
        storedValue = "WINDOWS",
        displayName = "Windows（OpenSSH Server）",
        family = Family.WINDOWS
    ),
    MACOS(
        storedValue = "MACOS",
        displayName = "macOS（リモートログイン）",
        family = Family.UNIX_LIKE
    ),
    UBUNTU_DESKTOP(
        storedValue = "UBUNTU_DESKTOP",
        displayName = "Ubuntu Desktop（OpenSSH Server）",
        family = Family.UNIX_LIKE
    ),
    UBUNTU_SERVER(
        storedValue = "UBUNTU_SERVER",
        displayName = "Ubuntu Server（OpenSSH Server）",
        family = Family.UNIX_LIKE
    );

    enum class Family {
        WINDOWS,
        UNIX_LIKE
    }

    companion object {
        /**
         * 追加前のprofiles.jsonはUbuntu系サーバーを想定していたため、未知値もUbuntu Serverへフォールバックします。
         */
        fun fromStoredValue(value: String?): RemoteOperatingSystem {
            return entries.firstOrNull { it.storedValue == value }
                ?: UBUNTU_SERVER
        }

        fun fromDisplayName(value: String?): RemoteOperatingSystem {
            return entries.firstOrNull { it.displayName == value }
                ?: UBUNTU_SERVER
        }
    }
}
