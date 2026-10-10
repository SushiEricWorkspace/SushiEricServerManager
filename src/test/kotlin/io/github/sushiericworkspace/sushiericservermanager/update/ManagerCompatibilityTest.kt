package io.github.sushiericworkspace.sushiericservermanager.update

import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.editor.store.RemoteEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import java.io.IOException
import kotlin.test.*

class ManagerCompatibilityTest {
    @Test fun `最小版より古い場合のみ読み取り専用`() {
        val text = """{"minManagerVersion":"0.3.0","modVersion":"0.5.0-dev"}"""
        assertIs<ManagerCompatibility.UpdateRequired>(evaluateManagerCompatibility(text, "0.2.2"))
        assertIs<ManagerCompatibility.Compatible>(evaluateManagerCompatibility(text, "0.3.0"))
        assertIs<ManagerCompatibility.Compatible>(evaluateManagerCompatibility(text, "0.10.0"))
        assertFails { evaluateManagerCompatibility("""{"minManagerVersion":"invalid","modVersion":"0.5.0"}""") }
        assertTrue(ManagerCompatibility.Missing.writable)
        assertTrue(ManagerCompatibility.Unreadable.writable)
    }

    @Test fun `読み取り専用では全SFTP書き込みとStore変更を開始しない`() {
        val ssh = SshManager()
        // 実サーバーへ接続せず、接続時の評価結果を再現します。
        SshManager::class.java.getDeclaredField("compatibility").apply { isAccessible = true }
            .set(ssh, ManagerCompatibility.UpdateRequired("0.3.0"))
        val writes = listOf<() -> Unit>(
            { ssh.upload("missing-local-file", "remote") }, { ssh.remove("remote") },
            { ssh.removeDirectory("remote") }, { ssh.rename("old", "new") }, { ssh.createDirectories("directory") }
        )
        writes.forEach { assertTrue(assertFailsWith<IOException> { it() }.message.orEmpty().contains("更新が必要")) }
        val store = RemoteEditorDataStore(ssh)
        val descriptor = EditorDataDescriptors.recipe
        val results = listOf(
            store.writeText("config.yml", "test"), store.delete(descriptor, "test"),
            store.rename(descriptor, "test", "new"), store.move(descriptor, "test", "nested"),
            store.createDirectory(descriptor, "nested"), store.deleteDirectory(descriptor, "nested"),
            store.save(descriptor, "test", descriptor.createDefault("test"))
        )
        results.forEach { assertEquals(StoreErrorCode.PERMISSION_DENIED, assertIs<StoreResult.Failure>(it).error.code) }
        ssh.disconnect()
        assertIs<ManagerCompatibility.Unchecked>(ssh.compatibility)
    }
}
