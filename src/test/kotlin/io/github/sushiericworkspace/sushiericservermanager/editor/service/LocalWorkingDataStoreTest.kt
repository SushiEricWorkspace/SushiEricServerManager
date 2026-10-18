package io.github.sushiericworkspace.sushiericservermanager.editor.service

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptor
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStoreKind
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.LocalEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResource
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalWorkingDataStoreTest {
    @TempDir lateinit var root: File

    private fun remote(delegate: InMemoryEditorDataStore): EditorDataStore =
        object : EditorDataStore by delegate {
            override val kind = EditorDataStoreKind.REMOTE
            override val documentSource = EditorDocumentSource.Server("profile", "host/root")
            override fun <T : ManagedData<T, *>> save(descriptor: EditorDataDescriptor<T>, id: String, data: T): StoreResult<Unit> =
                error("サーバー保存を呼び出しました。")
            override fun <T : ManagedData<T, *>> rename(descriptor: EditorDataDescriptor<T>, oldId: String, newName: String): StoreResult<Unit> =
                error("サーバー改名を呼び出しました。")
            override fun <T : ManagedData<T, *>> delete(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<Unit> =
                error("サーバー削除を呼び出しました。")
            override fun writeText(relativePath: String, text: String): StoreResult<Unit> =
                error("サーバー設定保存を呼び出しました。")
        }

    @Test
    fun saveRenameDeleteAndTextKeepRemoteSourceUnchanged() {
        val descriptor = EditorDataDescriptors.ore
        val original = descriptor.createDefault("same")
        val source = InMemoryEditorDataStore()
        source.save(descriptor, "same", original)
        val service = EditorDataService(remote(source), root.resolve("autosave"))
        val loaded = (service.ores.loadStore("same") as StoreResult.Success).value
        loaded.hardness = 22.5
        assertTrue(service.ores.saveStore("same", loaded) is StoreResult.Success)
        assertTrue(service.store.rename(descriptor, "same", "renamed") is StoreResult.Success)
        assertTrue(service.store.delete(descriptor, "renamed") is StoreResult.Success)
        assertTrue(service.modConfig.save("invalid: [") is StoreResult.Success)
        assertEquals(original.hardness, (source.load(descriptor, "same") as StoreResult.Success).value.hardness)
        assertEquals(listOf("same"), (source.list(descriptor) as StoreResult.Success).value.map { it.id })
        assertEquals("invalid: [", (service.modConfig.load() as StoreResult.Success).value)
        service.closeIo()
    }

    @Test
    fun selectingWorkingCopyDoesNotFetchAnotherServerValue() {
        val descriptor = EditorDataDescriptors.ore
        val source = InMemoryEditorDataStore()
        source.save(descriptor, "same", descriptor.createDefault("same"))
        val service = EditorDataService(remote(source), root.resolve("autosave"))
        val first = (service.ores.loadStore("same") as StoreResult.Success).value
        source.save(descriptor, "same", first.deepCopy().apply { hardness = 99.0 })
        assertEquals(first.hardness, (service.ores.loadStore("same") as StoreResult.Success).value.hardness)
        assertEquals(99.0, (service.ores.fetchSource("same") as StoreResult.Success).value.hardness)
        service.closeIo()
    }

    @Test
    fun importingOfflineDataAndBackupDoesNotChangeOriginalFiles() {
        val descriptor = EditorDataDescriptors.ore
        val offline = root.resolve("offline")
        val original = offline.resolve(descriptor.relativeDirectory).resolve("same.yml")
        original.parentFile.mkdirs()
        descriptor.saveBackup(original, descriptor.createDefault("same"))
        val autosave = root.resolve("autosave")
        val editing = autosave.resolve("offline").resolve(descriptor.dataType.categoryDirName).resolve("editing/same.yml")
        val backupOriginal = editing.parentFile.parentFile.resolve("original/same.yml")
        editing.parentFile.mkdirs(); backupOriginal.parentFile.mkdirs()
        descriptor.saveBackup(editing, descriptor.createDefault("same").apply { hardness = 12.5 })
        descriptor.saveBackup(backupOriginal, descriptor.createDefault("same"))
        val before = listOf(original, editing, backupOriginal).associateWith { it.readBytes().toList() to it.lastModified() }
        val service = EditorDataService(LocalEditorDataStore(offline), autosave)
        assertTrue(service.prepareLocalDocuments() is StoreResult.Success)
        val record = (service.ores.localDocuments() as StoreResult.Success).value.single()
        assertEquals(12.5, service.ores.decodeDocument("same", requireNotNull(record.editing)).hardness)
        assertNotEquals(record.editing, record.saved)
        before.forEach { (file, expected) -> assertEquals(expected, file.readBytes().toList() to file.lastModified()) }
        service.closeIo()
    }

    @Test
    fun renameCheckpointPublishesOldAndNewKeysInOneGeneration() {
        val source = InMemoryEditorDataStore()
        val descriptor = EditorDataDescriptors.ore
        source.save(descriptor, "same", descriptor.createDefault("same"))
        val service = EditorDataService(remote(source), root.resolve("autosave"))
        val loaded = (service.ores.loadStore("same") as StoreResult.Success).value
        val before = (service.documents.read() as StoreResult.Success).value
        val operation = PendingStoreOperationRecord(PendingStoreOperationKind.RENAME, "next", "same")
        val previous = before.documents.single { it.key.id == "same" }
        val versions = mapOf("same" to LocalDocumentVersion(previous.key, previous.revision),
            "next" to LocalDocumentVersion(service.ores.documentKey("next"), 0))
        assertTrue(service.ores.saveLocalDocument("next", loaded.deepCopy().apply { id = "next" },
            emptyMap(), operation, versions = versions) is StoreResult.Success)
        val after = (service.documents.read() as StoreResult.Success).value
        assertEquals(before.generation + 1, after.generation)
        assertEquals("next", after.documents.single { it.key.id == "same" }.movedTo)
        assertEquals(operation, after.documents.single { it.key.id == "next" }.savedPending)
        service.closeIo()
    }
    @Test
    fun incompleteLegacyDataOfAllFourTypesIsImportedWithoutChangingOriginals() {
        val offline = root.resolve("all-offline")
        val autosave = root.resolve("all-autosave")
        fun <T : ManagedData<T, *>> original(descriptor: EditorDataDescriptor<T>) {
            val category = autosave.resolve("offline").resolve(descriptor.dataType.categoryDirName)
            listOf("editing", "original").forEach { layer ->
                val file = category.resolve(layer).resolve("tab.same.yml")
                file.parentFile.mkdirs()
                descriptor.saveBackup(file, descriptor.createDefault("tab.same"))
                assertTrue(descriptor.loadBackup(file, file.parentFile) != null, descriptor.dataType.categoryDirName)
            }
        }
        original(EditorDataDescriptors.item)
        original(EditorDataDescriptors.ore)
        original(EditorDataDescriptors.shop)
        original(EditorDataDescriptors.recipe)
        val before = autosave.resolve("offline").walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(autosave).path to (it.readBytes().toList() to it.lastModified())
        }
        val service = EditorDataService(LocalEditorDataStore(offline), autosave)
        assertTrue(service.prepareLocalDocuments() is StoreResult.Success)
        listOf(service.items.localDocuments(), service.ores.localDocuments(),
            service.shops.localDocuments(), service.recipes.localDocuments()).forEach {
            val record = (it as StoreResult.Success).value.single()
            assertTrue(record.editing != null)
            assertEquals(null, record.saved)
            assertTrue(record.unsaved)
        }
        assertEquals(false, offline.exists())
        assertEquals(before.keys, autosave.resolve("offline").walkTopDown().filter { it.isFile }.map { it.relativeTo(autosave).path }.toSet())
        before.forEach { (path, expected) ->
            val file = autosave.resolve(path)
            assertEquals(expected, file.readBytes().toList() to file.lastModified())
        }
        service.closeIo()
    }

    @Test
    fun delayedInitialImportCannotReplaceAnAlreadyInitializedWorkingGeneration() {
        val source = InMemoryEditorDataStore()
        val descriptor = EditorDataDescriptors.ore
        source.save(descriptor, "same", descriptor.createDefault("same"))
        val shared = remote(source)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val delayed = object : EditorDataStore by shared {
            override fun <T : ManagedData<T, *>> list(descriptor: EditorDataDescriptor<T>): StoreResult<List<StoreResource>> {
                val listed = shared.list(descriptor)
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                return listed
            }
        }
        val first = EditorDataService(delayed, root.resolve("autosave"))
        val second = EditorDataService(shared, root.resolve("autosave"))
        val loading = first.submitIo { first.ores.listStoreResources() }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(second.ores.listStoreResources() is StoreResult.Success)
            assertTrue(second.store.createDirectory(descriptor, "new.folder") is StoreResult.Success)
            val latest = (second.documents.read() as StoreResult.Success).value
            release.countDown()
            assertTrue(loading.get(10, TimeUnit.SECONDS) is StoreResult.Success)
            assertEquals(latest, (first.documents.read() as StoreResult.Success).value)
            assertEquals(listOf("new", "new.folder"), (first.ores.listDirectories() as StoreResult.Success).value)
        } finally {
            release.countDown()
            first.closeIo()
            second.closeIo()
        }
    }

}
