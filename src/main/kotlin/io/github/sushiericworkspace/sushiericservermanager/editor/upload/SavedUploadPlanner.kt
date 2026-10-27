package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.PublicId
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseDataView
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.render.ItemPreviewContent
import io.github.sushiericworkspace.sushiericservermanager.editor.render.ItemPreviewReview
import io.github.sushiericworkspace.sushiericservermanager.editor.render.itemPreviewReviews
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeDataView
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.DataFieldPath
import io.github.sushiericworkspace.sushiericservermanager.editor.service.*
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import java.util.UUID
import org.slf4j.LoggerFactory

/** 保存元の選択と宛先IDです。編集中データは参照しません。 */
internal data class SavedUploadSelection(
    val key: EditorDocumentKey,
    val targetId: String = key.id,
    val projection: SavedUploadProjection? = null
)

internal data class SavedUploadCandidate(
    val input: SavedUploadDocument,
    val targetKey: EditorDocumentKey,
    val relocationKey: EditorDocumentKey?,
    val before: String?,
    val merge: SavedUploadMerge,
    val baselineKnown: Boolean,
    val previews: List<ItemPreviewReview> = emptyList(),
    val recipePreview: RecipeUploadPreview? = null,
    val itemPreview: ((Set<DataFieldPath>) -> ItemBaseDataView?)? = null
)

/** IO段階でdecodeした固定sideと、同じmerge選択の読取投影。保存・通信作用を持たない。 */
internal data class RecipeUploadPreview(
    val base: RecipeDataView?, val saved: RecipeDataView?, val latest: RecipeDataView?,
    val localItems: Map<ItemInternalId, ItemBaseDataView>?,
    val serverItems: Map<ItemInternalId, ItemBaseDataView>?,
    val planned: (Set<DataFieldPath>) -> RecipeDataView?
)

/** 通信の読取と文書revisionを固定した確認入力です。確認取消では公開・通信書込とも行いません。 */
internal data class SavedUploadPreview(
    val target: EditorDocumentSource.Server,
    val inputs: List<SavedUploadDocument>,
    val versions: List<SavedUploadTargetVersion>,
    val candidates: List<SavedUploadCandidate>
)

