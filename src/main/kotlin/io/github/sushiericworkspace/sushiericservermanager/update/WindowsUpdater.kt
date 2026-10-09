package io.github.sushiericworkspace.sushiericservermanager.update

import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Windows用のUpdaterです。PowerShellのスクリプトを一時ディレクトリへ書き出して起動します。
 *
 * スクリプトは`resources/updater/update-windows.ps1`にあり、次の順に動きます。
 * 1. Managerのプロセスが終了するのを待つ。
 * 2. データ（インストールで置き換わらない項目）を一時ディレクトリへ退避する。
 * 3. インストーラーを静かに実行して、上書き更新する。
 * 4. データを復元する。
 * 5. 結果を[resultFile]へ書き、Managerを再起動する。
 *
 * jpackageのMSIは、上書き更新のときにインストール先のフォルダ全体を削除します。
 * データがインストール先と同じフォルダにあるため、手順2と4で守ります。
 *
 * @param appExe インストールされたManagerの起動ファイル。再起動に使う。
 * @param dataDirectory 設定とデータのディレクトリ。
 * @param resultFile 結果を書くファイル。
 * @param processId 終了を待つ、実行中のManagerのプロセスID。
 * @param powershell PowerShellの実行ファイル。
 */
class WindowsUpdater(
    private val appExe: File,
    private val dataDirectory: File,
    private val resultFile: File,
    private val processId: Long = ProcessHandle.current().pid(),
    private val powershell: String = "powershell.exe"
) : Updater {

    @Throws(IOException::class)
    override fun start(installer: File, version: String) {
        val directory = Files.createTempDirectory("SushiEricServerManager-updater").toFile()
        val script = writeScript(directory)
        ProcessBuilder(buildCommand(script, installer, version))
            .redirectErrorStream(true)
            .redirectOutput(directory.resolve("updater.log"))
            .start()
    }

    /** スクリプトを実行するコマンドを作ります。値は引数として渡し、スクリプトへ埋め込みません。 */
    internal fun buildCommand(script: File, installer: File, version: String): List<String> = listOf(
        powershell,
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy", "Bypass",
        "-WindowStyle", "Hidden",
        "-File", script.absolutePath,
        "-ProcessId", processId.toString(),
        "-Installer", installer.absolutePath,
        "-DataDir", dataDirectory.absolutePath,
        "-AppExe", appExe.absolutePath,
        "-ResultFile", resultFile.absolutePath,
        "-Version", version
    )

    /** スクリプトを[directory]へ書き出します。日本語を含むため、PowerShell 5.1が読めるBOM付きのUTF-8で書きます。 */
    internal fun writeScript(directory: File): File {
        val text = WindowsUpdater::class.java.getResourceAsStream(SCRIPT_RESOURCE)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Updaterのスクリプトが見つかりません: $SCRIPT_RESOURCE")
        return directory.resolve("update.ps1").apply {
            writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.removePrefix("﻿").toByteArray(Charsets.UTF_8))
        }
    }

    companion object {
        private const val SCRIPT_RESOURCE = "/updater/update-windows.ps1"

        /**
         * インストールされたManagerとして起動している場合のUpdaterを返します。
         *
         * jpackageの起動ファイルが設定するシステムプロパティ`jpackage.app-path`で、起動ファイルを判断します。
         * 開発中の実行（`gradlew run`など）では設定されないため、nullを返します。
         */
        fun forInstalledApp(): WindowsUpdater? {
            val appExe = System.getProperty("jpackage.app-path")?.let(::File)
                ?.takeIf { it.isFile && it.name.endsWith(".exe", ignoreCase = true) }
                ?: return null
            return WindowsUpdater(
                appExe = appExe,
                dataDirectory = FilePath.dataDirectory(),
                resultFile = FilePath.UPDATE_RESULT.toFile()
            )
        }
    }
}
