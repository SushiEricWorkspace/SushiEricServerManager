package io.github.sushiericworkspace.sushiericservermanager.editor.main.shop

import kotlin.test.*

class ShopOrderHistoryTest {
    private fun values() = mapOf("tab.b" to ShopOrderValue(8, "008"), "tab.a" to ShopOrderValue(8, "8"), "tab.unresolved" to ShopOrderValue(-2, "-2"))

    @Test
    fun 同順位は完全ID順で未解決参照の商品も含めて挿入位置から採番する() {
        val before = values()
        assertEquals(listOf("tab.unresolved", "tab.a", "tab.b"), shopOrderIds(before))
        val after = assertNotNull(moveShopOrder(before, "tab.b", "tab.unresolved", false))
        assertEquals(listOf("tab.b", "tab.unresolved", "tab.a"), shopOrderIds(after))
        assertEquals(listOf(0, 1, 2), shopOrderIds(after).map { after.getValue(it).order })
        assertEquals(8, before.getValue("tab.b").order)
        assertNull(moveShopOrder(before, "other.hidden", "tab.a", false))
    }

    @Test
    fun 一ドロップの全memberとrawを一度にUndoRedoする() {
        val h = ShopOrderHistory(); val before = values(); val after = assertNotNull(moveShopOrder(before, "tab.b", "tab.a", false))
        h.recordOrder(before, after)
        assertEquals(before, assertIs<ShopOrderHistory.Step.Order>(h.peek("tab.a", false) { _, _ -> after }).values)
        h.traversed("tab.a", false)
        assertEquals(after, assertIs<ShopOrderHistory.Step.Order>(h.peek("tab.b", true) { _, _ -> before }).values)
        h.traversed("tab.b", true)
        assertEquals(before, assertIs<ShopOrderHistory.Step.Order>(h.peek("tab.unresolved", false) { _, _ -> after }).values)
    }

    @Test
    fun 他memberの値raw取得集合不一致でcursorを動かさず通常履歴へ流さない() {
        val h = ShopOrderHistory();val before = values();val after = assertNotNull(moveShopOrder(before, "tab.b", "tab.a", false))
        h.recordDocument("tab.a");h.recordOrder(before, after)
        for (current in listOf(after - "tab.b", after + ("tab.b" to ShopOrderValue(99, "99")), after + ("tab.b" to after.getValue("tab.b").copy(raw = "01")), after + ("tab.new" to ShopOrderValue(4, "4")))) {
            assertEquals(ShopOrderHistory.Step.Rejected, h.peek("tab.a", false) { _, _ -> current })
            assertTrue(h.hasOrder("tab.a", false))
        }
        assertEquals(ShopOrderHistory.Step.Rejected, h.peek("tab.a", false) { _, _ -> null })
        assertIs<ShopOrderHistory.Step.Order>(h.peek("tab.a", false) { _, _ -> after })
    }

    @Test
    fun 通常価格履歴と順序履歴の操作順を分離する() {
        val h = ShopOrderHistory();val before = values();val after = assertNotNull(moveShopOrder(before, "tab.b", "tab.a", false))
        h.recordDocument("tab.a");h.recordOrder(before, after)
        assertIs<ShopOrderHistory.Step.Order>(h.peek("tab.a", false) { _, _ -> after });h.traversed("tab.a", false)
        assertEquals(ShopOrderHistory.Step.Document, h.peek("tab.a", false) { _, _ -> before });h.traversed("tab.a", false)
        assertEquals(ShopOrderHistory.Step.Document, h.peek("tab.a", true) { _, _ -> before });h.traversed("tab.a", true)
        assertIs<ShopOrderHistory.Step.Order>(h.peek("tab.a", true) { _, _ -> before })
    }

    @Test
    fun 一memberの保存正本成功で関連順序だけ全member同時確定する() {
        val h = ShopOrderHistory();val before = values();val after = assertNotNull(moveShopOrder(before, "tab.b", "tab.a", false))
        h.recordDocument("tab.a");h.recordOrder(before, after);h.traversed("tab.b", false)
        assertTrue(h.hasOrder("tab.a", true))
        h.saved("tab.unresolved")
        before.keys.forEach { assertFalse(h.hasOrder(it, false));assertFalse(h.hasOrder(it, true)) }
        assertEquals(ShopOrderHistory.Step.Document,h.peek("tab.a", false) { _, _ -> before })
    }

