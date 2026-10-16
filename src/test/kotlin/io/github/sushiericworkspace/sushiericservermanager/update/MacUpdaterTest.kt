package io.github.sushiericworkspace.sushiericservermanager.update

import io.github.sushiericworkspace.sushiericservermanager.config.OS
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * macOS用のUpdaterのスクリプトを、本物の`hdiutil`と`ditto`で実行して確かめます。
 *
 * dmgは`hdiutil create`で作り、`.app`の起動コマンドは、呼ばれたことを記録するだけのスクリプトに置き換えます。
 */
class MacUpdaterTest {
    private class Sandbox(val root: File = createTempDirectory("mac-updater-test").toFile()) {
        /** 置換される、インストール済みの`.app`の置き場所。 */
        val applications = root.resolve("Applications").apply { mkdirs() }
        val appBundle = applications.resolve("Manager.app")
        val resultFile = root.resolve("data").resolve("update-result.json")
        val relaunchMarker = root.resolve("relaunched.txt")
        val launcher = root.resolve("launcher.sh")
        val dmg = root.resolve("Manager.dmg")

        fun seedInstalledApp() {
            appBundle.resolve("Contents/MacOS").mkdirs()
            appBundle.resolve("Contents/MacOS/Manager").writeText("old")
            launcher.writeText("#!/bin/sh\necho \"\$1\" > \"${relaunchMarker.absolutePath}\"\n")
            launcher.setExecutable(true)
        }

        /** 新しい版の`.app`だけを含むdmgを作る。 */
        fun createDmg(appName: String? = "Manager.app") {
            val source = root.resolve("dmg-source").apply { mkdirs() }
            if (appName != null) {
                source.resolve("$appName/Contents/MacOS").mkdirs()
                source.resolve("$appName/Contents/MacOS/Manager").writeText("new")
            } else {
                source.resolve("README.txt").writeText("appなし")
            }
            val process = ProcessBuilder(
                "hdiutil", "create", "-quiet", "-volname", "Manager", "-srcfolder", source.absolutePath,
                "-ov", "-format", "UDZO", dmg.absolutePath
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0) { "dmgを作成できません: $output" }
        }

        fun run(processId: Long = NO_SUCH_PROCESS): UpdateResult? {
            val updater = MacUpdater(appBundle, resultFile, processId = processId, launcher = launcher.absolutePath)
            val script = updater.writeScript(root)
            val process = ProcessBuilder(updater.buildCommand(script, dmg, "0.3.0")).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(120, TimeUnit.SECONDS)) { "スクリプトが終了しません" }
            check(process.exitValue() == 0) { "スクリプトが失敗しました: $output" }
            return UpdateResultStore(resultFile).read()
        }

