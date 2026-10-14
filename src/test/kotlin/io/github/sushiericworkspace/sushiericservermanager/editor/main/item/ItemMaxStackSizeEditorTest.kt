package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemType
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemMaxStackSizeEditorTest {
    @Test
    fun `OTHERだけ最大スタック数を編集できる`() {
        assertTrue(isMaxStackSizeEditable(ItemType.OTHER))

        ItemType.entries.filterNot { it == ItemType.OTHER }.forEach { type ->
            assertFalse(isMaxStackSizeEditable(type), type.name)
        }
    }

    @Test
    fun `OTHERから他種類へ変更するとSushiEricDataModelモデルが1へ正規化する`() {
        val item = MutableItemBaseData().apply {
            itemDetail.content = ItemType.OTHER.createMutableContent()
            itemDetail.maxStackSize = 64
        }

        item.itemDetail.content = ItemType.SWORD.createMutableContent()

        assertEquals(1, item.itemDetail.maxStackSize)
    }
}
