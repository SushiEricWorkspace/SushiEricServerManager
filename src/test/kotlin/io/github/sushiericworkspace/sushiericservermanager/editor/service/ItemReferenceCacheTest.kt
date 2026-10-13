package io.github.sushiericworkspace.sushiericservermanager.editor.service

import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.DeleteResult
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.RenameResult
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ItemReferenceCacheTest {
    private fun item(id: String) = MutableItemBaseData(id = id, internalId = ItemInternalId(id))

    @Test
    fun `同時要求をまとめて読込済み一覧を再利用し手動更新だけ再取得する`() = withService { service, store ->
        val data = item("first")
        store.memory.save(EditorDataDescriptors.item, data.id, data)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        store.afterLoad = { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        val first = service.itemReferences.load()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertSame(first, service.itemReferences.load())
            assertSame(first, service.itemReferences.load(force = true))
        } finally { release.countDown() }
        assertIs<StoreResult.Success<*>>(first.get(5, TimeUnit.SECONDS))
        service.itemReferences.load().get(5, TimeUnit.SECONDS)
        assertEquals(1, store.lists.get())
        assertEquals(1, store.loads.get())
        store.memory.save(EditorDataDescriptors.item, "second", item("second"))
        assertEquals(listOf("first"), service.itemReferences.snapshot.items.map { it.id })
        service.itemReferences.load(force = true).get(5, TimeUnit.SECONDS)
        assertEquals(setOf("first", "second"), service.itemReferences.snapshot.items.map { it.id }.toSet())
        assertEquals(2, store.lists.get())
    }

    @Test
    fun `保存した不変定義と名前変更と移動と削除を追加読込なしで共有する`() = withService { service, store ->
        service.itemReferences.load().get(5, TimeUnit.SECONDS)
        val data = item("original")
        data.itemDetail.maxStackSize = 2
        assertIs<StoreResult.Success<Unit>>(service.items.saveStore(data.id, data))
        data.itemDetail.maxStackSize = 64
        assertEquals(2, service.itemReferences.snapshot.items.single().itemDetail.maxStackSize)
        assertEquals(RenameResult.SUCCESS, service.items.renameTo("original", "renamed"))
        assertEquals("renamed", service.itemReferences.snapshot.items.single().id)
        assertEquals(data.internalId, service.itemReferences.snapshot.items.single().internalId)
        assertIs<StoreResult.Success<String>>(service.items.move("renamed", "folder"))
        assertEquals("folder.renamed", service.itemReferences.snapshot.items.single().id)
        assertEquals(RenameResult.SUCCESS, service.items.renameTo("folder.renamed", "other.final"))
        assertEquals("other.final", service.itemReferences.snapshot.items.single().id)
        assertEquals(DeleteResult.SUCCESS, service.items.delete("other.final"))
        assertTrue(service.itemReferences.snapshot.items.isEmpty())
        assertEquals(1, store.lists.get())
        assertEquals(0, store.loads.get())
    }

    @Test
    fun `読み込み中の古い定義が保存と名前変更の結果を上書きしない`() = withService { service, store ->
        val data = item("original")
        store.memory.save(EditorDataDescriptors.item, data.id, data)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        store.afterLoad = { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        val loading = service.itemReferences.load()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            data.itemDetail.maxStackSize = 2
            val writing = service.submitIo {
                service.items.saveStore(data.id, data)
                service.items.renameTo("original", "renamed")
            }
            assertFalse(writing.isDone)
        } finally { release.countDown() }
        assertIs<StoreResult.Success<*>>(loading.get(5, TimeUnit.SECONDS))
        service.submitIo {}.get(5, TimeUnit.SECONDS)
        assertEquals("renamed", service.itemReferences.snapshot.items.single().id)
        assertEquals(2, service.itemReferences.snapshot.items.single().itemDetail.maxStackSize)
    }

    @Test
    fun `一覧取得中の削除を直列実行し削除後の参照を維持する`() = withService { service, store ->
        store.memory.save(EditorDataDescriptors.item, "deleted", item("deleted"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        store.afterList = { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        val loading = service.itemReferences.load()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val deletion = service.submitIo { service.items.delete("deleted") }
            assertFalse(deletion.isDone)
        } finally { release.countDown() }
        assertIs<StoreResult.Success<*>>(loading.get(5, TimeUnit.SECONDS))
        service.submitIo {}.get(5, TimeUnit.SECONDS)
        assertEquals(ItemReferenceStatus.READY, service.itemReferences.snapshot.status)
        assertTrue(service.itemReferences.snapshot.items.isEmpty())
        assertEquals(1, store.lists.get())
    }

    @Test
    fun `一部の読込失敗を完全な一覧として公開せず再試行できる`() = withService { service, store ->
        store.memory.save(EditorDataDescriptors.item, "first", item("first"))
        store.memory.save(EditorDataDescriptors.item, "second", item("second"))
        store.failedId = "second"
        assertIs<StoreResult.Failure>(service.itemReferences.load().get(5, TimeUnit.SECONDS))
        assertEquals(ItemReferenceStatus.FAILED, service.itemReferences.snapshot.status)
        assertTrue(service.itemReferences.snapshot.items.isEmpty())
        store.failedId = null
        assertIs<StoreResult.Success<*>>(service.itemReferences.load().get(5, TimeUnit.SECONDS))
        assertEquals(2, service.itemReferences.snapshot.items.size)
    }

    @Test
    fun `失敗した保存と削除と名前変更はキャッシュに反映しない`() = withService { service, store ->
        val data = item("saved")
        store.memory.save(EditorDataDescriptors.item, data.id, data)
        service.itemReferences.load().get(5, TimeUnit.SECONDS)
        store.failWrites = true
        data.itemDetail.maxStackSize = 2
        assertIs<StoreResult.Failure>(service.items.saveStore(data.id, data))
        assertEquals(DeleteResult.FAILED, service.items.delete(data.id))
        assertEquals(RenameResult.FAILED, service.items.renameTo(data.id, "renamed"))
        assertEquals("saved", service.itemReferences.snapshot.items.single().id)
        assertNotEquals(2, service.itemReferences.snapshot.items.single().itemDetail.maxStackSize)
    }

    @Test
    fun `終了後に古い接続の読込結果を公開せず同名接続へ持ち越さない`() = withService { service, store ->
        store.memory.save(EditorDataDescriptors.item, "old", item("old"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        store.afterLoad = { entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) }
        val loading = service.itemReferences.load()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            service.itemReferences.close()
            assertIs<StoreResult.Failure>(loading.get(5, TimeUnit.SECONDS))
        } finally { release.countDown() }
        service.submitIo {}.get(5, TimeUnit.SECONDS)
        assertEquals(ItemReferenceStatus.EMPTY, service.itemReferences.snapshot.status)
        assertTrue(service.itemReferences.snapshot.items.isEmpty())
        val next = EditorDataService(InMemoryEditorDataStore(store.identity))
        try {
            next.itemReferences.load().get(5, TimeUnit.SECONDS)
            assertTrue(next.itemReferences.snapshot.items.isEmpty())
        } finally { next.itemReferences.close(); next.closeIo() }
    }

    private fun withService(action: (EditorDataService, CountingStore) -> Unit) {
        val directory = createTempDirectory("item-reference-cache").toFile()
        val store = CountingStore()
        val service = EditorDataService(store, directory)
        try { action(service, store) }
        finally { service.itemReferences.close(); service.closeIo(); directory.deleteRecursively() }
    }

    private class CountingStore(val memory: InMemoryEditorDataStore = InMemoryEditorDataStore()) : EditorDataStore by memory {
        val lists = AtomicInteger()
        val loads = AtomicInteger()
        var afterLoad: (() -> Unit)? = null
        var afterList: (() -> Unit)? = null
        var failedId: String? = null
        var failWrites = false
        override fun <T : ManagedData<T, *>> list(descriptor: EditorDataDescriptor<T>): StoreResult<List<StoreResource>> {
            val result = memory.list(descriptor)
            if (descriptor == EditorDataDescriptors.item) { lists.incrementAndGet(); afterList?.invoke() }
            return result
        }
        override fun <T : ManagedData<T, *>> load(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<T> {
            if (descriptor == EditorDataDescriptors.item) loads.incrementAndGet()
            if (id == failedId) return StoreResult.Failure(StoreError(StoreErrorCode.INVALID_YAML, id))
            val result = memory.load(descriptor, id)
            afterLoad?.invoke()
            return result
        }
        override fun <T : ManagedData<T, *>> save(descriptor: EditorDataDescriptor<T>, id: String, data: T): StoreResult<Unit> =
            if (failWrites) StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, id)) else memory.save(descriptor, id, data)
        override fun <T : ManagedData<T, *>> delete(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<Unit> =
            if (failWrites) StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, id)) else memory.delete(descriptor, id)
        override fun <T : ManagedData<T, *>> rename(descriptor: EditorDataDescriptor<T>, oldId: String, newName: String): StoreResult<Unit> =
            if (failWrites) StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, oldId)) else memory.rename(descriptor, oldId, newName)
    }
}
