package io.github.sushiericworkspace.sushiericservermanager.editor.main.item.diff

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.HeadSkinSource
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableHeadSkinData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ItemHeadSkinDiffTest {
    @Test
    fun `ヘッドスキンだけの変更は詳細データ差分に含めない`() {
        val original = MutableItemBaseData()
        val server = original.deepCopy().apply {
            itemDetail.mutableHeadSkin = MutableHeadSkinData(
                source = HeadSkinSource.PLAYER_NAME,
                value = "SushiEric"
            )
        }

        assertFalse(hasNonHeadSkinDetailDifference(original, server))
        assertEquals(setOf(ItemDiffField.HEAD_SKIN), itemDetailDiffFields(original, server))
    }

    @Test
    fun `通常の詳細変更は詳細データ差分として扱う`() {
        val original = MutableItemBaseData()
        val server = original.deepCopy().apply {
            itemDetail.enchantAura = !itemDetail.enchantAura
        }

        assertTrue(hasNonHeadSkinDetailDifference(original, server))
        assertEquals(setOf(ItemDiffField.DETAIL), itemDetailDiffFields(original, server))
    }
}
