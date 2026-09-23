package io.github.sushiericworkspace.sushiericservermanager.editor.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorDataHistoryTest {
    @Test
    fun `データごとに元に戻してやり直せる`() {
        val history = EditorDataHistory<String>(copy = { it })
        history.initialize("first", "A")
        history.record("first", "B")
        history.initialize("second", "X")
        history.record("second", "Y")

        assertEquals("A", history.undo("first", "B"))
        assertEquals("X", history.undo("second", "Y"))
        assertEquals("B", history.redo("first", "A"))
        assertEquals("Y", history.redo("second", "X"))
    }

    @Test
    fun `元に戻した後の新しい変更でやり直し履歴を破棄する`() {
        val history = EditorDataHistory<String>(copy = { it })
        history.initialize("data", "A")
        history.record("data", "B")
        assertEquals("A", history.undo("data", "B"))

        history.record("data", "C")

        assertNull(history.redo("data", "C"))
        assertEquals("A", history.undo("data", "C"))
    }

    @Test
    fun `履歴上限を超えた古い状態を破棄する`() {
        val history = EditorDataHistory<String>(copy = { it }, maxEntries = 2)
        history.initialize("data", "A")
        history.record("data", "B")
        history.record("data", "C")
        history.record("data", "D")

        assertEquals("C", history.undo("data", "D"))
        assertEquals("B", history.undo("data", "C"))
        assertNull(history.undo("data", "B"))
    }

    @Test
    fun `ID変更後も同じ履歴を引き継ぐ`() {
        val history = EditorDataHistory<String>(copy = { it })
        history.initialize("old", "A")
        history.record("old", "B")

        history.rename("old", "new")

        assertEquals("A", history.undo("new", "B"))
        assertNull(history.undo("old", "B"))
    }

    @Test
    fun `元に戻す・やり直しの可否を履歴状態から判定できる`() {
        val history = EditorDataHistory<String>(copy = { it })
        history.initialize("data", "A")
        assertFalse(history.canUndo("data"))
        assertFalse(history.canRedo("data"))

        history.record("data", "B")
        assertTrue(history.canUndo("data"))
        assertFalse(history.canRedo("data"))

        history.undo("data", "B")
        assertFalse(history.canUndo("data"))
        assertTrue(history.canRedo("data"))
    }
}
