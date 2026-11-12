package io.github.sushiericworkspace.sushiericservermanager.editor.render

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseDataView
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.toDisplayComponents
import org.slf4j.LoggerFactory
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

internal enum class PreviewSymbol { COMMON, MISSING, UNAVAILABLE }

/** 保存元の定義を保持せず、その時点の不変な表示内容をcacheのrevisionとして扱う。 */
internal data class ItemPreviewContent(val rows: List<Component>, val symbol: PreviewSymbol, val imageId: String? = null) {
    companion object {
        fun item(data: ItemBaseDataView): ItemPreviewContent = try {
            ItemPreviewContent(data.toDisplayComponents(), PreviewSymbol.COMMON, data.itemDetail.vanillaId.value)
        } catch (error: Exception) {
            LoggerFactory.getLogger(ItemPreviewContent::class.java).warn("プレビュー内容を組み立てられませんでした", error)
            unavailable(data.id)
        }

        fun unavailable(id: String) = ItemPreviewContent(listOf(
            Component.text("プレビュー未取得（元データは保持）"),
            Component.text(id, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ), PreviewSymbol.UNAVAILABLE)

        fun missing(id: ItemInternalId) = ItemPreviewContent(listOf(
            Component.text("参照先不明・個別画像未収録"),
            Component.text(id.value, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
        ), PreviewSymbol.MISSING)
    }
}

internal data class ItemPreviewKey(
    val source: String,
    val content: ItemPreviewContent,
    val scaleX: Double,
    val scaleY: Double,
    val formatRevision: Int,
    val fontRevision: String,
    val imageRevision: String
) {
    private val stableHash = listOf(source, content, scaleX, scaleY, formatRevision, fontRevision, imageRevision).hashCode()
    override fun hashCode(): Int = stableHash
}

/** 画像のpixel bytesと件数の両方に上限を持つ、保存先単位のLRU。 */
internal class ItemPreviewCache<T>(private val maxBytes: Long = 128L * 1024 * 1024, private val maxEntries: Int = 128) {
    private val entries = LinkedHashMap<ItemPreviewKey, Pair<T, Long>>(16, .75f, true)
    var bytes: Long = 0
        private set
    val size: Int get() = entries.size

    operator fun get(key: ItemPreviewKey): T? = entries[key]?.first

    fun put(key: ItemPreviewKey, value: T, weight: Long) {
        require(weight >= 0)
        entries.remove(key)?.let { bytes -= it.second }
        if (weight > maxBytes) return
        entries[key] = value to weight
        bytes += weight
        while (bytes > maxBytes || entries.size > maxEntries) {
            val oldest = entries.entries.iterator()
            bytes -= oldest.next().value.second
            oldest.remove()
        }
    }

    fun clear() { entries.clear(); bytes = 0 }
}
