package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.app.appWindowTitle
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.util.Duration
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.stage.Stage

object EditorWindowManager {
    // 開いているエディタをロジックのクラス名などで管理
    private var closingAll = false
    internal val isClosing: Boolean get() = closingAll
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
            this.scene = Utility.createScene(AppScreen.BASE, customRoot = root, windowKey = key)
        }

        val logic = logicFactory(mainController)

        if (logic.openCancelled) {
            return
        }

        newStage.setOnCloseRequest { event ->
            event.consume()
            if (closingAll) return@setOnCloseRequest
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

    /** JavaFXスレッドで全エディターの退避完了を待って閉じます。失敗時は全画面を保持します。 */
    fun closeAll(onComplete: (Boolean) -> Unit) {
        prepareCloseAll({}, { success ->
            if (success) completeCloseAll()
            onComplete(success)
        })
    }

    /**
     * 実行中操作の完了を待ち、全画面の編集内容を退避します。キャッシュとStageは保持します。
     * 接続終了成功時は[completeCloseAll]、失敗時は[cancelCloseAll]を呼んでください。
     * 引数の通知と呼び出しはすべてJavaFXスレッドで行います。重複要求は受け付けません。
     */
    internal fun prepareCloseAll(phase: (String) -> Unit, onComplete: (Boolean) -> Unit) {
        if (closingAll) { onComplete(false); return }
        closingAll = true
        phase("処理の完了を待っています")
        fun prepare() {
            phase("編集内容を退避中")
            val editors = activeEditors.values.toList()
            fun next(index: Int) {
                if (index == editors.size) { onComplete(true); return }
                editors[index].logic.prepareClose { success ->
                    if (success) next(index + 1)
                    else { cancelCloseAll(); onComplete(false) }
                }
            }
            next(0)
        }
        val wait = Timeline()
        wait.keyFrames.add(KeyFrame(Duration.millis(50.0), {
            if (EditorSession.dataService?.activeSaves.let { it == null || it == 0 } &&
                activeEditors.values.none { it.logic.closeBusy }) {
                wait.stop()
                prepare()
            }
        }))
        wait.cycleCount = Timeline.INDEFINITE
        wait.play()
    }

    internal fun cancelCloseAll() {
        activeEditors.values.forEach { it.logic.cancelClose() }
        closingAll = false
    }

    internal fun completeCloseAll() {
        val editors = activeEditors.values.toList()
        activeEditors.clear()
        editors.forEach { it.logic.completeClose(); it.stage.close() }
        closingAll = false
    }
}
