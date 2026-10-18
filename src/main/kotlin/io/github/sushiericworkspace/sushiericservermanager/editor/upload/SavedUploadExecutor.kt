package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.PublicId
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentKey
import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentRecord
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import java.util.UUID

/**
 * 確認済み計画を既存の接続I/Oキュー内で実行します。
 * 書込前に台帳を確定し、成功応答だけでなく読戻し一致を確認します。
 * WRITING/UNKNOWNはこの入口から再送できず、readbackで読取だけを行います。
 */
internal class SavedUploadExecutor(
    private val origin: EditorDataService,
    private val target: EditorDataService
) {
    fun execute(
        operation: SavedUploadOperation,
        cancelled: () -> Boolean = { false },
        onProgress: (SavedUploadOperation) -> Unit = {}
    ): StoreResult<SavedUploadOperation> {
        if (!target.isRemote || !target.sourceStore.isAvailable) return failure(StoreErrorCode.STORE_UNAVAILABLE)
        if (!target.sourceStore.isWritable) return failure(StoreErrorCode.PERMISSION_DENIED)
        if (operation.target != target.documentSource || operation.inputs.any { it.key.source != origin.documentSource })
            return failure(StoreErrorCode.INVALID_ID, "確認した保存元または宛先が一致しません。")
        if (!operation.validKeys || operation.inputs.any { !it.deleted && it.raw.isNotEmpty() })
            return failure(StoreErrorCode.VALIDATION_FAILED, "未確定入力または予約対象の不整合があるため送信しません。")
        val owner = UUID.randomUUID().toString()
        val originExpected = operation.inputs.associate { input ->
            input.key to (operation.targetRevisions.firstOrNull { it.key == input.key }?.revision ?: input.revision)
        }
        val reserved = origin.documents.reserve(owner, originExpected)
        if (reserved is StoreResult.Failure) return reserved
        try {
            val targetReservation = target.documents.reserve(owner, operation.targetRevisions.associate { it.key to it.revision })
            if (targetReservation is StoreResult.Failure) return targetReservation
            try {
                val sourceSnapshot = when (val loaded = origin.documents.read()) {
                    is StoreResult.Success -> loaded.value
                    is StoreResult.Failure -> return loaded
                }
                if (operation.inputs.any { input ->
                    val record = sourceSnapshot.documents.firstOrNull { it.key == input.key }
                    record == null || record.saved != input.saved || record.savedDeleted != input.deleted || record.savedRaw != input.raw ||
                        (record.savedPending != input.pending && !(record.savedPending == null &&
                            record.reflectedSavedHash == savedUploadFingerprint(input.key, input.saved, input.deleted, input.raw)))
                }) return failure(StoreErrorCode.IO_ERROR, "確認した保存済み内容が変更されています。")
                var current = when (val stored = loadOperation(operation, owner)) {
                    is StoreResult.Failure -> return stored
                    is StoreResult.Success -> stored.value
                }
                if (current.needsReadback) return failure(StoreErrorCode.IO_ERROR, "結果不明の工程を先に読戻しで確認してください。")
                for (index in current.steps.indices) {
                    val step = current.steps[index]
                    if (step.state == SavedUploadState.CONFIRMED) continue
                    if (cancelled()) return update(current, index, SavedUploadState.CANCELLED, owner, "未実行の工程をキャンセルしました。")
                    if (!target.sourceStore.isAvailable || !target.sourceStore.isWritable)
                        return update(current, index, SavedUploadState.FAILED, owner, "未接続または読取専用です。")
                    val before = read(step.key)
                    if (before is StoreResult.Failure) return update(current, index, SavedUploadState.FAILED, owner, before.error.detail)
                    if ((before as StoreResult.Success).value != step.before)
                        return update(current, index, SavedUploadState.FAILED, owner, "確認後にサーバーの内容が変わりました。再確認が必要です。")
                    step.destination?.let { destination ->
                        when (val existing = read(destination)) {
                            is StoreResult.Failure -> return update(current, index, SavedUploadState.FAILED, owner, existing.error.detail)
                            is StoreResult.Success -> if (existing.value != null)
                                return update(current, index, SavedUploadState.FAILED, owner, "移動先にデータがあります。再確認が必要です。")
                        }
                    }
                    if (step.destination == null && step.before == step.after) {
                        current = when (val confirmed = confirm(current, index, owner)) {
                            is StoreResult.Success -> confirmed.value
                            is StoreResult.Failure -> return confirmed
                        }
                        onProgress(current)
                        continue
                    }
                    if (step.key.category == target.items.dataType.categoryDirName && step.after == null && step.destination == null) {
                        val references = SavedUploadReferences(target).beforeItemDelete(step.key.id)
                        if (references is StoreResult.Failure)
                            return update(current, index, SavedUploadState.FAILED, owner, references.error.detail)
                    }
                    current = when (val marked = update(current, index, SavedUploadState.WRITING, owner)) {
                        is StoreResult.Success -> marked.value
                        is StoreResult.Failure -> return marked
                    }
                    val written = write(step)
                    if (matchesAfter(step)) {
                        current = when (val confirmed = confirm(current, index, owner)) {
                            is StoreResult.Success -> confirmed.value
                            is StoreResult.Failure -> return confirmed
                        }
                        onProgress(current)
                    } else {
                        val definitive = written is StoreResult.Failure && written.error.code in setOf(
                            StoreErrorCode.PERMISSION_DENIED, StoreErrorCode.INVALID_ID,
                            StoreErrorCode.VALIDATION_FAILED, StoreErrorCode.UNSUPPORTED_FORMAT)
                        return update(current, index, if (definitive) SavedUploadState.FAILED else SavedUploadState.UNKNOWN, owner,
                            "反映を確認できません。保存済み内容と台帳を保持しています。")
                    }
                }
                return StoreResult.Success(current)
            } finally { target.documents.release(owner) }
        } catch (error: Exception) {
            return StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, detail = error.message, cause = error))
        } finally { origin.documents.release(owner) }
    }

    /** 通信への書込は行いません。保存済みの同一要求だけを、実行と排他して照合します。 */
    fun readback(operation: SavedUploadOperation): StoreResult<SavedUploadOperation> {
        if (operation.target != target.documentSource || !target.sourceStore.isAvailable)
            return failure(StoreErrorCode.STORE_UNAVAILABLE)
        val saved = when (val loaded = existingOperation(operation)) {
            is StoreResult.Success -> loaded.value
            is StoreResult.Failure -> return loaded
        }
        val owner = UUID.randomUUID().toString()
        val reserved = target.documents.reserve(owner, saved.targetRevisions.associate { it.key to it.revision })
        if (reserved is StoreResult.Failure) return reserved
        try {
            var current = when (val loaded = existingOperation(saved)) {
                is StoreResult.Success -> loaded.value
                is StoreResult.Failure -> return loaded
            }
            for (index in current.steps.indices) {
                val step = current.steps[index]
                if (step.state !in setOf(SavedUploadState.WRITING, SavedUploadState.UNKNOWN)) continue
                val result = if (matchesAfter(step)) confirm(current, index, owner)
                    else update(current, index, SavedUploadState.UNKNOWN, owner, "読戻しで一致を確認できません。自動再送しません。")
                current = when (result) {
                    is StoreResult.Success -> result.value
                    is StoreResult.Failure -> return result
                }
            }
            return StoreResult.Success(current)
        } catch (error: Exception) {
            return StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, detail = error.message, cause = error))
        } finally { target.documents.release(owner) }
    }

    private fun existingOperation(operation: SavedUploadOperation): StoreResult<SavedUploadOperation> =
        when (val loaded = target.documents.read()) {
            is StoreResult.Failure -> loaded
            is StoreResult.Success -> {
                val saved = loaded.value.uploads.firstOrNull { it.id == operation.id }
                if (saved == null || saved != operation)
                    failure(StoreErrorCode.IO_ERROR, "保存済みの同じ要求・状態・revisionから読戻しを開始してください。")
                else StoreResult.Success(saved)
            }
        }

    private fun loadOperation(operation: SavedUploadOperation, owner: String): StoreResult<SavedUploadOperation> {
        val snapshot = when (val loaded = target.documents.read()) {
            is StoreResult.Success -> loaded.value
            is StoreResult.Failure -> return loaded
        }
        val existing = snapshot.uploads.firstOrNull { it.id == operation.id }
        if (existing != null) {
            if (existing != operation) return failure(StoreErrorCode.IO_ERROR, "操作台帳が更新されています。")
            return StoreResult.Success(existing)
        }
        val keys = operation.steps.flatMapTo(mutableSetOf()) { listOfNotNull(it.key, it.destination) }
        if (snapshot.uploads.any { prior ->
            prior.id != operation.id && prior.needsReadback && prior.steps.any { it.key in keys || it.destination in keys }
        }) return failure(StoreErrorCode.IO_ERROR, "同じ対象の結果不明操作を先に読戻しで確認してください。")
        return persist(operation, owner)
    }

    private fun update(
        operation: SavedUploadOperation, index: Int, state: SavedUploadState, owner: String, detail: String? = null
    ): StoreResult<SavedUploadOperation> = persist(operation.copy(
        steps = operation.steps.mapIndexed { position, step ->
            if (position == index) step.copy(state = state, detail = detail) else step
        }), owner)

    private fun persist(operation: SavedUploadOperation, owner: String): StoreResult<SavedUploadOperation> =
        when (val published = target.documents.publish(emptyMap(), emptyList(), upload = operation,
            expectedUploadRevision = operation.revision, uploadOwner = owner)) {
            is StoreResult.Failure -> published
            is StoreResult.Success -> StoreResult.Success(published.value.uploads.single { it.id == operation.id })
        }

    private fun confirm(operation: SavedUploadOperation, index: Int, owner: String): StoreResult<SavedUploadOperation> {
        val step = operation.steps[index]
        val snapshot = when (val loaded = target.documents.read()) {
            is StoreResult.Success -> loaded.value
            is StoreResult.Failure -> return loaded
        }
        val keys = listOfNotNull(step.key, step.destination)
        val changes = keys.map { key ->
            val old = snapshot.documents.firstOrNull { it.key == key } ?: LocalDocumentRecord(key)
            val finalForKey = operation.steps.indexOfLast { it.key == key || it.destination == key } == index
            val input = operation.inputs.firstOrNull { it.key == key }
            val reflectsSaved = finalForKey && input != null && old.saved == input.saved &&
                old.savedDeleted == input.deleted && old.savedRaw == input.raw && old.savedPending == input.pending
            old.copy(baseline = if (step.destination != null && key == step.key) null else step.after,
                baselineKnown = true,
                reflectedSavedHash = if (reflectsSaved) savedUploadFingerprint(key, old.saved, old.savedDeleted, old.savedRaw)
                    else null,
                savedPending = if (reflectsSaved) null else old.savedPending,
                pending = if (reflectsSaved && old.pending == input?.pending) null else old.pending)
        }
        val expected = keys.associateWith { key -> operation.targetRevisions.single { it.key == key }.revision }
        val next = operation.copy(
            steps = operation.steps.mapIndexed { position, value ->
                if (position == index) value.copy(state = SavedUploadState.CONFIRMED, detail = null) else value
            },
            targetRevisions = operation.targetRevisions.map {
                if (it.key in keys) it.copy(revision = expected.getValue(it.key) + 1) else it
            })
        // 旧/新基準と台帳を一緒に確定するのはローカル世代だけで、通信先の原子性は表しません。
        return when (val published = target.documents.publish(expected, changes, upload = next,
            expectedUploadRevision = operation.revision, uploadOwner = owner)) {
            is StoreResult.Failure -> published
            is StoreResult.Success -> StoreResult.Success(published.value.uploads.single { it.id == operation.id })
        }
    }

    private fun matchesAfter(step: SavedUploadStep): Boolean {
        val source = read(step.key)
        if (step.destination == null) return source is StoreResult.Success && source.value == step.after
        if (source !is StoreResult.Success || source.value != null) return false
        val destination = read(step.destination)
        return destination is StoreResult.Success && destination.value == step.after
    }

    private fun read(key: EditorDocumentKey): StoreResult<String?> = when (key.category) {
        target.items.dataType.categoryDirName -> read(target.items, key.id)
        target.ores.dataType.categoryDirName -> read(target.ores, key.id)
        target.recipes.dataType.categoryDirName -> read(target.recipes, key.id)
        "text" -> when (val loaded = target.sourceStore.readText(key.id)) {
            is StoreResult.Success -> loaded
            is StoreResult.Failure -> if (loaded.error.code == StoreErrorCode.FILE_NOT_FOUND) StoreResult.Success(null) else loaded
        }
        else -> failure(StoreErrorCode.UNSUPPORTED_FORMAT, "商品はDB適用経路が必要なためアップロードを保留しています。")
    }

    private fun <T : ManagedData<T, *>> read(access: EditorDataService.DataAccess<T>, id: String): StoreResult<String?> =
        when (val loaded = target.sourceStore.load(access.descriptor, id)) {
            is StoreResult.Success -> StoreResult.Success(access.encodeDocument(loaded.value.apply { this.id = id }))
            is StoreResult.Failure -> if (loaded.error.code == StoreErrorCode.FILE_NOT_FOUND) StoreResult.Success(null) else loaded
        }

    private fun write(step: SavedUploadStep): StoreResult<Unit> = when (step.key.category) {
        target.items.dataType.categoryDirName -> write(target.items, step)
        target.ores.dataType.categoryDirName -> write(target.ores, step)
        target.recipes.dataType.categoryDirName -> write(target.recipes, step)
        "text" -> step.after?.let { target.sourceStore.writeText(step.key.id, it) }
            ?: failure(StoreErrorCode.UNSUPPORTED_FORMAT, "設定ファイルの削除には対応していません。")
        else -> failure(StoreErrorCode.UNSUPPORTED_FORMAT, "商品はDB適用経路が必要です。")
    }

    private fun <T : ManagedData<T, *>> write(
        access: EditorDataService.DataAccess<T>, step: SavedUploadStep
    ): StoreResult<Unit> {
        val destination = step.destination
        if (destination != null) {
            val oldDirectory = PublicId.directoryOf(step.key.id)
            val newDirectory = PublicId.directoryOf(destination.id)
            return when {
                oldDirectory == newDirectory -> target.sourceStore.rename(
                    access.descriptor, step.key.id, PublicId.nameOf(destination.id))
                PublicId.nameOf(step.key.id) == PublicId.nameOf(destination.id) ->
                    when (val result = target.sourceStore.move(access.descriptor, step.key.id, newDirectory.joinToString("."))) {
                        is StoreResult.Success -> StoreResult.Success(Unit)
                        is StoreResult.Failure -> result
                    }
                else -> failure(StoreErrorCode.INVALID_ID, "改名とディレクトリ移動は工程を分けて確認してください。")
            }
        }
        return if (step.after == null) target.sourceStore.delete(access.descriptor, step.key.id)
        else target.sourceStore.save(access.descriptor, step.key.id, access.decodeDocument(step.key.id, step.after))
    }

    private fun failure(code: StoreErrorCode, detail: String? = null) = StoreResult.Failure(StoreError(code, detail = detail))
}
