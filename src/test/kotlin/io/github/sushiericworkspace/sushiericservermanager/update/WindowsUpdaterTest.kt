package io.github.sushiericworkspace.sushiericservermanager.update

import io.github.sushiericworkspace.sushiericservermanager.config.OS
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Windows用のUpdaterのスクリプトを、本物のPowerShellで実行して確かめます。
 *
 * インストーラーとManagerの起動ファイルは、動作を真似た`.cmd`に置き換えます。
 * 疑似インストーラーは、jpackageのMSIと同じように、成功時にインストール先のフォルダ全体を削除します。
 */
class WindowsUpdaterTest {
    private class Sandbox(val root: File = createTempDirectory("updater-test").toFile()) {
        /** インストール先と同じ場所にあるデータ領域。 */
        val dataDirectory = root.resolve("install").apply { mkdirs() }
        val resultFile = dataDirectory.resolve("update-result.json")
        val relaunchMarker = root.resolve("relaunched.txt")
        val appExe = dataDirectory.resolve("Manager.cmd")
        val installer = root.resolve("installer.cmd")

        fun seed() {
            dataDirectory.resolve("app").mkdirs()
            dataDirectory.resolve("app").resolve("old.jar").writeText("old")
            dataDirectory.resolve("runtime").mkdirs()
            dataDirectory.resolve("config.json").writeText("""{"theme": "dark"}""")
            dataDirectory.resolve("profiles.json").writeText("""{"日本語": "プロファイル"}""")
            dataDirectory.resolve("ssh").mkdirs()
            dataDirectory.resolve("ssh").resolve("known_hosts").writeText("host key")
            dataDirectory.resolve("offline").resolve("item_data").mkdirs()
            dataDirectory.resolve("offline").resolve("item_data").resolve("sword.yml").writeText("id: sword")
            appExe.writeText("@echo off\r\necho relaunched> \"${relaunchMarker.absolutePath}\"\r\n")
        }

        /** MSIと同じく、成功時にインストール先を空にしてから、新しいManagerを置く。 */
        fun installerThat(exitCode: Int, wipe: Boolean) {
            val wipeCommand = if (wipe) "for /d %%d in (\"${dataDirectory.absolutePath}\\*\") do rmdir /s /q \"%%d\"\r\n" +
                "del /q \"${dataDirectory.absolutePath}\\*.json\"\r\n" else ""
            installer.writeText("@echo off\r\n$wipeCommand" + "exit /b $exitCode\r\n")
        }

        fun run(processId: Long = NO_SUCH_PROCESS): UpdateResult? {
            val updater = WindowsUpdater(appExe, dataDirectory, resultFile, processId = processId)
            val script = updater.writeScript(root)
            val process = ProcessBuilder(updater.buildCommand(script, installer, "0.3.0")).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(60, TimeUnit.SECONDS)) { "スクリプトが終了しません" }
            check(process.exitValue() == 0) { "スクリプトが失敗しました: $output" }
            // 再起動は別プロセスのため、少し待つ。
            repeat(50) { if (!relaunchMarker.exists()) Thread.sleep(100) }
            return UpdateResultStore(resultFile).read()
        }

