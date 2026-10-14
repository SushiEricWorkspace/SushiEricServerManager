package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemType
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.detail.MutableShieldData
import io.github.sushiericworkspace.sushiericservermanager.ui.format.ItemDetailContentFormatter
import kotlin.test.Test
import kotlin.test.assertEquals

class ShieldEditorContentTest {
    @Test
    fun `盾のBlockとParryクールダウンをLongで保持する`() {
        val content = ItemType.SHIELD.createMutableContent() as MutableShieldData

        content.blockCooldown = 40L
        content.parryCooldown = 12L

        assertEquals(40L, content.blockCooldown)
        assertEquals(12L, content.parryCooldown)
    }

    @Test
    fun `盾の要約へBlockとParryクールダウンを表示する`() {
        val content = (ItemType.SHIELD.createMutableContent() as MutableShieldData).apply {
            blockCooldown = 40L
            parryCooldown = 12L
        }

        assertEquals(
            "盾 blockCooldown=40, parryCooldown=12",
            ItemDetailContentFormatter.format(content.freeze())
        )
    }
}
