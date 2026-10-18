package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementState
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.Instant
import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfile

class ConnectionStatusTest {
    private fun profile(name: String) = ServerProfile(name, "host-a", 22, "user", "/server-a", "key-a")

    @Test
    fun `API切断後は保持されたTPSを稼働状態として表示しない`() {
        val status = ConnectionStatus.from(AppMode.ONLINE, "試験", true, false,
            ServerManagementState.Disconnected, 20.0, ManagerCompatibility.Compatible("0.2.2"))
        assertEquals("接続中", status.ssh)
        assertEquals("未接続", status.api)
        assertEquals("不明", status.minecraft)
        assertEquals("不明", status.tps)
        assertEquals("書込可", status.access)
    }

    @Test
    fun `SSH切断時はAPIの旧接続状態も有効にしない`() {
        val status = ConnectionStatus.from(AppMode.ONLINE, "試験", false, false,
            ServerManagementState.Connected(10000), 20.0, ManagerCompatibility.Compatible("0.2.2"))
        assertEquals("未接続", status.api)
        assertEquals("不明", status.tps)
        assertEquals("書込状態不明", status.access)
    }

    @Test
    fun `管理接続はSSHと独立し読取専用を維持する`() {
        val status = ConnectionStatus.from(AppMode.ONLINE, "管理環境", false, true,
            ServerManagementState.Connected(10000), 19.8, ManagerCompatibility.UpdateRequired("9.0.0"))
        assertEquals("対象外", status.ssh)
        assertEquals("接続中", status.api)
        assertEquals("稼働中", status.minecraft)
        assertEquals("19.80", status.tps)
        assertEquals("読取専用", status.access)
    }

    @Test
    fun `オフラインと未選択は不明と対象外を区別する`() {
        val offline = ConnectionStatus.from(AppMode.OFFLINE, null, false, false,
            ServerManagementState.Disconnected, null, ManagerCompatibility.Unchecked)
        assertEquals("ローカル書込可", offline.access)
        assertEquals("対象外", offline.api)
        assertEquals("不明", offline.minecraft)
        val unselected = ConnectionStatus.from(null, null, false, false,
            ServerManagementState.Disconnected, null, ManagerCompatibility.Unchecked)
        assertEquals("サーバー未選択", unselected.serverName)
        assertEquals("書込状態不明", unselected.access)
    }

    @Test
    fun `サーバー一覧の未確認と前回確認時刻を区別する`() {
        assertEquals("前回状態: 未確認  |  確認時刻: 未確認", ServerStatusHistory.describe(profile("未確認用")))
        val status = ConnectionStatus.from(AppMode.ONLINE, "履歴用", true, false,
            ServerManagementState.Failed("拒否"), null, ManagerCompatibility.Unchecked)
        ServerStatusHistory.record(profile("履歴用"), status, Instant.parse("2026-10-18T00:00:00Z"))
        val text = ServerStatusHistory.describe(profile("履歴用"))
        assertTrue(text.contains("前回状態: SSH 接続中 / API 接続失敗 / MC 不明"))
        assertTrue(text.contains("2026/"))
        assertTrue(text.contains("今回の起動中"))
    }
    @Test
    fun `同名でも接続先hostやpathを変更すると未確認になる`() {
        val original = profile("接続先変更用")
        val status = ConnectionStatus.from(AppMode.ONLINE, original.name, true, false,
            ServerManagementState.Connected(10000), 20.0, ManagerCompatibility.Missing)
        ServerStatusHistory.record(original, status)
        assertTrue(ServerStatusHistory.describe(original.copy(host = "host-b")).contains("前回状態: 未確認"))
        assertTrue(ServerStatusHistory.describe(original.copy(path = "/server-b")).contains("前回状態: 未確認"))
    }

    @Test
    fun `削除後の同名再作成は過去の接続先も未確認へ戻す`() {
        val original = profile("再作成用")
        val status = ConnectionStatus.from(AppMode.ONLINE, original.name, true, false,
            ServerManagementState.Connected(10000), 20.0, ManagerCompatibility.Missing)
        ServerStatusHistory.record(original, status)
        ServerStatusHistory.forget(original.copy(host = "変更後host"))
        assertTrue(ServerStatusHistory.describe(original).contains("前回状態: 未確認"))
    }

    @Test
    fun `鍵や表示設定は接続先identityに含めない`() {
        val original = profile("鍵変更用")
        val status = ConnectionStatus.from(AppMode.ONLINE, original.name, true, false,
            ServerManagementState.Connected(10000), 20.0, ManagerCompatibility.Missing)
        ServerStatusHistory.record(original, status)
        assertEquals(ServerStatusHistory.describe(original),
            ServerStatusHistory.describe(original.copy(key = "別の鍵", consoleLogLevel = "DEBUG")))
    }

}
