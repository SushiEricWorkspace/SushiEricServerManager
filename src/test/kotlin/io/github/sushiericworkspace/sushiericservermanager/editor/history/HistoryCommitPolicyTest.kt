package io.github.sushiericworkspace.sushiericservermanager.editor.history

import javafx.scene.input.KeyCode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HistoryCommitPolicyTest {
    @Test
    fun `入力欄の外を押したときに入力を確定する`() {
        assertTrue(
            shouldCommitHistoryOnMousePress(focusOwnerIsTextInput = true, targetIsFocusOwner = false)
        )
    }

    @Test
    fun `入力欄の中を押しただけでは確定しない`() {
        assertFalse(
            shouldCommitHistoryOnMousePress(focusOwnerIsTextInput = true, targetIsFocusOwner = true)
        )
    }

    @Test
    fun `入力欄にフォーカスがなければ確定しない`() {
        assertFalse(
            shouldCommitHistoryOnMousePress(focusOwnerIsTextInput = false, targetIsFocusOwner = false)
        )
        assertFalse(
            shouldCommitHistoryOnKeyPress(
                focusOwnerIsTextInput = false,
                code = KeyCode.ENTER,
                shortcutDown = false
            )
        )
    }

    @Test
    fun `確定と移動とショートカットで入力を確定する`() {
        listOf(KeyCode.ENTER, KeyCode.TAB).forEach { code ->
            assertTrue(
                shouldCommitHistoryOnKeyPress(
                    focusOwnerIsTextInput = true,
                    code = code,
                    shortcutDown = false
                ),
                code.name
            )
        }

        assertTrue(
            shouldCommitHistoryOnKeyPress(
                focusOwnerIsTextInput = true,
                code = KeyCode.Z,
                shortcutDown = true
            )
        )
    }

    @Test
    fun `通常の文字入力では確定しない`() {
        assertFalse(
            shouldCommitHistoryOnKeyPress(
                focusOwnerIsTextInput = true,
                code = KeyCode.A,
                shortcutDown = false
            )
        )
    }
}
