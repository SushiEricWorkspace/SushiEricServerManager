package io.github.sushiericworkspace.sushiericservermanager.editor.render

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.DisplayInfo
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.toMutable
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.junit.jupiter.api.Test
import kotlin.test.*

class ItemPreviewCacheTest {
    private fun key(text: String) = ItemPreviewKey("local", ItemPreviewContent(listOf(Component.text(text)), PreviewSymbol.COMMON),
        1.0, 1.0, 2, "font-hash", "image-revision")

    @Test fun isolatesEveryRenderingInput() {
        val original = key("old")
        listOf(original.copy(source = "server"), original.copy(content = key("edited").content),
            original.copy(scaleX = 2.0), original.copy(scaleY = 1.5), original.copy(formatRevision = 3),
            original.copy(fontRevision = "other-font"), original.copy(imageRevision = "other-map"))
            .forEach { assertNotEquals(original, it) }
    }

    @Test fun evictsLeastRecentlyUsedByPixelBytes() {
        val cache = ItemPreviewCache<String>(4, 8)
        val a = key("a"); val b = key("b"); val c = key("c")
        cache.put(a, "a", 2); cache.put(b, "b", 2)
        assertEquals("a", cache[a])
        cache.put(c, "c", 2)
        assertNull(cache[b]); assertEquals("a", cache[a]); assertEquals("c", cache[c])
        assertEquals(4L, cache.bytes); assertEquals(2, cache.size)
    }

    @Test fun boundsEntriesAndRejectsOversizedImage() {
        val cache = ItemPreviewCache<String>(64, 1)
        cache.put(key("a"), "a", 1); cache.put(key("b"), "b", 1)
        assertNull(cache[key("a")]); assertEquals(1, cache.size)
        cache.put(key("oversize"), "large", 65)
        assertNull(cache[key("oversize")]); assertEquals(1L, cache.bytes)
    }

    @Test fun replacementAndDisposalReleaseBudget() {
        val cache = ItemPreviewCache<String>(10, 4)
        cache.put(key("a"), "old", 4); cache.put(key("a"), "new", 2)
        assertEquals(2L, cache.bytes); assertEquals("new", cache[key("a")])
        cache.clear(); assertEquals(0, cache.size); assertEquals(0L, cache.bytes)
    }

    @Test fun freezesDisplayAndKeepsPublicIdDarkAndNotItalic() {
        val item = ItemBaseData(id = "public.item", display = DisplayInfo(displayName = "before")).toMutable()
        val before = ItemPreviewContent.item(item)
        item.display.displayName = "after"
        assertNotEquals(before, ItemPreviewContent.item(item))
        assertEquals(Component.text("public.item").color(NamedTextColor.DARK_GRAY)
            .decoration(TextDecoration.ITALIC, false), before.rows.last())
        assertEquals("after", item.display.displayName)
        assertEquals("public.item", item.id)
    }
}