/** 接続のI/Oキュー上で保存済み世代から確認入力と通信工程を組み立てます。 */
internal class SavedUploadPlanner(private val origin: EditorDataService, private val target: EditorDataService) {
    fun preview(selections: List<SavedUploadSelection>): StoreResult<SavedUploadPreview> = result {
        checkTarget()
        val source = origin.documents.read().value()
        val destination = target.documents.read().value()
        val selected = selections.distinctBy { it.key }.toMutableList()
        require(selections.size == selected.size) { "同じ文書が重複しています。" }
        require(selected.all { it.key.source == source.source }) { "保存元が一致しません。" }
        require(selected.all { it.projection == null || it.key.source == EditorDocumentSource.Local }) {
            "コピー投影はローカル保存済み文書だけに使用できます。"
        }
        val records = source.documents.associateBy { it.key }
        for (selection in selected.toList()) {
            val moved = records[selection.key]?.movedTo ?: continue
            selected.remove(selection)
            val replacement = if (selection.projection == null) SavedUploadSelection(selection.key.copy(id = moved))
                else selection.copy(key = selection.key.copy(id = moved))
            if (selected.none { it.key == replacement.key }) selected += replacement
        }
        includeRequiredItems(selected, records)
        val inputs = linkedMapOf<EditorDocumentKey, SavedUploadDocument>()
        val versions = linkedMapOf<EditorDocumentKey, Long>()
        val candidates = mutableListOf<SavedUploadCandidate>()
        val displaySnapshots = linkedMapOf<EditorDocumentKey, Triple<String?, String?, String?>>()
        fun reserve(key: EditorDocumentKey) {
            versions[key] = destination.documents.firstOrNull { it.key == key }?.revision ?: 0
        }
        for (selection in selected) {
            val record = records[selection.key] ?: fail(StoreErrorCode.FILE_NOT_FOUND, selection.key.id)
            if (record.saved == null && !record.savedDeleted)
                fail(StoreErrorCode.VALIDATION_FAILED, "ローカル保存済み本文がありません: " + record.key.id)
            if (!record.savedDeleted && record.savedRaw.isNotEmpty())
                fail(StoreErrorCode.VALIDATION_FAILED, "未確定入力をローカル保存済みのため送信できません: " + record.key.id)
            if (record.key.category == target.shops.dataType.categoryDirName)
                fail(StoreErrorCode.UNSUPPORTED_FORMAT, "商品の反映にはDB適用経路が必要です。ローカル内容は保持しています。")
            val key = record.key.copy(source = destination.source, id = selection.targetId)
            require(selection.projection == null || !record.savedDeleted) { "コピー予約に削除済み文書は使用できません。" }
            val local = record.saved.takeUnless { record.savedDeleted }?.let { project(key, it, selection.projection) }
            val input = fixed(record).let { fixed ->
                if (selection.projection == null) fixed else fixed.copy(
                    projectionHash = selection.projection.fingerprint(record.key, key), projectedSaved = local)
            }
            val potential = setOfNotNull(key, input.pending?.sourceId?.let { key.copy(id = it) })
            if (destination.uploads.any { operation -> operation.needsReadback &&
                operation.steps.any { it.key in potential || it.destination in potential } })
                fail(StoreErrorCode.IO_ERROR, "同じ対象の結果不明操作を先に読戻しで確認してください。")
            val previous = receipt(destination, input, key)?.takeIf {
                if (selection.projection == null) it.second.projectionHash == null && it.second.projectedSaved == null
                else it.second.projectionHash == input.projectionHash && it.second.projectedSaved != null
            }
            if (selection.projection == null && previous != null && savedUploadFingerprint(input.key, input.saved, input.deleted, input.raw) ==
                savedUploadFingerprint(previous.second.key, previous.second.saved, previous.second.deleted, previous.second.raw) &&
                (input.pending == null || input.pending == previous.second.pending)) continue
            val last = lastConfirmedTouch(destination, key)
            if (selection.projection == null && input.pending?.kind == PendingStoreOperationKind.RENAME && input.pending.sourceId == key.id &&
                last != null && last.second.key == key && last.second.destination != null)
                fail(StoreErrorCode.IO_ERROR, "部分成功した改名・移動の現在位置と保留操作が一致しません。旧要求の台帳とローカル文書を保持しています。結果を確認してから再計画してください。")
            val relocated = last != null && last.second.destination == key && last.first.inputs.any {
                it.key == input.key && compatiblePending(input, it)
            }
            val relocation = if (selection.projection == null && previous == null && !relocated &&
                record.savedPending?.kind in setOf(PendingStoreOperationKind.RENAME, PendingStoreOperationKind.DELETE)) {
                record.savedPending?.sourceId?.let { oldId ->
                    record.key.copy(source = destination.source, id = oldId).takeUnless { it == key }
                }
            } else null
            val before = read(relocation ?: key).value()
            val latest = before?.let { rekey(key, it) }
            if (selection.projection != null && local != null && latest != null &&
                !selection.projection.matchesTarget(target, key, local, latest))
                fail(StoreErrorCode.IO_ERROR, "予約後に宛先の内部IDが変わりました。再割当てまたは再予約してください: " + key.id)
            val baseline = when {
                previous != null && selection.projection != null -> previous.second.projectedSaved
                previous != null -> previous.second.saved.takeUnless { previous.second.deleted }?.let { rekey(key, it) }
                record.key == key -> record.baseline?.let { rekey(key, it) }
                else -> null
            }
            val known = previous != null || (record.key == key && record.baselineKnown)
            inputs[input.key] = input
            relocation?.let { old ->
                val oldRecord = records[record.key.copy(id = old.id)]
                    ?: fail(StoreErrorCode.FILE_NOT_FOUND, "改名前のローカル文書がありません: " + old.id)
                inputs[oldRecord.key] = fixed(oldRecord)
                reserve(old)
                val middle = intermediate(old, key)
                if (!input.deleted && middle != old && middle != key) {
                    if (read(middle).value() != null) fail(StoreErrorCode.ALREADY_EXISTS, middle.id)
                    records[record.key.copy(id = middle.id)]?.let { intermediateRecord ->
                        inputs[intermediateRecord.key] = fixed(intermediateRecord)
                    }
                    reserve(middle)
                }
                if (read(key).value() != null) fail(StoreErrorCode.ALREADY_EXISTS, key.id)
            }
            reserve(key)
            displaySnapshots[key] = Triple(baseline, local, latest)
            candidates += SavedUploadCandidate(input, key, relocation, before,
                merge(key, baseline, local, latest, known), known)
        }
        val localItems = runCatching {
            val items = records.values.filter { it.key.category == target.items.dataType.categoryDirName && !it.savedDeleted }
                .mapNotNull { record -> record.saved?.let { target.items.decodeDocument(record.key.id, it).freeze() } }
                .associateByTo(linkedMapOf()) { it.internalId }
            candidates.filter { it.targetKey.category == target.items.dataType.categoryDirName }.forEach { candidate ->
                displaySnapshots.getValue(candidate.targetKey).second?.let { body ->
                    val item = target.items.decodeDocument(candidate.targetKey.id, body).freeze()
                    items[item.internalId] = item
                }
            }
            items
        }.getOrNull()
        val needsReferences = candidates.any { it.targetKey.category in setOf(target.ores.dataType.categoryDirName, target.recipes.dataType.categoryDirName) }
        val serverItems = if (needsReferences) sourcePreviewItems() else emptyMap()
        val displayed = candidates.map { candidate ->
            val (base, saved, latest) = displaySnapshots.getValue(candidate.targetKey)
            candidate.copy(previews = previewSide(candidate.targetKey, "比較元", base, null) +
                previewSide(candidate.targetKey, "保存済み", saved, localItems) +
                previewSide(candidate.targetKey, "最新サーバー", latest, serverItems),
                recipePreview = recipePreview(candidate, base, saved, latest, localItems, serverItems),
                itemPreview = itemPreview(candidate, base, saved, latest))
        }
        SavedUploadPreview(destination.source as EditorDocumentSource.Server, inputs.values.toList(),
            versions.map { SavedUploadTargetVersion(it.key, it.value) }, displayed)
    }

