package io.github.sushiericworkspace.sushiericservermanager.editor.service

import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.SavedUploadOperation
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.savedUploadFingerprint
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** 型に変換できない入力はrawに保持し、保存済み側にも同じ文字列を残します。 */
@Serializable
internal data class LocalDocumentRecord(
    val key: EditorDocumentKey,
    val revision: Long = 0,
    val editing: String? = null,
    val original: String? = null,
    val saved: String? = null,
    val baseline: String? = null,
    val baselineKnown: Boolean = false,
    val raw: Map<String, String> = emptyMap(),
    val savedRaw: Map<String, String> = emptyMap(),
    val pending: PendingStoreOperationRecord? = null,
    val savedPending: PendingStoreOperationRecord? = null,
    val deleted: Boolean = false,
    val savedDeleted: Boolean = false,
    val movedTo: String? = null,
    val reflectedSavedHash: String? = null
) {
    val unsaved: Boolean get() =
        editing != saved || raw != savedRaw || pending != savedPending || deleted != savedDeleted

    /** 基準未取得を一致と判定せず、宛先のないローカル文書にも基準を捏造しません。 */
    val unreflected: Boolean? get() =
        if (!baselineKnown) null
        else if (savedPending == null && reflectedSavedHash != null &&
            reflectedSavedHash == savedUploadFingerprint(key, saved, savedDeleted, savedRaw)) false
        else (saved.takeUnless { savedDeleted } != baseline || (!savedDeleted && savedRaw.isNotEmpty()) || savedPending != null)
}

@Serializable
internal data class LocalDocumentSnapshot(
    val source: EditorDocumentSource,
    val generation: Long = 0,
    val documents: List<LocalDocumentRecord> = emptyList(),
    val directories: Map<String, Set<String>> = emptyMap(),
    val directoryRevisions: Map<String, Long> = emptyMap(),
    val initializedCategories: Set<String> = emptySet(),
    val directoryBaselines: Map<String, Set<String>> = emptyMap(),
    val uploads: List<SavedUploadOperation> = emptyList()
)

/**
 * 保存元ごとの編集・保存済み・取得基準を一世代で公開します。
 * 呼出し元のI/Oキューに加えてファイル排他と文書revisionを照合し、古いsnapshotの上書きを拒否します。
 * 公開前に失敗した世代と既存世代は削除せず、currentが指す成功世代だけを読み込みます。
 */
