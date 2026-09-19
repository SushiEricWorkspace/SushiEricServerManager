package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.common.path.SushiEricDataDirectory
import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPException
import org.slf4j.LoggerFactory
import kotlin.io.path.createTempFile

class RemoteEditorDataStore(
    private val ssh: SshManager
) : EditorDataStore {
    private val logger = LoggerFactory.getLogger(javaClass)

    override val kind: EditorDataStoreKind = EditorDataStoreKind.REMOTE

    override val identity: String
        get() = ssh.currentProfile?.name ?: "remote"

    override val isAvailable: Boolean
        get() = ssh.isSftpActive && ssh.currentProfile != null

    override fun <T : ManagedData<T, *>> list(
        descriptor: EditorDataDescriptor<T>
    ): StoreResult<List<StoreResource>> {
        val profile = ssh.currentProfile
            ?: return failure(StoreErrorCode.PROFILE_NOT_SELECTED)
        if (!ssh.isSftpActive) return failure(StoreErrorCode.STORE_UNAVAILABLE)

        val directory = "${profile.path}/${SushiEricDataDirectory.BASE_ROOT}/${descriptor.relativeDirectory}"
            .replace(Regex("/+"), "/")

        return try {
            val resources = ssh.listFilesOrThrow(directory)
                .filter {
                    it.attributes.type == FileMode.Type.REGULAR &&
                            it.name.endsWith(".yml", ignoreCase = true)
                }
                .sortedBy { it.name }
                .map {
                    StoreResource(
                        id = it.name.removeSuffix(".yml"),
                        fileName = it.name,
                        location = "$directory/${it.name}".replace(Regex("/+"), "/")
                    )
                }
            StoreResult.Success(resources)
        } catch (e: SFTPException) {
            if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) {
                StoreResult.Success(emptyList())
            } else {
                logger.error("リモート一覧取得に失敗しました: {}", directory, e)
                failure(StoreErrorCode.IO_ERROR, cause = e)
            }
        } catch (e: Exception) {
            logger.error("リモート一覧取得に失敗しました: {}", directory, e)
            failure(StoreErrorCode.IO_ERROR, cause = e)
        }
    }

    override fun <T : ManagedData<T, *>> load(
        descriptor: EditorDataDescriptor<T>,
        id: String
    ): StoreResult<T> {
        if (!StorePathValidator.isValidId(id)) {
            return failure(StoreErrorCode.INVALID_ID, id)
        }
        val profile = ssh.currentProfile
            ?: return failure(StoreErrorCode.PROFILE_NOT_SELECTED, id)
        if (!ssh.isSftpActive) return failure(StoreErrorCode.STORE_UNAVAILABLE, id)

        val remotePath = Utility.getFullRemotePath(profile, descriptor.dataType.pathOf(id))
        val tempFile = createTempFile("remote_load_", ".yml").toFile()

        return try {
            ssh.download(remotePath, tempFile.absolutePath)
            val data = descriptor.load(tempFile, id)
                ?: return failure(StoreErrorCode.INVALID_YAML, id)
            StoreResult.Success(data)
        } catch (e: Exception) {
            val missing = e.message.orEmpty().contains("No such file", true) ||
                    e.message.orEmpty().contains("not found", true)
            if (!missing) logger.error("リモート読み込みに失敗しました: {}", remotePath, e)
            failure(
                code = if (missing) StoreErrorCode.FILE_NOT_FOUND else StoreErrorCode.IO_ERROR,
                dataId = id,
                cause = e
            )
        } finally {
            tempFile.delete()
        }
    }

    override fun <T : ManagedData<T, *>> save(
        descriptor: EditorDataDescriptor<T>,
        id: String,
        data: T
    ): StoreResult<Unit> {
        if (!StorePathValidator.isValidId(id)) {
            return failure(StoreErrorCode.INVALID_ID, id)
        }
        val profile = ssh.currentProfile
            ?: return failure(StoreErrorCode.PROFILE_NOT_SELECTED, id)
        if (!ssh.isSftpActive) return failure(StoreErrorCode.STORE_UNAVAILABLE, id)

        val remotePath = Utility.getFullRemotePath(profile, descriptor.dataType.pathOf(id))
        val tempFile = createTempFile("remote_save_", ".yml").toFile()

        return try {
            val itemIds = loadItemIds()
            val errors = descriptor.validate(data, itemIds)
            if (errors.isNotEmpty()) {
                return failure(
                    StoreErrorCode.VALIDATION_FAILED,
                    id,
                    errors.joinToString("\n") { it.message }
                )
            }
            descriptor.save(tempFile, data, itemIds)
            ssh.upload(tempFile.absolutePath, remotePath)
            StoreResult.Success(Unit)
        } catch (e: Exception) {
            logger.error("リモート保存に失敗しました: {}", remotePath, e)
            failure(StoreErrorCode.IO_ERROR, id, cause = e)
        } finally {
            tempFile.delete()
        }
    }

    override fun <T : ManagedData<T, *>> rename(
        descriptor: EditorDataDescriptor<T>,
        oldId: String,
        newId: String
    ): StoreResult<Unit> {
        if (!StorePathValidator.isValidId(oldId) || !StorePathValidator.isValidId(newId)) {
            return failure(StoreErrorCode.INVALID_ID, newId)
        }
        val profile = ssh.currentProfile
            ?: return failure(StoreErrorCode.PROFILE_NOT_SELECTED, oldId)
        if (!ssh.isSftpActive) return failure(StoreErrorCode.STORE_UNAVAILABLE, oldId)

        val oldPath = Utility.getFullRemotePath(profile, descriptor.dataType.pathOf(oldId))
        val newPath = Utility.getFullRemotePath(profile, descriptor.dataType.pathOf(newId))
        return try {
            ssh.rename(oldPath, newPath)
            StoreResult.Success(Unit)
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            val code = when {
                message.contains("No such file", true) || message.contains("not found", true) ->
                    StoreErrorCode.FILE_NOT_FOUND
                message.contains("Already exists", true) || message.contains("Failure", true) ->
                    StoreErrorCode.ALREADY_EXISTS
                else -> StoreErrorCode.IO_ERROR
            }
            if (code == StoreErrorCode.IO_ERROR) {
                logger.error("リモート名称変更に失敗しました: {} -> {}", oldPath, newPath, e)
            }
            failure(code, oldId, cause = e)
        }
    }

    override fun <T : ManagedData<T, *>> delete(
        descriptor: EditorDataDescriptor<T>,
        id: String
    ): StoreResult<Unit> {
        if (!StorePathValidator.isValidId(id)) {
            return failure(StoreErrorCode.INVALID_ID, id)
        }
        val profile = ssh.currentProfile
            ?: return failure(StoreErrorCode.PROFILE_NOT_SELECTED, id)
        if (!ssh.isSftpActive) return failure(StoreErrorCode.STORE_UNAVAILABLE, id)

        val remotePath = Utility.getFullRemotePath(profile, descriptor.dataType.pathOf(id))
        return try {
            ssh.remove(remotePath)
            StoreResult.Success(Unit)
        } catch (e: Exception) {
            val missing = e.message.orEmpty().contains("No such file", true) ||
                    e.message.orEmpty().contains("not found", true)
            if (!missing) logger.error("リモート削除に失敗しました: {}", remotePath, e)
            failure(
                if (missing) StoreErrorCode.FILE_NOT_FOUND else StoreErrorCode.IO_ERROR,
                id,
                cause = e
            )
        }
    }

    private fun loadItemIds(): Set<ItemInternalId> {
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

    private fun <T> failure(
        code: StoreErrorCode,
        dataId: String? = null,
        detail: String? = null,
        cause: Throwable? = null
    ): StoreResult<T> = StoreResult.Failure(StoreError(code, dataId, detail, cause))
}
