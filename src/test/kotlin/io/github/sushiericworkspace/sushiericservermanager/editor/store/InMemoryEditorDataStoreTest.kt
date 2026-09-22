package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.data.item.model.mutable.MutablePlainTextLoreSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class InMemoryEditorDataStoreTest {
    @Test
    fun `CRUDとdeepCopyを維持する`() {
        val store = InMemoryEditorDataStore()
        val descriptor = EditorDataDescriptors.item
        val source = MutableItemBaseData(id = "sword").apply {
            display.displayName = "Sword"
        }

        assertIs<StoreResult.Success<Unit>>(store.save(descriptor, "sword", source))
        val loaded = assertIs<StoreResult.Success<MutableItemBaseData>>(store.load(descriptor, "sword")).value
        assertNotSame(source, loaded)
        assertEquals("Sword", loaded.display.displayName)
        assertEquals(source.internalId, loaded.internalId)

        assertIs<StoreResult.Success<Unit>>(store.rename(descriptor, "sword", "long_sword"))
        val renamed = assertIs<StoreResult.Success<MutableItemBaseData>>(
            store.load(descriptor, "long_sword")
        ).value
        assertEquals("long_sword", renamed.id)
        assertEquals(source.internalId, renamed.internalId)
        assertEquals(
            listOf("long_sword"),
            assertIs<StoreResult.Success<List<StoreResource>>>(store.list(descriptor)).value.map { it.id }
        )
        assertIs<StoreResult.Success<Unit>>(store.delete(descriptor, "long_sword"))
        assertIs<StoreResult.Failure>(store.load(descriptor, "long_sword"))
    }

    @Test
    fun `新規作成と複製で異なるinternalIdを生成する`() {
        val descriptor = EditorDataDescriptors.item
        val first = descriptor.createDefault("first")
        val second = descriptor.createDefault("second")

        assertNotEquals(first.internalId, second.internalId)

        first.display.displayName = "Sword"
        first.display.mutableLore.add(mutableListOf(MutablePlainTextLoreSection("複製対象のLore")))
        first.editorMeta.comment.add("original")
        val duplicate = descriptor.duplicateAsNew(first, "copied_sword")

        assertEquals("copied_sword", duplicate.id)
        assertNotEquals(first.internalId, duplicate.internalId)
        assertEquals(first.display, duplicate.display)
        assertEquals("複製対象のLore", (duplicate.display.mutableLore.single().single() as MutablePlainTextLoreSection).text)
        assertNotSame(first.display, duplicate.display)
        assertNotSame(first.display.mutableLore, duplicate.display.mutableLore)
        assertNotSame(first.display.mutableLore.single(), duplicate.display.mutableLore.single())
        assertNotSame(first.editorMeta.comment, duplicate.editorMeta.comment)
    }

    @Test
    fun `不正IDを拒否する`() {
        val result = InMemoryEditorDataStore().save(
            EditorDataDescriptors.item,
            "../outside",
            MutableItemBaseData(id = "../outside")
        )

        assertEquals(StoreErrorCode.INVALID_ID, assertIs<StoreResult.Failure>(result).error.code)
    }

    @Test
    fun `完全IDを再帰一覧して同じ葉名を別ディレクトリへ保存できる`() {
        val store = InMemoryEditorDataStore()
        val descriptor = EditorDataDescriptors.item

        assertIs<StoreResult.Success<Unit>>(
            store.save(descriptor, "combat.sword.shared", MutableItemBaseData(id = "combat.sword.shared"))
        )
        assertIs<StoreResult.Success<Unit>>(
            store.save(descriptor, "mining.shared", MutableItemBaseData(id = "mining.shared"))
        )

        val resources = assertIs<StoreResult.Success<List<StoreResource>>>(store.list(descriptor)).value
        assertEquals(listOf("combat.sword.shared", "mining.shared"), resources.map(StoreResource::id))
        assertEquals("combat.sword", resources.first().directory)
        assertEquals("shared", resources.first().name)
        assertEquals(listOf("combat", "combat.sword", "mining"),
            assertIs<StoreResult.Success<List<String>>>(store.listDirectories(descriptor)).value)
    }

    @Test
    fun `完全IDの葉名変更とディレクトリ移動と再帰削除を行う`() {
        val store = InMemoryEditorDataStore()
        val descriptor = EditorDataDescriptors.item
        store.save(descriptor, "combat.sword.test", MutableItemBaseData(id = "combat.sword.test"))
        store.createDirectory(descriptor, "archive.items")
        assertTrue(
            "archive.items" in assertIs<StoreResult.Success<List<String>>>(
                store.listDirectories(descriptor)
            ).value
        )

        assertIs<StoreResult.Success<Unit>>(store.rename(descriptor, "combat.sword.test", "renamed"))
        val moved = assertIs<StoreResult.Success<String>>(
            store.move(descriptor, "combat.sword.renamed", "archive.items")
        )
        assertEquals("archive.items.renamed", moved.value)
        assertEquals("archive.items.renamed", assertIs<StoreResult.Success<MutableItemBaseData>>(
            store.load(descriptor, moved.value)
        ).value.id)

        assertIs<StoreResult.Success<Unit>>(store.deleteDirectory(descriptor, "archive"))
        assertTrue(assertIs<StoreResult.Success<List<StoreResource>>>(store.list(descriptor)).value.isEmpty())
    }

    @Test
    fun `移動先の同じ葉名との重複を拒否する`() {
        val store = InMemoryEditorDataStore()
        val descriptor = EditorDataDescriptors.item
        store.save(descriptor, "combat.shared", MutableItemBaseData(id = "combat.shared"))
        store.save(descriptor, "mining.shared", MutableItemBaseData(id = "mining.shared"))

        val result = store.move(descriptor, "mining.shared", "combat")

        assertEquals(StoreErrorCode.ALREADY_EXISTS, assertIs<StoreResult.Failure>(result).error.code)
        assertIs<StoreResult.Success<MutableItemBaseData>>(store.load(descriptor, "mining.shared"))
    }
}
