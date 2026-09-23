package io.github.sushiericworkspace.sushiericservermanager.editor.service

import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.DeleteResult
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.LoadResult
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.RenameResult
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
    fun `保存待ち操作を自動保存領域へ保存して再読み込みできる`() {
        val backupRoot = createTempDirectory("editor-pending-operations").toFile()
        try {
            val access = EditorDataService(
                InMemoryEditorDataStore("pending-operation-test"),
                backupRoot
            ).items
            val records = listOf(
                PendingStoreOperationRecord(PendingStoreOperationKind.CREATE, "new_item"),
                PendingStoreOperationRecord(PendingStoreOperationKind.DELETE, "old_item", "old_item"),
                PendingStoreOperationRecord(PendingStoreOperationKind.RENAME, "renamed_item", "source_item")
            )

            assertEquals(true, access.savePendingStoreOperations(records))
            assertEquals(records, access.loadPendingStoreOperations())
            assertEquals(true, access.savePendingStoreOperations(emptyList()))
            assertEquals(emptyList(), access.loadPendingStoreOperations())
        } finally {
            backupRoot.deleteRecursively()
        }
    }

    @Test
    fun `完全ID変更で別ディレクトリへ移動してファイル名も変更する`() {
        val backupRoot = createTempDirectory("editor-backup-rename-to").toFile()
        try {
            val oldId = "combat.old_sword"
            val newId = "archive.renamed_sword"
            val data = MutableItemBaseData(id = oldId)
            val store = InMemoryEditorDataStore("rename-to-test")
            store.save(EditorDataDescriptors.item, oldId, data)
            val access = EditorDataService(store, backupRoot).items

            assertEquals(RenameResult.SUCCESS, access.renameTo(oldId, newId))
            assertEquals(LoadResult.FILE_NOT_FOUND, access.load(oldId).second)
            val renamed = assertNotNull(access.load(newId).first)
            assertEquals(newId, renamed.id)
        } finally {
            backupRoot.deleteRecursively()
        }
    }

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

    @Test
    fun `サーバー未保存データの自動保存ペアを明示的に破棄できる`() {
        val backupRoot = createTempDirectory("editor-backup-discard").toFile()
        try {
            val id = "draft.unsaved_item"
            val data = MutableItemBaseData(id = id)
            val access = EditorDataService(InMemoryEditorDataStore("backup-discard-test"), backupRoot).items
            access.saveToLocalBackup(id, "editing", data)
            access.saveToLocalBackup(id, "original", data)

            assertEquals(DeleteResult.FILE_NOT_FOUND, access.delete(id))
            assertNotNull(access.loadBackupPair(id))

            access.deleteLocalBackup(id)

            assertNull(access.loadBackupPair(id))
        } finally {
            backupRoot.deleteRecursively()
        }
    }
}
