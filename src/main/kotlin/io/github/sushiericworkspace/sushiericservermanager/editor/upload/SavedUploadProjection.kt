package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.ore.model.OreInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentKey
import io.github.sushiericworkspace.sushiericservermanager.editor.service.documentHash
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 固定savedを変更せず、予約で固定したID対応だけを派生本文へ適用します。 */
internal data class SavedUploadProjection(
    val itemIds: Map<String, String>,
    val oreIds: Map<String, String>
) {
    /** fullkeyは要求のorigin/target照合と併用し、同じmapでも別の変換先へ成功基準を流用しません。 */
    fun fingerprint(origin: EditorDocumentKey, target: EditorDocumentKey): String = documentHash(Json.encodeToString(listOf(
        "copy-v1", Json.encodeToString(origin), Json.encodeToString(target),
        Json.encodeToString<Map<String, String>>(itemIds.toSortedMap()), Json.encodeToString<Map<String, String>>(oreIds.toSortedMap())
    )))

    fun matchesTarget(service: EditorDataService, key: EditorDocumentKey, projected: String, current: String): Boolean = when (key.category) {
        service.items.dataType.categoryDirName -> service.items.decodeDocument(key.id, projected).internalId ==
            service.items.decodeDocument(key.id, current).internalId
        service.ores.dataType.categoryDirName -> service.ores.decodeDocument(key.id, projected).internalId ==
            service.ores.decodeDocument(key.id, current).internalId
        else -> true
    }

    fun body(service: EditorDataService, key: EditorDocumentKey, saved: String): String = when (key.category) {
        service.items.dataType.categoryDirName -> {
            val item = service.items.decodeDocument(key.id, saved)
            val id = itemIds[item.internalId.value] ?: error("コピー先Itemの内部IDが予約されていません。")
            service.items.encodeDocument(item.copy(internalId = ItemInternalId(id)))
        }
        service.ores.dataType.categoryDirName -> {
            val ore = service.ores.decodeDocument(key.id, saved)
            val id = oreIds[ore.internalId.value] ?: error("コピー先Oreの内部IDが予約されていません。")
            val projected = ore.deepCopy().copy(internalId = OreInternalId(id))
            projected.mutableDropItems.forEach { drop -> drop.itemId = itemId(drop.itemId) }
            service.ores.encodeDocument(projected)
        }
        service.recipes.dataType.categoryDirName -> {
            val recipe = service.recipes.decodeDocument(key.id, saved)
            recipe.ingredients = when (val value = recipe.ingredients) {
                is RecipeIngredients.Shaped -> value.copy(slots = value.slots.mapValues { requireNotNull(itemId(it.value)) })
                is RecipeIngredients.Shapeless -> value.copy(items = value.items.map { requireNotNull(itemId(it)) })
            }
            recipe.resultItemInternalId = itemId(recipe.resultItemInternalId)
            service.recipes.encodeDocument(recipe)
        }
        else -> error("この種別は送信予約に対応していません。")
    }

    private fun itemId(id: ItemInternalId?): ItemInternalId? =
        id?.let { itemIds[it.value]?.let(::ItemInternalId) ?: it }
}
