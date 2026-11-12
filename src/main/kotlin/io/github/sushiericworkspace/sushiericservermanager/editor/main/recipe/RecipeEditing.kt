package io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeSize
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.mutable.MutableRecipeData

/** 形状切り替えでは材料を削除しません。盤を超える材料は追加の入力行で修正できます。 */
internal fun changeRecipeShape(data: MutableRecipeData, shaped: Boolean) {
    if (data.shaped == shaped) return
    data.ingredients = when (val ingredients = data.ingredients) {
        is RecipeIngredients.Shaped -> RecipeIngredients.Shapeless(ingredients.slots.toSortedMap().values.toList())
        is RecipeIngredients.Shapeless -> RecipeIngredients.Shaped(ingredients.items.withIndex().associate { it.index to it.value })
    }
}

/** サイズ変更ではマス番号を保持し、範囲外の材料も残して検証・編集の対象にします。 */
internal fun changeRecipeSize(data: MutableRecipeData, size: RecipeSize) {
    data.size = size
}

internal fun recipeSlots(ingredients: RecipeIngredients): Map<Int, ItemInternalId> = when (ingredients) {
    is RecipeIngredients.Shaped -> ingredients.slots
    is RecipeIngredients.Shapeless -> ingredients.items.withIndex().associate { it.index to it.value }
}


/** 指定マスだけを置き換え、形状なしでは既存のList契約へ空きを詰めます。 */
internal fun setRecipeSlot(data: MutableRecipeData, slot: Int, item: ItemInternalId?): Boolean {
    val slots = recipeSlots(data.ingredients).toMutableMap()
    if (item == null) slots.remove(slot) else slots[slot] = item
    return updateRecipeSlots(data, slots)
}

/** 移動元と置換先を一度に反映し、他の盤外材料も保持します。 */
internal fun moveRecipeSlot(data: MutableRecipeData, from: Int, to: Int): Boolean {
    if (from == to) return false
    val slots = recipeSlots(data.ingredients).toMutableMap()
    val item = slots.remove(from) ?: return false
    slots[to] = item
    return updateRecipeSlots(data, slots)
}

private fun updateRecipeSlots(data: MutableRecipeData, slots: Map<Int, ItemInternalId>): Boolean {
    val next = if (data.shaped) RecipeIngredients.Shaped(slots)
        else RecipeIngredients.Shapeless(slots.toSortedMap().values.toList())
    if (next == data.ingredients) return false
    data.ingredients = next
    return true
}
