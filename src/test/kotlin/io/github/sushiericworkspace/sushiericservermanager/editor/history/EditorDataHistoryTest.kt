package io.github.sushiericworkspace.sushiericservermanager.editor.history

import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentKey
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorDataHistoryTest {
    private fun key(id: String) = EditorDocumentKey(EditorDocumentSource.Local, "item", id)

    @Test
    fun `データごとに元に戻してやり直せる`() {
        val history = EditorDataHistory<String>(copy = { it })
        history.initialize(key("first"), "A")
        history.record(key("first"), "B")
        history.initialize(key("second"), "X")
        history.record(key("second"), "Y")

        assertEquals("A", history.undo(key("first"), "B"))
        assertEquals("X", history.undo(key("second"), "Y"))
        assertEquals("B", history.redo(key("first"), "A"))
        assertEquals("Y", history.redo(key("second"), "X"))
    }

    @Test
    fun `入力と追加を1操作ずつ戻せる`() {
        /*
         * 入力中のテキストは、ほかの操作が始まる前に確定する。
         * 確定してから追加を適用すると、入力と追加が別の履歴になる。
         */
        val history = EditorDataHistory<String>(copy = { it })
        history.initialize(key("data"), "名前なし")

        // 表示名を入力（確定前）
        val afterFirstInput = "名前1"
        // 追加の直前に入力を確定する
        history.record(key("data"), afterFirstInput)
        // 追加を適用した状態を記録する
        val afterAdd = "名前1+ロア"
        history.record(key("data"), afterAdd)
        // 2回目の表示名入力（確定前）
        val afterSecondInput = "名前2+ロア"

        // 元に戻すときは、確定前の入力を先に確定する
        history.record(key("data"), afterSecondInput)

        assertEquals(afterAdd, history.undo(key("data"), afterSecondInput))
        assertEquals(afterFirstInput, history.undo(key("data"), afterAdd))
        assertEquals("名前なし", history.undo(key("data"), afterFirstInput))
    }

    @Test
    fun `元に戻した後の新しい変更でやり直し履歴を破棄する`() {
        val history = EditorDataHistory<String>(copy = { it })
        history.initialize(key("data"), "A")
        history.record(key("data"), "B")
        assertEquals("A", history.undo(key("data"), "B"))

        history.record(key("data"), "C")

        assertNull(history.redo(key("data"), "C"))
        assertEquals("A", history.undo(key("data"), "C"))
    }

    @Test
    fun `履歴上限を超えた古い状態を破棄する`() {
        val history = EditorDataHistory<String>(copy = { it }, maxEntries = 2)
        history.initialize(key("data"), "A")
        history.record(key("data"), "B")
        history.record(key("data"), "C")
        history.record(key("data"), "D")

        assertEquals("C", history.undo(key("data"), "D"))
        assertEquals("B", history.undo(key("data"), "C"))
        assertNull(history.undo(key("data"), "B"))
    }

    @Test
    fun `ID変更後も同じ履歴を引き継ぐ`() {
        val history = EditorDataHistory<String>(copy = { it })
        history.initialize(key("old"), "A")
        history.record(key("old"), "B")

        history.rename(key("old"), key("new"))

        assertEquals("A", history.undo(key("new"), "B"))
        assertNull(history.undo(key("old"), "B"))
    }

    @Test
    fun `元に戻す・やり直しの可否を履歴状態から判定できる`() {
        val history = EditorDataHistory<String>(copy = { it })
        history.initialize(key("data"), "A")
        assertFalse(history.canUndo(key("data")))
        assertFalse(history.canRedo(key("data")))

        history.record(key("data"), "B")
        assertTrue(history.canUndo(key("data")))
        assertFalse(history.canRedo(key("data")))

        history.undo(key("data"), "B")
        assertFalse(history.canUndo(key("data")))
        assertTrue(history.canRedo(key("data")))
    }
    @Test
    fun 同じ公開IDの保存元別履歴は混ざらない() {
        val history = EditorDataHistory<String>(copy = { it })
        val local = key("same")
        val server = EditorDocumentKey(EditorDocumentSource.Server("profile", "root"), "item", "same")
        history.initialize(local, "local")
        history.record(local, "local-edit")
        history.initialize(server, "server")
        history.record(server, "server-edit")
        assertEquals("local", history.undo(local, "local-edit"))
        assertEquals("server", history.undo(server, "server-edit"))
    }
}
