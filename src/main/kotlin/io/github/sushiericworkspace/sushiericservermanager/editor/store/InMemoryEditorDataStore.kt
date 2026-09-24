package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.common.data.core.identity.PublicId
import java.util.concurrent.ConcurrentHashMap

class InMemoryEditorDataStore(
    override val identity: String = "memory"
) : EditorDataStore {
    private val entries = ConcurrentHashMap<String, ManagedData<*, *>>()
    private val directories = ConcurrentHashMap.newKeySet<String>()

    override val kind: EditorDataStoreKind = EditorDataStoreKind.IN_MEMORY
    override val isAvailable: Boolean = true

    private val texts = mutableMapOf<String, String>()

    override fun readText(relativePath: String): StoreResult<String> {
        if (!StorePathValidator.isValidRelativePath(relativePath)) {
            return StoreResult.Failure(StoreError(StoreErrorCode.INVALID_ID, relativePath))
        }

        val text = texts[relativePath]
            ?: return StoreResult.Failure(StoreError(StoreErrorCode.FILE_NOT_FOUND, relativePath))

        return StoreResult.Success(text)
    }

    override fun writeText(relativePath: String, text: String): StoreResult<Unit> {
        if (!StorePathValidator.isValidRelativePath(relativePath)) {
            return StoreResult.Failure(StoreError(StoreErrorCode.INVALID_ID, relativePath))
        }

        texts[relativePath] = text

        return StoreResult.Success(Unit)
    }

    override fun <T : ManagedData<T, *>> list(
        descriptor: EditorDataDescriptor<T>
    ): StoreResult<List<StoreResource>> {
        val prefix = "${descriptor.dataType.categoryDirName}/"
        return StoreResult.Success(
            entries.keys
                .asSequence()
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
                .sorted()
                .map {
                    StoreResource(
                        id = it,
                        fileName = "${PublicId.nameOf(it)}.yml",
                        location = "memory://$prefix${PublicId.relativePathOf(it)}.yml"
                    )
                }
                .toList()
        )
    }

    override fun <T : ManagedData<T, *>> load(
        descriptor: EditorDataDescriptor<T>,
        id: String
    ): StoreResult<T> {
        if (!StorePathValidator.isValidId(id)) return invalidId(id)
        val data = entries[key(descriptor, id)]
            ?: return StoreResult.Failure(StoreError(StoreErrorCode.FILE_NOT_FOUND, id))
        @Suppress("UNCHECKED_CAST")
        return StoreResult.Success(descriptor.deepCopy(data as T))
    }

    override fun <T : ManagedData<T, *>> save(
        descriptor: EditorDataDescriptor<T>,
        id: String,
        data: T
    ): StoreResult<Unit> {
        if (!StorePathValidator.isValidId(id)) return invalidId(id)
        entries[key(descriptor, id)] = descriptor.deepCopy(data)
        registerParentDirectories(descriptor, id)
        return StoreResult.Success(Unit)
    }

    override fun <T : ManagedData<T, *>> rename(
        descriptor: EditorDataDescriptor<T>,
        oldId: String,
        newName: String
    ): StoreResult<Unit> {
        if (!StorePathValidator.isValidId(oldId) || !StorePathValidator.isValidName(newName)) {
            return invalidId(newName)
        }
        val targetId = PublicId.join(PublicId.directoryOf(oldId), newName)
        return when (val moved = relocate(descriptor, oldId, targetId)) {
            is StoreResult.Success -> StoreResult.Success(Unit)
            is StoreResult.Failure -> moved
        }
    }

    override fun <T : ManagedData<T, *>> move(
        descriptor: EditorDataDescriptor<T>,
        id: String,
        targetDirectory: String
    ): StoreResult<String> {
        if (!StorePathValidator.isValidId(id) ||
            !StorePathValidator.isValidDirectory(targetDirectory)
        ) {
            return invalidId(id)
        }
        val targetId = PublicId.join(directorySegments(targetDirectory), PublicId.nameOf(id))
        if (targetId == id) return StoreResult.Success(id)
        return relocate(descriptor, id, targetId)
    }

    private fun <T : ManagedData<T, *>> relocate(
        descriptor: EditorDataDescriptor<T>,
        oldId: String,
        newId: String
    ): StoreResult<String> {
        val oldKey = key(descriptor, oldId)
        val newKey = key(descriptor, newId)
        if (entries.containsKey(newKey)) {
            return StoreResult.Failure(StoreError(StoreErrorCode.ALREADY_EXISTS, newId))
        }
        val current = entries[oldKey]
            ?: return StoreResult.Failure(StoreError(StoreErrorCode.FILE_NOT_FOUND, oldId))
        @Suppress("UNCHECKED_CAST")
        val renamed = descriptor.deepCopy(current as T).apply { id = newId }
        entries[newKey] = renamed
        entries.remove(oldKey)
        registerParentDirectories(descriptor, newId)
        return StoreResult.Success(newId)
    }

    override fun <T : ManagedData<T, *>> delete(
        descriptor: EditorDataDescriptor<T>,
        id: String
    ): StoreResult<Unit> {
        if (!StorePathValidator.isValidId(id)) return invalidId(id)
        return if (entries.remove(key(descriptor, id)) != null) {
            StoreResult.Success(Unit)
        } else {
            StoreResult.Failure(StoreError(StoreErrorCode.FILE_NOT_FOUND, id))
        }
    }

    override fun <T : ManagedData<T, *>> createDirectory(
        descriptor: EditorDataDescriptor<T>,
        directory: String
    ): StoreResult<Unit> {
        if (!StorePathValidator.isValidDirectory(directory, allowRoot = false)) {
            return invalidId(directory)
        }
        directorySegments(directory).indices.forEach { index ->
            val parent = directorySegments(directory).take(index + 1).joinToString(".")
            directories += directoryKey(descriptor, parent)
        }
        return StoreResult.Success(Unit)
    }

    override fun <T : ManagedData<T, *>> deleteDirectory(
        descriptor: EditorDataDescriptor<T>,
        directory: String
    ): StoreResult<Unit> {
        if (!StorePathValidator.isValidDirectory(directory, allowRoot = false)) {
            return invalidId(directory)
        }
        val directoryPrefix = directoryKey(descriptor, directory)
        if (directoryPrefix !in directories && entries.keys.none { it.startsWith("$directoryPrefix.") }) {
            return StoreResult.Failure(StoreError(StoreErrorCode.FILE_NOT_FOUND, directory))
        }
        entries.keys.removeIf { it.startsWith("$directoryPrefix.") }
        directories.removeIf { it == directoryPrefix || it.startsWith("$directoryPrefix.") }
        return StoreResult.Success(Unit)
    }

    override fun <T : ManagedData<T, *>> listDirectories(
        descriptor: EditorDataDescriptor<T>
    ): StoreResult<List<String>> {
        val prefix = "${descriptor.dataType.categoryDirName}/"
        return StoreResult.Success(
            directories.asSequence()
                .filter { it.startsWith(prefix) }
                .map { it.removePrefix(prefix) }
                .sorted()
                .toList()
        )
    }

    private fun <T : ManagedData<T, *>> key(
        descriptor: EditorDataDescriptor<T>,
        id: String
    ): String = "${descriptor.dataType.categoryDirName}/$id"

    private fun <T : ManagedData<T, *>> directoryKey(
        descriptor: EditorDataDescriptor<T>,
        directory: String
    ): String = "${descriptor.dataType.categoryDirName}/$directory"

    private fun <T : ManagedData<T, *>> registerParentDirectories(
        descriptor: EditorDataDescriptor<T>,
        id: String
    ) {
        val segments = PublicId.directoryOf(id)
        segments.indices.forEach { index ->
            directories += directoryKey(descriptor, segments.take(index + 1).joinToString("."))
        }
    }

    private fun directorySegments(directory: String): List<String> =
        if (directory.isEmpty()) emptyList() else directory.split('.')

    private fun <T> invalidId(id: String): StoreResult<T> =
        StoreResult.Failure(StoreError(StoreErrorCode.INVALID_ID, id))
}
