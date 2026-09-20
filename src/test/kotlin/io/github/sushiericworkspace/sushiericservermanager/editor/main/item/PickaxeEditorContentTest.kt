package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.common.data.item.model.ItemType
import io.github.sushiericworkspace.common.data.item.model.detail.PickaxeData
import io.github.sushiericworkspace.common.data.item.model.mutable.detail.MutablePickaxeData
import io.github.sushiericworkspace.common.stats.player.StatsPart
import io.github.sushiericworkspace.sushiericservermanager.ui.format.ItemDetailContentFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * PICKAXEのアイテム編集が、種類固有の値を持たない種別として既存の規則へ追従することを確認します。
 */
class PickaxeEditorContentTest {
    @Test
    fun `PICKAXEは種類固有の値を持たない編集用データを生成する`() {
        val content = ItemType.PICKAXE.createMutableContent()

        assertIs<MutablePickaxeData>(content)
        assertIs<PickaxeData>(content.freeze())
    }

    @Test
    fun `PICKAXEの要約は種別名だけを表示する`() {
        assertEquals(
            "ツルハシ",
            ItemDetailContentFormatter.format(ItemType.PICKAXE.createMutableContent().freeze())
        )
    }

    @Test
    fun `PICKAXEは最大スタック数を編集できない`() {
        assertFalse(isMaxStackSizeEditable(ItemType.PICKAXE))
    }

    @Test
    fun `PICKAXEの倍率の部位指定はメインハンドから始まる`() {
        assertEquals(setOf(StatsPart.MAIN_HAND), defaultItemStatMultiplierParts(ItemType.PICKAXE))
        assertTrue(ItemType.PICKAXE in StatsPart.MAIN_HAND.supportType)
    }
}
