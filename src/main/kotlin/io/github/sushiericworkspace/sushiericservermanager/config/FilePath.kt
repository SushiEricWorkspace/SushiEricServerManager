package io.github.sushiericworkspace.sushiericservermanager.config

import java.io.File

/**
 * アプリケーションで使用する設定ファイルとディレクトリのパスを一元管理する列挙型。
 *
 * 既存の保存先と列挙値は維持し、SSH用の管理ディレクトリとknown_hostsだけを追加します。
 */
enum class FilePath {
    SERVER_PROFILES,
    SERVER_CONTROL_COMMANDS,
    SETTINGS,
    AUTOSAVE_DIR,
    OFFLINE_DIR,
    UPDATES_DIR,
    UPDATE_RESULT,
    LOCK,
    SSH_DIR,
    KNOWN_HOSTS;

    val path: String
        get() = when (this) {
            SERVER_PROFILES -> buildPath("profiles.json")
            SERVER_CONTROL_COMMANDS -> buildPath("server-control.json")
            SETTINGS -> buildPath("config.json")
            AUTOSAVE_DIR -> buildPath("autosave")
            OFFLINE_DIR -> buildPath("offline")
            UPDATES_DIR -> buildPath("updates")
            UPDATE_RESULT -> buildPath("update-result.json")
            LOCK -> buildPath("lock")
            SSH_DIR -> buildPath("ssh")
            KNOWN_HOSTS -> buildPath("ssh${File.separator}known_hosts")
        }

    private fun buildPath(subPath: String): String {
        return "${baseDirectory().path}${File.separator}$subPath"
    }

    fun toFile(): File = File(path)

    companion object {
        /** 設定ディレクトリ名。製品名と一致させる。 */
        const val DIRECTORY_NAME: String = "SushiEricServerManager"

        private var migrated = false

        /** 設定とデータを保存するディレクトリを返す。 */
        fun dataDirectory(): File = baseDirectory()

        /**
         * 設定ディレクトリを返す。
         *
         * 初回の呼び出しでだけ、旧製品名のディレクトリからの移行を試みる。
         */
        private fun baseDirectory(): File {
            val base = File(OS.dataConfigBase, DIRECTORY_NAME)

            if (!migrated) {
                migrated = true

                ConfigDirectoryMigration.migrateIfNeeded(
                    legacyDirectory =
                        File(
                            OS.dataConfigBase,
                            ConfigDirectoryMigration.LEGACY_DIRECTORY_NAME
                        ),
                    currentDirectory = base
                )
            }

            return base
        }
    }
}
