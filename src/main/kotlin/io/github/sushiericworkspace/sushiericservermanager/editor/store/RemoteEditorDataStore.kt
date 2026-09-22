package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.common.path.SushiEricDataDirectory
import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.common.data.core.identity.PublicId
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPException
import org.slf4j.LoggerFactory
import kotlin.io.path.createTempFile
import kotlin.io.path.createTempDirectory

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

        val directory = baseDirectory(profile.path, descriptor)

        return try {
            val resources = mutableListOf<StoreResource>()
            walkRemote(directory) { path, segments, name, type ->
                if (type == FileMode.Type.REGULAR && name.endsWith(".yml", ignoreCase = true)) {
                    val leaf = name.substringBeforeLast('.')
                    val id = PublicId.join(segments, leaf)
                    if (StorePathValidator.isValidId(id)) {
                        resources += StoreResource(id, name, path)
                    }
                }
            }
            resources.sortBy(StoreResource::id)
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
        val tempRoot = createTempDirectory("remote_load_").toFile()
        val tempFile = descriptor.dataType.pathOf(id).resolve(tempRoot)
        tempFile.parentFile.mkdirs()

        return try {
            ssh.download(remotePath, tempFile.absolutePath)
            val data = descriptor.load(tempFile, tempRoot.resolve(descriptor.relativeDirectory))
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
            tempRoot.deleteRecursively()
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
            val validationResults = data.refreshCompleted(descriptor.validate(data, itemIds))
            val errors = validationResults.filter { it.isError }
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
        val segments = if (targetDirectory.isEmpty()) emptyList() else targetDirectory.split('.')
        val targetId = PublicId.join(segments, PublicId.nameOf(id))
        if (targetId == id) return StoreResult.Success(id)
        return relocate(descriptor, id, targetId)
    }

    private fun <T : ManagedData<T, *>> relocate(
        descriptor: EditorDataDescriptor<T>,
        oldId: String,
        newId: String
    ): StoreResult<String> {
        val profile = ssh.currentProfile
            ?: return failure(StoreErrorCode.PROFILE_NOT_SELECTED, oldId)
        if (!ssh.isSftpActive) return failure(StoreErrorCode.STORE_UNAVAILABLE, oldId)

        val oldPath = Utility.getFullRemotePath(profile, descriptor.dataType.pathOf(oldId))
        val newPath = Utility.getFullRemotePath(profile, descriptor.dataType.pathOf(newId))
        return try {
            if (ssh.exists(newPath)) return failure(StoreErrorCode.ALREADY_EXISTS, newId)
            ssh.createDirectories(newPath.substringBeforeLast('/'))
            ssh.rename(oldPath, newPath)
            StoreResult.Success(newId)
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

    override fun <T : ManagedData<T, *>> createDirectory(
        descriptor: EditorDataDescriptor<T>,
        directory: String
    ): StoreResult<Unit> {
        if (!StorePathValidator.isValidDirectory(directory, allowRoot = false)) {
            return failure(StoreErrorCode.INVALID_ID, directory)
        }
        val profile = ssh.currentProfile
            ?: return failure(StoreErrorCode.PROFILE_NOT_SELECTED, directory)
        if (!ssh.isSftpActive) return failure(StoreErrorCode.STORE_UNAVAILABLE, directory)
        return try {
            ssh.createDirectories(directoryPath(profile.path, descriptor, directory))
            StoreResult.Success(Unit)
        } catch (e: Exception) {
            logger.error("リモートディレクトリ作成に失敗しました: {}", directory, e)
            failure(StoreErrorCode.IO_ERROR, directory, cause = e)
        }
    }

    override fun <T : ManagedData<T, *>> deleteDirectory(
        descriptor: EditorDataDescriptor<T>,
        directory: String
    ): StoreResult<Unit> {
        if (!StorePathValidator.isValidDirectory(directory, allowRoot = false)) {
            return failure(StoreErrorCode.INVALID_ID, directory)
        }
        val profile = ssh.currentProfile
            ?: return failure(StoreErrorCode.PROFILE_NOT_SELECTED, directory)
        if (!ssh.isSftpActive) return failure(StoreErrorCode.STORE_UNAVAILABLE, directory)
        val target = directoryPath(profile.path, descriptor, directory)
        return try {
            val files = mutableListOf<String>()
            val directories = mutableListOf<String>()
            walkRemote(target) { path, _, _, type ->
                when (type) {
                    FileMode.Type.REGULAR -> files += path
                    FileMode.Type.DIRECTORY -> directories += path
                    else -> Unit
                }
            }
            files.forEach(ssh::remove)
            directories.sortedByDescending { it.count { character -> character == '/' } }
                .forEach(ssh::removeDirectory)
            ssh.removeDirectory(target)
            StoreResult.Success(Unit)
        } catch (e: SFTPException) {
            if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) {
                failure(StoreErrorCode.FILE_NOT_FOUND, directory, cause = e)
            } else {
                failure(StoreErrorCode.IO_ERROR, directory, cause = e)
            }
        } catch (e: Exception) {
            logger.error("リモートディレクトリ削除に失敗しました: {}", target, e)
            failure(StoreErrorCode.IO_ERROR, directory, cause = e)
        }
    }

    override fun <T : ManagedData<T, *>> listDirectories(
        descriptor: EditorDataDescriptor<T>
    ): StoreResult<List<String>> {
        val profile = ssh.currentProfile
            ?: return failure(StoreErrorCode.PROFILE_NOT_SELECTED)
        if (!ssh.isSftpActive) return failure(StoreErrorCode.STORE_UNAVAILABLE)
        val base = baseDirectory(profile.path, descriptor)
        return try {
            val result = mutableListOf<String>()
            walkRemote(base) { _, segments, _, type ->
                val directory = segments.joinToString(".")
                if (type == FileMode.Type.DIRECTORY &&
                    StorePathValidator.isValidDirectory(directory, allowRoot = false)
                ) {
                    result += directory
                }
            }
            StoreResult.Success(result.sorted())
        } catch (e: SFTPException) {
            if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) StoreResult.Success(emptyList())
            else failure(StoreErrorCode.IO_ERROR, cause = e)
        } catch (e: Exception) {
            logger.error("リモートディレクトリ一覧取得に失敗しました: {}", base, e)
            failure(StoreErrorCode.IO_ERROR, cause = e)
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

    private fun <T : ManagedData<T, *>> baseDirectory(
        profilePath: String,
        descriptor: EditorDataDescriptor<T>
    ): String =
        "$profilePath/${SushiEricDataDirectory.BASE_ROOT}/${descriptor.relativeDirectory}"
            .replace(Regex("/+"), "/")

    private fun <T : ManagedData<T, *>> directoryPath(
        profilePath: String,
        descriptor: EditorDataDescriptor<T>,
        directory: String
    ): String =
        "${baseDirectory(profilePath, descriptor)}/${PublicId.relativePathOf(directory)}"
            .replace(Regex("/+"), "/")

    private fun walkRemote(
        directory: String,
        segments: List<String> = emptyList(),
        visitor: (path: String, segments: List<String>, name: String, type: FileMode.Type) -> Unit
    ) {
        ssh.listFilesOrThrow(directory)
            .filterNot { it.name == "." || it.name == ".." }
            .forEach { resource ->
                val path = "$directory/${resource.name}".replace(Regex("/+"), "/")
                if (resource.attributes.type == FileMode.Type.DIRECTORY) {
                    val childSegments = segments + resource.name
                    visitor(path, childSegments, resource.name, resource.attributes.type)
                    walkRemote(path, childSegments, visitor)
                } else {
                    visitor(path, segments, resource.name, resource.attributes.type)
                }
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
