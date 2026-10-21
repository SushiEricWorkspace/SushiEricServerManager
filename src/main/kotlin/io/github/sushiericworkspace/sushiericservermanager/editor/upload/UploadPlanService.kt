package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentKey
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentSource
import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentRecord
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import java.util.UUID

/** 接続のI/Oキューから呼び、予約を固定savedとID投影へ結び付けます。通信書込は行いません。 */
internal class UploadPlanService(private val target: EditorDataService) {
    private val origin = target.localService
    private val repository = UploadPlanRepository(origin.autoSaveDirectory.resolve("upload-plans"),
        target.documentSource as? EditorDocumentSource.Server ?: error("送信予約には接続先が必要です。"))

    fun read(): StoreResult<UploadPlan> = repository.read()
    fun save(plan: UploadPlan): StoreResult<UploadPlan> = repository.save(plan)

    fun remove(plan: UploadPlan, id: String): StoreResult<UploadPlan> =
        if (plan.groups.first { it.id == id }.operationId == null) repository.save(plan.remove(id))
        else repository.removeResolved(plan, id, target.documents)

    fun createGroup(roots: List<UploadPlanRoot>, destination: String): StoreResult<UploadPlanGroup> = result {
        require(target.sourceStore.isAvailable) { "宛先へ接続してから予約してください。" }
        listOf(origin.items.descriptor, origin.ores.descriptor, origin.shops.descriptor, origin.recipes.descriptor)
            .forEach { origin.store.list(it).value() }
        val records = origin.documents.read().value().documents.associateBy { it.key }
        val documents = uploadPlanDocuments(roots, records.values.toList(), destination).toMutableList()
        val remoteItems = target.sourceStore.list(target.items.descriptor).value().associate { resource ->
            resource.id to target.sourceStore.load(target.items.descriptor, resource.id).value()
        }
        val needed = linkedSetOf<ItemInternalId>()
        documents.forEach { document ->
            val record = records.getValue(document.key)
            when (record.key.category) {
                origin.ores.dataType.categoryDirName ->
                    needed += origin.ores.decodeDocument(record.key.id, requireNotNull(record.saved)).dropItems.mapNotNull { it.itemId }
                origin.recipes.dataType.categoryDirName -> {
                    val recipe = origin.recipes.decodeDocument(record.key.id, requireNotNull(record.saved))
                    needed += when (val ingredients = recipe.ingredients) {
                        is RecipeIngredients.Shaped -> ingredients.slots.values
                        is RecipeIngredients.Shapeless -> ingredients.items
                    }
                    recipe.resultItemInternalId?.let { needed += it }
                }
            }
        }
        val remoteIds = remoteItems.values.mapTo(mutableSetOf()) { it.internalId }
        for (id in needed - remoteIds) {
            val item = records.values.firstOrNull {
                it.key.category == origin.items.dataType.categoryDirName && it.saved != null && !it.savedDeleted &&
                    origin.items.decodeDocument(it.key.id, it.saved).internalId == id
            } ?: error("参照するItemのローカル保存済み内容がありません: " + id.value)
            if (documents.none { it.key == item.key }) {
                require(!item.unsaved && item.savedRaw.isEmpty()) { "参照先Itemも先にローカル保存してください: " + item.key.id }
                documents += document(item, relativeUploadTargetId(item.key.id, "", destination))
            }
        }
        buildGroup(UUID.randomUUID().toString(), destination, documents, records)
    }

    fun reassign(plan: UploadPlan, id: String, destination: String): StoreResult<UploadPlan> = result {
        val previous = plan.groups.first { it.id == id }
        require(previous.operationId == null) { "実行済み要求の宛先は変更できません。" }
        val records = currentRecords(previous)
        val moved = previous.documents.map {
            it.copy(targetId = relativeUploadTargetId(it.targetId, previous.targetDirectory, destination))
        }
        val next = buildGroup(id, destination, moved, records)
        val groups = plan.groups.map { if (it.id == id) next else it }
        groups.fold(plan.copy(groups = emptyList())) { current, group -> current.add(group) }
    }

