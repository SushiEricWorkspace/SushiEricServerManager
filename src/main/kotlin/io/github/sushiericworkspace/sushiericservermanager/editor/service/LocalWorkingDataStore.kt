package io.github.sushiericworkspace.sushiericservermanager.editor.service

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.PublicId
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptor
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStoreKind
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResource
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StorePathValidator
import kotlinx.serialization.json.Json

/** 通信ストアから初回取得した作業コピーを、以降はローカル世代だけで読み書きします。 */
internal class LocalWorkingDataStore(
    private val service: EditorDataService,
    private val source: EditorDataStore
) : EditorDataStore by source {
    override val isAvailable: Boolean get() = true

    private fun snapshot(): LocalDocumentSnapshot = when (val result = service.documents.read()) {
        is StoreResult.Success -> result.value
        is StoreResult.Failure -> throw DocumentFailure(result.error)
    }

    private fun <T : ManagedData<T, *>> access(descriptor: EditorDataDescriptor<T>) = service.documentAccess(descriptor)

    /** 初回取得と既存退避の取込みは原本を変更せず、一世代で確定します。 */
    private fun <T : ManagedData<T, *>> initialize(descriptor: EditorDataDescriptor<T>): LocalDocumentSnapshot {
        val before = snapshot()
        val category = descriptor.dataType.categoryDirName
        if (category in before.initializedCategories) return before
        val importSource = if (source.kind == EditorDataStoreKind.LOCAL) service.localImportStore().value() else source
        val resources = importSource.list(descriptor).value()
        val directories = importSource.listDirectories(descriptor).value().toSet()
        val access = access(descriptor)
        val records = linkedMapOf<String, LocalDocumentRecord>()
        resources.forEach { resource ->
            val data = importSource.load(descriptor, resource.id).value()
            val text = access.encodeDocument(data)
            records[resource.id] = LocalDocumentRecord(access.documentKey(resource.id),
                editing = text, original = text, saved = text,
                baseline = text.takeIf { source.kind == EditorDataStoreKind.REMOTE },
                baselineKnown = source.kind == EditorDataStoreKind.REMOTE)
        }
        if (source.kind != EditorDataStoreKind.REMOTE) {
            val editing = service.autoSaveDirectory.resolve(source.identity).resolve(category).resolve("editing")
            editing.listFiles { file -> file.isFile && file.extension.equals("yml", true) }?.forEach { file ->
                val id = file.nameWithoutExtension
                val originalFile = editing.parentFile.resolve("original").resolve(file.name)
                val pair = checkNotNull(descriptor.loadBackup(file, editing)) { "既存の編集退避を復元できません: $id" } to
                    checkNotNull(descriptor.loadBackup(originalFile, originalFile.parentFile)) { "既存の比較基準を復元できません: $id" }
                val key = access.documentKey(pair.first.id)
                val previous = records[key.id] ?: LocalDocumentRecord(key)
                records[key.id] = previous.copy(editing = access.encodeDocument(pair.first),
                    original = access.encodeDocument(pair.second))
            }
        }
        if (source.kind != EditorDataStoreKind.REMOTE) {
            val pendingFile = service.autoSaveDirectory.resolve(source.identity).resolve(category).resolve("pending-operations.json")
            if (pendingFile.isFile) {
                val pending = Json.decodeFromString<List<PendingStoreOperationRecord>>(pendingFile.readText())
                pending.forEach { operation ->
                    val record = checkNotNull(records[operation.currentId]) { "保存待ち操作に対応する編集文書がありません。" }
                    records[operation.currentId] = record.copy(pending = operation, deleted = operation.kind == PendingStoreOperationKind.DELETE)
                }
            }
        }
        val latest = snapshot()
        if (category in latest.initializedCategories) return latest
        val existing = latest.documents.associateBy { it.key }
        val changes = records.values.mapNotNull { imported ->
            val old = existing[imported.key]
            when {
                old == null -> imported
                old.saved == null && old.savedPending == null && old.pending?.kind != PendingStoreOperationKind.CREATE ->
                    old.copy(saved = imported.saved, baseline = imported.baseline, baselineKnown = imported.baselineKnown)
                else -> null
            }
        }
        return service.documents.publish(changes.associate { it.key to (existing[it.key]?.revision ?: 0L) }, changes,
            mapOf(category to directories), mapOf(category to (latest.directoryRevisions[category] ?: 0L)),
            setOf(category), if (source.kind == EditorDataStoreKind.REMOTE) mapOf(category to directories) else emptyMap()).value()
    }

    override fun <T : ManagedData<T, *>> list(descriptor: EditorDataDescriptor<T>): StoreResult<List<StoreResource>> = result {
        initialize(descriptor).documents.filter {
            it.key.category == descriptor.dataType.categoryDirName && it.saved != null && !it.savedDeleted && it.movedTo == null
        }.map { StoreResource(it.key.id, PublicId.nameOf(it.key.id) + ".yml", it.key.id) }.sortedBy { it.id }
    }

    override fun <T : ManagedData<T, *>> load(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<T> = result {
        val record = initialize(descriptor).documents.firstOrNull { it.key == access(descriptor).documentKey(id) && !it.savedDeleted }
            ?: throw DocumentFailure(StoreError(StoreErrorCode.FILE_NOT_FOUND, id))
        val text = record.saved ?: throw DocumentFailure(StoreError(StoreErrorCode.FILE_NOT_FOUND, id))
        access(descriptor).decodeDocument(id, text)
    }

    override fun <T : ManagedData<T, *>> save(descriptor: EditorDataDescriptor<T>, id: String, data: T): StoreResult<Unit> = result {
        val before = initialize(descriptor)
        val key = access(descriptor).documentKey(id)
        val version = LocalDocumentVersion(key, before.documents.firstOrNull { it.key == key }?.revision ?: 0L)
        access(descriptor).saveLocalDocument(id, data, emptyMap(), versions = mapOf(id to version)).value()
        Unit
    }

    override fun <T : ManagedData<T, *>> rename(descriptor: EditorDataDescriptor<T>, oldId: String, newName: String): StoreResult<Unit> =
        renameTo(descriptor, oldId, PublicId.join(PublicId.directoryOf(oldId), newName))

    override fun <T : ManagedData<T, *>> move(descriptor: EditorDataDescriptor<T>, id: String, targetDirectory: String): StoreResult<String> {
        val newId = PublicId.join(targetDirectory.split('.').filter(String::isNotEmpty), PublicId.nameOf(id))
        return when (val moved = renameTo(descriptor, id, newId)) {
            is StoreResult.Success -> StoreResult.Success(newId)
            is StoreResult.Failure -> moved
        }
    }

    internal fun <T : ManagedData<T, *>> renameTo(descriptor: EditorDataDescriptor<T>, oldId: String, newId: String): StoreResult<Unit> = result {
        require(descriptor.isValidId(oldId) && descriptor.isValidId(newId)) { "IDが不正です。" }
        val before = initialize(descriptor)
        val access = access(descriptor)
        val previous = before.documents.firstOrNull { it.key == access.documentKey(oldId) && !it.savedDeleted }
            ?: throw DocumentFailure(StoreError(StoreErrorCode.FILE_NOT_FOUND, oldId))
        if (oldId == newId) return@result Unit
        check(before.documents.none { it.key == access.documentKey(newId) && !it.savedDeleted }) { "同じIDが存在します。" }
        val nextKey = access.documentKey(newId)
        val destination = before.documents.firstOrNull { it.key == nextKey }
        fun renamed(text: String?): String? = text?.let {
            access.encodeDocument(access.decodeDocument(oldId, it).apply { id = newId })
        }
        val pending = if (previous.savedPending?.kind == PendingStoreOperationKind.CREATE && previous.baseline == null)
            PendingStoreOperationRecord(PendingStoreOperationKind.CREATE, newId)
        else PendingStoreOperationRecord(PendingStoreOperationKind.RENAME, newId,
            previous.savedPending?.sourceId ?: oldId)
        val next = previous.copy(key = nextKey, editing = renamed(previous.editing),
            original = renamed(previous.original), saved = renamed(previous.saved),
            pending = pending, savedPending = pending, deleted = false, savedDeleted = false)
        service.documents.publish(mapOf(previous.key to previous.revision, nextKey to (destination?.revision ?: 0L)),
            listOf(previous.copy(deleted = true, savedDeleted = true, movedTo = newId), next.copy(movedTo = null))).value()
        Unit
    }

    override fun <T : ManagedData<T, *>> delete(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<Unit> = result {
        val before = initialize(descriptor)
        if (before.documents.none { it.key == access(descriptor).documentKey(id) && it.saved != null && !it.savedDeleted })
            throw DocumentFailure(StoreError(StoreErrorCode.FILE_NOT_FOUND, id))
        access(descriptor).updateLocalDocument(id) { previous ->
            val pending = PendingStoreOperationRecord(PendingStoreOperationKind.DELETE, id,
                previous.savedPending?.sourceId ?: id)
            previous.copy(pending = pending, savedPending = pending, deleted = true, savedDeleted = true)
        }.value()
        Unit
    }

    override fun <T : ManagedData<T, *>> listDirectories(descriptor: EditorDataDescriptor<T>): StoreResult<List<String>> = result {
        initialize(descriptor).directories[descriptor.dataType.categoryDirName].orEmpty().sorted()
    }

    override fun <T : ManagedData<T, *>> createDirectory(descriptor: EditorDataDescriptor<T>, directory: String): StoreResult<Unit> = result {
        require(StorePathValidator.isValidId(directory)) { "ディレクトリIDが不正です。" }
        val before = initialize(descriptor)
        val category = descriptor.dataType.categoryDirName
        val parts = directory.split('.')
        val added = parts.indices.map { parts.take(it + 1).joinToString(".") }
        service.documents.publish(emptyMap(), emptyList(),
            mapOf(category to (before.directories[category].orEmpty() + added)),
            mapOf(category to before.directoryRevisions.getValue(category))).value()
        Unit
    }

    override fun <T : ManagedData<T, *>> deleteDirectory(descriptor: EditorDataDescriptor<T>, directory: String): StoreResult<Unit> =
        when (val deleted = deleteDirectorySnapshot(descriptor, directory)) {
            is StoreResult.Success -> StoreResult.Success(Unit)
            is StoreResult.Failure -> deleted
        }

    internal fun <T : ManagedData<T, *>> deleteDirectorySnapshot(
        descriptor: EditorDataDescriptor<T>, directory: String
    ): StoreResult<LocalDocumentSnapshot> = result {
        require(StorePathValidator.isValidId(directory)) { "ディレクトリIDが不正です。" }
        val before = initialize(descriptor)
        val category = descriptor.dataType.categoryDirName
        val changes = before.documents.filter { it.key.category == category && it.key.id.startsWith("$directory.") }.map { previous ->
            val pending = PendingStoreOperationRecord(PendingStoreOperationKind.DELETE, previous.key.id,
                previous.savedPending?.sourceId ?: previous.key.id)
            previous.copy(deleted = true, savedDeleted = true, pending = pending, savedPending = pending)
        }
        service.documents.publish(changes.associate { it.key to it.revision }, changes,
            mapOf(category to before.directories[category].orEmpty().filterNot { it == directory || it.startsWith("$directory.") }.toSet()),
            mapOf(category to before.directoryRevisions.getValue(category))).value()
    }

    override fun readText(relativePath: String): StoreResult<String> = result {
        val key = EditorDocumentKey(service.documentSource, "text", relativePath)
        val record = snapshot().documents.firstOrNull { it.key == key }
        record?.saved ?: run {
            val text = source.readText(relativePath).value()
            val initial = LocalDocumentRecord(key, editing = text, original = text, saved = text,
                baseline = text.takeIf { source.kind == EditorDataStoreKind.REMOTE },
                baselineKnown = source.kind == EditorDataStoreKind.REMOTE)
            service.documents.publish(mapOf(key to 0L), listOf(initial)).value()
            text
        }
    }

    override fun writeText(relativePath: String, text: String): StoreResult<Unit> = result {
        val key = EditorDocumentKey(service.documentSource, "text", relativePath)
        val previous = snapshot().documents.firstOrNull { it.key == key } ?: LocalDocumentRecord(key)
        service.documents.publish(mapOf(key to previous.revision), listOf(previous.copy(
            editing = text, original = text, saved = text))).value()
        Unit
    }

    private class DocumentFailure(val error: StoreError) : RuntimeException(error.detail, error.cause)

    private fun <T> StoreResult<T>.value(): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> throw DocumentFailure(error)
    }

    private fun <T> result(action: () -> T): StoreResult<T> = try {
        StoreResult.Success(action())
    } catch (error: DocumentFailure) {
        StoreResult.Failure(error.error)
    } catch (error: Exception) {
        StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, detail = error.message, cause = error))
    }
}
