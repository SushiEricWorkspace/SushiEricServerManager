package io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe

import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.common.data.recipe.model.RecipeSize
import io.github.sushiericworkspace.common.data.recipe.model.mutable.MutableRecipeData

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
