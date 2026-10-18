package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*

/** 確認した最終定義をメモリへ重ね、参照が残るItem削除と未解決の参照を止めます。 */
internal class SavedUploadReferences(private val target: EditorDataService) {
    fun projected(steps: List<SavedUploadStep>): StoreResult<EditorDataStore> {
        val memory = InMemoryEditorDataStore("upload-confirmation")
        for (result in listOf(
            copy(target.items, memory), copy(target.ores, memory),
            copy(target.recipes, memory), copy(target.shops, memory))) {
            if (result is StoreResult.Failure) return result
        }
        for (step in steps) {
            val result = when (step.key.category) {
                target.items.dataType.categoryDirName -> apply(target.items, memory, step)
                target.ores.dataType.categoryDirName -> apply(target.ores, memory, step)
                target.recipes.dataType.categoryDirName -> apply(target.recipes, memory, step)
                "text" -> StoreResult.Success(Unit)
                else -> StoreResult.Failure(StoreError(StoreErrorCode.UNSUPPORTED_FORMAT,
                    step.key.id, "商品の反映にはDB適用経路が必要です。"))
            }
            if (result is StoreResult.Failure) return result
        }
        return when (val result = validate(memory)) {
            is StoreResult.Failure -> result
            is StoreResult.Success -> StoreResult.Success(memory)
        }
    }

    /** Item削除の直前に最新定義を読み直します。読取に失敗した場合も削除へ進みません。 */
    fun beforeItemDelete(id: String): StoreResult<Unit> {
        val memory = when (val result = projected(emptyList())) {
            is StoreResult.Success -> result.value
            is StoreResult.Failure -> return result
        }
        when (val removed = memory.delete(target.items.descriptor, id)) {
            is StoreResult.Failure -> return removed
            is StoreResult.Success -> Unit
        }
        return validate(memory)
    }

    private fun validate(store: EditorDataStore): StoreResult<Unit> {
        val items = when (val loaded = loadAll(store, target.items.descriptor)) {
            is StoreResult.Success -> loaded.value.mapTo(mutableSetOf()) { it.internalId }
            is StoreResult.Failure -> return loaded
        }
        val missing = mutableListOf<String>()
        fun check(id: String, references: Collection<ItemInternalId>) {
            references.filter { it !in items }.forEach { missing += "$id: " + it.value }
        }
        when (val loaded = loadAll(store, target.ores.descriptor)) {
            is StoreResult.Failure -> return loaded
            is StoreResult.Success -> loaded.value.forEach { ore ->
                check(ore.id, ore.dropItems.mapNotNull { it.itemId })
            }
        }
        when (val loaded = loadAll(store, target.recipes.descriptor)) {
            is StoreResult.Failure -> return loaded
            is StoreResult.Success -> loaded.value.forEach { recipe ->
                val ingredients = when (val value = recipe.ingredients) {
                    is RecipeIngredients.Shaped -> value.slots.values.toList()
                    is RecipeIngredients.Shapeless -> value.items
                }
                check(recipe.id, ingredients + listOfNotNull(recipe.resultItemInternalId))
            }
        }
        when (val loaded = loadAll(store, target.shops.descriptor)) {
            is StoreResult.Failure -> return loaded
            is StoreResult.Success -> loaded.value.forEach { shop -> check(shop.id, listOfNotNull(shop.itemInternalId)) }
        }
        return if (missing.isEmpty()) StoreResult.Success(Unit)
        else StoreResult.Failure(StoreError(StoreErrorCode.VALIDATION_FAILED,
            detail = "参照先がない定義を確認してください。\n" + missing.joinToString("\n")))
    }

    private fun <T : ManagedData<T, *>> copy(
        access: EditorDataService.DataAccess<T>, destination: EditorDataStore
    ): StoreResult<Unit> = when (val loaded = loadAll(target.sourceStore, access.descriptor)) {
        is StoreResult.Failure -> loaded
        is StoreResult.Success -> {
            loaded.value.forEach { destination.save(access.descriptor, it.id, it) }
            StoreResult.Success(Unit)
        }
    }

    private fun <T : ManagedData<T, *>> apply(
        access: EditorDataService.DataAccess<T>, memory: EditorDataStore, step: SavedUploadStep
    ): StoreResult<Unit> {
        if (step.destination != null) {
            memory.delete(access.descriptor, step.key.id)
            return memory.save(access.descriptor, step.destination.id,
                access.decodeDocument(step.destination.id, requireNotNull(step.after)))
        }
        return if (step.after == null) {
            memory.delete(access.descriptor, step.key.id)
            StoreResult.Success(Unit)
        } else memory.save(access.descriptor, step.key.id, access.decodeDocument(step.key.id, step.after))
    }

    private fun <T : ManagedData<T, *>> loadAll(
        store: EditorDataStore, descriptor: EditorDataDescriptor<T>
    ): StoreResult<List<T>> {
        val resources = when (val listed = store.list(descriptor)) {
            is StoreResult.Success -> listed.value
            is StoreResult.Failure -> return listed
        }
        val data = mutableListOf<T>()
        for (resource in resources) {
            when (val loaded = store.load(descriptor, resource.id)) {
                is StoreResult.Success -> data += loaded.value
                is StoreResult.Failure -> return loaded
            }
        }
        return StoreResult.Success(data)
    }
}
