package io.github.sushiericworkspace.sushiericservermanager.feature.servercontrol

import io.github.sushiericworkspace.sushiericservermanager.config.ServerControlCommandSet
import io.github.sushiericworkspace.sushiericservermanager.config.ServerControlCommandsConfig
import io.github.sushiericworkspace.sushiericservermanager.config.ServerControlCommandsExport
import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * サーバー操作コマンドの取り出しと、受け渡し用ファイルの読み書きを検証する。
 */
class ServerControlCommandsTest {

    @Test
    fun `登録されたコマンドを操作ごとに取り出す`() {
        val commands =
            ServerControlCommandSet(
                startCommand = "systemctl start minecraft",
                stopCommand = "systemctl stop minecraft",
                restartCommand = "systemctl restart minecraft"
            )

        assertEquals(
            "systemctl start minecraft",
            ServerControlCommand.START.commandOf(commands)
        )
        assertEquals(
            "systemctl stop minecraft",
            ServerControlCommand.STOP.commandOf(commands)
        )
        assertEquals(
            "systemctl restart minecraft",
            ServerControlCommand.RESTART.commandOf(commands)
        )
    }

    @Test
    fun `空白だけのコマンドは未設定として扱う`() {
        val commands =
            ServerControlCommandSet(
                startCommand = "   ",
                stopCommand = "",
                restartCommand = "systemctl restart minecraft"
            )

        assertNull(ServerControlCommand.START.commandOf(commands))
        assertFalse(ServerControlCommand.START.isConfigured(commands))

        assertNull(ServerControlCommand.STOP.commandOf(commands))
        assertTrue(ServerControlCommand.RESTART.isConfigured(commands))
    }

    @Test
    fun `プロファイルが無い場合も未設定として扱う`() {
        ServerControlCommand.entries.forEach { control ->
            assertNull(control.commandOf(null))
            assertFalse(control.isConfigured(null))
        }
    }

    @Test
    fun `前後の空白を取り除いて保持する`() {
        val normalized =
            ServerControlCommandSet(
                startCommand = "  prepare.sh\n\nstart.sh  ",
                stopCommand = "\tstop.sh\n",
                restartCommand = "",
                workingDirectory = "  /srv/minecraft  "
            ).normalized()

        assertEquals("prepare.sh\n\nstart.sh", normalized.startCommand)
        assertEquals("stop.sh", normalized.stopCommand)
        assertEquals("", normalized.restartCommand)
        assertEquals("/srv/minecraft", normalized.workingDirectory)
        assertTrue(normalized.hasAnyCommand)
        assertTrue(normalized.hasAnySetting)
    }

    @Test
    fun `すべて空の場合はコマンドを持たないと判定する`() {
        assertFalse(ServerControlCommandSet.EMPTY.hasAnyCommand)
        assertFalse(ServerControlCommandSet(startCommand = "  ").normalized().hasAnyCommand)
        assertTrue(ServerControlCommandSet(workingDirectory = "/srv/minecraft").hasAnySetting)
    }

    @Test
    fun `書き出したファイルを読み込める`() {
        val file = Files.createTempFile("server-control", ".json").toFile()
        file.deleteOnExit()

        val commands =
            ServerControlCommandSet(
                startCommand = "start.sh",
                stopCommand = "stop.sh",
                restartCommand = "restart.sh",
                workingDirectory = "/srv/minecraft"
            )

        assertTrue(ServerControlCommandsIo.export(file, commands))

        assertEquals(commands, ServerControlCommandsIo.import(file))
    }

    @Test
    fun `書き出したファイルにプロファイル名を含めない`() {
        val file = Files.createTempFile("server-control", ".json").toFile()
        file.deleteOnExit()

        ServerControlCommandsIo.export(
            file,
            ServerControlCommandSet(startCommand = "start.sh")
        )

        val text = file.readText()

        assertTrue(text.contains("formatVersion"))
        assertTrue(text.contains("startCommand"))
        assertTrue(text.contains("workingDirectory"))
        assertFalse(text.contains("name"))
        assertFalse(text.contains("host"))
    }

