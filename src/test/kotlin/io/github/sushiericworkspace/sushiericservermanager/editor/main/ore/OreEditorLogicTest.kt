package io.github.sushiericworkspace.sushiericservermanager.editor.main.ore

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
}
