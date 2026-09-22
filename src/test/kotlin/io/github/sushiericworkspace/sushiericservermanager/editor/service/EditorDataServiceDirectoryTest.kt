package io.github.sushiericworkspace.sushiericservermanager.editor.service

import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EditorDataServiceDirectoryTest {
    @Test
    fun `移動時に編集用とオリジナルのバックアップを新しい完全IDへ付け替える`() {
        val backupRoot = createTempDirectory("editor-backup-move").toFile()
        try {
            val oldId = "combat.sword.test_sword"
            val data = MutableItemBaseData(id = oldId)
            val store = InMemoryEditorDataStore("backup-test")
            store.save(EditorDataDescriptors.item, oldId, data)
            val access = EditorDataService(store, backupRoot).items
            access.saveToLocalBackup(oldId, "editing", data)
            access.saveToLocalBackup(oldId, "original", data)

            val moved = assertIs<StoreResult.Success<String>>(access.move(oldId, "archive"))

            assertEquals("archive.test_sword", moved.value)
            assertNull(access.loadBackupPair(oldId))
            val movedPair = assertNotNull(access.loadBackupPair(moved.value))
            assertEquals(moved.value, movedPair.first.id)
            assertEquals(moved.value, movedPair.second.id)
        } finally {
            backupRoot.deleteRecursively()
        }
    }
}