    @Test
    fun `対応していない形式は読み込まない`() {
        val file = Files.createTempFile("server-control", ".json").toFile()
        file.deleteOnExit()

        val unsupportedVersion = ServerControlCommandsExport.FORMAT_VERSION + 1
        file.writeText("""{"formatVersion":$unsupportedVersion,"startCommand":"start.sh"}""")

        assertNull(ServerControlCommandsIo.import(file))
    }

    @Test
    fun `壊れたファイルは読み込まない`() {
        val file = Files.createTempFile("server-control", ".json").toFile()
        file.deleteOnExit()

        file.writeText("これはJSONではありません")

        assertNull(ServerControlCommandsIo.import(file))
    }

    @Test
    fun `未設定の項目を含む定義も読み込める`() {
        val file = Files.createTempFile("server-control", ".json").toFile()
        file.deleteOnExit()

        file.writeText(
            """{"formatVersion":${ServerControlCommandsExport.FORMAT_VERSION},"startCommand":"start.sh"}"""
        )

        val imported = ServerControlCommandsIo.import(file)

        assertEquals("start.sh", imported?.startCommand)
        assertEquals("", imported?.stopCommand)
        assertEquals("", imported?.restartCommand)
        assertEquals("", imported?.workingDirectory)
    }

    @Test
    fun `作業ディレクトリ追加前の受け渡し用ファイルを読み込める`() {
        val file = Files.createTempFile("server-control-legacy", ".json").toFile()
        file.deleteOnExit()

        file.writeText(
            """{"formatVersion":${ServerControlCommandsExport.LEGACY_FORMAT_VERSION},"startCommand":"start.sh"}"""
        )

        val imported = ServerControlCommandsIo.import(file)

        assertEquals("start.sh", imported?.startCommand)
        assertEquals("", imported?.workingDirectory)
    }

    @Test
    fun `作業ディレクトリ追加前の保存設定を読み込める`() {
        val config =
            Json.decodeFromString(
                ServerControlCommandsConfig.serializer(),
                """{"commands":{"server":{"startCommand":"start.sh"}}}"""
            )

        assertEquals("start.sh", config.commands["server"]?.startCommand)
        assertEquals("", config.commands["server"]?.workingDirectory)
    }

    @Test
    fun `複数行の空行を除いて成功時だけ続くコマンドへ連結する`() {
        val command = "prepare.sh\n\n  start.sh --nogui  "

        assertEquals(
            "prepare.sh && start.sh --nogui",
            ServerControlCommandLine.combine(command)
        )
    }

    @Test
    fun `Unix系では引用した作業ディレクトリへ移動してから実行する`() {
        assertEquals(
            "cd -- '/srv/minecraft server' && prepare.sh && start.sh",
            ServerControlCommandLine.build(
                command = "prepare.sh\nstart.sh",
                workingDirectory = "/srv/minecraft server",
                operatingSystem = RemoteOperatingSystem.UBUNTU_SERVER
            )
        )
    }

    @Test
    fun `Windowsではドライブを含む作業ディレクトリへ移動してから実行する`() {
        assertEquals(
            "cd /d \"C:\\Minecraft Server\" && prepare.bat && start.bat",
            ServerControlCommandLine.build(
                command = "prepare.bat\nstart.bat",
                workingDirectory = "C:\\Minecraft Server",
                operatingSystem = RemoteOperatingSystem.WINDOWS
            )
        )
    }

    @Test
    fun `作業ディレクトリ未指定では連結したコマンドだけを実行する`() {
        assertEquals(
            "prepare.sh && start.sh",
            ServerControlCommandLine.build(
                command = "prepare.sh\nstart.sh",
                workingDirectory = " ",
                operatingSystem = RemoteOperatingSystem.MACOS
            )
        )
    }
}
