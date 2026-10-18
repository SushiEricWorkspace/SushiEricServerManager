package io.github.sushiericworkspace.sushiericservermanager.ui.shortcut

import io.github.sushiericworkspace.sushiericservermanager.config.AppSettingsManager
import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.beans.binding.StringBinding
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ChangeListener
import javafx.event.EventHandler
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.TextInputControl
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuButton
import javafx.scene.input.InputMethodEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window

/** Sceneごとに入力経路を一つ持ち、フォーカス中の登録領域へ操作IDを振り分けます。 */
object ShortcutManager {
    private val sceneKey = Any()
    private val targetKey = Any()
    private val bindings = SimpleObjectProperty<Map<String, String>>(AppSettingsManager.load().shortcuts)

    fun combination(shortcut: EditorShortcut) = ShortcutBindings.resolve(shortcut, bindings.get())
    fun overrides(): Map<String, String> = bindings.get()

    /** メニューやボタンの表示も、保存されたキー割当に追従します。acceleratorは登録しません。 */
    fun hint(shortcut: EditorShortcut, text: String = shortcut.displayName): StringBinding = Bindings.createStringBinding({
        val key = combination(shortcut)
        when {
            key != null && ShortcutBindings.problem(shortcut, key, bindings.get()) != null -> "$text（割当無効）"
            key != null -> "$text（${key.displayText}）"
            !bindings.get()[shortcut.name].isNullOrEmpty() -> "$text（割当無効）"
            else -> text
        }
    }, bindings)

    /** 他の設定を保持して差分を保存します。空文字は解除、キーの削除は既定値への復元です。 */
    fun save(overrides: Map<String, String>): Boolean {
        check(Platform.isFxApplicationThread())
        val settings = AppSettingsManager.load()
        AppSettingsManager.save(settings.copy(shortcuts = overrides))
        if (AppSettingsManager.load().shortcuts != overrides) return false
        bindings.set(overrides.toMap())
        return true
    }

    /** 同じNodeと操作IDの再登録は置換します。Scene移動時は古い入力監視を解除します。 */
    fun register(root: Node, scope: ShortcutScope, shortcut: EditorShortcut, action: () -> Boolean) {
        require(scope in shortcut.scopes && shortcut.available)
        val target = root.properties[targetKey] as? Target ?: Target(root, scope).also {
            root.properties[targetKey] = it
            root.sceneProperty().addListener(it.sceneListener)
            root.scene?.let { scene -> state(scene).add(it) }
        }
        require(target.scope == scope)
        target.actions[shortcut] = action
    }

    fun unregister(root: Node) {
        val target = root.properties.remove(targetKey) as? Target ?: return
        root.sceneProperty().removeListener(target.sceneListener)
        root.scene?.let { (it.properties[sceneKey] as? SceneState)?.remove(target) }
    }

    /** メニュー・ボタンもキーと同じ実行経路を使い、入力中のUndo/Redoは標準編集へ委譲します。 */
    fun execute(root: Node, shortcut: EditorShortcut): Boolean {
        val scene = root.scene ?: return false
        return (scene.properties[sceneKey] as? SceneState)?.execute(shortcut) == true
    }

    /** 表示領域の切替に共通メニューの宛先を合わせます。操作のない領域では宛先を解除します。 */
    internal fun activate(root: Node) {
        val scene = root.scene ?: return
        val state = scene.properties[sceneKey] as? SceneState ?: return
        state.active = state.containing(root)
    }

    /** 履歴確定側も同じSceneのIME未確定入力を保護します。FXスレッドから参照します。 */
    internal fun isComposing(scene: Scene): Boolean = (scene.properties[sceneKey] as? SceneState)?.composing != null

    private fun state(scene: Scene): SceneState = (scene.properties[sceneKey] as? SceneState)
        ?: SceneState(scene).also { scene.properties[sceneKey] = it }

    private class Target(val root: Node, val scope: ShortcutScope) {
        val actions = linkedMapOf<EditorShortcut, () -> Boolean>()
        val sceneListener = ChangeListener<Scene> { _, previous, current ->
            (previous?.properties?.get(sceneKey) as? SceneState)?.remove(this)
            current?.let { state(it).add(this) }
        }
    }

