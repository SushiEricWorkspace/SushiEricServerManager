package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.common.data.core.identity.PublicId
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.path.SushiEricDataDirectory
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class LocalEditorDataStore(
    private val rootDirectory: File
) : EditorDataStore {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val kind: EditorDataStoreKind = EditorDataStoreKind.LOCAL
    override val identity: String = "offline"
    override val isAvailable: Boolean
        get() = rootDirectory.exists() || rootDirectory.mkdirs()

    fun ensureDirectories(): StoreResult<Unit> {
        return try {
            if (!rootDirectory.exists() && !rootDirectory.mkdirs()) {
                return failure(StoreErrorCode.PERMISSION_DENIED)
            }
            EditorDataDescriptors.all.forEach { descriptor ->
                val directory = rootDirectory.resolve(descriptor.relativeDirectory)
                if (!directory.exists() && !directory.mkdirs()) {
                    return failure(StoreErrorCode.PERMISSION_DENIED, detail = directory.path)
                }
            }
            StoreResult.Success(Unit)
        } catch (e: SecurityException) {
            failure(StoreErrorCode.PERMISSION_DENIED, cause = e)
        }
    }

    override fun readText(relativePath: String): StoreResult<String> {
        val file = resolveRelativeFile(relativePath)
            ?: return failure(StoreErrorCode.INVALID_ID, relativePath)
        if (!file.isFile) return failure(StoreErrorCode.FILE_NOT_FOUND, relativePath)

        return try {
            StoreResult.Success(file.readText(Charsets.UTF_8))
        } catch (e: SecurityException) {
            failure(StoreErrorCode.PERMISSION_DENIED, relativePath, cause = e)
        } catch (e: Exception) {
            logger.error("ローカルファイルの読み込みに失敗しました: {}", file, e)
            failure(StoreErrorCode.IO_ERROR, relativePath, cause = e)
        }
    }

    override fun writeText(relativePath: String, text: String): StoreResult<Unit> {
        val target = resolveRelativeFile(relativePath)
            ?: return failure(StoreErrorCode.INVALID_ID, relativePath)
        val directory = target.parentFile

        if (!directory.exists() && !directory.mkdirs()) {
            return failure(StoreErrorCode.PERMISSION_DENIED, relativePath)
        }

        val temporary = try {
            Files.createTempFile(directory.toPath(), ".${target.name}-", ".tmp").toFile()
        } catch (e: SecurityException) {
            return failure(StoreErrorCode.PERMISSION_DENIED, relativePath, cause = e)
        } catch (e: Exception) {
            logger.error("ローカルファイルの一時ファイル作成に失敗しました: {}", target, e)
            return failure(StoreErrorCode.IO_ERROR, relativePath, cause = e)
        }

        return try {
            temporary.writeText(text, Charsets.UTF_8)
            replaceAtomically(temporary, target)
            StoreResult.Success(Unit)
        } catch (e: SecurityException) {
            failure(StoreErrorCode.PERMISSION_DENIED, relativePath, cause = e)
        } catch (e: Exception) {
            logger.error("ローカルファイルの保存に失敗しました: {}", target, e)
            failure(StoreErrorCode.IO_ERROR, relativePath, cause = e)
        } finally {
            temporary.delete()
        }
    }

    /** 基準ディレクトリの外を指す相対パスは扱いません。 */
    private fun resolveRelativeFile(relativePath: String): File? {
        if (!StorePathValidator.isValidRelativePath(relativePath)) return null

        return rootDirectory.resolve(relativePath)
    }

    override fun <T : ManagedData<T, *>> list(
        descriptor: EditorDataDescriptor<T>
    ): StoreResult<List<StoreResource>> {
        if (ensureDirectories() is StoreResult.Failure) {
            return failure(StoreErrorCode.PERMISSION_DENIED)
        }
        val directory = descriptorDirectory(descriptor)
        return try {
            val resources = SushiEricDataDirectory.walkYmlFiles(directory)
                .asSequence()
                .mapNotNull {
                    val id = it.relativeTo(directory)
                        .invariantSeparatorsPath
                        .substringBeforeLast('.')
                        .replace('/', '.')
                    if (!StorePathValidator.isValidId(id)) return@mapNotNull null
                    StoreResource(
                        id = id,
                        fileName = it.name,
                        location = it.absolutePath
                    )
                }
                .sortedBy(StoreResource::id)
                .toList()
            StoreResult.Success(resources)
        } catch (e: SecurityException) {
            failure(StoreErrorCode.PERMISSION_DENIED, cause = e)
        }
    }

    override fun <T : ManagedData<T, *>> load(
        descriptor: EditorDataDescriptor<T>,
        id: String
    ): StoreResult<T> {
        val file = resolveFile(descriptor, id) ?: return failure(StoreErrorCode.INVALID_ID, id)
        if (!file.isFile) return failure(StoreErrorCode.FILE_NOT_FOUND, id)

        return try {
            val data = descriptor.load(file, descriptorDirectory(descriptor))
                ?: return failure(StoreErrorCode.INVALID_YAML, id)
            StoreResult.Success(data)
        } catch (e: SecurityException) {
            failure(StoreErrorCode.PERMISSION_DENIED, id, cause = e)
        } catch (e: Exception) {
            logger.error("ローカルデータの読み込みに失敗しました: {}", file, e)
            failure(StoreErrorCode.IO_ERROR, id, cause = e)
        }
    }

    override fun <T : ManagedData<T, *>> save(
        descriptor: EditorDataDescriptor<T>,
        id: String,
        data: T
    ): StoreResult<Unit> {
        val target = resolveFile(descriptor, id) ?: return failure(StoreErrorCode.INVALID_ID, id)
        val directory = target.parentFile
        if (!directory.exists() && !directory.mkdirs()) {
            return failure(StoreErrorCode.PERMISSION_DENIED, id)
        }

        val itemIds = localItemIds()
        val validationResults = data.refreshCompleted(descriptor.validate(data, itemIds))
        val errors = validationResults.filter { it.isError }
        if (errors.isNotEmpty()) {
            return failure(
                StoreErrorCode.VALIDATION_FAILED,
                id,
                errors.joinToString("\n") { it.message }
            )
        }

        val temporary = try {
            Files.createTempFile(directory.toPath(), ".$id-", ".tmp").toFile()
        } catch (e: SecurityException) {
            return failure(StoreErrorCode.PERMISSION_DENIED, id, cause = e)
        } catch (e: Exception) {
            logger.error("ローカルデータの一時ファイル作成に失敗しました: {}", target, e)
            return failure(StoreErrorCode.IO_ERROR, id, cause = e)
        }
        return try {
            descriptor.save(temporary, data, itemIds)
            replaceAtomically(temporary, target)
            StoreResult.Success(Unit)
        } catch (e: SecurityException) {
            failure(StoreErrorCode.PERMISSION_DENIED, id, cause = e)
        } catch (e: Exception) {
            logger.error("ローカルデータの保存に失敗しました: {}", target, e)
            failure(StoreErrorCode.IO_ERROR, id, cause = e)
        } finally {
            temporary.delete()
        }
    }

    override fun <T : ManagedData<T, *>> rename(
        descriptor: EditorDataDescriptor<T>,
        oldId: String,
        newName: String
    ): StoreResult<Unit> {
        if (!StorePathValidator.isValidId(oldId) || !StorePathValidator.isValidName(newName)) {
            return failure(StoreErrorCode.INVALID_ID, newName)
        }
        val targetId = PublicId.join(PublicId.directoryOf(oldId), newName)
        return when (val result = relocate(descriptor, oldId, targetId)) {
            is StoreResult.Success -> StoreResult.Success(Unit)
            is StoreResult.Failure -> result
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
            return failure(StoreErrorCode.INVALID_ID, id)
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
        val source = resolveFile(descriptor, oldId) ?: return failure(StoreErrorCode.INVALID_ID, oldId)
        val target = resolveFile(descriptor, newId) ?: return failure(StoreErrorCode.INVALID_ID, newId)
        if (!source.isFile) return failure(StoreErrorCode.FILE_NOT_FOUND, oldId)
        if (target.exists()) return failure(StoreErrorCode.ALREADY_EXISTS, newId)

        return when (val loaded = load(descriptor, oldId)) {
            is StoreResult.Failure -> loaded
            is StoreResult.Success -> {
                val renamed = descriptor.deepCopy(loaded.value).apply { id = newId }
                when (val saved = save(descriptor, newId, renamed)) {
                    is StoreResult.Failure -> saved
                    is StoreResult.Success -> {
                        try {
                            Files.delete(source.toPath())
                            StoreResult.Success(newId)
                        } catch (e: Exception) {
                            target.delete()
                            failure(StoreErrorCode.IO_ERROR, oldId, cause = e)
                        }
                    }
                }
            }
        }
    }

    override fun <T : ManagedData<T, *>> createDirectory(
        descriptor: EditorDataDescriptor<T>,
        directory: String
    ): StoreResult<Unit> {
        val target = resolveDirectory(descriptor, directory, allowRoot = false)
            ?: return failure(StoreErrorCode.INVALID_ID, directory)
        return try {
            when {
                target.isDirectory -> StoreResult.Success(Unit)
                target.exists() -> failure(StoreErrorCode.ALREADY_EXISTS, directory)
                target.mkdirs() -> StoreResult.Success(Unit)
                else -> failure(StoreErrorCode.PERMISSION_DENIED, directory)
            }
        } catch (e: SecurityException) {
            failure(StoreErrorCode.PERMISSION_DENIED, directory, cause = e)
        }
    }

    override fun <T : ManagedData<T, *>> deleteDirectory(
        descriptor: EditorDataDescriptor<T>,
        directory: String
    ): StoreResult<Unit> {
        val target = resolveDirectory(descriptor, directory, allowRoot = false)
            ?: return failure(StoreErrorCode.INVALID_ID, directory)
        if (!target.isDirectory) return failure(StoreErrorCode.FILE_NOT_FOUND, directory)
        return try {
            if (target.deleteRecursively()) StoreResult.Success(Unit)
            else failure(StoreErrorCode.IO_ERROR, directory)
        } catch (e: SecurityException) {
            failure(StoreErrorCode.PERMISSION_DENIED, directory, cause = e)
        }
    }

    override fun <T : ManagedData<T, *>> listDirectories(
        descriptor: EditorDataDescriptor<T>
    ): StoreResult<List<String>> {
        val base = descriptorDirectory(descriptor)
        if (!base.exists() && !base.mkdirs()) return failure(StoreErrorCode.PERMISSION_DENIED)
        return try {
            StoreResult.Success(
                base.walkTopDown()
                    .filter(File::isDirectory)
                    .drop(1)
                    .map { it.relativeTo(base).invariantSeparatorsPath.replace('/', '.') }
                    .filter { StorePathValidator.isValidDirectory(it) }
                    .sorted()
                    .toList()
            )
        } catch (e: SecurityException) {
            failure(StoreErrorCode.PERMISSION_DENIED, cause = e)
        }
    }

    override fun <T : ManagedData<T, *>> delete(
        descriptor: EditorDataDescriptor<T>,
        id: String
    ): StoreResult<Unit> {
        val file = resolveFile(descriptor, id) ?: return failure(StoreErrorCode.INVALID_ID, id)
        if (!file.exists()) return failure(StoreErrorCode.FILE_NOT_FOUND, id)
        return try {
            Files.delete(file.toPath())
            StoreResult.Success(Unit)
        } catch (e: SecurityException) {
            failure(StoreErrorCode.PERMISSION_DENIED, id, cause = e)
        } catch (e: Exception) {
            failure(StoreErrorCode.IO_ERROR, id, cause = e)
        }
    }

    fun root(): File = rootDirectory

    private fun <T : ManagedData<T, *>> descriptorDirectory(
        descriptor: EditorDataDescriptor<T>
    ): File = rootDirectory.resolve(descriptor.relativeDirectory)

    private fun <T : ManagedData<T, *>> resolveFile(
        descriptor: EditorDataDescriptor<T>,
        id: String
    ): File? {
        if (!StorePathValidator.isValidId(id)) return null
        val directory = descriptorDirectory(descriptor).canonicalFile
        val file = descriptor.dataType.pathOf(id).resolve(rootDirectory).canonicalFile
        return file.takeIf { it.toPath().startsWith(directory.toPath()) }
    }

    private fun <T : ManagedData<T, *>> resolveDirectory(
        descriptor: EditorDataDescriptor<T>,
        directory: String,
        allowRoot: Boolean
    ): File? {
        if (!StorePathValidator.isValidDirectory(directory, allowRoot)) return null
        val base = descriptorDirectory(descriptor).canonicalFile
        val target = directorySegments(directory)
            .fold(base) { parent, segment -> parent.resolve(segment) }
            .canonicalFile
        return target.takeIf { it.toPath().startsWith(base.toPath()) }
    }

    private fun directorySegments(directory: String): List<String> =
        if (directory.isEmpty()) emptyList() else directory.split('.')

    private fun localItemIds(): Set<ItemInternalId> {
        return when (val result = list(EditorDataDescriptors.item)) {
            is StoreResult.Success -> result.value.mapNotNullTo(mutableSetOf()) { resource ->
                when (val loaded = load(EditorDataDescriptors.item, resource.id)) {
                    is StoreResult.Success -> loaded.value.internalId
                    is StoreResult.Failure -> null
                }
            }
            is StoreResult.Failure -> emptySet()
        }
    }

    private fun replaceAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun <T> failure(
        code: StoreErrorCode,
        dataId: String? = null,
        detail: String? = null,
        cause: Throwable? = null
    ): StoreResult<T> = StoreResult.Failure(StoreError(code, dataId, detail, cause))
}