    private fun itemPreview(
        candidate: SavedUploadCandidate, base: String?, saved: String?, latest: String?
    ): ((Set<DataFieldPath>) -> ItemBaseDataView?)? {
        if (candidate.targetKey.category != target.items.dataType.categoryDirName) return null
        return runCatching {
            fun decode(body: String?) = body?.let { target.items.decodeDocument(candidate.targetKey.id, it) }
            val before = decode(base)
            val local = decode(saved)
            val remote = decode(latest)
            val outputId = candidate.relocationKey?.id?.takeIf { candidate.input.deleted } ?: candidate.targetKey.id
            val resolve: (Set<DataFieldPath>) -> ItemBaseDataView? = { paths ->
                val value = if (candidate.merge.previewWithLocal != null) candidate.merge.previewWithLocal.invoke(paths) as? MutableItemBaseData
                else when (candidate.merge.resolveWithLocal(paths)) {
                    saved -> local
                    latest -> remote
                    base -> before
                    else -> null
                }
                value?.deepCopy()?.apply { id = outputId }
            }
            resolve
        }.onFailure { LoggerFactory.getLogger(javaClass).warn("反映予定の参照定義を準備できませんでした", it) }.getOrNull()
    }

    private fun recipePreview(
        candidate: SavedUploadCandidate, base: String?, saved: String?, latest: String?,
        localItems: Map<ItemInternalId, ItemBaseDataView>?, serverItems: Map<ItemInternalId, ItemBaseDataView>?
    ): RecipeUploadPreview? {
        if (candidate.targetKey.category != target.recipes.dataType.categoryDirName) return null
        return runCatching {
            fun decode(body: String?) = body?.let { target.recipes.decodeDocument(candidate.targetKey.id, it) }
            val before = decode(base)
            val local = decode(saved)
            val remote = decode(latest)
            RecipeUploadPreview(before, local, remote, localItems, serverItems) { paths ->
                if (candidate.merge.previewWithLocal != null) candidate.merge.previewWithLocal.invoke(paths) as? RecipeDataView
                else when (candidate.merge.resolveWithLocal(paths)) {
                    saved -> local
                    latest -> remote
                    base -> before
                    else -> null
                }
            }
        }.onFailure { LoggerFactory.getLogger(javaClass).warn("レシピ盤面を準備できませんでした", it) }.getOrNull()
    }

