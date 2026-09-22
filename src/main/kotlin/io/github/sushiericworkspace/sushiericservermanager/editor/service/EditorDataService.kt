package io.github.sushiericworkspace.sushiericservermanager.editor.service

import io.github.sushiericworkspace.common.data.core.SushiEricDataType
import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.common.data.core.identity.PublicId
import io.github.sushiericworkspace.common.data.core.validation.SushiEricValidationError
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.ore.model.mutable.MutableOreBaseData
import io.github.sushiericworkspace.sushiericservermanager.communication.RemoteResource
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.ThreeWayMergeResult
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.DeleteResult
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.LoadResult
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.RenameResult
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.SaveResult
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptor
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStoreKind
import io.github.sushiericworkspace.sushiericservermanager.editor.store.RemoteEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResource
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * エディターのデータ操作を保存先に依存しない形で提供します。
 *
 * 既存のDataAccess APIは維持し、実際のI/Oだけを[EditorDataStore]へ委譲します。
 */
class EditorDataService(
    val store: EditorDataStore,
    private val autoSaveDirectory: File = FilePath.AUTOSAVE_DIR.toFile()
) {
    constructor(ssh: SshManager) : this(RemoteEditorDataStore(ssh))

    private val logger = LoggerFactory.getLogger(javaClass)

    val items: DataAccess<MutableItemBaseData> = DataAccess(EditorDataDescriptors.item)
    val ores: DataAccess<MutableOreBaseData> = DataAccess(EditorDataDescriptors.ore)

    val storeKind: EditorDataStoreKind
        get() = store.kind

    val isRemote: Boolean
        get() = store.kind == EditorDataStoreKind.REMOTE

    val cacheIdentity: String
        get() = store.identity.ifBlank { store.kind.name.lowercase() }

    val currentProfileName: String?
        get() = cacheIdentity.takeIf { isRemote }

    fun forceBackToSelect() {
        if (isRemote) {
            Utility.navigateToServerSelect()
        } else {
            Utility.navigateToModeSelect()
        }
    }

    inner class DataAccess<T : ManagedData<T, *>> internal constructor(
        val descriptor: EditorDataDescriptor<T>
    ) {
        val dataType: SushiEricDataType<T>
            get() = descriptor.dataType

        val displayName: String
            get() = descriptor.displayName

        fun createDefault(id: String): T = descriptor.createDefault(id)

        fun duplicateAsNew(data: T, newId: String): T = descriptor.duplicateAsNew(data, newId)

        /**
         * Common側で定義された検証処理を使って、編集中データの問題を取得します。
         *
         * @param data 検証対象のデータ。
         * @param availableIds 参照先として利用できるアイテム内部ID。
         * @return 検出された検証エラー。問題がない場合は空のリスト。
         */
        fun validationErrors(data: T, availableIds: Set<ItemInternalId>): List<SushiEricValidationError> =
            descriptor.validate(data, availableIds)

        fun listYmlResources(): Pair<List<RemoteResource>, Boolean> {
            return when (val result = store.list(descriptor)) {
                is StoreResult.Success -> result.value.map {
                    RemoteResource(name = "${it.id}.yml", remotePath = it.location)
                } to true
                is StoreResult.Failure -> {
                    logger.error(
                        "{}一覧取得に失敗しました: code={}, detail={}",
                        displayName,
                        result.error.code,
                        result.error.detail,
                        result.error.cause
                    )
                    emptyList<RemoteResource>() to false
                }
            }
        }

        fun listStoreResources(): StoreResult<List<StoreResource>> = store.list(descriptor)

        fun load(fileName: String): Pair<T?, LoadResult> {
            return when (val result = store.load(descriptor, fileName)) {
                is StoreResult.Success -> result.value to LoadResult.SUCCESS
                is StoreResult.Failure -> null to result.error.code.toLoadResult()
            }
        }

        fun loadStore(id: String): StoreResult<T> = store.load(descriptor, id)

        fun save(fileName: String, data: T): SaveResult {
            return when (val result = store.save(descriptor, fileName, data)) {
                is StoreResult.Success -> SaveResult.SUCCESS
                is StoreResult.Failure -> {
                    logger.error(
                        "{}保存に失敗しました: id={}, code={}, detail={}",
                        displayName,
                        fileName,
                        result.error.code,
                        result.error.detail,
                        result.error.cause
                    )
                    result.error.code.toSaveResult()
                }
            }
        }

        fun save(data: T): SaveResult = save(data.id, data)

        fun saveStore(id: String, data: T): StoreResult<Unit> =
            store.save(descriptor, id, data)

        fun merge(base: T, local: T, remote: T): ThreeWayMergeResult<T> =
            descriptor.merger.merge(base, local, remote)

        fun saveToLocalBackup(
            fileName: String,
            subDirName: String,
            data: T
        ): Boolean {
            if (!StorePathValidatorForBackup.isValidSubDirectory(subDirName)) return false
            val target = resolveBackupFile(dataType.categoryDirName, subDirName, fileName)
            val parent = target.parentFile
            if (!parent.exists() && !parent.mkdirs()) return false

            val temporary = try {
                Files.createTempFile(parent.toPath(), ".$fileName-", ".tmp").toFile()
            } catch (e: Exception) {
                logger.error("自動保存用一時ファイルを作成できませんでした: {}", target, e)
                return false
            }

            return try {
                descriptor.save(temporary, data, null)
                replaceAtomically(temporary, target)
                true
            } catch (e: Exception) {
                logger.error(
                    "自動保存に失敗しました: category={}, type={}, id={}",
                    dataType.categoryDirName,
                    subDirName,
                    fileName,
                    e
                )
                false
            } finally {
                temporary.delete()
            }
        }

        fun saveToLocalBackup(subDirName: String, data: T): Boolean =
            saveToLocalBackup(data.id, subDirName, data)

        fun loadBackupPair(fileName: String): Pair<T, T>? {
            val editing = resolveBackupFile(dataType.categoryDirName, "editing", fileName)
            val original = resolveBackupFile(dataType.categoryDirName, "original", fileName)
            if (!editing.isFile || !original.isFile) return null

            return try {
                val editingData = descriptor.load(editing, editing.parentFile) ?: return null
                val originalData = descriptor.load(original, original.parentFile) ?: return null
                editingData to originalData
            } catch (e: Exception) {
                logger.error("自動保存ペアの読み込みに失敗しました: {}", fileName, e)
                null
            }
        }

        fun deleteLocalBackup(fileName: String) {
            listOf("editing", "original").forEach { subDirectory ->
                val file = resolveBackupFile(dataType.categoryDirName, subDirectory, fileName)
                if (file.exists() && !file.delete()) {
                    logger.warn("自動保存ファイルを削除できませんでした: {}", file)
                }
            }
        }

        fun clearLocalBackupsExcept(idsToKeep: Set<String> = emptySet()) {
            listOf("editing", "original").forEach { subDirectory ->
                val directory = backupCategoryDirectory(dataType.categoryDirName, subDirectory)
                directory.listFiles()
                    ?.filter { it.isFile && it.extension.equals("yml", ignoreCase = true) }
                    ?.filterNot { it.nameWithoutExtension in idsToKeep }
                    ?.forEach { file ->
                        if (!file.delete()) logger.warn("自動保存ファイルを削除できませんでした: {}", file)
                    }
            }
        }

        fun delete(fileName: String): DeleteResult {
            return when (val result = store.delete(descriptor, fileName)) {
                is StoreResult.Success -> {
                    deleteLocalBackup(fileName)
                    DeleteResult.SUCCESS
                }
                is StoreResult.Failure -> result.error.code.toDeleteResult()
            }
        }

        fun rename(oldName: String, newName: String): RenameResult {
            return when (val result = store.rename(descriptor, oldName, newName)) {
                is StoreResult.Success -> {
                    val newId = PublicId.join(PublicId.directoryOf(oldName), newName)
                    renameBackup(oldName, newId)
                    RenameResult.SUCCESS
                }
                is StoreResult.Failure -> result.error.code.toRenameResult()
            }
        }

        fun createDirectory(directory: String): StoreResult<Unit> =
            store.createDirectory(descriptor, directory)

        fun deleteDirectory(directory: String): StoreResult<Unit> =
            store.deleteDirectory(descriptor, directory)

        fun listDirectories(): StoreResult<List<String>> =
            store.listDirectories(descriptor)

        fun move(id: String, targetDirectory: String): StoreResult<String> {
            return when (val result = store.move(descriptor, id, targetDirectory)) {
                is StoreResult.Success -> {
                    renameBackup(id, result.value)
                    result
                }
                is StoreResult.Failure -> result
            }
        }

        private fun renameBackup(oldName: String, newName: String) {
            listOf("editing", "original").forEach { subDirectory ->
                val oldFile = resolveBackupFile(dataType.categoryDirName, subDirectory, oldName)
                if (!oldFile.isFile) return@forEach
                val data = descriptor.load(oldFile, oldFile.parentFile)
                if (data == null) {
                    logger.warn("名称変更対象の自動保存を読み込めませんでした: {}", oldFile)
                    return@forEach
                }
                data.id = newName
                if (saveToLocalBackup(newName, subDirectory, data)) {
                    oldFile.delete()
                }
            }
        }
    }

    private fun resolveBackupFile(
        categoryDirName: String,
        subDirName: String,
        fileName: String
    ): File {
        return backupCategoryDirectory(categoryDirName, subDirName)
            .resolve("$fileName.yml")
    }

    private fun backupCategoryDirectory(categoryDirName: String, subDirName: String): File {
        return autoSaveDirectory
            .resolve(cacheIdentity)
            .resolve(categoryDirName)
            .resolve(subDirName)
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

    private fun StoreErrorCode.toLoadResult(): LoadResult = when (this) {
        StoreErrorCode.STORE_UNAVAILABLE -> LoadResult.SFTP_INACTIVE
        StoreErrorCode.PROFILE_NOT_SELECTED -> LoadResult.PROFILE_NOT_SELECTED
        StoreErrorCode.FILE_NOT_FOUND -> LoadResult.FILE_NOT_FOUND
        StoreErrorCode.INVALID_YAML -> LoadResult.INVALID_YAML
        else -> LoadResult.FAILED
    }

    private fun StoreErrorCode.toSaveResult(): SaveResult = when (this) {
        StoreErrorCode.STORE_UNAVAILABLE,
        StoreErrorCode.PROFILE_NOT_SELECTED -> SaveResult.SFTP_INACTIVE
        else -> SaveResult.FAILED
    }

    private fun StoreErrorCode.toDeleteResult(): DeleteResult = when (this) {
        StoreErrorCode.STORE_UNAVAILABLE -> DeleteResult.SFTP_INACTIVE
        StoreErrorCode.PROFILE_NOT_SELECTED -> DeleteResult.PROFILE_NOT_SELECTED
        StoreErrorCode.FILE_NOT_FOUND -> DeleteResult.FILE_NOT_FOUND
        else -> DeleteResult.FAILED
    }

    private fun StoreErrorCode.toRenameResult(): RenameResult = when (this) {
        StoreErrorCode.STORE_UNAVAILABLE -> RenameResult.SFTP_INACTIVE
        StoreErrorCode.PROFILE_NOT_SELECTED -> RenameResult.PROFILE_NOT_SELECTED
        StoreErrorCode.FILE_NOT_FOUND -> RenameResult.FILE_NOT_FOUND
        StoreErrorCode.ALREADY_EXISTS -> RenameResult.ALREADY_EXISTS
        else -> RenameResult.FAILED
    }
}

private object StorePathValidatorForBackup {
    private val validNames = setOf("editing", "original")

    fun isValidSubDirectory(value: String): Boolean = value in validNames
}
