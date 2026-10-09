package io.github.sushiericworkspace.sushiericservermanager.editor.merge

import io.github.sushiericworkspace.common.data.core.identity.VanillaBlockId
import io.github.sushiericworkspace.common.data.core.identity.VanillaItemId
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.item.model.ItemStatMultiplier
import io.github.sushiericworkspace.common.data.item.model.LoreSection
import io.github.sushiericworkspace.common.data.item.model.HeadSkinData
import io.github.sushiericworkspace.common.data.item.model.detail.ItemDetailContent
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableLoreSection
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableHeadSkinData
import io.github.sushiericworkspace.common.data.item.model.mutable.detail.MutableItemDetailContent
import io.github.sushiericworkspace.common.stats.player.SkillType
import io.github.sushiericworkspace.common.stats.player.AchievementType
import io.github.sushiericworkspace.common.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.common.data.recipe.model.RecipeSize
import io.github.sushiericworkspace.sushiericservermanager.ui.format.ItemDetailContentFormatter
import io.github.sushiericworkspace.sushiericservermanager.ui.format.ItemStatMultiplierFormatter
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer

/**
 * [DataConflict]が保持する競合値を、競合解決ダイアログへ表示するための文字列へ整形します。
 *
 * #### 仕様:
 * - [DataConflict.baseValue]、[DataConflict.localValue]、[DataConflict.remoteValue]はいずれも
 *   `Any?`のため、種別ごとの整形をこのクラスへ集約し、3つの値すべてを同じ経路で整形します。
 * - Map・Listの競合値は[MergeAccumulator]が要素の有無を包んだ状態で保持するため、
 *   包みを外したうえで中身を整形し、要素が存在しない場合は「なし」と表示します。
 * - 種別固有データは[ItemDetailContentFormatter]へ委譲します。
 *   これらは既定の`toString()`がオブジェクト参照になり、そのままでは表示に使えません。
 * - Loreセクションは、競合解決時に内容を読みやすくするためプレーンテキストにします。
 * - 上記に該当しない値はdata classなどの`toString()`をそのまま使用します。
 */
object ConflictValueFormatter {

    /** 値そのものが存在しないことを表す表示文字列。 */
    private const val ABSENT = "なし"

    /** 値は存在するが中身が空であることを表す表示文字列。 */
    private const val EMPTY = "（空）"

    private val plainText = PlainTextComponentSerializer.plainText()

    /**
     * 競合値を表示用の文字列へ整形します。
     *
     * @param value 整形対象の競合値。
     * @return 競合内容が読み取れる表示文字列。材料盤などは複数行になります。
     */
    fun format(value: Any?, itemDisplayText: ((ItemInternalId) -> String)? = null): String {
        return when (value) {
            null,
            MergeAccumulator.EntryValue.Missing,
            MergeAccumulator.IndexValue.Missing -> ABSENT

            is MergeAccumulator.EntryValue.Present<*> -> format(value.value, itemDisplayText)
            is MergeAccumulator.IndexValue.Present<*> -> format(value.value, itemDisplayText)

            is ItemDetailContent -> ItemDetailContentFormatter.format(value)
            is MutableItemDetailContent ->
                ItemDetailContentFormatter.format(value.freeze())
            is HeadSkinData -> "${value.source.name}: ${value.value}"
            is MutableHeadSkinData -> "${value.source.name}: ${value.value}"
            is ItemStatMultiplier -> ItemStatMultiplierFormatter.format(value)
            is LoreSection -> plainText.serialize(value.toComponent())
            is MutableLoreSection -> plainText.serialize(value.toComponent())
            is VanillaItemId -> value.value
            is VanillaBlockId -> value.value
            is ItemInternalId -> itemDisplayText?.invoke(value) ?: value.value
            is SkillType -> value.display
            is AchievementType -> value.display
            is RecipeSize -> "${value.sideLength} × ${value.sideLength}"
            is RecipeIngredients.Shaped -> formatRecipeIngredients(value, null, itemDisplayText)
            is RecipeIngredients.Shapeless -> formatRecipeIngredients(value, null, itemDisplayText)
            is Pair<*, *> -> if (value.first is RecipeSize && value.second is RecipeIngredients) {
                formatRecipeIngredients(
                    value.second as RecipeIngredients,
                    value.first as RecipeSize,
                    itemDisplayText
                )
            } else "${format(value.first, itemDisplayText)} / ${format(value.second, itemDisplayText)}"

            is Collection<*> -> {
                if (value.isEmpty()) EMPTY else value.joinToString(" | ") { format(it, itemDisplayText) }
            }

            is Map<*, *> -> {
                if (value.isEmpty()) EMPTY else value.entries.joinToString(", ") { (key, entry) ->
                    "${format(key, itemDisplayText)}=${format(entry, itemDisplayText)}"
                }
            }

            is String -> value.ifBlank { EMPTY }

            else -> value.toString()
        }
    }

    private fun formatRecipeIngredients(
        ingredients: RecipeIngredients,
        size: RecipeSize?,
        itemDisplayText: ((ItemInternalId) -> String)?
    ): String = when (ingredients) {
        is RecipeIngredients.Shaped -> {
            val boardSize = size ?: if (ingredients.slots.keys.any { it >= 9 }) RecipeSize.FIVE_BY_FIVE else RecipeSize.THREE_BY_THREE
            buildString {
                append("形状あり（${boardSize.sideLength} × ${boardSize.sideLength}）")
                boardSize.slots.chunked(boardSize.sideLength).forEach { row ->
                    append("\n│ ")
                    append(row.joinToString(" │ ") { slot -> ingredients.slots[slot]?.let { itemDisplayText?.invoke(it) ?: it.value } ?: "・" })
                    append(" │")
                }
                val outside = ingredients.slots.filterKeys { it !in boardSize.slots }.toSortedMap()
                if (outside.isNotEmpty()) {
                    append("\n盤外: ")
                    append(outside.entries.joinToString("、") { (slot, id) -> "${slot + 1}番目 ${itemDisplayText?.invoke(id) ?: id.value}" })
                }
            }
        }
        is RecipeIngredients.Shapeless -> buildString {
            append("形状なし${size?.let { "（${it.sideLength}×${it.sideLength}盤）" }.orEmpty()}")
            ingredients.items.groupingBy { it }.eachCount().forEach { (id, count) ->
                append("\n・${itemDisplayText?.invoke(id) ?: id.value}")
                if (count > 1) append(" × $count")
            }
        }
    }
}
