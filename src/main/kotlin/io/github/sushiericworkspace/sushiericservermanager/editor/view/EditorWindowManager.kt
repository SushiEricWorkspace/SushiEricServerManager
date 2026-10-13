package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.app.appWindowTitle
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.stage.Stage

object EditorWindowManager {
    // 開いているエディタをロジックのクラス名などで管理
    private var closingAll = false
    private val activeEditors = mutableMapOf<String, ActiveEditor>()

    /**
     * 開いているエディタのウィンドウと、その編集ロジックです。
     *
     * 一括クローズでも終了処理を実行できるよう、ロジックを併せて保持します。
     */
    private data class ActiveEditor(
        val stage: Stage,
        val logic: EditorView<*>
    )

    fun openEditor(
        key: String,
        title: String,
        loader: FXMLLoader,
        logicFactory: (MainController) -> EditorView<*>
    ) {
        // マップにあるが、実際には閉じられている Stage がないかチェック
        val existingEditor = activeEditors[key]
        if (existingEditor != null && existingEditor.stage.isShowing) {
            existingEditor.stage.toFront()
            return
        } else {
            // 閉じられているのにマップに残っている場合は掃除
            activeEditors.remove(key)
        }

        if (hasRunningSave || closingAll) return

        val root = loader.load<Parent>()
        val mainController = loader.getController<MainController>()

        val newStage = Stage().apply {
            this.title = appWindowTitle(title)
            this.scene = Utility.createScene(AppScreen.BASE, customRoot = root)
        }

        val logic = logicFactory(mainController)

        if (logic.openCancelled) {
            return
        }

        newStage.setOnCloseRequest { event ->
            event.consume()
            logic.onClose { success ->
                if (success) {
                    activeEditors.remove(key)
                    newStage.close()
                }
            }
        }

        activeEditors[key] = ActiveEditor(newStage, logic)
        newStage.setOnHidden {
            logic.onClose { }
            activeEditors.remove(key)
        }
        newStage.show()
        mainController.switchView(logic)
    }

    val hasRunningSave: Boolean
        get() = activeEditors.values.any { it.logic.saveRunning }

    /**
     * JavaFXスレッドから呼び出し、全エディターの退避完了を待って閉じます。
     * 結果もJavaFXスレッドで通知し、処理中や対象がない場合はその場で通知します。
     * 退避失敗時はその画面と未処理の画面を保持し、切断・画面遷移を中断してください。
     */
    fun closeAll(onComplete: (Boolean) -> Unit) {
        if (hasRunningSave || closingAll) { onComplete(false); return }
        closingAll = true
        val editors = activeEditors.entries.map { it.key to it.value }
        fun closeNext(index: Int) {
            if (index == editors.size) {
                closingAll = false
                onComplete(true)
                return
            }
            val (key, editor) = editors[index]
            editor.logic.onClose { success ->
                if (!success) {
                    closingAll = false
                    onComplete(false)
                } else {
                    activeEditors.remove(key)
                    editor.stage.close()
                    closeNext(index + 1)
                }
            }
        }
        closeNext(0)
    }
}