    /** 元savedの一致を再確認し、変換本文ではなく元文書を250の固定入力へ渡します。 */
    fun selections(group: UploadPlanGroup): StoreResult<List<SavedUploadSelection>> = result {
        currentRecords(group)
        val projection = SavedUploadProjection(group.itemIds.toMap(), group.oreIds.toMap())
        group.documents.map { SavedUploadSelection(it.key, it.targetId, projection) }
    }

    private fun currentRecords(group: UploadPlanGroup): Map<EditorDocumentKey, LocalDocumentRecord> {
        val records = origin.documents.read().value().documents.associateBy { it.key }
        group.documents.forEach { document ->
            val current = records[document.key] ?: error("予約元文書がありません: " + document.key.id)
            require(!current.savedDeleted && current.saved != null &&
                savedUploadFingerprint(current.key, current.saved, false, current.savedRaw) == document.savedHash) {
                "予約後に保存済み内容が変わりました。再度追加してください: " + document.key.id
            }
        }
        return records
    }

    private fun buildGroup(
        id: String, destination: String, documents: List<UploadPlanDocument>,
        records: Map<EditorDocumentKey, LocalDocumentRecord>
    ): UploadPlanGroup {
        val itemIds = linkedMapOf<String, String>()
        val oreIds = linkedMapOf<String, String>()
        for (document in documents) {
            val body = requireNotNull(records.getValue(document.key).saved)
            when (document.key.category) {
                origin.items.dataType.categoryDirName -> {
                    require(target.items.descriptor.isValidId(document.targetId)) { "Itemの宛先IDが不正です。" }
                    val item = origin.items.decodeDocument(document.key.id, body)
                    val existing = target.sourceStore.load(target.items.descriptor, document.targetId).optional()
                    require(item.internalId.value !in itemIds) { "予約元Itemの内部IDが重複しています。" }
                    itemIds[item.internalId.value] = (existing?.internalId ?: item.duplicateAsNew().internalId).value
                }
                origin.ores.dataType.categoryDirName -> {
                    require(target.ores.descriptor.isValidId(document.targetId)) { "Oreの宛先IDが不正です。" }
                    val ore = origin.ores.decodeDocument(document.key.id, body)
                    val existing = target.sourceStore.load(target.ores.descriptor, document.targetId).optional()
                    require(ore.internalId.value !in oreIds) { "予約元Oreの内部IDが重複しています。" }
                    oreIds[ore.internalId.value] = (existing?.internalId ?: ore.duplicateAsNew().internalId).value
                }
                origin.recipes.dataType.categoryDirName ->
                    require(target.recipes.descriptor.isValidId(document.targetId)) { "Recipeの宛先IDが不正です。" }
                origin.shops.dataType.categoryDirName -> error("Shopの反映はDB capability未接続のため保留します。ローカル内容は保持しています。")
                else -> error("この種別は送信予約に対応していません。")
            }
        }
        require(documents.map { it.key.category to it.targetId }.toSet().size == documents.size) {
            "参照先Itemを含む予約の宛先が重複しています。"
        }
        return UploadPlanGroup(id, destination, documents.toList(), itemIds.toMap(), oreIds.toMap())
    }

    private fun document(record: LocalDocumentRecord, targetId: String) =
        UploadPlanDocument(record.key, targetId, record.revision,
            savedUploadFingerprint(record.key, record.saved, record.savedDeleted, record.savedRaw))

    private fun <T> StoreResult<T>.value(): T = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> error(error.detail ?: error.code.name)
    }

    private fun <T> StoreResult<T>.optional(): T? = when (this) {
        is StoreResult.Success -> value
        is StoreResult.Failure -> if (error.code == StoreErrorCode.FILE_NOT_FOUND) null
            else error(error.detail ?: error.code.name)
    }

    private fun <T> result(action: () -> T): StoreResult<T> = try {
        StoreResult.Success(action())
    } catch (error: Exception) {
        StoreResult.Failure(StoreError(StoreErrorCode.VALIDATION_FAILED, detail = error.message, cause = error))
    }
}
