package io.github.sushiericworkspace.sushiericservermanager.config

import java.io.File

/**
 * 設定ディレクトリの移行で取り残された生成鍵のパスを修復する。
 *
 * `ConfigDirectoryMigration`は鍵ファイルを新ディレクトリへコピーするが、
 * `profiles.json`へ絶対パスで保存された`key`は書き換えない。
 * そのため移行後も旧ディレクトリを指したままになり、旧ディレクトリを削除すると
 * 生成鍵を使うプロファイルで接続できなくなる。
 *
 * 移行時ではなく読み込み時に修復するのは、移行が既に済んでいる環境でも
 * 旧パスが残っているためである。移行時だけの処理では対象にできない。
 */
object GeneratedKeyPathRepair {

    /**
     * 旧ディレクトリを指す生成鍵のパスを新ディレクトリへ向け直す。
     *
     * 対象は次をすべて満たすプロファイルに限る。
     *
     * - `generatedKey`が`true`である
     * - `key`が旧設定ディレクトリ配下を指している
     *
     * 利用者が用意した鍵は設定ディレクトリの外にあるため対象にならない。
     * 生成鍵であっても設定ディレクトリ外を指す場合は書き換えない。
     *
     * @param config 修復対象の設定。
     * @param legacyDirectory 旧設定ディレクトリ。
     * @param currentDirectory 新設定ディレクトリ。
     * @return 修復後の設定。対象が無い場合は[config]をそのまま返す。
     */
    fun repair(
        config: ServerConfig,
        legacyDirectory: File,
        currentDirectory: File
    ): ServerConfig {
        val legacyPrefix =
            legacyDirectory.path + File.separator

        var repaired = false

        val profiles =
            config.list.map { profile ->
                val relative =
                    relativeKeyPath(profile, legacyPrefix)
                        ?: return@map profile

                repaired = true

                profile.copy(
                    key = File(currentDirectory, relative).path
                )
            }

        return if (repaired) {
            config.copy(list = profiles)
        } else {
            config
        }
    }

    /**
     * 旧ディレクトリ配下からの相対パスを返す。
     *
     * 対象外の場合は`null`を返す。
     */
    private fun relativeKeyPath(
        profile: ServerProfile,
        legacyPrefix: String
    ): String? {
        if (!profile.generatedKey) {
            return null
        }

        /*
         * Windowsではパス区切りと大文字小文字の差異が生じ得るため、
         * 比較用に正規化した文字列で判定する。
         */
        val normalizedKey =
            normalize(profile.key)

        val normalizedPrefix =
            normalize(legacyPrefix)

        if (!normalizedKey.startsWith(normalizedPrefix)) {
            return null
        }

        return profile.key.substring(legacyPrefix.length)
            .takeIf { it.isNotEmpty() }
    }

    private fun normalize(
        path: String
    ): String =
        path.replace('/', File.separatorChar)
            .lowercase()
}
