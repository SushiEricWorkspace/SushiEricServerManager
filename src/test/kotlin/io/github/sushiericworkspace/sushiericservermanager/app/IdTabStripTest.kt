package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.app.workspace.IdTabStrip
import io.github.sushiericworkspace.sushiericservermanager.config.IdTabsState
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.input.*
import javafx.scene.layout.VBox
import javafx.stage.Stage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.*

class IdTabStripTest {
    @Test fun `通常ドラッグと横wheelは列だけを動かしAltドラッグだけが順序を変える`() {
        startFx()
        fx {
            val moves = mutableListOf<Pair<String, Int>>()
            val selected = mutableListOf<String>()
            val strip = IdTabStrip(selected::add, {}, { id, index -> moves += id to index })
            val other = IdTabStrip({}, {}, { _, _ -> })
            val state = IdTabsState((0..19).map { "document-$it-long-id" }, "document-0-long-id")
            strip.show(state) { false }; other.show(state) { false }
            val stage = Stage().apply { scene = Scene(VBox(strip, other), 350.0, 100.0); show() }
            try {
                stage.scene.root.applyCss(); stage.scene.root.layout()
                assertTrue(strip.content.layoutBounds.width > strip.viewportBounds.width)
                pointer(strip, MouseEvent.MOUSE_PRESSED, 50.0)
                pointer(strip, MouseEvent.MOUSE_DRAGGED, -120.0)
                pointer(strip, MouseEvent.MOUSE_RELEASED, -120.0)
                assertTrue(strip.hvalue > 0.0)
                assertTrue(moves.isEmpty()); assertTrue(selected.isEmpty()); assertEquals(0.0, other.hvalue)
                val previous = strip.hvalue
                strip.fireEvent(ScrollEvent(ScrollEvent.SCROLL, 20.0, 10.0, 20.0, 10.0, false, false, false, false, false, false,
                    -120.0, 0.0, -120.0, 0.0, ScrollEvent.HorizontalTextScrollUnits.NONE, 0.0, ScrollEvent.VerticalTextScrollUnits.NONE, 0.0, 0, null))
                assertTrue(strip.hvalue > previous); assertEquals(0.0, other.hvalue)
                strip.hvalue = 0.0
                pointer(strip, MouseEvent.MOUSE_PRESSED, 50.0, true)
                pointer(strip, MouseEvent.MOUSE_DRAGGED, 280.0, true)
                pointer(strip, MouseEvent.MOUSE_RELEASED, 280.0, true)
                assertEquals("document-0-long-id", moves.single().first)
                assertTrue(moves.single().second > 0)
            } finally { strip.dispose(); other.dispose(); stage.close() }
        }
    }

    @Test fun `並べ替えはEscapeで取消しキー選択と解除を独立して扱う`() {
        startFx()
        fx {
            val moves = mutableListOf<Pair<String, Int>>()
            val selected = mutableListOf<String>()
            val closed = mutableListOf<String>()
            val strip = IdTabStrip(selected::add, closed::add, { id, index -> moves += id to index })
            strip.show(IdTabsState(listOf("first-long-document", "second-long-document", "third-long-document"), "first-long-document")) { false }
            val stage = Stage().apply { scene = Scene(VBox(strip), 350.0, 80.0); show() }
            try {
                stage.scene.root.applyCss(); stage.scene.root.layout()
                pointer(strip, MouseEvent.MOUSE_PRESSED, 50.0, true)
                pointer(strip, MouseEvent.MOUSE_DRAGGED, 290.0, true)
                key(strip, KeyCode.ESCAPE)
                pointer(strip, MouseEvent.MOUSE_RELEASED, 290.0, true)
                assertTrue(moves.isEmpty())
                key(strip, KeyCode.RIGHT)
                assertEquals(listOf("second-long-document"), selected)
                key(strip, KeyCode.END, true)
                assertEquals(listOf("first-long-document" to 2), moves)
                key(strip, KeyCode.DELETE)
                assertEquals(listOf("first-long-document"), closed)
            } finally { strip.dispose(); stage.close() }
        }
    }

    @Test fun `drag挿入位置は移動元を除いた順序で間と両端と自己位置へ一致する`() {
        startFx()
        fx {
            var state = IdTabsState(listOf("first-long-id", "second-long-id", "third-long-id"), "first-long-id")
            lateinit var strip: IdTabStrip
            strip = IdTabStrip({}, {}, { id, index -> state = state.move(id, index); strip.show(state) { false } })
            val stage = Stage().apply { scene = Scene(VBox(strip), 900.0, 80.0); show() }
            fun reset() {
                state = IdTabsState(listOf("first-long-id", "second-long-id", "third-long-id"), "first-long-id")
                strip.show(state) { false }; stage.scene.root.applyCss(); stage.scene.root.layout()
            }
            fun center(index: Int): Double = (strip.content as javafx.scene.layout.HBox).children[index].boundsInParent.centerX
            fun drag(source: Int, destination: Double, cancel: Boolean = false) {
                pointer(strip, MouseEvent.MOUSE_PRESSED, center(source), true)
                pointer(strip, MouseEvent.MOUSE_DRAGGED, destination, true)
                if (cancel) key(strip, KeyCode.ESCAPE)
                pointer(strip, MouseEvent.MOUSE_RELEASED, destination, true)
            }
            try {
                reset(); drag(0, (center(1) + center(2)) / 2)
                assertEquals(listOf("second-long-id", "first-long-id", "third-long-id"), state.ids)
                reset(); drag(0, center(2) + 100)
                assertEquals(listOf("second-long-id", "third-long-id", "first-long-id"), state.ids)
                reset(); drag(2, (center(0) + center(1)) / 2)
                assertEquals(listOf("first-long-id", "third-long-id", "second-long-id"), state.ids)
                reset(); drag(2, 0.0)
                assertEquals(listOf("third-long-id", "first-long-id", "second-long-id"), state.ids)
                reset(); drag(1, center(1) + 8)
                assertEquals(listOf("first-long-id", "second-long-id", "third-long-id"), state.ids)
                reset(); drag(0, (center(1) + center(2)) / 2, true)
                assertEquals(listOf("first-long-id", "second-long-id", "third-long-id"), state.ids)
            } finally { strip.dispose(); stage.close() }
        }
    }

    private fun pointer(strip: IdTabStrip, type: javafx.event.EventType<MouseEvent>, x: Double, alt: Boolean = false) {
        val point = strip.localToScene(x, 12.0)
        Event.fireEvent(strip, MouseEvent(type, point.x, point.y, point.x, point.y, MouseButton.PRIMARY, 1,
            false, false, alt, false, type != MouseEvent.MOUSE_RELEASED, false, false, false, false, true, PickResult(strip, x, 12.0)))
    }
    private fun key(strip: IdTabStrip, code: KeyCode, alt: Boolean = false) = strip.fireEvent(KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, alt, false))
    private fun startFx() {
        val ready = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
        catch (_: IllegalStateException) { Platform.runLater { Platform.setImplicitExit(false); ready.countDown() } }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
    }
    private fun <T> fx(action: () -> T): T {
        val task = FutureTask(action); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS)
    }
}
