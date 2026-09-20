package io.github.sushiericworkspace.sushiericservermanager.editor.view

import kotlin.test.Test
import kotlin.test.assertEquals

class ManagedDataEditorViewTest {

    @Test
    fun `公開IDの表示値を大文字小文字を区別せず絞り込む`() {
        val ids = listOf("iron_ore", "deep_gold", "stone")

        assertEquals(listOf("deep_gold"), filterManagedDataIds(ids, "GOLD"))
        assertEquals(ids, filterManagedDataIds(ids, "  "))
    }
}
