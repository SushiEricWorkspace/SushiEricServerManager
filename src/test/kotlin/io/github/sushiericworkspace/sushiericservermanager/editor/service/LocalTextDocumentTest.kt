package io.github.sushiericworkspace.sushiericservermanager.editor.service

import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStoreKind
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.*

class LocalTextDocumentTest {
    @TempDir lateinit var root: File

    private fun service(memory: InMemoryEditorDataStore) = EditorDataService(object : EditorDataStore by memory {
        override val kind = EditorDataStoreKind.REMOTE
        override val documentSource = EditorDocumentSource.Server("text-profile", "declared/root")
        override fun writeText(relativePath: String, text: String): StoreResult<Unit> = error("接続元へ保存してはいけません")
    }, root.resolve("autosave"))

    @Test
    fun explicitFetchPreservesUnsentSavedAndInvalidEditingText() {
        val memory = InMemoryEditorDataStore()
        val service = service(memory)
        val access = service.modConfig
        memory.writeText(access.relativePath, "server: first")
        val initial = (access.localState() as StoreResult.Success).value
        val version = LocalDocumentVersion(initial.key, initial.revision)
        assertTrue(access.saveObserved("invalid: [", version) is StoreResult.Success)
        assertTrue(access.backup("uncommitted: [", version).get(10, TimeUnit.SECONDS) is StoreResult.Success)
        memory.writeText(access.relativePath, "server: latest")
        val latest = (access.fetchSource() as StoreResult.Success).value
        val record = (access.acceptFetched(version, latest, "uncommitted: [") as StoreResult.Success).value
        assertEquals("invalid: [", record.saved)
        assertEquals("uncommitted: [", record.editing)
        assertEquals("server: latest", record.baseline)
        assertTrue(record.unsaved)
        assertEquals(true, record.unreflected)
        assertEquals("server: latest", (memory.readText(access.relativePath) as StoreResult.Success).value)
        service.closeIo()
    }

    @Test
    fun staleTextSaveBackupAndFetchCannotReplaceAnotherEditorsGeneration() {
        val memory = InMemoryEditorDataStore()
        val first = service(memory)
        val second = service(memory)
        memory.writeText(first.modConfig.relativePath, "server: first")
        val one = (first.modConfig.localState() as StoreResult.Success).value
        val two = (second.modConfig.localState() as StoreResult.Success).value
        val a = LocalDocumentVersion(one.key, one.revision)
        val b = LocalDocumentVersion(two.key, two.revision)
        assertTrue(first.modConfig.saveObserved("latest-local: [", a) is StoreResult.Success)
        val before = (first.documents.read() as StoreResult.Success).value
        assertTrue(second.modConfig.saveObserved("stale", b) is StoreResult.Failure)
        assertTrue(second.modConfig.backup("stale", b).get(10, TimeUnit.SECONDS) is StoreResult.Failure)
        assertTrue(second.modConfig.acceptFetched(b, "new-server", "stale") is StoreResult.Failure)
        assertEquals(before, (first.documents.read() as StoreResult.Success).value)
        first.closeIo(); second.closeIo()
    }

    @Test
    fun cleanConfirmedTextFetchAdvancesSavedAndBaselineTogether() {
        val memory = InMemoryEditorDataStore()
        val service = service(memory)
        val access = service.modConfig
        memory.writeText(access.relativePath, "first: true")
        val old = (access.localState() as StoreResult.Success).value
        val version = LocalDocumentVersion(old.key, old.revision)
        val accepted = (access.acceptFetched(version, "latest: true", old.editing.orEmpty()) as StoreResult.Success).value
        assertEquals("latest: true", accepted.editing)
        assertEquals(accepted.saved, accepted.baseline)
        assertFalse(accepted.unsaved)
        assertEquals(false, accepted.unreflected)
        service.closeIo()
    }
}
