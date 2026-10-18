package io.github.sushiericworkspace.sushiericservermanager.ui.shortcut

import io.github.sushiericworkspace.sushiericservermanager.config.AppSettingsManager
import io.github.sushiericworkspace.sushiericservermanager.config.OS
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.event.Event
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.input.*
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ShortcutManagerTest {
    @Test fun `同Sceneの現在領域だけへメニューとキーを送り再登録解除とScene移動が重複しない`() {
        startFx()
        val previous = fx { ShortcutManager.overrides() }
        val firstButton = fx { Button("文書A") }
        val secondButton = fx { Button("文書B") }
        val first = fx { VBox(firstButton) }
        val second = fx { VBox(secondButton) }
        val outside = fx { javafx.scene.control.MenuButton("登録されていない機能") }
        val layout = fx { HBox(first, second, outside) }
        val stage = fx { Stage().apply { scene = Scene(layout); show() } }
        val other = fx { Stage() }
        var a = 0; var b = 0; var replacement = 0
        try {
            fx {
                assertTrue(ShortcutManager.save(emptyMap()))
                ShortcutManager.register(first, ShortcutScope.EDITOR, EditorShortcut.SAVE) { a++; true }
                ShortcutManager.register(second, ShortcutScope.EDITOR, EditorShortcut.SAVE) { b++; true }
                firstButton.requestFocus()
            }
            press(firstButton, KeyCode.S)
            assertEquals(1, a); assertEquals(0, b)
            fx { secondButton.requestFocus() }
            press(secondButton, KeyCode.S)
            fx { assertTrue(ShortcutManager.execute(first, EditorShortcut.SAVE)) }
            assertEquals(2, b)
            fx { outside.requestFocus(); assertFalse(ShortcutManager.execute(first, EditorShortcut.SAVE)) }
            press(outside, KeyCode.S)
            assertEquals(2, b)
            fx {
                ShortcutManager.register(second, ShortcutScope.EDITOR, EditorShortcut.SAVE) { replacement++; true }
                assertTrue(stage.scene.accelerators.isEmpty())
                layout.children.remove(second)
                other.scene = Scene(second); other.show(); secondButton.requestFocus()
            }
            press(secondButton, KeyCode.S)
            assertEquals(1, replacement); assertEquals(2, b)
            fx { firstButton.requestFocus() }
            press(firstButton, KeyCode.S)
            assertEquals(2, a)
            fx { ShortcutManager.unregister(first) }
            press(firstButton, KeyCode.S)
            assertEquals(2, a)
        } finally { fx { ShortcutManager.unregister(first); ShortcutManager.unregister(second); other.close(); stage.close(); ShortcutManager.save(previous) } }
    }

    @Test fun `未確定IME入力とTextInputの標準キーを文書操作が奪わない`() {
        startFx()
        val field = fx { TextField() }
        val root = fx { VBox(field) }
        val stage = fx { Stage().apply { scene = Scene(root); show() } }
        var saves = 0; var documentUndo = 0; var copyReachedInput = false; var composeReachedInput = false
        val previous = fx { ShortcutManager.overrides() }
        try {
            fx {
                ShortcutManager.save(emptyMap())
                ShortcutManager.register(root, ShortcutScope.EDITOR, EditorShortcut.SAVE) { saves++; true }
                ShortcutManager.register(root, ShortcutScope.EDITOR, EditorShortcut.UNDO) { documentUndo++; true }
                field.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
                    if (event.isShortcutDown && event.code == KeyCode.C) { copyReachedInput = !event.isConsumed; event.consume() }
                }
                field.addEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED) { composeReachedInput = !it.isConsumed }
                field.requestFocus()
                field.appendText("原稿")
                assertTrue(field.isUndoable)
            }
            press(field, KeyCode.C)
            assertTrue(copyReachedInput, "コピーキーがTextInputへ届かない")
            press(field, KeyCode.Z)
            assertEquals(0, documentUndo)
            fx { assertTrue(field.isRedoable); assertTrue(ShortcutManager.execute(root, EditorShortcut.REDO)); assertEquals("原稿", field.text) }
            fx { Event.fireEvent(field, InputMethodEvent(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, FXCollections.observableArrayList(InputMethodTextRun("にほん", InputMethodHighlight.UNSELECTED_RAW)), "", 0)) }
            assertTrue(composeReachedInput)
            press(field, KeyCode.S)
            assertEquals(0, saves)
            fx { Event.fireEvent(field, InputMethodEvent(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, FXCollections.observableArrayList(), "日本", 0)) }
            press(field, KeyCode.S)
            assertEquals(1, saves)
        } finally { fx { ShortcutManager.unregister(root); stage.close(); ShortcutManager.save(previous) } }
    }

    @Test fun `ownerのキーとメニューはモーダル中に実行せず閉じれば復帰する`() {
        startFx()
        val button = fx { Button("文書") }; val root = fx { VBox(button) }
        val owner = fx { Stage().apply { scene = Scene(root); show() } }
        val modal = fx { Stage().apply { initOwner(owner); initModality(Modality.WINDOW_MODAL); scene = Scene(VBox(Label("確認"))); show() } }
        var count = 0
        try {
            fx { ShortcutManager.register(root, ShortcutScope.EDITOR, EditorShortcut.SAVE) { count++; true }; button.requestFocus(); assertFalse(ShortcutManager.execute(root, EditorShortcut.SAVE)) }
            press(button, KeyCode.S); assertEquals(0, count)
            fx { modal.close(); button.requestFocus(); assertTrue(ShortcutManager.execute(root, EditorShortcut.SAVE)) }
            assertEquals(1, count)
        } finally { fx { ShortcutManager.unregister(root); modal.close(); owner.close() } }
    }

    @Test fun `登録解除復元が表示と保存へ反映され不明値を既定へ戻さない`() {
        startFx()
        val previous = fx { AppSettingsManager.load() }
        val originalBindings = fx { ShortcutManager.overrides() }
        val button = fx { Button("文書") }; val root = fx { VBox(button) }
        val stage = fx { Stage().apply { scene = Scene(root); show() } }
        val hint = fx { ShortcutManager.hint(EditorShortcut.SAVE, "保存") }
        var saves = 0
        try {
            fx {
                ShortcutManager.register(root, ShortcutScope.EDITOR, EditorShortcut.SAVE) { saves++; true }
                ShortcutManager.save(mapOf("SAVE" to "F6")); button.requestFocus()
                assertTrue(hint.value.contains("F6"))
                assertEquals("F6", AppSettingsManager.load().shortcuts["SAVE"])
            }
            press(button, KeyCode.F6, false); assertEquals(1, saves)
            fx { ShortcutManager.save(mapOf("SAVE" to "")); assertEquals("保存", hint.value) }
            press(button, KeyCode.S); assertEquals(1, saves)
            fx { ShortcutManager.save(mapOf("SAVE" to "unknown key value")); assertNull(ShortcutManager.combination(EditorShortcut.SAVE)) }
            press(button, KeyCode.S); assertEquals(1, saves)
            fx { ShortcutManager.save(emptyMap()); assertEquals(previous.theme, AppSettingsManager.load().theme) }
            press(button, KeyCode.S); assertEquals(2, saves)
        } finally { fx { hint.dispose(); ShortcutManager.unregister(root); stage.close(); ShortcutManager.save(originalBindings); AppSettingsManager.save(previous) } }
    }

    @Test fun `保存済み競合と非表示領域はどちらも実行しない`() {
        startFx()
        val previous = fx { ShortcutManager.overrides() }
        val button = fx { Button("文書") }; val root = fx { VBox(button) }
        val stage = fx { Stage().apply { scene = Scene(root); show() } }
        var count = 0
        try {
            fx {
                ShortcutManager.register(root, ShortcutScope.EDITOR, EditorShortcut.SAVE) { count++; true }
                ShortcutManager.register(root, ShortcutScope.EDITOR, EditorShortcut.CREATE_DATA) { count++; true }
                ShortcutManager.save(mapOf("SAVE" to "F6", "CREATE_DATA" to "F6")); button.requestFocus()
            }
            press(button, KeyCode.F6, false); assertEquals(0, count)
            fx { ShortcutManager.save(emptyMap()); root.isVisible = false; assertFalse(ShortcutManager.execute(root, EditorShortcut.SAVE)) }
            assertEquals(0, count)
        } finally { fx { ShortcutManager.unregister(root); stage.close(); ShortcutManager.save(previous) } }
    }

    @Test fun `TextInputのカスタムUndoRedoは現在入力へ届きreadonlyとdisabledを変更しない`() {
        startFx()
        val previous = fx { ShortcutManager.overrides() }
        val field = fx { TextField() }; val root = fx { VBox(field) }
        val stage = fx { Stage().apply { scene = Scene(root); show() } }
        var documentHistory = 0
        var altGraphReached = false
        try {
            fx {
                listOf(EditorShortcut.UNDO, EditorShortcut.REDO, EditorShortcut.REDO_ALTERNATIVE, EditorShortcut.REPAIR_WARNING).forEach { id ->
                    ShortcutManager.register(root, ShortcutScope.EDITOR, id) { documentHistory++; true }
                }
                ShortcutManager.save(mapOf("UNDO" to "F6", "REDO" to "F7", "REDO_ALTERNATIVE" to "F8"))
                field.requestFocus(); field.appendText("原稿")
                field.addEventFilter(KeyEvent.KEY_PRESSED) {
                    if (it.isControlDown && it.isAltDown && it.code == KeyCode.W) { altGraphReached = !it.isConsumed; it.consume() }
                }
            }
            press(field, KeyCode.F6, false)
            fx { assertEquals("", field.text) }
            press(field, KeyCode.F7, false)
            fx { assertEquals("原稿", field.text) }
            press(field, KeyCode.F6, false); press(field, KeyCode.F8, false)
            fx {
                assertEquals("原稿", field.text); assertEquals(0, documentHistory)
                field.isEditable = false
                assertFalse(ShortcutManager.execute(root, EditorShortcut.UNDO))
                assertEquals("原稿", field.text)
                field.isEditable = true; field.isDisable = true
                ShortcutManager.execute(root, EditorShortcut.UNDO)
                assertEquals("原稿", field.text)
                field.isDisable = false; field.requestFocus(); documentHistory = 0
            }
            press(field, KeyCode.Z)
            fx { assertEquals("", field.text); assertEquals(0, documentHistory) }
            fx { Event.fireEvent(field, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.W, false, true, true, false)) }
            assertTrue(altGraphReached); assertEquals(0, documentHistory)
        } finally { fx { ShortcutManager.unregister(root); stage.close(); ShortcutManager.save(previous) } }
    }

    @Test fun `保存済み競合を操作選択時に表示して解除と復元で訂正できる`() {
        startFx()
        val previous = fx { ShortcutManager.overrides() }
        val owner = fx { Stage().apply { scene = Scene(VBox(Label("文書"))); show() } }
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        try {
            fx {
                ShortcutManager.save(mapOf("SAVE" to "F6", "CREATE_DATA" to "F6"))
                Platform.runLater {
                    val dialog = javafx.stage.Window.getWindows().filterIsInstance<Stage>().first { it.title == "キーボードショートカット" }
                    try {
                        val root = dialog.scene.root
                        val list = root.lookup("#shortcut-operations") as javafx.scene.control.ListView<*>
                        val capture = root.lookup("#shortcut-key") as TextField
                        val status = root.lookup("#shortcut-conflict") as Label
                        list.selectionModel.select(EditorShortcut.SAVE.ordinal)
                        assertTrue(status.text.contains("競合"), status.text)
                        list.applyCss(); list.layout()
                        assertTrue(list.lookupAll(".list-cell").filterIsInstance<javafx.scene.control.ListCell<*>>().any { it.text?.contains("設定エラー") == true })
                        (root.lookup("#shortcut-clear") as Button).fire()
                        assertNull(ShortcutManager.combination(EditorShortcut.SAVE)); assertEquals("", capture.text)
                        assertEquals("", status.text)
                        (root.lookup("#shortcut-restore") as Button).fire()
                        assertEquals(KeyCode.S, ShortcutManager.combination(EditorShortcut.SAVE)?.code)
                        assertTrue(capture.text.isNotEmpty()); assertEquals("", status.text)
                        (root.lookup("#shortcut-restore-all") as Button).fire()
                        assertTrue(AppSettingsManager.load().shortcuts.isEmpty())
                    } catch (error: Throwable) { failure.set(error) }
                    finally { dialog.close() }
                }
                io.github.sushiericworkspace.sushiericservermanager.ui.dialog.ShortcutSettingsDialog.show(owner)
            }
            failure.get()?.let { throw it }
        } finally { fx { owner.close(); ShortcutManager.save(previous) } }
    }

    private fun press(node: javafx.scene.Node, code: KeyCode, shortcut: Boolean = true) {
        fx { Event.fireEvent(node, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, shortcut && !OS.isMac, false, shortcut && OS.isMac)) }
    }

    private fun startFx() {
        val isolated = System.getProperty("sushieric.test.configRoot") ?: error("隔離したGradle testタスクを使用してください")
        assertTrue(Path.of(OS.dataConfigBase).toAbsolutePath().normalize().startsWith(Path.of(isolated).toAbsolutePath().normalize()))
        val ready = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
        catch (_: IllegalStateException) { Platform.runLater { Platform.setImplicitExit(false); ready.countDown() } }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
    }

    private fun <T> fx(action: () -> T): T {
        val task = FutureTask(action); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS)
    }
}
