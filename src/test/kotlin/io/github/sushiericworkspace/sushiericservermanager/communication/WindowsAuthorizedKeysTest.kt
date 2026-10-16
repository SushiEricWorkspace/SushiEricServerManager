package io.github.sushiericworkspace.sushiericservermanager.communication

import io.github.sushiericworkspace.sushiericservermanager.config.OS
import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsAuthorizedKeysTest {
    private val keyLine = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA test key"
    private val keySummary = WindowsPublicKeySummary.from(keyLine)

    @Test
    fun `PowerShellスクリプトは公開鍵を空白で分割する`() {
        val script = WindowsAuthorizedKeysScript.build(keyLine)

        assertContains(script, "-split '\\s+'")
        assertFalse(script.contains("-split '\\\\s+'"))
    }

    @Test
    fun `PowerShellスクリプトは8KB未満の圧縮EncodedCommandで実行する`() {
        val command = WindowsPowerShellCommand.build(WindowsAuthorizedKeysScript.build(keyLine))

        assertContains(command, "-EncodedCommand")
        assertTrue(command.length < 8_000, "PowerShell command length=${command.length}")
    }

    @Test
    fun `PowerShellスクリプトはトークンのグループSIDで管理者登録先を判定する`() {
        val script = WindowsAuthorizedKeysScript.build(keyLine)

        assertContains(script, "identity.Groups")
        assertContains(script, "S-1-5-32-544")
        assertFalse(script.contains("IsInRole"))
        assertContains(script, "administrators_authorized_keys")
        assertContains(script, "authorized_keys")
    }

    @Test
    fun `PowerShellスクリプトは公開鍵を追記し既存ACLを基準に更新する`() {
        val script = WindowsAuthorizedKeysScript.build(keyLine)

        assertContains(script, "AppendAllText")
        assertContains(script, "[IO.File]::GetAccessControl")
        assertFalse(script.contains("New-Object Security.AccessControl.FileSecurity"))
    }

    @Test
    fun `Windows PowerShellが生成スクリプトを構文解析できる`() {
        if (!OS.isWindows) return

        val script = WindowsAuthorizedKeysScript.build(keyLine)
        val scriptData = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val validationScript =
            "[ScriptBlock]::Create([Text.Encoding]::Unicode.GetString([Convert]::FromBase64String('$scriptData'))) | Out-Null"
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-Command",
            validationScript
        ).start()

        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "PowerShellの構文確認がタイムアウトしました")
        val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.exitValue(), errorOutput)
    }

    @Test
    fun `Windows PowerShellが圧縮EncodedCommandを復元して実行できる`() {
        if (!OS.isWindows) return

        val command = WindowsPowerShellCommand.build("[Console]::Out.WriteLine('SUSHIERIC_TEST_OK')")
        val encodedCommand = command.substringAfterLast(' ')
        val process = ProcessBuilder(
            "powershell.exe",
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-ExecutionPolicy",
            "Bypass",
            "-EncodedCommand",
            encodedCommand
        ).start()

        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "PowerShellの実行確認がタイムアウトしました")
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val errorOutput = process.errorStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.exitValue(), errorOutput)
        assertContains(output, "SUSHIERIC_TEST_OK")
    }

    @Test
    fun `コメントが異なっても同じEd25519公開鍵を重複登録しない`() {
        val publicKey = OpenSshPublicKeyEncoder.encode(
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair().public,
            "first comment with spaces"
        )
        val existing = publicKey.substringBeforeLast(" ") + " another-comment\n"

        val result = AuthorizedKeysEditor.merge(existing, publicKey)

        assertFalse(result.added)
        assertEquals(existing, result.content)
    }

    @Test
    fun `RSA公開鍵を既存内容を保持して追記する`() {
        val publicKey = OpenSshPublicKeyEncoder.encode(
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public,
            "rsa test key"
        )
        val existing = "# existing key list\n"

        val result = AuthorizedKeysEditor.merge(existing, publicKey)

        assertTrue(result.added)
        assertTrue(result.content.startsWith(existing))
        assertContains(result.content, publicKey)
    }

    @Test
    fun `追加成功の構造化出力を解析する`() {
        val result = WindowsAuthorizedKeysCommandResultParser.parse(
            exitStatus = 0,
            output = """
                SUSHIERIC_RESULT:ADDED
                SUSHIERIC_STAGE:COMPLETE
                SUSHIERIC_TARGET:ADMINISTRATORS
                SUSHIERIC_PATH:C:\ProgramData\ssh\administrators_authorized_keys
                SUSHIERIC_KEY_STATE:APPENDED
            """.trimIndent(),
            errorOutput = "",
            keySummary = keySummary
        )

        assertTrue(result.added)
        assertEquals(WindowsAuthorizedKeysTarget.ADMINISTRATORS, result.target)
        assertEquals(WindowsAuthorizedKeysKeyState.APPENDED, result.keyState)
    }

    @Test
    fun `登録済みの構造化出力を追加なしとして解析する`() {
        val result = WindowsAuthorizedKeysCommandResultParser.parse(
            exitStatus = 0,
            output = """
                SUSHIERIC_RESULT:EXISTS
                SUSHIERIC_STAGE:COMPLETE
                SUSHIERIC_TARGET:USER_PROFILE
                SUSHIERIC_PATH:C:\Users\user\.ssh\authorized_keys
                SUSHIERIC_KEY_STATE:EXISTS
            """.trimIndent(),
            errorOutput = "",
            keySummary = keySummary
        )

        assertFalse(result.added)
        assertEquals(WindowsAuthorizedKeysTarget.USER_PROFILE, result.target)
        assertEquals(WindowsAuthorizedKeysKeyState.EXISTS, result.keyState)
    }

    @Test
    fun `追記後のACL失敗を部分成功として保持する`() {
        val failure = assertFailsWith<WindowsAuthorizedKeysRegistrationException> {
            WindowsAuthorizedKeysCommandResultParser.parse(
                exitStatus = 1,
                output = "",
                errorOutput = """
                    SUSHIERIC_ERROR:ACCESS_DENIED
                    SUSHIERIC_STAGE:SET_FILE_ACL
                    SUSHIERIC_TARGET:ADMINISTRATORS
                    SUSHIERIC_PATH:C:\ProgramData\ssh\administrators_authorized_keys
                    SUSHIERIC_KEY_STATE:APPENDED
                """.trimIndent(),
                keySummary = keySummary
            )
        }

        assertTrue(failure.publicKeyMayBeRegistered)
        assertEquals("ACCESS_DENIED", failure.errorCode)
        assertContains(failure.safeDiagnostic(), "fingerprint=SHA256:")
    }

    @Test
    fun `追記後の失敗メッセージは公開鍵が残っていることを伝える`() {
        val failure = SshFailure(
            code = SshFailureCode.AUTHORIZED_KEYS_UPDATE_FAILED,
            publicKeyRegistered = true,
            remoteOperatingSystem = RemoteOperatingSystem.WINDOWS
        )

        val message = failure.userMessage()

        assertContains(message, "公開鍵は追記済み")
        assertContains(message, "自動削除していません")
    }

    @Test
    fun `タイムアウトを終了コードなしの失敗として扱う`() {
        val failure = assertFailsWith<WindowsAuthorizedKeysRegistrationException> {
            WindowsAuthorizedKeysCommandResultParser.parse(
                exitStatus = null,
                output = "",
                errorOutput = "",
                keySummary = keySummary
            )
        }

        assertEquals("TIMEOUT", failure.errorCode)
        assertFalse(failure.publicKeyMayBeRegistered)
    }
}
