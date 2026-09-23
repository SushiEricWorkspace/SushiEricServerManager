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

/** PICKAXE固有のTierを含む編集用データが既存の規則へ追従することを確認します。 */
class PickaxeEditorContentTest {
    @Test
    fun `PICKAXEはTierを保持する編集用データを生成する`() {
        val content = ItemType.PICKAXE.createMutableContent()

        assertIs<MutablePickaxeData>(content)
        assertEquals(0, content.tier)

        content.tier = 3
        val frozen = assertIs<PickaxeData>(content.freeze())

        assertEquals(3, frozen.tier)
    }

    @Test
    fun `PICKAXEの要約はTierを表示する`() {
        val content = ItemType.PICKAXE.createMutableContent()
        assertIs<MutablePickaxeData>(content).tier = 4

        assertEquals(
            "ツルハシ tier=4",
            ItemDetailContentFormatter.format(content.freeze())
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
