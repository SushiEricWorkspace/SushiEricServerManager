package io.github.sushiericworkspace.sushiericservermanager.editor.session

import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import kotlin.test.*

class EditorConnectionTest {
    @Test fun `準備失敗と終了未確認の接続は保持し別モードへの切替を拒否する`() {
        val connection = Connection()
        try {
            assertFailsWith<IllegalStateException> { EditorSession.startConnection(connection) }
            assertSame(connection, EditorSession.connection)
            assertFailsWith<IllegalStateException> { EditorSession.prepareOnlineMode() }
            assertFailsWith<IllegalStateException> { EditorSession.startOfflineSession(InMemoryEditorDataStore()) }
            assertFailsWith<IllegalStateException> { EditorSession.resetMode() }
            assertSame(connection, EditorSession.connection)
            connection.failClose = false
            EditorSession.resetMode()
            assertNull(EditorSession.connection)
            assertNull(EditorSession.dataService)
        } finally {
            connection.failClose = false
            EditorSession.resetMode()
        }
    }

    private class Connection : EditorConnection {
        var failClose = true
        override val contextName = "テスト"
        override val compatibility = ManagerCompatibility.Unchecked
        override fun open(): EditorDataService = error("登録後の準備失敗")
        override fun close() { check(!failClose) { "終了未確認" } }
        override fun abort() = Unit
    }
}
