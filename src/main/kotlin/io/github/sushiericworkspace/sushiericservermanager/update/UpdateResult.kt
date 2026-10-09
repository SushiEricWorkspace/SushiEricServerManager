package io.github.sushiericworkspace.sushiericservermanager.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

/**
 * Updaterが書く、更新の結果です。次回の起動時にManagerが読んで表示します。
 *
 * @property success 更新に成功し、データも復元できた場合はtrue。
 * @property version 更新しようとした版。
 * @property exitCode インストーラーの終了コード。実行できなかった場合はnull。
 * @property message 失敗した場合の原因。成功した場合はnull。
 * @property timestamp 結果を書いた時刻（ISO-8601）。
 */
@Serializable
data class UpdateResult(
    val success: Boolean,
    val version: String,
    val exitCode: Int? = null,
    val message: String? = null,
    val timestamp: String = Instant.now().toString()
)

/**
 * Updaterが書いた結果ファイルの読み込みと削除を行います。
 *
 * @param file 結果ファイル。
 */
class UpdateResultStore(private val file: File) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 結果を読み込みます。
     *
     * @return 結果。ファイルがない場合と、読み込めない場合はnull。読み込めない場合は警告を記録する。
     */
    fun read(): UpdateResult? {
        if (!file.isFile) return null
        return try {
            json.decodeFromString<UpdateResult>(file.readText(Charsets.UTF_8).removePrefix("﻿"))
        } catch (e: Exception) {
            logger.warn("更新の結果ファイルを読み込めません: {}", file, e)
            null
        }
    }

    /** 結果ファイルを削除します。 */
    fun delete() {
        if (file.exists() && !file.delete()) {
            logger.warn("更新の結果ファイルを削除できません: {}", file)
        }
    }
}

/**
 * ダウンロード済みの古い成果物を削除します。
 *
 * [UpdateDownloader]は成果物を`<updates>/<版>/`へ保存します。更新後の起動では、
 * 実行中の版以下の版のディレクトリは不要なため、削除します。
 *
 * @param updatesDirectory `updates`ディレクトリ。存在しない場合は何もしません。
 * @param currentVersion 実行中の版。
 * @return 削除した版のディレクトリの数。
 */
fun pruneOldUpdates(updatesDirectory: File, currentVersion: String = AppVersion.CURRENT): Int {
    val directories = updatesDirectory.listFiles(File::isDirectory) ?: return 0
    return directories.count { directory ->
        val old = Regex("""\d+\.\d+\.\d+""").matches(directory.name) &&
            !isNewerVersion(directory.name, currentVersion)
        old && directory.deleteRecursively()
    }
}
