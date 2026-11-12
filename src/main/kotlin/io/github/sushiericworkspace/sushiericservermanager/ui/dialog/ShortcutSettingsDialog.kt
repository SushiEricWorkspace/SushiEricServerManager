package io.github.sushiericworkspace.sushiericservermanager.ui.dialog

import io.github.sushiericworkspace.sushiericservermanager.config.OS
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.EditorShortcut
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.ShortcutBindings
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.ShortcutManager
import javafx.collections.FXCollections
import javafx.scene.control.*
import javafx.scene.input.InputMethodEvent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Stage

/** 操作IDごとのキー登録・解除・既定値復元を保存します。同一scopeの競合は登録前に表示します。 */
object ShortcutSettingsDialog {
    fun show(owner: Stage?) {
        val operations = ListView(FXCollections.observableArrayList(EditorShortcut.entries)).apply {
            id = "shortcut-operations"
            style = "-fx-selection-bar-non-focused: -fx-selected-color;"
            prefHeight = 360.0
            setCellFactory {
                object : ListCell<EditorShortcut>() {
                    override fun updateItem(item: EditorShortcut?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = if (empty || item == null) null else {
                            val raw = ShortcutManager.overrides()[item.name]
                            val assigned = ShortcutManager.combination(item)
                            val key = assigned?.displayText ?: if (!raw.isNullOrEmpty()) "無効な設定: $raw" else "割当なし"
                            val invalid = assigned?.let { ShortcutBindings.problem(item, it, ShortcutManager.overrides()) } != null
                            "${item.scopes.joinToString("・") { it.displayName }} / ${item.displayName} — $key${if (invalid) "（設定エラー）" else ""}"
                        }
                    }
                }
            }
        }
        val capture = TextField().apply { id = "shortcut-key"; isEditable = false; promptText = "ここで登録するキーを押してください" }
        val status = Label().apply { id = "shortcut-conflict"; isWrapText = true }
        val register = Button("登録").apply { id = "shortcut-register" }
        val clear = Button("解除").apply { id = "shortcut-clear" }
        val restore = Button("選択を既定値へ戻す").apply { id = "shortcut-restore" }
        val restoreAll = Button("すべて既定値へ戻す").apply { id = "shortcut-restore-all" }
        var candidate: KeyCodeCombination? = null
        var composing = false
        fun update() {
            val selected = operations.selectionModel.selectedItem
            val problem = selected?.let { action -> candidate?.let { ShortcutBindings.problem(action, it, ShortcutManager.overrides()) } }
            val current = selected?.let(ShortcutManager::combination)
            val currentProblem = selected?.let { action ->
                current?.let { ShortcutBindings.problem(action, it, ShortcutManager.overrides()) }
                    ?: if (current == null && !ShortcutManager.overrides()[action.name].isNullOrEmpty()) "保存されたキー設定が無効です。解除または既定値へ戻してください。" else null
            }
            register.isDisable = selected == null || candidate == null || problem != null || !selected.available
            clear.isDisable = selected == null || !selected.available
            restore.isDisable = clear.isDisable
            status.text = when {
                selected?.available == false -> "この操作は現在利用できません。"
                candidate != null -> problem.orEmpty()
                else -> currentProblem.orEmpty()
            }
        }
        fun save(values: Map<String, String>) {
            if (!ShortcutManager.save(values)) { status.text = "設定を保存できませんでした。保存先を確認してください。"; return }
            candidate = null
            capture.text = operations.selectionModel.selectedItem?.let { ShortcutManager.combination(it)?.displayText }.orEmpty()
            operations.refresh()
            update()
        }
        operations.selectionModel.selectedItemProperty().addListener { _, _, selected ->
            candidate = null
            capture.text = selected?.let { ShortcutManager.combination(it)?.displayText }.orEmpty()
            update()
        }
        capture.addEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED) { composing = it.composed.isNotEmpty() }
        capture.focusedProperty().addListener { _, _, focused -> if (!focused) composing = false }
        capture.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (!composing && event.code != KeyCode.UNDEFINED && !event.code.isModifierKey) {
                val modifiers = mutableListOf<KeyCombination.Modifier>()
                if (event.isShortcutDown) modifiers += KeyCombination.SHORTCUT_DOWN
                if (event.isShiftDown) modifiers += KeyCombination.SHIFT_DOWN
                if (event.isAltDown) modifiers += KeyCombination.ALT_DOWN
                if (event.isControlDown && (OS.isMac || !event.isShortcutDown)) modifiers += KeyCombination.CONTROL_DOWN
                if (event.isMetaDown && (!OS.isMac || !event.isShortcutDown)) modifiers += KeyCombination.META_DOWN
                candidate = KeyCodeCombination(event.code, *modifiers.toTypedArray())
                capture.text = candidate!!.displayText
                update()
                if (!register.isDisable) event.consume()
            }
        }
        register.setOnAction {
            val selected = operations.selectionModel.selectedItem ?: return@setOnAction
            val key = candidate ?: return@setOnAction
            if (ShortcutBindings.problem(selected, key, ShortcutManager.overrides()) == null) save(ShortcutManager.overrides() + (selected.name to key.name))
        }
        clear.setOnAction { operations.selectionModel.selectedItem?.let { save(ShortcutManager.overrides() + (it.name to "")) } }
        restore.setOnAction {
            val selected = operations.selectionModel.selectedItem ?: return@setOnAction
            val next = ShortcutManager.overrides() - selected.name
            val problem = selected.combination?.let { ShortcutBindings.problem(selected, it, next) }
            if (problem != null) status.text = problem else save(next)
        }
        restoreAll.setOnAction { save(emptyMap()) }
        operations.selectionModel.selectFirst()
        Dialog<Void>().apply {
            title = "キーボードショートカット"
            if (owner != null) initOwner(owner)
            dialogPane.content = VBox(12.0,
                Label("操作とキーを選び「登録」で保存します。テキストの標準編集と日本語の未確定入力中は文書操作を実行しません。").apply { isWrapText = true },
                operations, capture, status, HBox(8.0, register, clear, restore, restoreAll)
            ).apply { prefWidth = 680.0; styleClass.add("appearance-settings") }
            dialogPane.buttonTypes.add(ButtonType("閉じる", ButtonBar.ButtonData.CANCEL_CLOSE))
            showAndWait()
        }
    }
}
