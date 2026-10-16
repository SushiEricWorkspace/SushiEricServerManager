package io.github.sushiericworkspace.sushiericservermanager.config

import org.slf4j.LoggerFactory
import java.io.File

/**
 * 製品名変更にともなう設定ディレクトリの移行を行う。
 *
 * 旧名称のディレクトリが存在し、新名称のディレクトリがまだ無い場合だけ、
 * 内容を新名称へコピーする。旧ディレクトリは削除しない。
 *
 * 移動ではなくコピーとするのは、移行に問題があった場合に旧バージョンの
 * アプリケーションから元の設定を読めるようにするためである。
 *
 * 1度移行すると新ディレクトリが存在するため、以降は何もしない。
 * 移行後の変更は新ディレクトリにだけ反映され、旧ディレクトリは古いまま残る。
 */
object ConfigDirectoryMigration {
    private val logger = LoggerFactory.getLogger(ConfigDirectoryMigration::class.java)

    /** 旧製品名の設定ディレクトリ名。 */
    const val LEGACY_DIRECTORY_NAME: String = "SushiEricDataEditor2"

    /**
     * 移行対象から除外するファイル名。
     *
     * lockは起動中のプロセスを表す一時ファイルであり、
     * 引き継ぐと起動済みと誤判定される可能性がある。
     */
    private val EXCLUDED_NAMES: Set<String> = setOf("lock")

    /**
     * 必要であれば旧ディレクトリから設定をコピーする。
     *
     * 失敗しても例外を投げない。移行できなかった場合は
     * 新規インストールと同じ初期状態で起動する。
     *
     * @param legacyDirectory 旧名称のディレクトリ。
     * @param currentDirectory 新名称のディレクトリ。
     * @return コピーを実行した場合はtrue。
     */
    fun migrateIfNeeded(
        legacyDirectory: File,
        currentDirectory: File
    ): Boolean {
        if (currentDirectory.exists()) {
            return false
        }

        if (!legacyDirectory.isDirectory) {
            return false
        }

        return try {
            copyDirectory(legacyDirectory, currentDirectory)

            logger.info(
                "設定を{}から{}へ移行しました。旧ディレクトリは残しています。",
                legacyDirectory.absolutePath,
                currentDirectory.absolutePath
            )

            true
        } catch (exception: Exception) {
            logger.warn(
                "設定の移行に失敗しました。初期状態で起動します。",
                exception
            )

            false
        }
    }

    private fun copyDirectory(
        source: File,
        destination: File
    ) {
        check(destination.mkdirs()) {
            "移行先ディレクトリを作成できません: ${destination.absolutePath}"
        }

        source.listFiles()?.forEach { child ->
            if (child.name in EXCLUDED_NAMES) {
                return@forEach
            }

            val target = File(destination, child.name)

            if (child.isDirectory) {
                copyDirectory(child, target)
            } else {
                child.copyTo(target, overwrite = false)
            }
        }
    }
}