    // 表示の追加読取失敗は通信候補の拒否条件にせず、未取得表示へ渡す。
    private fun sourcePreviewItems(): Map<ItemInternalId, ItemBaseDataView>? { return runCatching {
        val resources = when (val listed = target.sourceStore.list(target.items.descriptor)) {
            is StoreResult.Success -> listed.value
            is StoreResult.Failure -> return null
        }
        resources.map { resource ->
            val loaded = target.sourceStore.load(target.items.descriptor, resource.id)
            val item = when (loaded) {
                is StoreResult.Success -> loaded.value
                is StoreResult.Failure -> return null
            }
            item.freeze().copy(id = resource.id)
        }.associateBy { it.internalId }
    }.onFailure { LoggerFactory.getLogger(javaClass).warn("参照プレビュー一覧を取得できませんでした", it) }.getOrNull() }

    private fun previewSide(key: EditorDocumentKey, label: String, body: String?, items: Map<ItemInternalId, ItemBaseDataView>?): List<ItemPreviewReview> {
        if (body == null) return emptyList()
        return try {
            val data = when (key.category) {
                target.items.dataType.categoryDirName -> target.items.decodeDocument(key.id, body)
                target.ores.dataType.categoryDirName -> target.ores.decodeDocument(key.id, body)
                target.recipes.dataType.categoryDirName -> target.recipes.decodeDocument(key.id, body)
                else -> null
            }
            itemPreviewReviews(label, data, items)
        } catch (error: Exception) {
            LoggerFactory.getLogger(javaClass).warn("比較プレビューを準備できませんでした", error)
            listOf(ItemPreviewReview(label, label + ": " + key.id, ItemPreviewContent.unavailable(key.id)))
        }
    }

    /** UIで選択した競合値だけを使い、最終参照と正式保存のvalidationを通信作用前に確認します。 */
    fun build(
        preview: SavedUploadPreview,
        localPaths: Map<EditorDocumentKey, Set<DataFieldPath>>,
        confirmUnknownBaseline: Boolean = false
    ): StoreResult<SavedUploadOperation> = result {
        checkTarget()
        require(preview.target == target.documentSource) { "確認した宛先が一致しません。" }
        require(confirmUnknownBaseline || preview.candidates.all { it.baselineKnown }) { "基準未取得の文書を先に確認してください。" }
        val steps = mutableListOf<SavedUploadStep>()
        for (candidate in preview.candidates) {
            val conflicts = candidate.merge.conflicts.mapTo(mutableSetOf()) { it.path }
            require(conflicts.isEmpty() || candidate.input.key in localPaths) { "競合の採用値を選択してください。" }
            require(localPaths[candidate.input.key].orEmpty().all { it in conflicts }) { "確認対象外の競合値が含まれています。" }
            val resolved = candidate.merge.resolveWithLocal(localPaths[candidate.input.key].orEmpty())
            val old = candidate.relocationKey
            if (old != null && candidate.input.deleted) {
                steps += SavedUploadStep(old, candidate.before, resolved?.let { rekey(old, it) })
                steps += SavedUploadStep(candidate.targetKey, null, null)
            } else if (old != null && resolved != null) {
                val middle = intermediate(old, candidate.targetKey)
                var current = old
                var body = requireNotNull(candidate.before)
                if (middle != current) {
                    val renamed = rekey(middle, body)
                    steps += SavedUploadStep(current, body, renamed, destination = middle)
                    current = middle
                    body = renamed
                }
                if (current != candidate.targetKey) {
                    val moved = rekey(candidate.targetKey, body)
                    steps += SavedUploadStep(current, body, moved, destination = candidate.targetKey)
                    body = moved
                }
                steps += SavedUploadStep(candidate.targetKey, body, resolved)
            } else {
                require(old == null) { "改名の競合を削除へ置き換えず、元の文書を保持してください。" }
                steps += SavedUploadStep(candidate.targetKey, candidate.before, resolved)
            }
        }
        val ordered = steps.sortedBy { step ->
            when {
                step.key.category == target.items.dataType.categoryDirName && step.after != null -> 0
                step.key.category == target.items.dataType.categoryDirName -> 2
                else -> 1
            }
        }
        val projected = SavedUploadReferences(target).projected(ordered).value()
        val normalized = ordered.map { step ->
            if (step.after == null || step.destination != null) step
            else step.copy(after = validate(step.key, step.after, projected))
        }
        SavedUploadOperation(UUID.randomUUID().toString(), preview.target, preview.inputs, preview.versions, normalized)
            .also { require(it.validKeys) { "改名・移動の作用先が重複しています。" } }
    }

