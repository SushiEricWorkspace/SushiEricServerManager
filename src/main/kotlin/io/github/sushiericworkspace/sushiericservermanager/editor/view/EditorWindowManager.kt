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

        if (hasRunningSave) return

        val root = loader.load<Parent>()
        val mainController = loader.getController<MainController>()

        val newStage = Stage().apply {
            this.title = appWindowTitle(title)
            this.scene = Utility.createScene(AppScreen.BASE, customRoot = root)
        }

        val logic = logicFactory(mainController)
        mainController.switchView(logic)

        if (logic.openCancelled) {
            return
        }

        newStage.setOnCloseRequest { event ->
            if (!logic.onClose()) {
                event.consume()
            } else {
                activeEditors.remove(key)
            }
        }

        activeEditors[key] = ActiveEditor(newStage, logic)
        newStage.show()
    }

    val hasRunningSave: Boolean
        get() = activeEditors.values.any { it.logic.saveRunning }

    /**
     * 全エディターの終了処理でローカル退避してからウィンドウを閉じます。
     * [Stage.close]はclose requestを発生させないため、終了処理を明示的に呼びます。
     *
     * @throws IllegalStateException 保存中のエディターがあり、まだ画面を閉じられない場合。
     */
    fun closeAll() {
        check(!hasRunningSave) { "保存中のエディターは閉じられません。" }
        val editors = activeEditors.values.toList()

        activeEditors.clear()

        editors.forEach { editor ->
            editor.logic.onClose()
            editor.stage.close()
        }
    }
}
