package io.github.sushiericworkspace.sushiericservermanager.update

import io.github.sushiericworkspace.common.path.SushiEricDataDirectory
import io.github.sushiericworkspace.sushiericservermanager.communication.HostKeyApprovalHandler
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.communication.SshResult
import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfile
import io.github.sushiericworkspace.sushiericservermanager.editor.store.RemoteEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** 明示的に有効化したときだけ、既存のホスト鍵検証と秘密鍵でlocalhostへ接続します。実データは変更しません。 */
@EnabledIfEnvironmentVariable(named = "MANAGER_LOCAL_SFTP_TEST", matches = "true")
class ManagerCompatibilitySftpTest {
    @Test fun `実SFTPで互換ファイルを再接続ごとに評価し変更を遮断する`() {
        val root = Files.createTempDirectory("manager-compat-test-").toFile()
        val compat = File(root, "${SushiEricDataDirectory.BASE_ROOT}/${SushiEricDataDirectory.ManagerCompat().getRawPath()}")
        val dataFile = File(compat.parentFile, "test.txt")
        val profile = ServerProfile("互換性テスト", "127.0.0.1", 22, System.getenv("USERNAME"), root.path,
            File(System.getenv("USERPROFILE"), ".ssh/id_ed25519").path, remoteOperatingSystem = "WINDOWS")
        val ssh = SshManager()
        fun connect() { assertIs<SshResult.Success<Unit>>(ssh.connect(profile, HostKeyApprovalHandler.REJECT_UNKNOWN)) }
        try {
            connect()
            assertIs<ManagerCompatibility.Missing>(ssh.compatibility)
            val store = RemoteEditorDataStore(ssh)
            assertIs<StoreResult.Success<Unit>>(store.writeText("test.txt", "before"))
            compat.parentFile.mkdirs()
            compat.writeText("not json")
            connect()
            assertIs<ManagerCompatibility.Unreadable>(ssh.compatibility)
            compat.writeText("""{"minManagerVersion":"0.0.0","modVersion":"test"}""")
            connect()
            assertIs<ManagerCompatibility.Compatible>(ssh.compatibility)
            assertIs<StoreResult.Success<Unit>>(store.writeText("test.txt", "compatible"))
            compat.writeText("""{"minManagerVersion":"999.0.0","modVersion":"test"}""")
            connect()
            assertIs<ManagerCompatibility.UpdateRequired>(ssh.compatibility)
            assertIs<StoreResult.Failure>(store.writeText("test.txt", "denied"))
            assertFails { ssh.remove(dataFile.path) }
            assertFails { ssh.rename(dataFile.path, File(dataFile.parentFile, "renamed.txt").path) }
            assertEquals("compatible", dataFile.readText())
            assertEquals("compatible", assertIs<StoreResult.Success<String>>(store.readText("test.txt")).value)
            assertTrue(ssh.listFilesOrThrow(root.path).isNotEmpty())
            assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".sushieric-write-test-") })
        } finally {
            ssh.disconnect()
            // createTempDirectoryで作成した、このテスト専用のディレクトリだけを削除します。
            root.deleteRecursively()
        }
    }
}
