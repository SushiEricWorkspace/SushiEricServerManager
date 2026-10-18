package io.github.sushiericworkspace.sushiericservermanager.editor.service

import io.github.sushiericworkspace.sushiericdatamodel.data.core.SushiEricDataType
import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.PublicId
import io.github.sushiericworkspace.sushiericdatamodel.data.core.validation.SushiEricValidationError
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.ore.model.mutable.MutableOreBaseData
import io.github.sushiericworkspace.sushiericdatamodel.path.SushiEricDataDirectory
import io.github.sushiericworkspace.sushiericservermanager.communication.RemoteResource
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.editor.offline.OfflineWorkspaceMigrator
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
import io.github.sushiericworkspace.sushiericservermanager.editor.store.LocalEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResource
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import javafx.beans.property.ReadOnlyIntegerProperty
import javafx.beans.property.ReadOnlyIntegerWrapper
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** 旧退避の表示情報です。接続先との由来の一致を表しません。 */
internal data class LegacyBackupCandidate(val name: String, val modified: Long, val count: Int, val location: String)

/**
 * エディターのデータ操作を保存先に依存しない形で提供します。
 *
 * 文書の保存・退避は保存元別のローカル世代へ直列化し、明示取得だけを接続元へ委譲します。
 */
class EditorDataService(
    store: EditorDataStore,
    val autoSaveDirectory: File = FilePath.AUTOSAVE_DIR.toFile(),
    private val documentDirectory: File = autoSaveDirectory.resolve("documents"),
    private val localDirectory: File = autoSaveDirectory.resolve("offline"),
    private val legacyAutoSaveRoots: List<File> = listOf(autoSaveDirectory)
) {
    constructor(ssh: SshManager) : this(RemoteEditorDataStore(ssh), localDirectory = FilePath.OFFLINE_DIR.toFile())

    private val ioLock = Any()
    internal val sourceStore: EditorDataStore = SerializedEditorDataStore(store, ioLock)
    private val workingStore = LocalWorkingDataStore(this, sourceStore)
    val store: EditorDataStore = SerializedEditorDataStore(workingStore, ioLock)
    private val ioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "editor-store-io").apply { isDaemon = true }
    }

    /** ローカル退避の書き込み・削除・保留操作更新を同じ順序で実行します。 */
    internal fun <R> submitBackupIo(operation: () -> R): CompletableFuture<R> =
        submitIo(operation)

    private val saves = ReadOnlyIntegerWrapper(0)
    val activeSaves: Int get() = saves.get()
    internal val activeSavesProperty: ReadOnlyIntegerProperty get() = saves.readOnlyProperty

    internal fun beginSave() { saves.set(activeSaves + 1) }
    internal fun endSave() { saves.set(activeSaves - 1) }

    /**
     * この接続のストアI/Oを順番に背景実行します。複合操作も1つの工程として渡します。
     * 処理内から別の工程を投入して待機せず、完了後の画面更新はUIスレッドへ戻してください。
     */
    fun <R> submitIo(operation: () -> R): CompletableFuture<R> =
        CompletableFuture.supplyAsync({ synchronized(ioLock) { operation() } }, ioExecutor)

    internal fun executeIo(task: Runnable) { ioExecutor.execute { synchronized(ioLock) { task.run() } } }

    /** UIスレッド外で、受理済みのストアI/Oと退避キューの完了を待ちます。 */
    internal fun awaitIo() {
        submitIo {}.join()
        submitBackupIo {}.join()
        if (localCompanion.isInitialized() && localCompanion.value !== this) localCompanion.value.awaitIo()
    }

    internal fun closeIo() {
        ioExecutor.shutdown()
        if (localCompanion.isInitialized() && localCompanion.value !== this) {
            localCompanion.value.itemReferences.close()
            localCompanion.value.closeIo()
        }
    }

    internal val documentSource: EditorDocumentSource by lazy { store.documentSource }
    internal val documents: LocalDocumentRepository by lazy {
        LocalDocumentRepository(documentDirectory, documentSource)
    }

    private val importedLocalStore by lazy {
        val local = sourceStore
        if (local.kind != EditorDataStoreKind.LOCAL) StoreResult.Success(local)
        else OfflineWorkspaceMigrator(checkNotNull(local.offlineDirectory))
            .copyToCurrent(documentDirectory.resolve("imports").resolve(UUID.randomUUID().toString()))
    }

    internal fun localImportStore(): StoreResult<EditorDataStore> = importedLocalStore

    private val localCompanion = lazy {
        if (!isRemote) this else EditorDataService(
            LocalEditorDataStore(localDirectory),
            documentDirectory.parentFile, documentDirectory, localDirectory,
            (legacyAutoSaveRoots + documentDirectory.parentFile).distinct())
    }
    internal val localService: EditorDataService get() = localCompanion.value

    private val logger = LoggerFactory.getLogger(javaClass)
    private val pendingOperationJson = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    val items: DataAccess<MutableItemBaseData> = DataAccess(EditorDataDescriptors.item)
    internal val itemReferences = ItemReferenceCache(this)
    val ores: DataAccess<MutableOreBaseData> = DataAccess(EditorDataDescriptors.ore)
    val shops = DataAccess(EditorDataDescriptors.shop)
    val recipes = DataAccess(EditorDataDescriptors.recipe)

    /**
     * Mod共通設定（config.yml）の生テキストです。
     *
     * 内容の解析、変換、検証は行いません。設定値の妥当性判断と既定値への
     * フォールバックはServerMod側が行います。
     */
    val modConfig: TextFileAccess = TextFileAccess(SushiEricDataDirectory.Config().getRawPath())

    val storeKind: EditorDataStoreKind
        get() = store.kind

    val isRemote: Boolean
        get() = store.kind == EditorDataStoreKind.REMOTE

    val cacheIdentity: String
        get() = documentSource.directory

    val currentProfileName: String?
        get() = sourceStore.identity.takeIf { isRemote }

    /** 既存ローカル領域を読取専用の取込元とし、4種別の作業コピーを準備します。 */
    internal fun prepareLocalDocuments(): StoreResult<Unit> {
        fun <T : ManagedData<T, *>> initialize(access: DataAccess<T>): StoreResult.Failure? =
            access.listStoreResources() as? StoreResult.Failure
        initialize(items)?.let { return it }
        initialize(ores)?.let { return it }
        initialize(shops)?.let { return it }
        initialize(recipes)?.let { return it }
        return StoreResult.Success(Unit)
    }

    fun forceBackToSelect() {
        if (isRemote) {
            Utility.navigateToServerSelect()
        } else {
            Utility.navigateToModeSelect()
        }
    }

    /**
     * 保存先に依存せず、1つのテキストファイルを読み書きします。
     *
     * @property relativePath 基準ディレクトリからの相対パス（区切りは`/`）。
     */
    inner class TextFileAccess internal constructor(
        val relativePath: String
    ) {
        /** ファイルの内容をそのまま読み込みます。 */
        fun load(): StoreResult<String> = store.readText(relativePath)

        /** 入力された内容をローカル保存し、サーバーには書き込みません。 */
        fun save(text: String): StoreResult<Unit> = store.writeText(relativePath, text)

        internal fun localState(): StoreResult<LocalDocumentRecord> {
            val key = EditorDocumentKey(documentSource, "text", relativePath)
            when (val current = documents.read()) {
                is StoreResult.Success -> current.value.documents.firstOrNull { it.key == key }?.let {
                    return StoreResult.Success(it)
                }
                is StoreResult.Failure -> return current
            }
            when (val loaded = load()) {
                is StoreResult.Success -> Unit
                is StoreResult.Failure -> if (loaded.error.code != StoreErrorCode.FILE_NOT_FOUND) return loaded
            }
            return when (val current = documents.read()) {
                is StoreResult.Success -> StoreResult.Success(current.value.documents.firstOrNull { it.key == key }
                    ?: LocalDocumentRecord(key, editing = ""))
                is StoreResult.Failure -> current
            }
        }

        /** 接続元の生テキストを明示取得します。ローカルの三層は変更しません。 */
        internal fun fetchSource(): StoreResult<String?> = when (val loaded = sourceStore.readText(relativePath)) {
            is StoreResult.Success -> StoreResult.Success(loaded.value)
            is StoreResult.Failure -> if (loaded.error.code == StoreErrorCode.FILE_NOT_FOUND) StoreResult.Success(null) else loaded
        }

        internal fun saveObserved(text: String, version: LocalDocumentVersion): StoreResult<LocalDocumentRecord> =
            publishText(version) { it.copy(editing = text, original = text, saved = text) }

        /** 編集文字列だけを同じI/Oキューへ退避し、保存済み・基準を進めません。 */
        internal fun backup(text: String, version: LocalDocumentVersion): CompletableFuture<StoreResult<LocalDocumentRecord>> =
            submitIo { publishText(version) { it.copy(editing = text, original = it.saved) } }

        /** 確認済みの取得だけを適用し、未送信の保存済み本文と編集中の本文は保持します。 */
        internal fun acceptFetched(version: LocalDocumentVersion, latest: String?, editing: String): StoreResult<LocalDocumentRecord> =
            publishText(version) { previous ->
                val replaceSaved = previous.saved == null || (previous.baselineKnown && previous.saved == previous.baseline)
                val saved = if (replaceSaved) latest else previous.saved
                val nextEditing = if (replaceSaved && editing == previous.saved.orEmpty()) latest.orEmpty() else editing
                previous.copy(editing = nextEditing, original = saved, saved = saved, baseline = latest, baselineKnown = true)
            }

        private fun publishText(version: LocalDocumentVersion,
                                update: (LocalDocumentRecord) -> LocalDocumentRecord): StoreResult<LocalDocumentRecord> = synchronized(ioLock) {
            val key = EditorDocumentKey(documentSource, "text", relativePath)
            check(version.key == key) { "テキスト文書の観測元が一致しません。" }
            val snapshot = when (val loaded = documents.read()) {
                is StoreResult.Success -> loaded.value
                is StoreResult.Failure -> return@synchronized loaded
            }
            val previous = snapshot.documents.firstOrNull { it.key == key } ?: LocalDocumentRecord(key)
            when (val published = documents.publish(mapOf(key to version.revision), listOf(update(previous)))) {
                is StoreResult.Success -> {
                    val record = published.value.documents.single { it.key == key }
                    version.accepted(record)
                    StoreResult.Success(record)
                }
                is StoreResult.Failure -> published
            }
        }
    }

    internal fun <T : ManagedData<T, *>> documentAccess(descriptor: EditorDataDescriptor<T>): DataAccess<T> = DataAccess(descriptor)

    inner class DataAccess<T : ManagedData<T, *>> internal constructor(
        val descriptor: EditorDataDescriptor<T>
    ) {
        internal fun documentKey(id: String): EditorDocumentKey =
            EditorDocumentKey(documentSource, dataType.categoryDirName, id)

        /** 既存の編集用Serializerを使用し、正式保存の完成データ制約を適用しません。 */
        internal fun encodeDocument(data: T): String {
            val directory = documentDirectory.parentFile.resolve("document-codec")
            Files.createDirectories(directory.toPath())
            val temporary = Files.createTempFile(directory.toPath(), ".document-", ".yml").toFile()
            return try {
                descriptor.saveBackup(temporary, data.deepCopy())
                val text = temporary.readText(Charsets.UTF_8)
                check(text.isNotBlank() && descriptor.loadBackup(temporary, directory) != null) {
                    "編集文書を書き出せませんでした: " + data.id
                }
                text
            } finally { temporary.delete() }
        }

        internal fun decodeDocument(id: String, text: String): T {
            val directory = documentDirectory.parentFile.resolve("document-codec")
            Files.createDirectories(directory.toPath())
            val temporary = Files.createTempFile(directory.toPath(), ".document-", ".yml").toFile()
            return try {
                temporary.writeText(text, Charsets.UTF_8)
                checkNotNull(descriptor.loadBackup(temporary, directory)) { "ローカル文書を復元できません: $id" }
                    .apply { this.id = id }
            } finally { temporary.delete() }
        }

        /** 呼出し元が取得した文書と、その時点のversionを同じI/O工程で返します。 */
        internal fun observeStore(id: String): StoreResult<Pair<T, LocalDocumentRecord>> = synchronized(ioLock) {
            when (val initialized = listStoreResources()) {
                is StoreResult.Failure -> return@synchronized initialized
                is StoreResult.Success -> Unit
            }
            when (val loaded = localDocuments()) {
                is StoreResult.Failure -> loaded
                is StoreResult.Success -> {
                    val record = loaded.value.firstOrNull {
                        it.key == documentKey(id) && it.saved != null && !it.savedDeleted && it.movedTo == null
                    } ?: return@synchronized StoreResult.Failure(StoreError(StoreErrorCode.FILE_NOT_FOUND, id))
                    try {
                        StoreResult.Success(decodeDocument(id, requireNotNull(record.saved)) to record)
                    } catch (error: Exception) {
                        StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, id, cause = error))
                    }
                }
            }
        }

        /** I/Oキュー内で最新世代を読み、対象文書の変更だけを合成します。 */
        internal fun updateLocalDocument(
            id: String,
            update: (LocalDocumentRecord) -> LocalDocumentRecord
        ): StoreResult<LocalDocumentRecord> = synchronized(ioLock) {
            val snapshot = when (val loaded = documents.read()) {
                is StoreResult.Success -> loaded.value
                is StoreResult.Failure -> return@synchronized loaded
            }
            val key = documentKey(id)
            val previous = snapshot.documents.firstOrNull { it.key == key } ?: LocalDocumentRecord(key)
            val next = update(previous)
            when (val published = documents.publish(mapOf(key to previous.revision), listOf(next))) {
                is StoreResult.Success -> StoreResult.Success(published.value.documents.single { it.key == key })
                is StoreResult.Failure -> published
            }
        }

        internal fun localDocuments(): StoreResult<List<LocalDocumentRecord>> =
            when (val loaded = documents.read()) {
                is StoreResult.Success -> StoreResult.Success(loaded.value.documents.filter { it.key.category == dataType.categoryDirName })
                is StoreResult.Failure -> loaded
            }

        internal fun legacyBackupCandidates(): List<LegacyBackupCandidate> =
            legacyAutoSaveRoots.distinct().filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }.flatMap { root ->
                root.listFiles { file -> file.isDirectory && isLegacyChild(file, root) }.orEmpty().toList()
            }.mapNotNull { directory ->
                val category = directory.resolve(dataType.categoryDirName)
                val editing = category.resolve("editing")
                if (!isLegacyChild(category, directory) || !isLegacyChild(editing, category)) return@mapNotNull null
                val files = editing.listFiles { file -> file.isFile && isLegacyChild(file, editing) && file.extension.equals("yml", true) }.orEmpty()
                files.takeIf { it.isNotEmpty() }?.let {
                    LegacyBackupCandidate(directory.name, files.maxOf(File::lastModified), files.size, directory.absolutePath)
                }
            }.sortedByDescending(LegacyBackupCandidate::modified)

        private fun isLegacyChild(file: File, parent: File): Boolean =
            file.exists() && !Files.isSymbolicLink(file.toPath()) && file.toPath().toRealPath().parent == parent.toPath().toRealPath()

        /** 明示選択した旧退避を編集層へ取り込み、保存済み・取得基準と原本は保持します。 */
        internal fun restoreLegacyBackup(location: String): StoreResult<List<LocalDocumentRecord>> = synchronized(ioLock) {
            try {
                val candidate = checkNotNull(legacyBackupCandidates().firstOrNull { it.location == location }) {
                    "選択した旧退避がありません。"
                }
                val category = File(candidate.location).resolve(dataType.categoryDirName)
                val editingDirectory = category.resolve("editing")
                val pendingFile = category.resolve("pending-operations.json")
                check(!pendingFile.exists() || isLegacyChild(pendingFile, category)) { "旧退避の操作記録が候補の外を参照しています。" }
                val operations = if (pendingFile.isFile)
                    pendingOperationJson.decodeFromString<List<PendingStoreOperationRecord>>(pendingFile.readText()).associateBy { it.currentId }
                    else emptyMap()
                val before = when (val loaded = documents.read()) {
                    is StoreResult.Success -> loaded.value
                    is StoreResult.Failure -> return@synchronized loaded
                }
                val records = before.documents.associateBy { it.key }
                val changes = editingDirectory.listFiles { file -> file.isFile && isLegacyChild(file, editingDirectory) && file.extension.equals("yml", true) }.orEmpty().map { file ->
                    val originalDirectory = category.resolve("original")
                    val originalFile = originalDirectory.resolve(file.name)
                    check(isLegacyChild(originalDirectory, category) && isLegacyChild(originalFile, originalDirectory)) { "旧比較基準が候補の外を参照しています。" }
                    val editing = checkNotNull(descriptor.loadBackup(file, editingDirectory)) { "旧編集退避を読み込めません。" }
                    val original = checkNotNull(descriptor.loadBackup(originalFile, originalFile.parentFile)) { "旧比較基準を読み込めません。" }
                    val key = documentKey(editing.id)
                    val previous = records[key] ?: LocalDocumentRecord(key)
                    val pending = operations[editing.id]
                    previous.copy(editing = encodeDocument(editing), original = encodeDocument(original),
                        raw = emptyMap(), pending = pending, deleted = pending?.kind == PendingStoreOperationKind.DELETE, movedTo = null)
                }
                when (val published = documents.publish(changes.associate { it.key to (records[it.key]?.revision ?: 0L) }, changes)) {
                    is StoreResult.Success -> StoreResult.Success(published.value.documents.filter { it.key in changes.map(LocalDocumentRecord::key) })
                    is StoreResult.Failure -> published
                }
            } catch (error: Exception) {
                StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, cause = error))
            }
        }

        /** 取込元の同じ保存世代から型データと入力途中の文字列を一緒に取得します。 */
        internal fun savedDocumentsForImport(): StoreResult<Map<String, Pair<T, Map<String, String>>>> = synchronized(ioLock) {
            when (val initialized = store.list(descriptor)) {
                is StoreResult.Failure -> return@synchronized initialized
                is StoreResult.Success -> Unit
            }
            when (val loaded = localDocuments()) {
                is StoreResult.Failure -> loaded
                is StoreResult.Success -> StoreResult.Success(loaded.value.filter {
                    it.saved != null && !it.savedDeleted && it.movedTo == null
                }.associate { it.key.id to (decodeDocument(it.key.id, requireNotNull(it.saved)) to it.savedRaw.toMap()) })
            }
        }

        /** 明示取得の確認結果だけを確定します。変更済みの保存チェックポイントは上書きしません。 */
        internal fun acceptFetched(
            previous: LocalDocumentSnapshot,
            fetched: Map<String, T>,
            editing: Map<String, T>,
            ids: Set<String>,
            directories: List<String>? = null
        ): StoreResult<List<LocalDocumentRecord>> = synchronized(ioLock) {
            val category = dataType.categoryDirName
            val records = previous.documents.filter { it.key.category == category }.associateBy { it.key.id }
            val referenceChanges = linkedMapOf<String, MutableItemBaseData?>()
            val changes = ids.map { id ->
                val old = records[id] ?: LocalDocumentRecord(documentKey(id))
                val latest = fetched[id]?.let(::encodeDocument)
                val cleanSaved = old.baselineKnown && old.saved == old.baseline &&
                    old.savedRaw.isEmpty() && old.savedPending == null && !old.savedDeleted
                val replaceSaved = cleanSaved || old.saved == null
                if (replaceSaved && dataType == SushiEricDataType.Item) {
                    referenceChanges[id] = fetched[id] as? MutableItemBaseData
                }
                val saved = if (replaceSaved) latest else old.saved
                val nextEditing = editing[id]?.let(::encodeDocument)
                old.copy(baseline = latest, baselineKnown = true,
                    saved = saved, original = saved, editing = nextEditing,
                    savedDeleted = if (replaceSaved) latest == null else old.savedDeleted,
                    deleted = nextEditing == null,
                    raw = old.raw,
                    savedRaw = if (replaceSaved) emptyMap() else old.savedRaw)
            }
            val directoryUpdate = directories?.let { fetchedDirectories ->
                val saved = previous.directories[category].orEmpty()
                val baseline = previous.directoryBaselines[category]
                val merged = if (baseline == null) saved + fetchedDirectories
                    else (fetchedDirectories.toSet() + (saved - baseline)) - (baseline - saved)
                mapOf(category to merged)
            }.orEmpty()
            when (val published = documents.publish(
                changes.associate { it.key to (records[it.key.id]?.revision ?: 0L) }, changes,
                directoryUpdate, directories?.let { mapOf(category to (previous.directoryRevisions[category] ?: 0L)) }.orEmpty(),
                directoryBaselines = directories?.let { mapOf(category to it.toSet()) }.orEmpty()
            )) {
                is StoreResult.Success -> {
                    referenceChanges.forEach { (id, data) ->
                        if (data == null) itemReferences.deleted(id) else itemReferences.saved(id, data.freeze())
                    }
                    StoreResult.Success(published.value.documents.filter { it.key in changes.map(LocalDocumentRecord::key) })
                }
                is StoreResult.Failure -> published
            }
        }

        /** ローカル保存で取得基準は進めません。versions省略は未作成文書（revision 0）だけを受理します。 */
        internal fun saveLocalDocument(
            id: String, data: T, raw: Map<String, String>,
            pending: PendingStoreOperationRecord? = null, deleted: Boolean = false,
            versions: Map<String, LocalDocumentVersion> = emptyMap()
        ): StoreResult<LocalDocumentRecord> = synchronized(ioLock) {
            val text = try { encodeDocument(data) } catch (error: Exception) {
                return@synchronized StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, id, cause = error))
            }
            val before = when (val loaded = documents.read()) {
                is StoreResult.Success -> loaded.value
                is StoreResult.Failure -> return@synchronized loaded
            }
            val key = documentKey(id)
            val records = before.documents.associateBy { it.key }
            val previous = records[key] ?: LocalDocumentRecord(key)
            val sourceId = pending?.sourceId
            val source = sourceId?.takeIf { it != id }?.let { records[documentKey(it)] }
            val baseline = source ?: previous
            val storedPending = when {
                pending == null -> previous.savedPending
                pending.kind == PendingStoreOperationKind.RENAME && source?.savedPending?.kind == PendingStoreOperationKind.CREATE &&
                    source.baseline == null -> PendingStoreOperationRecord(PendingStoreOperationKind.CREATE, id)
                pending.kind == PendingStoreOperationKind.RENAME && source?.savedPending?.sourceId != null ->
                    pending.copy(sourceId = source.savedPending.sourceId)
                else -> pending
            }
            val next = previous.copy(editing = text, original = text, saved = text,
                baseline = baseline.baseline, baselineKnown = baseline.baselineKnown ||
                    (pending?.kind == PendingStoreOperationKind.CREATE && isRemote &&
                        dataType.categoryDirName in before.initializedCategories),
                raw = raw.toMap(), savedRaw = raw.toMap(), pending = storedPending, savedPending = storedPending,
                deleted = deleted, savedDeleted = deleted, movedTo = null)
            val changes = buildList {
                add(next)
                if (pending?.kind in setOf(PendingStoreOperationKind.RENAME, PendingStoreOperationKind.DELETE) && source != null) {
                    add(source.copy(deleted = true, savedDeleted = true, movedTo = id))
                }
            }
            when (val published = documents.publish(changes.associate { change ->
                val observed = versions[change.key.id]
                check(observed == null || observed.key == change.key) { "保存文書の観測元が一致しません。" }
                change.key to (observed?.revision ?: 0L)
            }, changes)) {
                is StoreResult.Success -> {
                    published.value.documents.filter { it.key in changes.map(LocalDocumentRecord::key) }.forEach {
                        versions[it.key.id]?.accepted(it)
                    }
                    if (data is MutableItemBaseData) {
                        sourceId?.takeIf { it != id }?.let(itemReferences::deleted)
                        if (deleted) itemReferences.deleted(id) else itemReferences.saved(id, data.freeze())
                    }
                    StoreResult.Success(published.value.documents.single { it.key == key })
                }
                is StoreResult.Failure -> published
            }
        }

        /** 複製済みの退避内容を直列に保存し、全工程の成功を返します。 */
        internal fun persistBackup(snapshot: LocalBackupSnapshot<T>): CompletableFuture<Boolean> =
            submitBackupIo { persistDocumentBackup(snapshot) }

        private fun persistDocumentBackup(snapshot: LocalBackupSnapshot<T>): Boolean {
            val before = when (val loaded = documents.read()) {
                is StoreResult.Success -> loaded.value
                is StoreResult.Failure -> return false
            }
            val previous = before.documents.associateBy { it.key }
            val pending = snapshot.pending.associateBy { it.currentId }
            val changes = snapshot.entries.map { (id, pair) ->
                val key = documentKey(id)
                val record = previous[key] ?: LocalDocumentRecord(key)
                record.copy(editing = encodeDocument(pair.first), original = encodeDocument(pair.second),
                    raw = snapshot.raw[id].orEmpty(), pending = pending[id], deleted = pending[id]?.kind == PendingStoreOperationKind.DELETE)
            }.toMutableList()
            snapshot.removedIds.filterNot { it in snapshot.entries }
                .filter { snapshot.versions[it]?.backupOwned == true }.forEach { id ->
                previous[documentKey(id)]?.let { record ->
                    changes += record.copy(editing = record.saved, original = record.saved,
                        raw = record.savedRaw, pending = record.savedPending, deleted = record.savedDeleted)
                }
            }
            if (changes.isEmpty()) return true
            val expected = changes.associate { change ->
                val observed = snapshot.versions[change.key.id]
                check(observed == null || observed.key == change.key) { "退避文書の観測元が一致しません。" }
                change.key to (observed?.revision ?: 0L)
            }
            if (changes.any { (previous[it.key]?.revision ?: 0L) != expected.getValue(it.key) }) return false
            val updated = changes.filter { it != previous[it.key] }
            if (updated.isEmpty()) {
                changes.forEach { snapshot.versions[it.key.id]?.accepted(it) }
                return true
            }
            return when (val published = documents.publish(expected, updated)) {
                is StoreResult.Success -> {
                    val updatedKeys = updated.mapTo(mutableSetOf(), LocalDocumentRecord::key)
                    changes.forEach { change ->
                        val record = if (change.key in updatedKeys)
                            published.value.documents.single { it.key == change.key } else change
                        snapshot.versions[change.key.id]?.accepted(record)
                    }
                    true
                }
                is StoreResult.Failure -> false
            }
        }

        val dataType: SushiEricDataType<T>
            get() = descriptor.dataType

        val displayName: String
            get() = descriptor.displayName

        fun createDefault(id: String): T = descriptor.createDefault(id)

        fun duplicateAsNew(data: T, newId: String): T = descriptor.duplicateAsNew(data, newId)

        /**
         * SushiEricDataModel側で定義された検証処理を使って、編集中データの問題を取得します。
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

        internal fun fetchSource(id: String): StoreResult<T> = sourceStore.load(descriptor, id)
        internal fun listSourceResources(): StoreResult<List<StoreResource>> = sourceStore.list(descriptor)
        internal fun listSourceDirectories(): StoreResult<List<String>> = sourceStore.listDirectories(descriptor)

        fun save(fileName: String, data: T): SaveResult {
            return when (val result = saveStore(fileName, data)) {
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

        /** 編集退避の片層だけを更新し、旧退避の原本と保存チェックポイントは変更しません。 */
        fun saveToLocalBackup(fileName: String, subDirName: String, data: T): Boolean {
            if (subDirName !in setOf("editing", "original")) return false
            return try {
                val text = encodeDocument(data.deepCopy().apply { id = fileName })
                updateLocalDocument(fileName) { previous ->
                    if (subDirName == "editing") previous.copy(editing = text) else previous.copy(original = text)
                } is StoreResult.Success
            } catch (error: Exception) {
                logger.error("編集退避を保存できませんでした: {}", fileName, error)
                false
            }
        }

        fun saveToLocalBackup(subDirName: String, data: T): Boolean =
            saveToLocalBackup(data.id, subDirName, data)

        /** 現世代の未保存編集と比較元を返します。保存済みだけの文書は退避対象ではありません。 */
        fun loadBackupPair(fileName: String): Pair<T, T>? = synchronized(ioLock) {
            val record = when (val loaded = localDocuments()) {
                is StoreResult.Success -> loaded.value.firstOrNull { it.key.id == fileName && it.movedTo == null }
                is StoreResult.Failure -> return@synchronized null
            } ?: return@synchronized null
            if (!record.unsaved) return@synchronized null
            val editing = record.editing ?: return@synchronized null
            val original = record.original ?: return@synchronized null
            decodeDocument(fileName, editing) to decodeDocument(fileName, original)
        }

        /** 未保存編集だけを保存チェックポイントへ戻します。旧退避ファイルは削除しません。 */
        fun deleteLocalBackup(fileName: String): Boolean = updateLocalDocument(fileName) {
            it.copy(editing = it.saved, original = it.saved, raw = it.savedRaw,
                pending = it.savedPending, deleted = it.savedDeleted)
        } is StoreResult.Success

        fun clearLocalBackupsExcept(idsToKeep: Set<String> = emptySet()) {
            synchronized(ioLock) {
                val records = when (val loaded = localDocuments()) {
                    is StoreResult.Success -> loaded.value.filter { it.key.id !in idsToKeep && it.unsaved }
                    is StoreResult.Failure -> throw IllegalStateException("編集退避を読み込めませんでした。", loaded.error.cause)
                }
                val changes = records.map { it.copy(editing = it.saved, original = it.saved,
                    raw = it.savedRaw, pending = it.savedPending, deleted = it.savedDeleted) }
                if (changes.isEmpty()) return@synchronized
                check(documents.publish(records.associate { it.key to it.revision }, changes) is StoreResult.Success) {
                    "編集退避を更新できませんでした。"
                }
            }
        }

        fun delete(fileName: String): DeleteResult {
            return when (val result = store.delete(descriptor, fileName)) {
                is StoreResult.Success -> {
                    if (descriptor == EditorDataDescriptors.item) itemReferences.deleted(fileName)
                    DeleteResult.SUCCESS
                }
                is StoreResult.Failure -> result.error.code.toDeleteResult()
            }
        }

        fun rename(oldName: String, newName: String): RenameResult {
            val newId = PublicId.join(PublicId.directoryOf(oldName), newName)
            return renameTo(oldName, newId)
        }

        internal fun loadPendingStoreOperations(): List<PendingStoreOperationRecord> =
            when (val loaded = localDocuments()) {
                is StoreResult.Success -> loaded.value.mapNotNull { it.pending?.takeIf { pending -> pending != it.savedPending } }
                is StoreResult.Failure -> throw IllegalStateException("保存待ち操作を読み込めませんでした。", loaded.error.cause)
            }

        internal fun savePendingStoreOperations(operations: Collection<PendingStoreOperationRecord>): Boolean = synchronized(ioLock) {
            val records = when (val loaded = localDocuments()) {
                is StoreResult.Success -> loaded.value.associateBy { it.key.id }
                is StoreResult.Failure -> return@synchronized false
            }
            val pending = operations.associateBy { it.currentId }
            val changes = (records.keys + pending.keys).mapNotNull { id ->
                val old = records[id] ?: LocalDocumentRecord(documentKey(id))
                val operation = pending[id] ?: old.savedPending
                val next = old.copy(pending = operation, deleted = operation?.kind == PendingStoreOperationKind.DELETE || old.savedDeleted)
                next.takeIf { it != old }
            }
            documents.publish(changes.associate { it.key to (records[it.key.id]?.revision ?: 0L) }, changes) is StoreResult.Success
        }

        /** IDとディレクトリの同時変更も、一つのローカル世代で確定します。 */
        fun renameTo(oldId: String, newId: String): RenameResult = synchronized(ioLock) {
            when (val result = workingStore.renameTo(descriptor, oldId, newId)) {
                is StoreResult.Success -> {
                    if (descriptor == EditorDataDescriptors.item) itemReferences.renamed(oldId, newId)
                    RenameResult.SUCCESS
                }
                is StoreResult.Failure -> result.error.code.toRenameResult()
            }
        }

        internal fun renameStoreTo(oldId: String, newId: String): RenameResult = renameTo(oldId, newId)

        fun createDirectory(directory: String): StoreResult<Unit> =
            store.createDirectory(descriptor, directory)

        fun deleteDirectory(directory: String): StoreResult<Unit> =
            store.deleteDirectory(descriptor, directory)

        /** この削除で確定した文書revisionを返し、画面自身の次の退避へ引き継ぎます。 */
        internal fun deleteDirectoryObserved(directory: String): StoreResult<List<LocalDocumentRecord>> = synchronized(ioLock) {
            when (val deleted = workingStore.deleteDirectorySnapshot(descriptor, directory)) {
                is StoreResult.Success -> StoreResult.Success(deleted.value.documents.filter {
                    it.key.category == dataType.categoryDirName && it.key.id.startsWith("$directory.")
                })
                is StoreResult.Failure -> deleted
            }
        }

        fun listDirectories(): StoreResult<List<String>> =
            store.listDirectories(descriptor)

        fun move(id: String, targetDirectory: String): StoreResult<String> {
            return when (val result = store.move(descriptor, id, targetDirectory)) {
                is StoreResult.Success -> {
                    if (descriptor == EditorDataDescriptors.item) itemReferences.renamed(id, result.value)
                    result
                }
                is StoreResult.Failure -> result
            }
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
