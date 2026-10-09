package io.github.sushiericworkspace.sushiericservermanager.editor.service

import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.common.data.core.validation.SushiEricValidationError
import io.github.sushiericworkspace.common.data.core.validation.SushiEricValidationSeverity
import io.github.sushiericworkspace.common.data.item.model.ItemBaseDataView
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.data.recipe.model.RecipeDataView
import io.github.sushiericworkspace.common.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.common.data.recipe.model.mutable.MutableRecipeData
import io.github.sushiericworkspace.common.data.recipe.validation.RecipeIngredientOverlapValidator
import io.github.sushiericworkspace.common.data.recipe.validation.RecipeReferenceLocation
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptor
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult

/** 検証用の正式定義。重いI/Oを伴うloadは画面側ではバックグラウンドで呼び出します。 */
internal data class RecipeEditorCatalog(val items: List<MutableItemBaseData>, val recipes: List<MutableRecipeData>) {
    companion object {
        fun load(store: EditorDataStore): StoreResult<RecipeEditorCatalog> {
            val items = when (val result = loadAll(store, EditorDataDescriptors.item)) {
                is StoreResult.Success -> result.value
                is StoreResult.Failure -> return result
            }
            val recipes = when (val result = loadAll(store, EditorDataDescriptors.recipe)) {
                is StoreResult.Success -> result.value
                is StoreResult.Failure -> return result
            }
            return StoreResult.Success(RecipeEditorCatalog(items, recipes))
        }

        private fun <T : ManagedData<T, *>> loadAll(store: EditorDataStore, descriptor: EditorDataDescriptor<T>): StoreResult<List<T>> {
            val resources = when (val result = store.list(descriptor)) {
                is StoreResult.Success -> result.value
                is StoreResult.Failure -> return result
            }
            val data = mutableListOf<T>()
            for (resource in resources) {
                when (val result = store.load(descriptor, resource.id)) {
                    is StoreResult.Success -> data += result.value
                    is StoreResult.Failure -> return result
                }
            }
            return StoreResult.Success(data)
        }
    }
}

/** Commonの詳細検証を使用し、未解決参照だけを保持可能な警告へ変換します。 */
internal fun validateRecipeForEditor(
    data: MutableRecipeData,
    items: Collection<ItemBaseDataView>,
    recipes: Iterable<RecipeDataView>,
    knownIds: Set<ItemInternalId> = items.mapTo(mutableSetOf()) { it.internalId }
): List<SushiEricValidationError> {
    val report = data.validateDetailed(items)
    return buildList {
        addAll(report.errors)
        report.unresolvedReferences.filter { it.itemInternalId !in knownIds }.forEach { reference ->
            val result = reference.location == RecipeReferenceLocation.RESULT_ITEM
            add(SushiEricValidationError(
                property = if (result) data::resultItemInternalId else data::ingredients,
                message = "${if (result) "完成品" else "材料 ${requireNotNull(reference.position) + 1}番目"}: 参照先が見つかりません (${reference.itemInternalId.value})。IDは保持されます。",
                key = reference.position,
                severity = SushiEricValidationSeverity.WARNING
            ))
        }
        val hasMaterials = when (val value = data.ingredients) {
            is RecipeIngredients.Shaped -> value.slots.isNotEmpty()
            is RecipeIngredients.Shapeless -> value.items.isNotEmpty()
        }
        if (hasMaterials) {
            RecipeIngredientOverlapValidator.conflictingRecipeIds(data, recipes).forEach { id ->
                add(SushiEricValidationError(data::ingredients, "材料配置がレシピ「$id」と重複しています。"))
            }
        }
    }
}