    private class SceneState(val scene: Scene) {
        val targets = linkedMapOf<Node, Target>()
        var active: Target? = null
        var composing: Node? = null
        val focusListener = ChangeListener<Node> { _, _, node ->
            composing = null
            if (!menuFocus(node)) active = containing(node)
        }
        val inputHandler = EventHandler<InputMethodEvent> { event ->
            composing = if (event.composed.isNotEmpty()) scene.focusOwner else null
        }
        val keyHandler = EventHandler<KeyEvent> { event ->
            if (event.isConsumed || composing != null || blocked()) return@EventHandler
            val input = scene.focusOwner as? TextInputControl
            if (input != null && (event.isControlDown && event.isAltDown ||
                    event.isShortcutDown && event.code in setOf(KeyCode.C, KeyCode.X, KeyCode.V, KeyCode.A, KeyCode.Z, KeyCode.Y))) return@EventHandler
            val target = focused() ?: return@EventHandler
            val matched = target.actions.keys.filter { shortcut ->
                val key = combination(shortcut)
                key != null && key.match(event) && ShortcutBindings.problem(shortcut, key, bindings.get()) == null
            }
            if (matched.size == 1) {
                val shortcut = matched.single()
                if (execute(shortcut)) event.consume()
            }
        }

        init {
            scene.addEventFilter(KeyEvent.KEY_PRESSED, keyHandler)
            scene.addEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, inputHandler)
            scene.focusOwnerProperty().addListener(focusListener)
        }

        fun add(target: Target) {
            targets[target.root] = target
            if (active == null) active = target
        }

        fun remove(target: Target) {
            targets.remove(target.root)
            if (active === target) active = null
            if (targets.isEmpty()) {
                scene.removeEventFilter(KeyEvent.KEY_PRESSED, keyHandler)
                scene.removeEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED, inputHandler)
                scene.focusOwnerProperty().removeListener(focusListener)
                scene.properties.remove(sceneKey)
            }
        }

        fun containing(node: Node?): Target? {
            var current = node
            while (current != null) {
                targets[current]?.let { return it }
                current = current.parent
            }
            return null
        }

        fun focused(): Target? = containing(scene.focusOwner) ?: active?.takeIf {
            targets[it.root] === it && (scene.focusOwner == null || menuFocus(scene.focusOwner))
        }

        fun menuFocus(node: Node?): Boolean {
            var current = node
            while (current != null) {
                if (current is MenuBar && current.id == "applicationMenu" ||
                    current is MenuButton && current.id == "applicationSettings") return true
                current = current.parent
            }
            return false
        }

        fun execute(shortcut: EditorShortcut): Boolean {
            if (composing != null || scene.window?.isShowing != true || blocked()) return false
            val input = scene.focusOwner as? TextInputControl
            if (input != null) {
                when (shortcut) {
                    EditorShortcut.UNDO, EditorShortcut.REDO, EditorShortcut.REDO_ALTERNATIVE -> {
                        if (!input.isEditable || input.isDisabled) return false
                        if (shortcut == EditorShortcut.UNDO) { if (input.isUndoable) input.undo() }
                        else if (input.isRedoable) input.redo()
                        return true
                    }
                    else -> Unit
                }
            }
            val target = focused() ?: return false
            if (target.root.isDisabled) return false
            var node: Node? = target.root
            while (node != null) { if (!node.isVisible) return false; node = node.parent }
            return target.actions[shortcut]?.invoke() == true
        }

        fun blocked(): Boolean {
            val owner = scene.window ?: return false
            return Window.getWindows().filterIsInstance<Stage>().any { modal ->
                var ancestor: Window? = owner
                while (ancestor != null && ancestor !== modal) ancestor = (ancestor as? Stage)?.owner
                ancestor == null && modal.isShowing && when (modal.modality) {
                    Modality.APPLICATION_MODAL -> true
                    Modality.WINDOW_MODAL -> {
                        var parent = modal.owner
                        while (parent != null && parent !== owner) parent = (parent as? Stage)?.owner
                        parent === owner
                    }
                    else -> false
                }
            }
        }
    }
}
