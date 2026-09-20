package io.github.sushiericworkspace.sushiericservermanager.editor.main.ore

import io.github.sushiericworkspace.common.data.core.identity.VanillaBlockId
import io.github.sushiericworkspace.common.data.drop.model.mutable.MutableDropItemData
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.ore.model.mutable.MutableOreBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.validation.ValidationRepairResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OreEditorLogicTest {

    @Test
    fun `有限なDoubleだけ硬度として解釈する`() {
        assertEquals(12.5, parseHardnessInput("12.5"))
        assertEquals(-1.0, parseHardnessInput("-1"))
        assertNull(parseHardnessInput(""))
        assertNull(parseHardnessInput("NaN"))
        assertNull(parseHardnessInput("Infinity"))
    }

    @Test
    fun `ドロップアイテム件数を表示する`() {
        assertEquals("ドロップアイテムを編集（0件）", formatDropItemCount(0))
        assertEquals("ドロップアイテムを編集（3件）", formatDropItemCount(3))
    }

    @Test
    fun `解決不能なドロップ行を警告のkeyで削除する`() {
        val ore = MutableOreBaseData(
            id = "test_ore",
            blockId = VanillaBlockId("stone"),
            mutableDropItems = mutableListOf(
                MutableDropItemData(itemId = ItemInternalId("missing-1")),
                MutableDropItemData(itemId = ItemInternalId("missing-2"))
            )
        )
        val warning = ore.validate(emptySet()).first { it.key == 1 }

        val result = createOreValidationRepairRegistry().repair(ore, warning)

        assertEquals(ValidationRepairResult.Applied("ドロップアイテム 2 番目を削除"), result)
        assertEquals(listOf(ItemInternalId("missing-1")), ore.mutableDropItems.map { it.itemId })
    }
}
