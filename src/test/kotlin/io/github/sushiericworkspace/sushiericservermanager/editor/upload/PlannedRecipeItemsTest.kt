package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseDataView
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.DataFieldPath
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentKey
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentSource
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.LocalEditorDataStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class PlannedRecipeItemsTest {
    @TempDir lateinit var root: File

    private fun candidate(id: String, merge: SavedUploadMerge = SavedUploadMerge(emptyList()) { null },
                          old: String? = null, deleted: Boolean = false, resolve: (Set<DataFieldPath>) -> ItemBaseDataView?): SavedUploadCandidate {
        val input = EditorDocumentKey(EditorDocumentSource.Local, EditorDataDescriptors.item.dataType.categoryDirName, id)
        val target = input.copy(source = EditorDocumentSource.Server("profile", "host/root"))
        return SavedUploadCandidate(SavedUploadDocument(input, 1, null, deleted, emptyMap()), target,
            old?.let { target.copy(id = it) }, null, merge, true, itemPreview = resolve)
    }

    @Test
    fun `同batchの新規更新改名削除だけを最新serverへ重ね元catalogを変更しない`() {
        val old = MutableItemBaseData(id = "old")
        val deleted = MutableItemBaseData(id = "deleted")
        val untouched = MutableItemBaseData(id = "untouched")
        val added = MutableItemBaseData(id = "new")
        val renamed = old.deepCopy().apply { id = "renamed"; itemDetail.maxStackSize = 16 }
        val server = listOf(old, deleted, untouched).associateBy { it.internalId }
        val inputs = listOf(candidate("renamed", old = "old") { renamed }, candidate("deleted") { null }, candidate("new") { added })
        val planned = plannedUploadItems(server, inputs, emptyMap())!!
        assertEquals(setOf("renamed", "untouched", "new"), planned.values.map { it.id }.toSet())
        assertEquals(16, planned.getValue(old.internalId).itemDetail.maxStackSize)
        assertEquals(setOf("old", "deleted", "untouched"), server.values.map { it.id }.toSet())
        assertNull(plannedUploadItems(null, inputs, emptyMap()))
    }

    @Test
    fun `改名保留後の削除をserver側採用した場合は旧publicIDを維持し新targetを生成しない`() {
        val old = MutableItemBaseData(id = "old")
        val server = mapOf(old.internalId to old)
        val merge = SavedUploadMerges.text("before", null, "server", true)
        val input = candidate("new", merge, old = "old", deleted = true) { paths ->
            if (merge.resolveWithLocal(paths) == null) null else old.deepCopy()
        }
        val path = merge.conflicts.single().path
        assertNull(plannedUploadItems(server, listOf(input), emptyMap()))
        assertEquals("old", plannedUploadItems(server, listOf(input), mapOf(input.input.key to emptySet()))!!.values.single().id)
        assertTrue(plannedUploadItems(server, listOf(input), mapOf(input.input.key to setOf(path)))!!.isEmpty())
        assertEquals("old", old.id)
    }

    @Test
    fun `Item競合未選択は未確定で選択を変えると同じ正式merge結果へ切り替わる`() {
        val service = EditorDataService(LocalEditorDataStore(root.resolve("files")), root.resolve("documents"))
        try {
            val access = service.items
            val base = access.createDefault("item")
            val local = base.deepCopy().apply { itemDetail.maxStackSize = 16 }
            val remote = base.deepCopy().apply { itemDetail.maxStackSize = 32 }
            val merge = SavedUploadMerges.typed(access, base.id, access.encodeDocument(base), access.encodeDocument(local), access.encodeDocument(remote), true)
            val input = candidate(base.id, merge) { merge.previewWithLocal!!.invoke(it) as ItemBaseDataView }
            val server = mapOf(remote.internalId to remote)
            assertNull(plannedUploadItems(server, listOf(input), emptyMap()))
            val path = merge.conflicts.single().path
            for (paths in listOf(emptySet(), setOf(path), emptySet())) {
                val planned = plannedUploadItems(server, listOf(input), mapOf(input.input.key to paths))!!
                assertEquals(access.decodeDocument(base.id, merge.resolveWithLocal(paths)!!).itemDetail.maxStackSize,
                    planned.getValue(base.internalId).itemDetail.maxStackSize)
            }
            assertEquals(32, remote.itemDetail.maxStackSize)
            assertEquals(16, local.itemDetail.maxStackSize)
        } finally { service.closeIo() }
    }

    @Test
    fun `比較元未取得の文書全体選択も未選択を隠さず採用側の定義を使う`() {
        val local = MutableItemBaseData(id = "item")
        val remote = local.deepCopy().apply { itemDetail.maxStackSize = 16 }
        val merge = SavedUploadMerges.text(null, "local", "remote", false)
        val input = candidate("item", merge) { if (merge.resolveWithLocal(it) == "local") local else remote }
        val server = mapOf(remote.internalId to remote)
        assertNull(plannedUploadItems(server, listOf(input), emptyMap()))
        assertEquals(16, plannedUploadItems(server, listOf(input), mapOf(input.input.key to emptySet()))!!.getValue(local.internalId).itemDetail.maxStackSize)
        assertEquals(local.itemDetail.maxStackSize, plannedUploadItems(server, listOf(input), mapOf(input.input.key to setOf(merge.conflicts.single().path)))!!.getValue(local.internalId).itemDetail.maxStackSize)
    }
}
