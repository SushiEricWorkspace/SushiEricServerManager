package io.github.sushiericworkspace.sushiericservermanager.editor.controller

import io.github.sushiericworkspace.sushiericservermanager.editor.result.ValidationResult
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorView
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.ValidatedInputDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.EditorShortcut
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.ShortcutManager
import javafx.animation.PauseTransition
import javafx.fxml.FXML
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import javafx.util.Duration

class MainController {
    @FXML lateinit var actionButtonContainer: HBox
    @FXML lateinit var sidebarContainer: VBox
    @FXML lateinit var mainContentContainer: HBox
    @FXML private lateinit var topInfoLabel: Label

    private var topLabelTimer: PauseTransition? = null

    val currentStage: Stage?
        get() = sidebarContainer.scene?.window as? Stage

    fun switchView(logic: EditorView<*>) {
        actionButtonContainer.children.clear()
        sidebarContainer.children.clear()
        mainContentContainer.children.clear()
        setTopLabel("")

        logic.setupActions(actionButtonContainer)
        logic.setupSidebar(sidebarContainer)

        if (mainContentContainer.scene != null) {
            setupEditorShortcuts(logic)
        } else {
            mainContentContainer.sceneProperty().addListener { _, _, newScene ->
                if (newScene != null) {
                    setupEditorShortcuts(logic)
                }
            }
        }
    }

    fun setTopLabel(text: String, textColor: Color = Color.WHITE) {
        topInfoLabel.text = text
        topInfoLabel.textFill = textColor
    }

    /**
     * トップバーの右側にあるラベルの文字と色を、カラーコード（文字列）で更新します。
     *
     * @param text 表示する文字列
     * @param hexColor カラーコード（例: "#FF0000", "red", "#32CD32" など）
     */
    fun setTopLabel(text: String, hexColor: String) {
        setTopLabel(text, parseColor(hexColor))
    }

    fun showTimedTopLabel(text: String, textColor: Color = Color.WHITE, delaySeconds: Double = 2.0) {
        setTopLabel(text, textColor)
        topLabelTimer?.stop()
        topLabelTimer = PauseTransition(Duration.seconds(delaySeconds)).apply {
            setOnFinished {
                setTopLabel("")
                topLabelTimer = null
            }
            play()
        }
    }

    /**
     * 【カラーコード文字列版】指定した時間（秒）だけトップバーにメッセージを表示し、その後自動で消去します。
     *
     * @param text 表示する文字列
     * @param hexColor カラーコード（例: "#32CD32"）
     * @param delaySeconds 表示しておく時間（デフォルトは 1.0 秒）
     */
    fun showTimedTopLabel(text: String, hexColor: String, delaySeconds: Double = 1.0) {
        showTimedTopLabel(text, parseColor(hexColor), delaySeconds)
    }

    fun clearTopLabelTimer() {
        topLabelTimer?.stop()
        topLabelTimer = null
    }

    /**
     * 現在のエディタに対して保存ショートカットを登録します。
     */
    private fun setupEditorShortcuts(logic: EditorView<*>) {
        val scene = mainContentContainer.scene ?: return

        ShortcutManager.register(
            scene = scene,
            shortcut = EditorShortcut.SAVE
        ) {
            logic.onSave()
        }
        listOf(
            EditorShortcut.SAVE_ALL to logic::onSaveAll,
            EditorShortcut.SYNC to logic::onSynchronizeSelected,
            EditorShortcut.SYNC_ALL to logic::onSynchronizeAll,
            EditorShortcut.CREATE_DATA to logic::onCreateData,
            EditorShortcut.CREATE_DIRECTORY to logic::onCreateDirectory,
            EditorShortcut.REPAIR_WARNING to logic::repairSelectedWarnings,
            EditorShortcut.REPAIR_ALL_WARNINGS to logic::repairAllWarnings,
            EditorShortcut.FOCUS_ERROR to logic::focusSelectedError,
            EditorShortcut.FOCUS_ALL_ERRORS to logic::focusAllErrors
        ).forEach { (shortcut, action) ->
            ShortcutManager.register(scene, shortcut) { action() }
        }
        ShortcutManager.registerHistoryShortcuts(
            scene = scene,
            onUndo = logic::onUndo,
            onRedo = logic::onRedo
        )
    }

    /**
     * 現在のエディタ画面に登録されたショートカットを解除します。
     */
    fun clearShortcuts() {
        ShortcutManager.unregisterAll(mainContentContainer.scene)
    }

    /**
     * ユーザーに入力を求めるモーダルダイアログを表示します。
     *
     * このメソッドは [Stage.showAndWait] を使用しているため、ダイアログが閉じられるまで
     * 呼び出し元のスレッドの実行を一時停止（ブロック）し、結果を返します。
     *
     * ### 戻り値の仕様:
     * - **String**: ユーザーがテキストを入力し「決定」を押した場合（空文字を含む）。
     * - **null**: ユーザーが「キャンセル」を押した、またはウィンドウを直接閉じた場合。
     *
     * @param title ダイアログのウィンドウタイトル
     * @param validator ボタン押下時に実行される検証ロジック。重い処理（通信等）を想定し、決定ボタン押下時のみ実行する。
     * @return ユーザーが入力した文字列、またはキャンセルされた場合は null
     *
     * @sample
     * val name = requestInput("新規ファイル名")
     * if (!name.isNullOrBlank()) {
     *     // 入力ありの時の処理
     * }
     */
    fun requestInput(
        title: String,
        validator: (String) -> ValidationResult = { ValidationResult.Success }
    ): String? = ValidatedInputDialog.show(title, validator)

    private fun parseColor(color: String): Color {
        return try {
            Color.web(color)
        } catch (_: IllegalArgumentException) {
            Color.WHITE
        }
    }
}
