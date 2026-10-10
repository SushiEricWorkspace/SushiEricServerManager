package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.stats.player.StatsType
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.ItemDataMerger
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class LocalEditImportTest {
    @Test
    fun `既存と新規を保存せず指定ディレクトリへ読み込みローカルを保持する`() {
        val root = createTempDirectory("local-edit-import").toFile().canonicalFile
        try {
            val local = LocalEditorDataStore(root)
            val descriptor = EditorDataDescriptors.item
            local.save(descriptor, "existing", item("existing", "Local"))
            local.save(descriptor, "new", item("new", "New"))
            val remote = InMemoryEditorDataStore("server")
            remote.save(descriptor, "folder.existing", item("folder.existing", "Remote"))
            val existing = UploadKey(UploadDataCategory.ITEM, "existing")
            val new = UploadKey(UploadDataCategory.ITEM, "new")

            val result = OfflineUploadService(root, SaveForbiddenStore(remote)).loadForEditing(
                descriptor, setOf(existing, new), setOf(existing), "folder"
            )

            assertTrue(result.failed.isEmpty())
            val imported = result.entries.single { it.key == existing }
            assertEquals("folder.existing", imported.data.id)
            assertEquals("Local", imported.data.display.displayName)
            assertEquals("Remote", imported.original?.display?.displayName)
            assertNull(result.entries.single { it.key == new }.original)
            imported.data.display.displayName = "Edited"
            assertEquals("Remote", imported.original?.display?.displayName)
            assertEquals("Remote", remote.load(descriptor, "folder.existing").success().display.displayName)
            assertIs<StoreResult.Failure>(remote.load(descriptor, "folder.new"))
            assertEquals("Local", local.load(descriptor, "existing").success().display.displayName)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `選択後に追加された宛先は未承認の上書きとして拒否する`() {
        val root = createTempDirectory("local-edit-race").toFile().canonicalFile
        try {
            val descriptor = EditorDataDescriptors.item
            LocalEditorDataStore(root).save(descriptor, "same", item("same", "Local"))
            val remote = InMemoryEditorDataStore("server")
            val service = OfflineUploadService(root, SaveForbiddenStore(remote))
            assertIs<UploadScanResult.Success>(service.scan(UploadDataCategory.ITEM))
            remote.save(descriptor, "same", item("same", "Remote"))

            val result = service.loadForEditing(
                descriptor, setOf(UploadKey(UploadDataCategory.ITEM, "same")), emptySet(), ""
            )

            assertTrue(result.entries.isEmpty())
            assertEquals(StoreErrorCode.ALREADY_EXISTS, result.failed.single().error.code)
            assertEquals("Remote", remote.load(descriptor, "same").success().display.displayName)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `取り込み時の元データを基準に後続のサーバー変更をマージできる`() {
        val root = createTempDirectory("local-edit-merge").toFile().canonicalFile
        try {
            val descriptor = EditorDataDescriptors.item
            val base = item("same", "Original")
            LocalEditorDataStore(root).save(
                descriptor, "same", base.deepCopy().apply { display.displayName = "Local" }
            )
            val remote = InMemoryEditorDataStore("server")
            remote.save(descriptor, "same", base)
            val key = UploadKey(UploadDataCategory.ITEM, "same")
            val imported = OfflineUploadService(root, SaveForbiddenStore(remote)).loadForEditing(
                descriptor, setOf(key), setOf(key), ""
            ).entries.single()
            val latest = base.deepCopy().apply { stats[StatsType.PHYSICS_DAMAGE] = 16.0 }
            remote.save(descriptor, "same", latest)

            val merge = ItemDataMerger.merge(requireNotNull(imported.original), imported.data, latest)

            assertTrue(merge.conflicts.isEmpty())
            assertEquals("Local", merge.merged.display.displayName)
            assertEquals(16.0, merge.merged.stats[StatsType.PHYSICS_DAMAGE])
            assertEquals("Original", remote.load(descriptor, "same").success().display.displayName)
            val conflict = ItemDataMerger.merge(
                requireNotNull(imported.original), imported.data,
                latest.deepCopy().apply { display.displayName = "Other" }
            )
            assertTrue(conflict.conflicts.isNotEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun item(id: String, name: String) =
        MutableItemBaseData(id = id).apply { display.displayName = name }

    private fun <T> StoreResult<T>.success(): T = assertIs<StoreResult.Success<T>>(this).value

    private class SaveForbiddenStore(delegate: EditorDataStore) : EditorDataStore by delegate {
        override fun <T : ManagedData<T, *>> save(
            descriptor: EditorDataDescriptor<T>, id: String, data: T
        ): StoreResult<Unit> = error("読み込み処理からサーバーへ保存してはいけません")
    }
}
