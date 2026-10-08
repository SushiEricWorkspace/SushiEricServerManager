package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.shop.model.mutable.MutableShopProductData
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ShopSaveChangeDetailsTest {
    @Test
    fun `商品差分は変更項目と公開IDだけを表示する`() {
        val original = MutableShopProductData("tab.product", ItemInternalId("internal-before"), 100, 50)
        val current = original.copy(itemInternalId = ItemInternalId("internal-after"), stock = 12)
        val details = saveChangeDetails(current.id, null, original, current,
            itemDisplayText = { if (it == original.itemInternalId) "items.before" else "items.after" }).joinToString("\n")
        assertTrue(details.contains("対象アイテム"))
        assertTrue(details.contains("items.before"))
        assertTrue(details.contains("items.after"))
        assertTrue(details.contains("無限"))
        assertTrue(details.contains("12"))
        assertFalse(details.contains("購入価格"))
        assertFalse(details.contains("MutableShopProductData"))
        assertFalse(details.contains("internal-"))
    }
}
