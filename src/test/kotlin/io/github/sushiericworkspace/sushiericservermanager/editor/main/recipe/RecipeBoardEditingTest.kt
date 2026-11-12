package io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeSize
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.mutable.MutableRecipeData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RecipeBoardEditingTest {
    private val first = ItemInternalId("first")
    private val second = ItemInternalId("second")
    private val unresolved = ItemInternalId("unresolved")

    @Test
    fun `形状ありの配置と移動は指定先だけを置換し盤外の未解決材料を保持する`() {
        val data = MutableRecipeData(ingredients = RecipeIngredients.Shaped(mapOf(0 to first, 1 to second, 24 to unresolved)), resultCount = 7)
        assertTrue(setRecipeSlot(data, 4, unresolved))
        assertTrue(moveRecipeSlot(data, 0, 1))
        assertEquals(mapOf(1 to first, 4 to unresolved, 24 to unresolved), recipeSlots(data.ingredients))
        assertEquals(7, data.resultCount)
        assertFalse(moveRecipeSlot(data, 1, 1))
        assertFalse(moveRecipeSlot(data, 8, 2))
    }

    @Test
    fun `形状なしは同じIDの各要素を一個として保持し空き枠だけを詰める`() {
        val data = MutableRecipeData(ingredients = RecipeIngredients.Shapeless(listOf(first, first, unresolved)))
        assertTrue(setRecipeSlot(data, 8, second))
        assertEquals(listOf(first, first, unresolved, second), assertIs<RecipeIngredients.Shapeless>(data.ingredients).items)
        assertTrue(moveRecipeSlot(data, 0, 3))
        assertEquals(listOf(first, unresolved, first), assertIs<RecipeIngredients.Shapeless>(data.ingredients).items)
        assertTrue(setRecipeSlot(data, 1, null))
        assertEquals(listOf(first, first), assertIs<RecipeIngredients.Shapeless>(data.ingredients).items)
    }

    @Test
    fun `別の編集コピーとサイズ変更で元の材料を変更しない`() {
        val original = MutableRecipeData(size = RecipeSize.FIVE_BY_FIVE,
            ingredients = RecipeIngredients.Shaped(mapOf(0 to first, 24 to unresolved)))
        val editing = original.deepCopy()
        changeRecipeSize(editing, RecipeSize.THREE_BY_THREE)
        assertEquals(mapOf(0 to first, 24 to unresolved), recipeSlots(editing.ingredients))
        setRecipeSlot(editing, 0, second)
        assertEquals(mapOf(0 to first, 24 to unresolved), recipeSlots(original.ingredients))
        assertEquals(RecipeSize.FIVE_BY_FIVE, original.size)
        assertFalse(setRecipeSlot(editing, 0, second))
    }
}