        fun close() {
            applications.setWritable(true)
            root.deleteRecursively()
        }
    }

    private companion object {
        /** 存在しないプロセスID。Updaterは、待つ相手がいない場合はすぐに進む。 */
        const val NO_SUCH_PROCESS = 2_000_000_000L
    }

    private inline fun withSandbox(block: (Sandbox) -> Unit) {
        assumeTrue(OS.isMac, "macOSだけで実行する")
        val sandbox = Sandbox()
        try {
            sandbox.seedInstalledApp()
            block(sandbox)
        } finally {
            sandbox.close()
        }
    }

    private fun Sandbox.installedBinary(): String = appBundle.resolve("Contents/MacOS/Manager").readText()

    private fun Sandbox.leftovers(): List<String> =
        applications.list().orEmpty().filter { it != appBundle.name }

    @Test
    fun `dmgの新しい版で置換して再起動する`() = withSandbox { sandbox ->
        sandbox.createDmg()

        val result = sandbox.run()

        assertEquals(true, result?.success)
        assertEquals("0.3.0", result?.version)
        assertNull(result?.message)
        assertEquals("new", sandbox.installedBinary())
        assertEquals(emptyList(), sandbox.leftovers(), "一時的な.appが残らない")
        assertEquals(sandbox.appBundle.absolutePath, sandbox.relaunchMarker.readText().trim())
    }

    @Test
    fun `dmgに差し替え対象のapp名が違っても置換する`() = withSandbox { sandbox ->
        sandbox.createDmg(appName = "SushiEricServerManager.app")

        val result = sandbox.run()

        assertEquals(true, result?.success)
        assertEquals("new", sandbox.installedBinary())
    }

    @Test
    fun `dmgが存在しない場合は失敗として結果を書き、旧版で再起動する`() = withSandbox { sandbox ->
        val result = sandbox.run()

        assertEquals(false, result?.success)
        assertTrue(result?.message.orEmpty().isNotBlank())
        assertEquals("old", sandbox.installedBinary())
        assertTrue(sandbox.relaunchMarker.exists(), "失敗しても旧版のManagerを再起動する")
    }

    @Test
    fun `dmgが壊れている場合は失敗として結果を書き、旧版で再起動する`() = withSandbox { sandbox ->
        sandbox.dmg.writeText("dmgではない")

        val result = sandbox.run()

        assertEquals(false, result?.success)
        assertEquals("old", sandbox.installedBinary())
        assertTrue(sandbox.relaunchMarker.exists())
    }

    @Test
    fun `dmgにappが含まれていない場合は失敗として結果を書く`() = withSandbox { sandbox ->
        sandbox.createDmg(appName = null)

        val result = sandbox.run()

        assertEquals(false, result?.success)
        assertTrue(".app" in result?.message.orEmpty())
        assertEquals("old", sandbox.installedBinary())
    }

    @Test
    fun `置換先に書き込めない場合は失敗として結果を書き、旧版を残す`() = withSandbox { sandbox ->
        sandbox.createDmg()
        sandbox.applications.setWritable(false)

        val result = sandbox.run()

        assertEquals(false, result?.success)
        assertTrue(result?.message.orEmpty().isNotBlank())
        assertEquals("old", sandbox.installedBinary())
        assertTrue(sandbox.relaunchMarker.exists())
    }

    @Test
    fun `実行中のManagerが終了するまで待ってから更新する`() = withSandbox { sandbox ->
        sandbox.createDmg()
        val running = ProcessBuilder("sleep", "3").start()
        val started = System.nanoTime()

        val result = sandbox.run(processId = running.pid())

        assertEquals(true, result?.success)
        val elapsedSeconds = (System.nanoTime() - started) / 1e9
        assertTrue(elapsedSeconds >= 2.5, "Managerの終了を待っていません: ${elapsedSeconds}s")
        running.waitFor()
    }

    @Test
    fun `失敗の結果をJSONとして読める`() = withSandbox { sandbox ->
        val result = sandbox.run()

        assertNotNull(result, "結果ファイルをJSONとして読める")
        assertFalse(result.success)
        assertTrue(result.message.orEmpty().isNotBlank())
    }

    @Test
    fun `コマンドは値を引数として渡す`() {
        val appBundle = File("/Applications/Manager.app")
        val resultFile = File("/data/result.json")
        val script = File("/tmp/update.sh")
        val installer = File("/dl/installer's.dmg")
        val updater = MacUpdater(
            appBundle, resultFile, processId = 42, launcher = "open"
        )

        val command = updater.buildCommand(script, installer, "0.3.0")

        assertEquals(
            listOf(
                "/bin/sh", script.absolutePath, "42", installer.absolutePath,
                appBundle.absolutePath, resultFile.absolutePath, "0.3.0", "open"
            ),
            command
        )
    }

    @Test
    fun `起動ファイルから外側のappを見つける`() {
        val root = createTempDirectory("mac-bundle-test").toFile()
        try {
            val launcher = root.resolve("Manager.app/Contents/MacOS/Manager").apply {
                parentFile.mkdirs()
                writeText("")
            }

            assertEquals(root.resolve("Manager.app"), MacUpdater.findAppBundle(launcher))
            assertNull(MacUpdater.findAppBundle(root.resolve("plain/file").apply { parentFile.mkdirs() }))
        } finally {
            root.deleteRecursively()
        }
    }
}
