package io.github.sushiericworkspace.sushiericservermanager.update

import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * macOS用のUpdaterです。shのスクリプトを一時ディレクトリへ書き出して起動します。
 *
 * スクリプトは`resources/updater/update-macos.sh`にあり、次の順に動きます。
 * 1. Managerのプロセスが終了するのを待つ。
 * 2. dmgをマウントする。
 * 3. dmg内の`.app`を、現在の`.app`と同じ場所へコピーしてから入れ替える。
 * 4. dmgをアンマウントする。
 * 5. 結果を[resultFile]へ書き、Managerを再起動する。
 *
 * 設定とデータは`.app`の外にあるため、退避は行いません。置換に失敗した場合は旧版のまま起動します。
 *
 * @param appBundle インストールされたManagerの`.app`。置換と再起動の対象。
 * @param resultFile 結果を書くファイル。
 * @param processId 終了を待つ、実行中のManagerのプロセスID。
 * @param shell スクリプトを実行するシェル。
 * @param launcher `.app`を起動するコマンド。
 */
class MacUpdater(
    private val appBundle: File,
    private val resultFile: File,
    private val processId: Long = ProcessHandle.current().pid(),
    private val shell: String = "/bin/sh",
    private val launcher: String = "open"
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
        shell,
        script.absolutePath,
        processId.toString(),
        installer.absolutePath,
        appBundle.absolutePath,
        resultFile.absolutePath,
        version,
        launcher
    )

    /** スクリプトを[directory]へ書き出します。 */
    internal fun writeScript(directory: File): File {
        val text = MacUpdater::class.java.getResourceAsStream(SCRIPT_RESOURCE)
            ?.use { it.readBytes() }
            ?: throw IOException("Updaterのスクリプトが見つかりません: $SCRIPT_RESOURCE")
        return directory.resolve("update.sh").apply { writeBytes(text) }
    }

    companion object {
        private const val SCRIPT_RESOURCE = "/updater/update-macos.sh"

        /**
         * インストールされたManagerとして起動している場合のUpdaterを返します。
         *
         * jpackageの起動ファイルが設定するシステムプロパティ`jpackage.app-path`から、`.app`を判断します。
         * 開発中の実行（`gradlew run`など）では設定されないため、nullを返します。
         * dmgをマウントした領域（`/Volumes`）から直接起動している場合も、置換先にできないためnullを返します。
         */
        fun forInstalledApp(): MacUpdater? {
            val appBundle = System.getProperty("jpackage.app-path")?.let(::File)
                ?.let(::findAppBundle)
                ?.takeIf { !it.absolutePath.startsWith("/Volumes/") }
                ?: return null
            return MacUpdater(
                appBundle = appBundle,
                resultFile = FilePath.UPDATE_RESULT.toFile()
            )
        }

        /** [launcher]から親をたどって、最も近い`.app`のディレクトリを返します。見つからない場合はnull。 */
        internal fun findAppBundle(launcher: File): File? =
            generateSequence(launcher) { it.parentFile }
                .firstOrNull { it.isDirectory && it.name.endsWith(".app") }
    }
}
