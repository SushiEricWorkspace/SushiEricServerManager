package io.github.sushiericworkspace.sushiericservermanager.editor.render

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.DisplayInfo
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.mutable.MutableRecipeData
import io.github.sushiericworkspace.sushiericservermanager.editor.service.ItemReferenceSnapshot
import io.github.sushiericworkspace.sushiericservermanager.editor.service.ItemReferenceStatus
import org.junit.jupiter.api.Test
import kotlin.test.*

class ItemPreviewReviewTest {
    private val id = ItemInternalId("same-item")

    @Test fun keepsComparisonSidesIndependent() {
        val saved = ItemBaseData(internalId = id, id = "saved.id", display = DisplayInfo(displayName = "saved"))
        val server = saved.copy(id = "server.id", display = DisplayInfo(displayName = "server"))
        val localPreview = itemPreviewReviews("保存済み", saved, null).single()
        val serverPreview = itemPreviewReviews("サーバー", server, null).single()
        assertNotEquals(localPreview.content, serverPreview.content)
        assertNotEquals(localPreview.source, serverPreview.source)
        assertEquals(ItemPreviewContent.item(saved), localPreview.content)
        assertEquals(ItemPreviewContent.item(server), serverPreview.content)
    }

    @Test fun resolvesEveryRecipeSlotAndResultFromTheGivenSnapshot() {
        val item = ItemBaseData(internalId = id, id = "stored.item")
        val recipe = MutableRecipeData(resultItemInternalId = id,
            ingredients = RecipeIngredients.Shaped(mapOf(0 to id, 24 to id)))
        val before = recipe.deepCopy()
        val entries = itemPreviewReviews("保存予定", recipe, mapOf(id to item))
        assertEquals(3, entries.size)
        assertTrue(entries.all { it.content == ItemPreviewContent.item(item) })
        assertEquals(before, recipe)
    }

    @Test fun distinguishesUnloadedCatalogFromMissingReference() {
        val recipe = MutableRecipeData(resultItemInternalId = id)
        val unavailable = itemPreviewReviews("前回保存", recipe, null).single()
        val missing = itemPreviewReviews("前回保存", recipe, emptyMap()).single()
        assertNotEquals(unavailable.content, missing.content)
        assertEquals(ItemPreviewContent.missing(id), missing.content)
        assertEquals(id, recipe.resultItemInternalId)
    }

    @Test fun absentDocumentAndUnselectedSlotsDoNotInventItems() {
        assertTrue(itemPreviewReviews("削除", null, emptyMap()).isEmpty())
        assertTrue(itemPreviewReviews("編集", MutableRecipeData(), emptyMap()).isEmpty())
    }

    @Test fun onlyReadyCatalogCanResolveOrDeclareMissing() {
        val item = ItemBaseData(internalId = id, id = "stored.item")
        assertEquals(ItemPreviewContent.item(item), itemReferencePreview(ItemReferenceSnapshot(ItemReferenceStatus.READY, listOf(item)), id))
        assertEquals(ItemPreviewContent.missing(id), itemReferencePreview(ItemReferenceSnapshot(ItemReferenceStatus.READY), id))
    }

    @Test fun pendingAndFailedCatalogNeverDeclareAbsent() {
        val item = ItemBaseData(internalId = id, id = "previous.item")
        for (state in listOf(ItemReferenceStatus.EMPTY, ItemReferenceStatus.LOADING, ItemReferenceStatus.FAILED)) {
            for (items in listOf(emptyList(), listOf(item))) {
                val content = itemReferencePreview(ItemReferenceSnapshot(state, items), id)
                assertEquals(PreviewSymbol.UNAVAILABLE, content.symbol)
                assertEquals(ItemPreviewContent.unavailable(id.value), content)
            }
        }
    }

}
