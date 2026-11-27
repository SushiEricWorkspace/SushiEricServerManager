package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory.HistorySource
import io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory.checkHistoryWorker
import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.PublicId
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericservermanager.communication.database.PostgresHostReference
import io.github.sushiericworkspace.sushiericservermanager.editor.shop.*
import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericdatamodel.data.shop.model.mutable.MutableShopProductData
import io.github.sushiericworkspace.sushiericdatamodel.path.SushiEricDataDirectory
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

class LocalEditorDataStore(
    rootDirectory: File,
    historySource: HistorySource? = null,
    private val hostReference: PostgresHostReference? = null
) : EditorDataStore {
    private val rootDirectory = rootDirectory.canonicalFile
    private val logger = LoggerFactory.getLogger(javaClass)
    private val configuredHistorySource = historySource
    override val databaseReference get() = hostReference
    private val hostTransport = lazy { LocalShopTransport(checkedReference()) }
    private val databaseTransport by hostTransport
    override fun closeDatabase() {
        if (hostTransport.isInitialized()) databaseTransport.close()
    }
    override val shopProducts: ShopEditorStore? by lazy {
        hostReference?.let { reference ->
            val binding = reference.status.state.binding
            val key = UUID.nameUUIDFromBytes(listOf(binding.deploymentUuid, binding.databaseUuid,
                binding.worldUuid, binding.generation).joinToString("\u0000").toByteArray()).toString()
            ShopEditorStore(ShopSaveSession(databaseTransport,
                FilePath.AUTOSAVE_DIR.toFile().resolve("shop-pending/$key.json").toPath()))
        }
    }
    override val historySource: HistorySource?
        get() {
            configuredHistorySource?.let { return it }
            checkHistoryWorker()
            if (hostReference != null) return requireNotNull(shopProducts).historySource()
            val databaseDirectory = rootDirectory.resolve("sushieric")
            check(!databaseDirectory.resolve("world-id").exists() && !databaseDirectory.resolve("run-state").exists() &&
                !databaseDirectory.resolve("run-state-v2").exists() && !databaseDirectory.resolve("server.sqlite").exists()) {
                "world結合済み履歴には承認済みv2 Host参照が必要です。"
            }
            return null
        }

    private fun checkedReference(): PostgresHostReference {
        checkHistoryWorker()
        val reference = requireNotNull(hostReference)
        reference.validate()
        check(Path.of(reference.status.state.binding.worldRoot).toRealPath() == rootDirectory.toPath().toRealPath()) {
            "接続参照のworldがローカルrootと一致しません。"
        }
        check(File(reference.shop.root).isDirectory) { "承認済みshopの配置を確認できません。" }
        return reference
    }

    override val kind: EditorDataStoreKind = EditorDataStoreKind.LOCAL
    override val identity: String = "offline"
    override val offlineDirectory: File get() = rootDirectory
    override val isAvailable: Boolean
        get() = if (configuredHistorySource != null || hostReference != null) rootDirectory.isDirectory
            else rootDirectory.exists() || rootDirectory.mkdirs()

    fun ensureDirectories(): StoreResult<Unit> {
        if (hostReference != null) return if (rootDirectory.isDirectory) StoreResult.Success(Unit)
            else failure(StoreErrorCode.STORE_UNAVAILABLE)
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
        if (hostReference != null && (relativePath.equals("shop_data", true) || relativePath.startsWith("shop_data/", true)))
            return failure(StoreErrorCode.UNSUPPORTED_FORMAT, relativePath)
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

    override fun listPath(relativePath: String): StoreResult<List<StorePathEntry>> {
        val directory = if (relativePath.isEmpty()) rootDirectory else resolveRelativeFile(relativePath)
            ?: return failure(StoreErrorCode.INVALID_ID, relativePath)
        if (!directory.exists()) return StoreResult.Success(emptyList())
        if (!directory.isDirectory) return failure(StoreErrorCode.INVALID_ID, relativePath)
        return try {
            val files = directory.listFiles()
                ?: return failure(StoreErrorCode.IO_ERROR, relativePath)
            StoreResult.Success(files.map { StorePathEntry(it.name, it.isDirectory) }.sortedBy { it.name })
        } catch (e: SecurityException) {
            failure(StoreErrorCode.PERMISSION_DENIED, relativePath, cause = e)
        }
    }

    /** 基準ディレクトリの外を指す相対パスは扱いません。 */
    private fun resolveRelativeFile(relativePath: String): File? {
        if (!StorePathValidator.isValidRelativePath(relativePath)) return null

        if (hostReference != null && (relativePath == "shop_data" || relativePath.startsWith("shop_data/")))
            return File(checkedReference().shop.root).resolve(relativePath.removePrefix("shop_data").trimStart('/'))
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
                    if (!StorePathValidator.isValidId(id) || !descriptor.isValidId(id)) return@mapNotNull null
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
        if (hostReference != null && descriptor.readProduct != null) {
            return when (val loaded = requireNotNull(shopProducts).load(id)) {
                is StoreResult.Success -> StoreResult.Success(descriptor.readProduct.invoke(loaded.value))
                is StoreResult.Failure -> loaded
            }
        }
        val file = resolveFile(descriptor, id) ?: return failure(StoreErrorCode.INVALID_ID, id)
        if (!file.isFile) return failure(StoreErrorCode.FILE_NOT_FOUND, id)

        return try {
            val data = descriptor.load(file, descriptorDirectory(descriptor))
                ?: return failure(StoreErrorCode.INVALID_YAML, id)
            if (descriptor.readProduct != null) data.id = id
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
        if (hostReference != null && data is MutableShopProductData) {
            return requireNotNull(shopProducts).save(data, requireNotNull(shopProducts).snapshot(id))
        }
        val target = resolveFile(descriptor, id) ?: return failure(StoreErrorCode.INVALID_ID, id)
        val directory = target.parentFile
        if (!directory.exists() && !directory.mkdirs()) {
            return failure(StoreErrorCode.PERMISSION_DENIED, id)
        }

        val itemIds = if (descriptor.requiresItemIds) localItemIds() else emptySet()
        val validation = descriptor.validateInStore?.invoke(data, this)
        if (validation is StoreResult.Failure) return validation
        val validationResults = data.refreshCompleted(
            (validation as? StoreResult.Success)?.value ?: descriptor.validate(data, itemIds)
        )
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
            if (descriptor.readProduct != null) descriptor.saveBackup(temporary, data)
            else descriptor.save(temporary, data, itemIds)
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
        if (hostReference != null && descriptor.readProduct != null)
            return failure(StoreErrorCode.UNSUPPORTED_FORMAT, detail = "商品操作にはHostの親要求が必要です。")

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
        if (hostReference != null && descriptor.readProduct != null)
            return failure(StoreErrorCode.UNSUPPORTED_FORMAT, detail = "商品操作にはHostの親要求が必要です。")

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

        if (!descriptor.storesIdInFile) {
            return try {
                Files.createDirectories(target.parentFile.toPath())
                Files.move(source.toPath(), target.toPath())
                StoreResult.Success(newId)
            } catch (e: SecurityException) {
                failure(StoreErrorCode.PERMISSION_DENIED, oldId, cause = e)
            } catch (e: Exception) {
                failure(StoreErrorCode.IO_ERROR, oldId, cause = e)
            }
        }

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
        if (hostReference != null && descriptor.readProduct != null)
            return failure(StoreErrorCode.UNSUPPORTED_FORMAT, detail = "商品操作にはHostの親要求が必要です。")

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
        if (hostReference != null && descriptor.readProduct != null)
            return failure(StoreErrorCode.UNSUPPORTED_FORMAT, detail = "商品操作にはHostの親要求が必要です。")

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
        if (!base.exists() && (hostReference != null || !base.mkdirs())) return failure(StoreErrorCode.PERMISSION_DENIED)
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
        if (hostReference != null && descriptor.readProduct != null)
            return failure(StoreErrorCode.UNSUPPORTED_FORMAT, detail = "商品操作にはHostの親要求が必要です。")

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
    ): File = if (hostReference != null && descriptor.readProduct != null) File(checkedReference().shop.root)
        else rootDirectory.resolve(descriptor.relativeDirectory)

    private fun <T : ManagedData<T, *>> resolveFile(
        descriptor: EditorDataDescriptor<T>,
        id: String
    ): File? {
        if (!StorePathValidator.isValidId(id) || !descriptor.isValidId(id)) return null
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