        fun close() = root.deleteRecursively()
    }

    private companion object {
        /** 存在しないプロセスID。Updaterは、待つ相手がいない場合はすぐに進む。 */
        const val NO_SUCH_PROCESS = 2_000_000_000L
    }

    private inline fun withSandbox(block: (Sandbox) -> Unit) {
        assumeTrue(OS.isWindows, "Windowsだけで実行する")
        val sandbox = Sandbox()
        try {
            sandbox.seed()
            block(sandbox)
        } finally {
            sandbox.close()
        }
    }

    private fun Sandbox.assertDataIntact() {
        assertEquals("""{"theme": "dark"}""", dataDirectory.resolve("config.json").readText())
        assertEquals("""{"日本語": "プロファイル"}""", dataDirectory.resolve("profiles.json").readText(Charsets.UTF_8))
        assertEquals("host key", dataDirectory.resolve("ssh").resolve("known_hosts").readText())
        assertEquals("id: sword", dataDirectory.resolve("offline").resolve("item_data").resolve("sword.yml").readText())
    }

    @Test
    fun `インストールが成功してデータが消えても復元して再起動する`() = withSandbox { sandbox ->
        sandbox.installerThat(exitCode = 0, wipe = true)

        val result = sandbox.run()

        assertEquals(true, result?.success)
        assertEquals("0.3.0", result?.version)
        assertEquals(0, result?.exitCode)
        sandbox.assertDataIntact()
        assertFalse(sandbox.dataDirectory.resolve("app").exists(), "疑似インストーラーが旧版を削除する")
        assertTrue(sandbox.relaunchMarker.exists(), "Managerを再起動する")
    }

    @Test
    fun `再起動が必要な成功の終了コードも成功として扱う`() = withSandbox { sandbox ->
        sandbox.installerThat(exitCode = 3010, wipe = true)

        val result = sandbox.run()

        assertEquals(true, result?.success)
        assertEquals(3010, result?.exitCode)
        sandbox.assertDataIntact()
    }

    @Test
    fun `インストールが失敗した場合は失敗として結果を書き、データは残して再起動する`() = withSandbox { sandbox ->
        sandbox.installerThat(exitCode = 1603, wipe = false)

        val result = sandbox.run()

        assertEquals(false, result?.success)
        assertEquals(1603, result?.exitCode)
        assertTrue("1603" in result?.message.orEmpty())
        sandbox.assertDataIntact()
        assertTrue(sandbox.dataDirectory.resolve("app").resolve("old.jar").exists(), "旧版は残る")
        assertTrue(sandbox.relaunchMarker.exists(), "失敗しても旧版のManagerを再起動する")
    }

    @Test
    fun `インストールが失敗してデータが消えた場合も復元する`() = withSandbox { sandbox ->
        sandbox.installerThat(exitCode = 1, wipe = true)

        val result = sandbox.run()

        assertEquals(false, result?.success)
        sandbox.assertDataIntact()
    }

    @Test
    fun `インストーラーが存在しない場合は失敗として結果を書く`() = withSandbox { sandbox ->
        sandbox.installer.delete()

        val result = sandbox.run()

        assertEquals(false, result?.success)
        assertTrue(result?.message.orEmpty().isNotBlank())
        sandbox.assertDataIntact()
        assertTrue(sandbox.relaunchMarker.exists())
    }

    @Test
    fun `実行中のManagerが終了するまで待ってから更新する`() = withSandbox { sandbox ->
        sandbox.installerThat(exitCode = 0, wipe = false)
        val running = ProcessBuilder("powershell.exe", "-NoProfile", "-Command", "Start-Sleep -Seconds 3").start()
        val started = System.nanoTime()

        val result = sandbox.run(processId = running.pid())

        assertEquals(true, result?.success)
        val elapsedSeconds = (System.nanoTime() - started) / 1e9
        assertTrue(elapsedSeconds >= 2.5, "Managerの終了を待っていません: ${elapsedSeconds}s")
        running.waitFor()
    }

    @Test
    fun `コマンドは値を引数として渡す`() {
        val updater = WindowsUpdater(File("C:/app/Manager.exe"), File("C:/data"), File("C:/data/result.json"), processId = 42)

        val command = updater.buildCommand(File("C:/tmp/update.ps1"), File("C:/dl/installer's.exe"), "0.3.0")

        assertEquals(
            listOf("-ProcessId", "42", "-Installer", File("C:/dl/installer's.exe").absolutePath),
            command.subList(command.indexOf("-ProcessId"), command.indexOf("-ProcessId") + 4)
        )
        assertEquals("0.3.0", command.last())
        assertTrue("-File" in command && "Bypass" in command)
    }
}