    private fun includeRequiredItems(
        selections: MutableList<SavedUploadSelection>, records: Map<EditorDocumentKey, LocalDocumentRecord>
    ) {
        if (selections.none { it.key.category in setOf(target.ores.dataType.categoryDirName, target.recipes.dataType.categoryDirName) }) return
        val remoteIds = target.sourceStore.list(target.items.descriptor).value().mapTo(mutableSetOf()) {
            target.sourceStore.load(target.items.descriptor, it.id).value().internalId
        }
        val needed = mutableSetOf<ItemInternalId>()
        selections.forEach { selection ->
            val record = records[selection.key] ?: fail(StoreErrorCode.FILE_NOT_FOUND, selection.key.id)
            val body = record.saved?.takeUnless { record.savedDeleted } ?: return@forEach
            when (record.key.category) {
                target.ores.dataType.categoryDirName ->
                    needed += origin.ores.decodeDocument(record.key.id, body).dropItems.mapNotNull { it.itemId }
                target.recipes.dataType.categoryDirName -> {
                    val recipe = origin.recipes.decodeDocument(record.key.id, body)
                    needed += when (val ingredients = recipe.ingredients) {
                        is RecipeIngredients.Shaped -> ingredients.slots.values
                        is RecipeIngredients.Shapeless -> ingredients.items
                    }
                    recipe.resultItemInternalId?.let { needed += it }
                }
            }
        }
        for (internalId in needed - remoteIds) {
            val item = records.values.firstOrNull { record ->
                record.key.category == target.items.dataType.categoryDirName && !record.savedDeleted &&
                    record.saved != null && origin.items.decodeDocument(record.key.id, record.saved).internalId == internalId
            } ?: fail(StoreErrorCode.VALIDATION_FAILED, "参照するアイテムの保存済み内容がありません: " + internalId.value)
            if (selections.none { it.key == item.key }) {
                require(selections.none { it.projection != null }) {
                    "参照先Itemを送信予約に含めてから確認してください: " + item.key.id
                }
                selections += SavedUploadSelection(item.key)
            }
        }
    }

    private fun fixed(record: LocalDocumentRecord) = SavedUploadDocument(record.key, record.revision,
        record.saved, record.savedDeleted, record.savedRaw.toMap(), record.savedPending)

    private fun receipt(
        snapshot: LocalDocumentSnapshot, input: SavedUploadDocument, key: EditorDocumentKey
    ): Pair<SavedUploadOperation, SavedUploadDocument>? {
        val latest = lastConfirmedTouch(snapshot, key) ?: return null
        val operation = latest.first
        val prior = operation.inputs.firstOrNull { it.key == input.key } ?: return null
        if (!compatiblePending(input, prior)) return null
        if (operation.steps.any { (it.key == key || it.destination == key) && it.state != SavedUploadState.CONFIRMED }) return null
        return operation to prior
    }