internal class LocalDocumentRepository(
    root: File,
    val source: EditorDocumentSource
) {
    private val directory = root.resolve(source.directory)

    private companion object {
        val publishers = ConcurrentHashMap<Path, Any>()
        val reservedDocuments = ConcurrentHashMap<Path, MutableMap<EditorDocumentKey, String>>()
    }
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val pointer get() = directory.resolve("current")

    @Synchronized
    fun read(): StoreResult<LocalDocumentSnapshot> = result {
        readCurrent()
    }

    /**
     * 変更対象だけを最新世代へ合成します。削除にはnullを使わず、文書の削除状態を保存してください。
     * expectedは呼出し元が観測した文書revision（未作成は0）です。
     */
    @Synchronized
    fun publish(
        expected: Map<EditorDocumentKey, Long>,
        changes: Collection<LocalDocumentRecord>,
        directories: Map<String, Set<String>> = emptyMap(),
        expectedDirectories: Map<String, Long> = emptyMap(),
        initializeCategories: Set<String> = emptySet(),
        directoryBaselines: Map<String, Set<String>> = emptyMap(),
        upload: SavedUploadOperation? = null,
        expectedUploadRevision: Long = 0,
        uploadOwner: String? = null
    ): StoreResult<LocalDocumentSnapshot> = result {
        Files.createDirectories(directory.toPath())
        val canonical = directory.toPath().toRealPath()
        synchronized(publishers.computeIfAbsent(canonical) { Any() }) {
            FileChannel.open(directory.resolve("publisher.lock").toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    val current = readCurrent()
                    changes.forEach { change ->
                        val owner = reservedDocuments[canonical]?.get(change.key)
                        check(owner == null || owner == uploadOwner) { "アップロード確認中の文書は変更できません。" }
                    }
                    val uploads = current.uploads.associateByTo(linkedMapOf()) { it.id }
                    upload?.let {
                        check(it.target == source && it.validKeys) { "アップロードの宛先または対象文書が不正です。" }
                        check((uploads[it.id]?.revision ?: 0L) == expectedUploadRevision) { "アップロード結果が更新されています。" }
                        uploads[it.id] = it.copy(revision = expectedUploadRevision + 1)
                    }
                    val records = current.documents.associateByTo(linkedMapOf()) { it.key }
                    require(changes.map { it.key }.toSet().size == changes.size) { "同じ文書を重複更新できません。" }
                    changes.forEach { change ->
                        require(change.key.source == source) { "異なる保存元の文書は保存できません。" }
                        val revision = records[change.key]?.revision ?: 0L
                        check(expected[change.key] == revision) { "文書が更新されています。最新の編集内容から保存してください。" }
                        records[change.key] = change.copy(revision = revision + 1)
                    }
                    val directoryChanges = directories.keys + directoryBaselines.keys
                    directoryChanges.forEach { category ->
                        check(expectedDirectories[category] == (current.directoryRevisions[category] ?: 0L)) {
                            "ディレクトリが更新されています。最新の一覧から再操作してください。"
                        }
                    }
                    val next = current.copy(generation = current.generation + 1,
                        documents = records.values.toList(), directories = current.directories + directories,
                        directoryRevisions = current.directoryRevisions + directoryChanges.associateWith {
                            (current.directoryRevisions[it] ?: 0L) + 1
                        }, initializedCategories = current.initializedCategories + initializeCategories,
                        directoryBaselines = current.directoryBaselines + directoryBaselines,
                        uploads = uploads.values.toList())
                    publishGeneration(next)
                    next
                }
            }
        }
    }

    /** 同じ保存元の別Serviceからの書込みも、確認中の対象keyだけ止めます。 */
    fun reserve(owner: String, expected: Map<EditorDocumentKey, Long>): StoreResult<Unit> = result {
        Files.createDirectories(directory.toPath())
        val canonical = directory.toPath().toRealPath()
        synchronized(publishers.computeIfAbsent(canonical) { Any() }) {
            val current = readCurrent().documents.associateBy { it.key }
            val reservations = reservedDocuments.computeIfAbsent(canonical) { mutableMapOf() }
            expected.forEach { (key, revision) ->
                check(key.source == source && (current[key]?.revision ?: 0L) == revision) { "確認対象の文書が更新されています。" }
                check(reservations[key] == null || reservations[key] == owner) { "この文書は別のアップロードで使用中です。" }
            }
            expected.keys.forEach { reservations[it] = owner }
        }
    }

    /** 取得したownerの予約だけを解除します。台帳の不明状態は消しません。 */
    fun release(owner: String) {
        if (!directory.isDirectory) return
        val canonical = directory.toPath().toRealPath()
        synchronized(publishers.computeIfAbsent(canonical) { Any() }) {
            reservedDocuments[canonical]?.entries?.removeIf { it.value == owner }
        }
    }

    private fun readCurrent(): LocalDocumentSnapshot {
        if (!pointer.exists()) return LocalDocumentSnapshot(source)
        val name = pointer.readText(Charsets.UTF_8)
        check(name.matches(Regex("[0-9]+-[0-9a-f-]{36}\\.json"))) { "保存世代の参照が不正です。" }
        val snapshot = json.decodeFromString<LocalDocumentSnapshot>(directory.resolve(name).readText(Charsets.UTF_8))
        check(snapshot.source == source && snapshot.documents.all { it.key.source == source }) { "保存元が一致しません。" }
        check(snapshot.documents.map { it.key }.toSet().size == snapshot.documents.size) { "保存文書が重複しています。" }
        check(snapshot.uploads.map { it.id }.toSet().size == snapshot.uploads.size) { "アップロード台帳が重複しています。" }
        check(snapshot.uploads.all { it.target == source && it.validKeys }) { "アップロード台帳の宛先または対象文書が不正です。" }
        return snapshot
    }

    private fun publishGeneration(snapshot: LocalDocumentSnapshot) {
        val name = "${snapshot.generation}-${UUID.randomUUID()}.json"
        val generation = directory.resolve(name).toPath()
        writeNew(generation.toFile(), json.encodeToString(snapshot))
        val temporary = directory.resolve(".current-${UUID.randomUUID()}")
        writeNew(temporary, name)
        Files.move(temporary.toPath(), pointer.toPath(),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun writeNew(file: File, text: String) {
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
            val bytes = java.nio.ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
    }

    private fun <T> result(operation: () -> T): StoreResult<T> = try {
        StoreResult.Success(operation())
    } catch (error: Exception) {
        StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, detail = error.message, cause = error))
    }
}