    @Test
    fun 保存されなかったmemberの通常編集と別商品の順序履歴は残す() {
        val h=ShopOrderHistory();val before=values();val after=assertNotNull(moveShopOrder(before,"tab.b","tab.a",false))
        h.recordOrder(before,after);h.recordDocument("tab.a");h.saved("other.item")
        assertEquals(ShopOrderHistory.Step.Document,h.peek("tab.a",false){ _, _ ->after});h.traversed("tab.a",false)
        assertIs<ShopOrderHistory.Step.Order>(h.peek("tab.a",false){ _, _ ->after})
    }
    @Test
    fun 手入力の単商品commandは全兄弟採番と区別してUndo条件を確認する() {
        val h = ShopOrderHistory()
        val before = mapOf("tab.a" to ShopOrderValue(8, "8"))
        val after = mapOf("tab.a" to ShopOrderValue(5, "5"))
        h.recordOrder(before, after, allSiblings = false)
        var whole = true
        assertIs<ShopOrderHistory.Step.Order>(h.peek("tab.a", false) { ids, all -> whole = all; assertEquals(setOf("tab.a"), ids); after })
        assertFalse(whole)
        h.traversed("tab.a", false)
        assertEquals(after, assertIs<ShopOrderHistory.Step.Order>(h.peek("tab.a", true) { _, all -> assertFalse(all); before }).values)
    }

    @Test
    fun 他memberが後続順序で同じ値へ戻っても飛び越さず正しい順でUndoRedoする() {
        val h = ShopOrderHistory()
        val before = values()
        val after = assertNotNull(moveShopOrder(before, "tab.b", "tab.a", false))
        val b = mapOf("tab.b" to after.getValue("tab.b"))
        val changed = mapOf("tab.b" to ShopOrderValue(9, "9"))
        h.recordOrder(before, after)
        h.recordOrder(b, changed, allSiblings = false)
        h.recordOrder(changed, b, allSiblings = false)
        assertEquals(ShopOrderHistory.Step.Rejected, h.peek("tab.a", false) { _, _ -> after })
        assertEquals(changed, assertIs<ShopOrderHistory.Step.Order>(h.peek("tab.b", false) { _, _ -> b }).values)
        h.traversed("tab.b", false)
        assertEquals(b, assertIs<ShopOrderHistory.Step.Order>(h.peek("tab.b", false) { _, _ -> changed }).values)
        h.traversed("tab.b", false)
        h.recordDocument("tab.b")
        // 価格のDocumentは順序を所有しないため、Aからの順序Undoを妨げない。
        assertEquals(before, assertIs<ShopOrderHistory.Step.Order>(h.peek("tab.a", false) { _, _ -> after }).values)
        h.traversed("tab.a", false)
        assertEquals(after, assertIs<ShopOrderHistory.Step.Order>(h.peek("tab.a", true) { _, _ -> before }).values)
        h.traversed("tab.a", true)

        val redo = ShopOrderHistory()
        redo.recordOrder(before, after)
        redo.recordOrder(b, changed, allSiblings = false)
        redo.recordOrder(changed, b, allSiblings = false)
        redo.traversed("tab.b", false); redo.traversed("tab.b", false); redo.traversed("tab.a", false)
        // redo側でも値が一致するだけでは、まだ先に戻すべき全体コマンドを飛び越せない。
        assertEquals(ShopOrderHistory.Step.Rejected, redo.peek("tab.b", true) { _, _ -> b })
        assertEquals(after, assertIs<ShopOrderHistory.Step.Order>(redo.peek("tab.a", true) { _, _ -> before }).values)
        redo.traversed("tab.a", true)
        assertEquals(changed, assertIs<ShopOrderHistory.Step.Order>(redo.peek("tab.b", true) { _, _ -> b }).values)
    }

}