    private fun lastConfirmedTouch(
        snapshot: LocalDocumentSnapshot, key: EditorDocumentKey
    ): Pair<SavedUploadOperation, SavedUploadStep>? {
        val candidates = snapshot.uploads.mapNotNull { operation ->
            operation.steps.lastOrNull { (it.key == key || it.destination == key) && it.state == SavedUploadState.CONFIRMED }
                ?.let { operation to it }
        }
        val revision = candidates.maxOfOrNull { pair ->
            pair.first.targetRevisions.single { it.key == key }.revision
        } ?: return null
        val latest = candidates.filter { pair -> pair.first.targetRevisions.single { it.key == key }.revision == revision }
        if (latest.size != 1) fail(StoreErrorCode.IO_ERROR, "反映確認済みの世代を一意に照合できません。")
        return latest.single()
    }

    private fun compatiblePending(input: SavedUploadDocument, prior: SavedUploadDocument): Boolean {
        val pending = input.pending ?: return true
        if (pending == prior.pending) return true
        if (pending.kind != PendingStoreOperationKind.DELETE || prior.deleted) return false
        val previous = prior.pending
        return if (previous?.kind == PendingStoreOperationKind.RENAME)
            pending.currentId == previous.currentId && pending.sourceId == previous.sourceId
        else pending.sourceId == pending.currentId && (previous == null || previous.kind == PendingStoreOperationKind.CREATE)
    }

    private fun intermediate(old: EditorDocumentKey, next: EditorDocumentKey): EditorDocumentKey =
        old.copy(id = PublicId.join(PublicId.directoryOf(old.id), PublicId.nameOf(next.id)))

    private fun read(key: EditorDocumentKey): StoreResult<String?> = when (key.category) {
        target.items.dataType.categoryDirName -> read(target.items, key.id)
        target.ores.dataType.categoryDirName -> read(target.ores, key.id)
        target.recipes.dataType.categoryDirName -> read(target.recipes, key.id)
        "text" -> when (val text = target.sourceStore.readText(key.id)) {
            is StoreResult.Success -> text
            is StoreResult.Failure -> if (text.error.code == StoreErrorCode.FILE_NOT_FOUND) StoreResult.Success(null) else text
        }
        else -> StoreResult.Failure(StoreError(StoreErrorCode.UNSUPPORTED_FORMAT, key.id))
    }

    private fun <T : ManagedData<T, *>> read(access: EditorDataService.DataAccess<T>, id: String): StoreResult<String?> =
        when (val loaded = target.sourceStore.load(access.descriptor, id)) {
            is StoreResult.Success -> StoreResult.Success(access.encodeDocument(loaded.value.apply { this.id = id }))
            is StoreResult.Failure -> if (loaded.error.code == StoreErrorCode.FILE_NOT_FOUND) StoreResult.Success(null) else loaded
        }

    private fun project(key: EditorDocumentKey, body: String, projection: SavedUploadProjection?): String =
        projection?.body(target, key, body) ?: rekey(key, body)

    private fun rekey(key: EditorDocumentKey, body: String): String = when (key.category) {
        target.items.dataType.categoryDirName -> rekey(target.items, key.id, body)
        target.ores.dataType.categoryDirName -> rekey(target.ores, key.id, body)
        target.recipes.dataType.categoryDirName -> rekey(target.recipes, key.id, body)
        "text" -> body
        else -> fail(StoreErrorCode.UNSUPPORTED_FORMAT, key.id)
    }

    private fun <T : ManagedData<T, *>> rekey(access: EditorDataService.DataAccess<T>, id: String, body: String): String =
        access.encodeDocument(access.decodeDocument(id, body))

