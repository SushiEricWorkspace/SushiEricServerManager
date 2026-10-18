package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericdatamodel.data.drop.model.mutable.MutableDropItemData
import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentSource
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentKey
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SavedUploadReferencesTest {
    @TempDir lateinit var root: File

    @Test
    fun unresolvedItemDeletionIsBlockedIncludingUnselectedRemoteReferences() {
        val memory = InMemoryEditorDataStore()
        val item = EditorDataDescriptors.item.createDefault("item")
        memory.save(EditorDataDescriptors.item, item.id, item)
        val ore = EditorDataDescriptors.ore.createDefault("ore").apply {
            mutableDropItems += MutableDropItemData(item.internalId)
        }
        memory.save(EditorDataDescriptors.ore, ore.id, ore)
        val service = EditorDataService(memory, root)
        try {
            val key = EditorDocumentKey(service.documentSource, service.items.dataType.categoryDirName, item.id)
            assertTrue(SavedUploadReferences(service).projected(listOf(SavedUploadStep(key, "old", null))) is StoreResult.Failure)
            assertTrue(SavedUploadReferences(service).beforeItemDelete(item.id) is StoreResult.Failure)
            assertTrue(memory.load(EditorDataDescriptors.item, item.id) is StoreResult.Success)
        } finally { service.closeIo() }
    }

    @Test
    fun requiredNewItemsAreIncludedInTheProjectedReferenceSet() {
        val memory = InMemoryEditorDataStore()
        val service = EditorDataService(memory, root)
        try {
            val item = service.items.createDefault("new_item")
            val ore = service.ores.createDefault("new_ore").apply { mutableDropItems += MutableDropItemData(item.internalId) }
            val steps = listOf(
                SavedUploadStep(service.items.documentKey(item.id), null, service.items.encodeDocument(item)),
                SavedUploadStep(service.ores.documentKey(ore.id), null, service.ores.encodeDocument(ore)))
            val result = SavedUploadReferences(service).projected(steps)
            assertTrue(result is StoreResult.Success, result.toString())
            val projected = result as StoreResult.Success
            assertTrue(projected.value.load(EditorDataDescriptors.ore, ore.id) is StoreResult.Success)
            assertTrue(memory.list(EditorDataDescriptors.item).let { (it as StoreResult.Success).value.isEmpty() })
        } finally { service.closeIo() }
    }

    @Test
    fun referenceUpdateMustBeAppliedBeforeItemDeletionCanProceed() {
        val memory = InMemoryEditorDataStore()
        val item = EditorDataDescriptors.item.createDefault("item")
        val ore = EditorDataDescriptors.ore.createDefault("ore").apply { mutableDropItems += MutableDropItemData(item.internalId) }
        memory.save(EditorDataDescriptors.item, item.id, item)
        memory.save(EditorDataDescriptors.ore, ore.id, ore)
        val service = EditorDataService(memory, root)
        try {
            val changed = ore.deepCopy().apply { mutableDropItems.clear() }
            val steps = listOf(
                SavedUploadStep(service.ores.documentKey(ore.id), service.ores.encodeDocument(ore), service.ores.encodeDocument(changed)),
                SavedUploadStep(service.items.documentKey(item.id), service.items.encodeDocument(item), null))
            assertTrue(SavedUploadReferences(service).projected(steps) is StoreResult.Success)
            assertTrue(SavedUploadReferences(service).beforeItemDelete(item.id) is StoreResult.Failure)
            memory.save(EditorDataDescriptors.ore, ore.id, changed)
            assertTrue(SavedUploadReferences(service).beforeItemDelete(item.id) is StoreResult.Success)
        } finally { service.closeIo() }
    }

    @Test
    fun failedReferenceReadNeverFallsBackToAnEmptyCatalog() {
        val remote = object : EditorDataStore by InMemoryEditorDataStore() {
            override val kind = EditorDataStoreKind.REMOTE
            override val documentSource = EditorDocumentSource.Server("profile", "root")
            override fun <T : ManagedData<T, *>> list(descriptor: EditorDataDescriptor<T>): StoreResult<List<StoreResource>> =
                StoreResult.Failure(StoreError(StoreErrorCode.STORE_UNAVAILABLE))
        }
        val service = EditorDataService(remote, root)
        try {
            assertTrue(SavedUploadReferences(service).projected(emptyList()) is StoreResult.Failure)
            assertTrue(SavedUploadReferences(service).beforeItemDelete("item") is StoreResult.Failure)
        } finally { service.closeIo() }
    }
}
