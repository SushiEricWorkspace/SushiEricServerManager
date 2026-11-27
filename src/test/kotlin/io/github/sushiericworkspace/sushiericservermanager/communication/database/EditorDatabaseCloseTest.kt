package io.github.sushiericworkspace.sushiericservermanager.communication.database

import io.github.sushiericworkspace.sushiericservermanager.app.AppMode
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import org.mockito.Mockito.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class EditorDatabaseCloseTest {
    @Test fun `Host終了を確認してからSSHを切断する`() {
        withSession(false) { _, order ->
            EditorSession.disconnect()
            assertEquals(listOf("db-close", "ssh-disconnect"), order)
            assertNull(EditorSession.dataService)
        }
    }

    @Test fun `Host終了不明ならSSHと編集sessionを保持する`() {
        withSession(true) { ssh, order ->
            val original = EditorSession.dataService
            assertFails { EditorSession.disconnect() }
            verify(ssh, never()).disconnect()
            assertEquals(listOf("db-close-unknown"), order)
            assertSame(original, EditorSession.dataService)
        }
    }

    private fun withSession(fail: Boolean, check: (SshManager, List<String>) -> Unit) {
        PreviewCanvasFxTest.startFx()
        assertNull(EditorSession.connection)
        val oldSsh = EditorSession.sshManager
        val dataField = EditorSession::class.java.getDeclaredField("dataService").apply { isAccessible = true }
        val modeField = EditorSession::class.java.getDeclaredField("mode").apply { isAccessible = true }
        val oldData = dataField.get(EditorSession)
        val oldMode = modeField.get(EditorSession)
        val ssh = mock(SshManager::class.java)
        val order = CopyOnWriteArrayList<String>()
        var failed = fail
        var closed = false
        doAnswer { order += "ssh-disconnect"; null }.`when`(ssh).disconnect()
        val store = object : EditorDataStore by InMemoryEditorDataStore() {
            override fun closeDatabase() {
                if (closed) return
                if (failed) { order += "db-close-unknown"; throw java.io.IOException("終了不明") }
                order += "db-close"
                closed = true
            }
        }
        val service = EditorDataService(store)
        EditorSession.sshManager = ssh
        dataField.set(EditorSession, service)
        modeField.set(EditorSession, AppMode.ONLINE)
        try { check(ssh, order) } finally {
            failed = false
            if (EditorSession.dataService === service) EditorSession.disconnect()
            service.closeIo()
            EditorSession.sshManager = oldSsh
            dataField.set(EditorSession, oldData)
            modeField.set(EditorSession, oldMode)
        }
    }
}