    private fun merge(key: EditorDocumentKey, base: String?, saved: String?, latest: String?, known: Boolean): SavedUploadMerge =
        when (key.category) {
            target.items.dataType.categoryDirName -> SavedUploadMerges.typed(target.items, key.id, base, saved, latest, known)
            target.ores.dataType.categoryDirName -> SavedUploadMerges.typed(target.ores, key.id, base, saved, latest, known)
            target.recipes.dataType.categoryDirName -> SavedUploadMerges.typed(target.recipes, key.id, base, saved, latest, known)
            "text" -> SavedUploadMerges.text(base, saved, latest, known)
            else -> fail(StoreErrorCode.UNSUPPORTED_FORMAT, key.id)
        }

    private fun validate(key: EditorDocumentKey, body: String, store: EditorDataStore): String = when (key.category) {
        target.items.dataType.categoryDirName -> validate(target.items, key.id, body, store)
        target.ores.dataType.categoryDirName -> validate(target.ores, key.id, body, store)
        target.recipes.dataType.categoryDirName -> validate(target.recipes, key.id, body, store)
        "text" -> body
        else -> fail(StoreErrorCode.UNSUPPORTED_FORMAT, key.id)
    }

    private fun <T : ManagedData<T, *>> validate(
        access: EditorDataService.DataAccess<T>, id: String, body: String, store: EditorDataStore
    ): String {
        val descriptor = access.descriptor
        val value = access.decodeDocument(id, body)
        val itemIds = store.list(target.items.descriptor).value().mapTo(mutableSetOf()) { resource ->
            store.load(target.items.descriptor, resource.id).value().internalId
        }
        val validation = descriptor.validateInStore?.invoke(value, store)?.value() ?: descriptor.validate(value, itemIds)
        val errors = value.refreshCompleted(validation).filter { it.isError }
        if (errors.isNotEmpty()) fail(StoreErrorCode.VALIDATION_FAILED, errors.joinToString("\n") { it.message })
        return access.encodeDocument(value)
    }

    private fun checkTarget() {
        if (!target.isRemote || !target.sourceStore.isAvailable) fail(StoreErrorCode.STORE_UNAVAILABLE)
        if (!target.sourceStore.isWritable) fail(StoreErrorCode.PERMISSION_DENIED)
    }

    private class PlanFailure(val error: StoreError) : RuntimeException(error.detail, error.cause)

    private fun fail(code: StoreErrorCode, detail: String? = null): Nothing = throw PlanFailure(StoreError(code, detail = detail))

    private fun <T> StoreResult<T>.value(): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> throw PlanFailure(error)
    }

    private fun <T> result(action: () -> T): StoreResult<T> = try {
        StoreResult.Success(action())
    } catch (failure: PlanFailure) {
        StoreResult.Failure(failure.error)
    } catch (error: Exception) {
        StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, detail = error.message, cause = error))
    }
}

/** 固定server一覧へ同batchの実採用Itemを重ねる。未取得・未選択を不在と確定しない。 */
internal fun plannedUploadItems(
    server: Map<ItemInternalId, ItemBaseDataView>?, candidates: List<SavedUploadCandidate>,
    localPaths: Map<EditorDocumentKey, Set<DataFieldPath>>
): Map<ItemInternalId, ItemBaseDataView>? {
    val items = server?.values?.associateByTo(linkedMapOf()) { it.id } ?: return null
    for (candidate in candidates.filter { it.targetKey.category == EditorDataDescriptors.item.dataType.categoryDirName }) {
        val resolver = candidate.itemPreview ?: return null
        if (candidate.merge.conflicts.isNotEmpty() && candidate.input.key !in localPaths) return null
        items.remove(candidate.relocationKey?.id ?: candidate.targetKey.id)
        val destination = if (candidate.input.deleted && candidate.relocationKey != null) {
            items.remove(candidate.targetKey.id)
            candidate.relocationKey.id
        } else candidate.targetKey.id
        resolver(localPaths[candidate.input.key].orEmpty())?.let { items[destination] = it }
    }
    return items.values.associateBy { it.internalId }
}
