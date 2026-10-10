package io.github.sushiericworkspace.sushiericservermanager.update

import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** MSIの削除範囲を一時領域で再現し、本物のPowerShell Updaterで移行と分離後の更新を確認します。 */
@EnabledOnOs(OS.WINDOWS)
class WindowsInstallLayoutTest {
    @Test fun `直下のインストールからデータを復元しapp配下の新アプリを起動する`() = checkUpdate(legacy = true)
    @Test fun `app配下の上書きはデータを退避せず保持する`() = checkUpdate(legacy = false)

    private fun checkUpdate(legacy: Boolean) {
        val root = Files.createTempDirectory("manager-layout-test-").toFile()
        val data = File(root, "データ 領域").apply { mkdirs() }
        val app = File(data, "app")
        val install = if (legacy) data else app.apply { mkdirs() }
        val exe = File(install, "Manager.cmd")
        val marker = File(root, "relaunch.txt")
        val config = File(data, "config.json").apply { writeText("設定") }
        File(data, "ssh").mkdirs()
        File(data, "ssh/known_hosts").writeText("test")
        File(data, "offline/nested").mkdirs()
        File(data, "offline/nested/item.yml").writeText("id: item")
        exe.writeText("@echo off\r\necho old>\"${marker.path}\"\r\n")
        val installerScript = File(root, "install.ps1")
        val installer = File(root, "install.cmd")
        // 疑似インストーラーの削除対象はcreateTempDirectoryの子であることを先に確認します。
        assertTrue(install.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
        val escapedInstall = install.path.replace("'", "''")
        val escapedApp = app.path.replace("'", "''")
        val installText = """
            ${'$'}ErrorActionPreference = 'Stop'
            Remove-Item -LiteralPath '$escapedInstall' -Recurse -Force
            New-Item -ItemType Directory -Path '$escapedApp' -Force | Out-Null
            Copy-Item -LiteralPath '${File(root, "new.cmd").path.replace("'", "''")}' -Destination '$escapedApp/Manager.cmd'
        """.trimIndent()
        installerScript.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + installText.toByteArray(Charsets.UTF_8))
        File(root, "new.cmd").writeText("@echo off\r\necho new>\"${marker.path}\"\r\n")
        installer.writeText("@echo off\r\npowershell.exe -NoProfile -ExecutionPolicy Bypass -File \"${installerScript.path}\"\r\nexit /b %errorlevel%\r\n")
        try {
            val result = File(data, "update-result.json")
            val updater = WindowsUpdater(exe, data, result, processId = 2_000_000_000L)
            val script = updater.writeScript(root)
            val process = ProcessBuilder(updater.buildCommand(script, installer, "0.3.0")).redirectErrorStream(true).start()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
            assertEquals(true, UpdateResultStore(result).read()?.success)
            assertEquals("設定", config.readText())
            assertEquals("test", File(data, "ssh/known_hosts").readText())
            assertEquals("id: item", File(data, "offline/nested/item.yml").readText())
            repeat(50) { if (!marker.exists()) Thread.sleep(100) }
            assertEquals("new", marker.readText().trim())
        } finally {
            // このテストで生成した一時ディレクトリだけを削除します。
            root.deleteRecursively()
        }
    }
}
