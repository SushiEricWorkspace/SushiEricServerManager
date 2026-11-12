package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import javafx.beans.value.ChangeListener
import javafx.scene.input.MouseButton
import javafx.stage.Modality
import io.github.sushiericworkspace.sushiericservermanager.feature.simulator.SimulatorImportedItem
import javafx.application.Platform
import javafx.event.ActionEvent
import javafx.event.EventHandler
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.TextInputControl
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.stage.PopupWindow
import javafx.stage.Stage
import javafx.stage.Window
import javafx.stage.WindowEvent

/** Item専用取込。Stage captureで既存Scene履歴filterより先にconsumeし、元編集を確定しません。 */
internal class ItemSimulatorImport(
    private val content: Parent,
    private val button: Button,
    private val message: Label,
    private val busy: () -> Boolean,
    private val snapshot: () -> SimulatorImportedItem?,
    private val accept: (SimulatorImportedItem) -> Unit
) {
    private var stage: Stage? = null
    private var disposed = false
    private val mouse = EventHandler<MouseEvent> { event ->
        if (within(event.target as? Node, button)) {
            event.consume()
            if (event.button == MouseButton.PRIMARY) attempt()
        }
    }
    private val key = EventHandler<KeyEvent> { event ->
        if (event.code in listOf(KeyCode.ENTER, KeyCode.SPACE) && within(event.target as? Node, button)) {
            event.consume(); attempt()
        }
    }
    private val action = EventHandler<ActionEvent> { event ->
        if (within(event.target as? Node, button)) { event.consume(); attempt() }
    }
    private val hidden = EventHandler<WindowEvent> { detach() }

    private val windowListener = ChangeListener<Window> { _, _, window -> attach(window as? Stage) }

    init {
        button.setOnAction { attempt() }
        button.sceneProperty().addListener { _, old, scene ->
            old?.windowProperty()?.removeListener(windowListener)
            detach()
            scene?.windowProperty()?.addListener(windowListener)
            attach(scene?.window as? Stage)
        }
        button.scene?.windowProperty()?.addListener(windowListener)
        attach(button.scene?.window as? Stage)
    }

    private fun attach(window: Stage?) {
        detach()
        if (disposed) return
        stage = window
        window?.addEventFilter(MouseEvent.MOUSE_PRESSED, mouse)
        window?.addEventFilter(KeyEvent.KEY_PRESSED, key)
        window?.addEventFilter(ActionEvent.ACTION, action)
        window?.addEventHandler(WindowEvent.WINDOW_HIDDEN, hidden)
    }

    private fun detach() {
        stage?.removeEventFilter(MouseEvent.MOUSE_PRESSED, mouse)
        stage?.removeEventFilter(KeyEvent.KEY_PRESSED, key)
        stage?.removeEventFilter(ActionEvent.ACTION, action)
        stage?.removeEventHandler(WindowEvent.WINDOW_HIDDEN, hidden)
        stage = null
    }

    internal fun dispose() {
        disposed = true
        button.scene?.windowProperty()?.removeListener(windowListener)
        button.onAction = null
        detach()
    }

    internal fun attempt() {
        check(Platform.isFxApplicationThread())
        val reason = rejectionReason()
        if (reason != null) { message.text = reason; return }
        try {
            val item = snapshot()
            if (item == null) { message.text = "選択中の確定済みItemがありません。"; return }
            accept(item); message.text = "固定したItemをシミュレーターへ取り込みました。" }
        catch (error: IllegalArgumentException) { message.text = error.message.orEmpty() }
        catch (error: IllegalStateException) { message.text = error.message.orEmpty() }
    }

    internal fun rejectionReason(): String? {
        val window = stage ?: return "Item画面の状態を確認できないため取り込めません。"
        if (!window.isShowing || content.scene !== window.scene || busy()) return "読み込み・保存・修正中は取り込めません。"
        if (Window.getWindows().any { it !== window && it.isShowing && (it is PopupWindow || it is Stage && it.modality != Modality.NONE) && ownedBy(it, window) }) return "Itemのダイアログ・ポップアップを閉じてから取り込んでください。"
        val focus = window.scene.focusOwner
        if (focus is TextInputControl && focus.isEditable && within(focus, content)) return pendingMessage
        return try {
            if (descendants(content).filterIsInstance<Spinner<*>>().any { spinner ->
                spinner.isEditable && spinner.editor.text != formatted(spinner)
            }) pendingMessage else null
        } catch (_: RuntimeException) { "入力状態を確認できないため取り込めません。" }
    }

    private fun <T> formatted(spinner: Spinner<T>): String? = spinner.valueFactory?.converter?.toString(spinner.value)

    private fun descendants(node: Node): Sequence<Node> = sequence {
        yield(node)
        if (node is Parent) for (child in node.childrenUnmodifiable) yieldAll(descendants(child))
    }

    private fun within(node: Node?, parent: Node): Boolean = generateSequence(node) { it.parent }.any { it === parent }
    private fun ownedBy(window: Window, owner: Window): Boolean {
        val parent = when (window) { is Stage -> window.owner; is PopupWindow -> window.ownerWindow; else -> null }
        return parent != null && (parent === owner || ownedBy(parent, owner))
    }

    private val pendingMessage = "入力が未確定です。Enter等で確定し、入力欄から離れて再取込してください。"
}
