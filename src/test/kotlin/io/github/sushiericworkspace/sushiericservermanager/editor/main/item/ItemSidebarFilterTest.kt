package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ItemSidebarFilterTest {

    private val ids = listOf(
        "steel_sword",
        "steel_short_sword",
        "wooden_bow"
    )

    @Test
    fun `空の検索文字列では全Itemを元の順序で返す`() {
        assertEquals(ids, filterSidebarItemIds(ids, ""))
        assertEquals(ids, filterSidebarItemIds(ids, "   "))
    }

    @Test
    fun `公開IDを完全一致と部分一致で絞り込む`() {
        assertEquals(
            listOf("steel_sword"),
            filterSidebarItemIds(ids, "steel_sword")
        )
        assertEquals(
            listOf("steel_sword", "steel_short_sword"),
            filterSidebarItemIds(ids, "steel")
        )
        assertEquals(
            listOf("steel_sword", "steel_short_sword"),
            filterSidebarItemIds(ids, "sword")
        )
    }

    @Test
    fun `大文字と小文字を区別せず絞り込む`() {
        assertEquals(
            listOf("steel_sword", "steel_short_sword"),
            filterSidebarItemIds(ids, "StEeL")
        )
    }

    @Test
    fun `一致するItemがなければ空の一覧を返す`() {
        assertTrue(filterSidebarItemIds(ids, "pickaxe").isEmpty())
    }
}
