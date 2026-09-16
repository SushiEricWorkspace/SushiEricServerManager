package io.github.sushiericworkspace.sushiericservermanager.editor.merge

import io.github.sushiericworkspace.common.data.item.model.ItemStatMultiplier
import io.github.sushiericworkspace.common.data.item.model.LoreSection
import io.github.sushiericworkspace.common.data.item.model.HeadSkinData
import io.github.sushiericworkspace.common.data.item.model.detail.ItemDetailContent
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableLoreSection
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableHeadSkinData
import io.github.sushiericworkspace.common.data.item.model.mutable.detail.MutableItemDetailContent
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
     * @return 競合内容が読み取れる1行の文字列。
     */
    fun format(value: Any?): String {
        return when (value) {
            null,
            MergeAccumulator.EntryValue.Missing,
            MergeAccumulator.IndexValue.Missing -> ABSENT

            is MergeAccumulator.EntryValue.Present<*> -> format(value.value)
            is MergeAccumulator.IndexValue.Present<*> -> format(value.value)

            is ItemDetailContent -> ItemDetailContentFormatter.format(value)
            is MutableItemDetailContent ->
                ItemDetailContentFormatter.format(value.freeze())
            is HeadSkinData -> "${value.source.name}: ${value.value}"
            is MutableHeadSkinData -> "${value.source.name}: ${value.value}"
            is ItemStatMultiplier -> ItemStatMultiplierFormatter.format(value)
            is LoreSection -> plainText.serialize(value.toComponent())
            is MutableLoreSection -> plainText.serialize(value.toComponent())

            is Collection<*> -> {
                if (value.isEmpty()) EMPTY else value.joinToString(" | ") { format(it) }
            }

            is Map<*, *> -> {
                if (value.isEmpty()) EMPTY else value.entries.joinToString(", ") { (key, entry) ->
                    "${format(key)}=${format(entry)}"
                }
            }

            is String -> value.ifBlank { EMPTY }

            else -> value.toString()
        }
    }
}
