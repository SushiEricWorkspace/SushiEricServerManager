package io.github.sushiericworkspace.sushiericservermanager.editor.service

import io.github.sushiericworkspace.sushiericservermanager.editor.view.SidebarDataState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SidebarDataStateTest {
    @Test
    fun `通常状態では装飾を追加しない`() {
        val state = SidebarDataState(false, false, false, false)

        assertEquals(emptyList(), state.styleClasses)
        assertEquals("sample", state.displayText("sample"))
        assertNull(state.description())
    }

    @Test
    fun `警告とエラーを別の表示で保持する`() {
        val warning = SidebarDataState(false, false, hasWarnings = true, hasErrors = false)
        val error = SidebarDataState(false, false, hasWarnings = false, hasErrors = true)

        assertEquals(listOf("button-warning"), warning.styleClasses)
        assertEquals("⚠ sample", warning.displayText("sample"))
        assertEquals("警告あり", warning.description())
        assertEquals(listOf("button-invalid"), error.styleClasses)
        assertEquals("⛔ sample", error.displayText("sample"))
        assertEquals("エラーあり", error.description())
    }

    @Test
    fun `選択と変更と両方の問題を同時に保持する`() {
        val state = SidebarDataState(true, true, hasWarnings = true, hasErrors = true)

        assertEquals(
            listOf("button-selected", "button-modified", "button-warning", "button-invalid"),
            state.styleClasses
        )
        assertEquals("⛔ sample  ●", state.displayText("sample"))
        assertEquals("選択中 / 未保存の変更あり / 警告あり / エラーあり", state.description())
    }

    @Test
    fun `サーバー未保存と他の状態を同時に保持する`() {
        val state = SidebarDataState(
            selected = true,
            modified = true,
            hasWarnings = true,
            hasErrors = false,
            localOnly = true
        )

        assertEquals(
            listOf("button-selected", "button-modified", "button-warning", "button-local-only"),
            state.styleClasses
        )
        assertEquals("⚠ ＋ sample  ●", state.displayText("sample"))
        assertEquals(
            "選択中 / サーバー未保存 / 未保存の変更あり / 警告あり",
            state.description()
        )
    }
}
