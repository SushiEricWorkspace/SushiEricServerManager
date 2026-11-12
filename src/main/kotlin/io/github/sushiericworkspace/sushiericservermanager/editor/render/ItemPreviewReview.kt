package io.github.sushiericworkspace.sushiericservermanager.editor.render

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseDataView
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.ore.model.OreBaseDataView
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeDataView
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.sushiericdatamodel.data.shop.model.ShopProductDataView
import io.github.sushiericworkspace.sushiericservermanager.editor.service.ItemReferenceSnapshot
import io.github.sushiericworkspace.sushiericservermanager.editor.service.ItemReferenceStatus

internal data class ItemPreviewReview(val source: String, val label: String, val content: ItemPreviewContent)

/** 各比較sideの固定定義から作る。参照一覧nullは未取得であり、不在とは区別する。 */
internal fun itemPreviewReviews(
    source: String, data: Any?, items: Map<ItemInternalId, ItemBaseDataView>?
): List<ItemPreviewReview> {
    if (data is ItemBaseDataView) return listOf(ItemPreviewReview(source, "$source: ${data.id}", ItemPreviewContent.item(data)))
    val references: List<Pair<String, ItemInternalId?>> = when (data) {
        is RecipeDataView -> listOf("完成品" to data.resultItemInternalId) + when (val ingredients = data.ingredients) {
            is RecipeIngredients.Shaped -> ingredients.slots.toSortedMap().map { (slot, id) -> "材料${slot + 1}" to id }
            is RecipeIngredients.Shapeless -> ingredients.items.mapIndexed { index, id -> "材料${index + 1}" to id }
        }
        is ShopProductDataView -> listOf("対象アイテム" to data.itemInternalId)
        is OreBaseDataView -> data.dropItems.mapIndexed { index, drop -> "ドロップ${index + 1}" to drop.itemId }
        else -> emptyList()
    }
    return references.mapNotNull { (label, id) -> id?.let {
        val item = items?.get(id)
        val content = when {
            item != null -> ItemPreviewContent.item(item)
            items != null -> ItemPreviewContent.missing(id)
            else -> ItemPreviewContent.unavailable(id.value)
        }
        ItemPreviewReview(source, "$source / $label: ${item?.id ?: id.value}", content)
    } }
}

/** 完了した一覧だけが参照先不在を確定できる。途中/失敗時は未取得表示を返す。 */
internal fun itemReferencePreview(snapshot: ItemReferenceSnapshot, id: ItemInternalId): ItemPreviewContent =
    if (snapshot.status != ItemReferenceStatus.READY) ItemPreviewContent.unavailable(id.value)
    else snapshot.byInternalId[id]?.let(ItemPreviewContent::item) ?: ItemPreviewContent.missing(id)
