package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.util.Duration
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentKey
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentSource
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceManager
import io.github.sushiericworkspace.sushiericservermanager.config.WorkspaceFeature
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.ShortcutManager

object EditorWindowManager {
    private var closingAll = false
    internal val isClosing: Boolean get() = closingAll
    private val activeEditors = mutableMapOf<WorkspaceFeature, EditorView<*>>()

    /** 表示中のworkspaceへ既存Viewを一度だけ生成し、再選択時はその領域へ移動します。 */
    fun openEditor(
        key: WorkspaceFeature,
        title: String,
        loader: FXMLLoader,
        logicFactory: (MainController) -> EditorView<*>
    ) {
        val workspace = WorkspaceManager.requireWorkspace()
        if (workspace.focus(key) || hasRunningSave || closingAll) return
        val root = loader.load<Parent>()
        val mainController = loader.getController<MainController>()
        if (EditorSession.dataService?.isRemote == true) {
            mainController.sidebarContainer.isVisible = false
            mainController.sidebarContainer.isManaged = false
        }
        val logic = logicFactory(mainController)
        if (logic.openCancelled) return
        activeEditors[key] = logic
        workspace.show(key, root, title = title, close = { completed ->
            logic.onClose { success ->
                if (success) activeEditors.remove(key)
                completed(success)
            }
        })
        mainController.switchView(logic)
    }

    /** 機能の起動後、同じViewへ保存元を含む文書を渡します。 */
    internal fun openDocument(key: EditorDocumentKey,
        save: Boolean = false, completed: (Boolean) -> Unit = {}) {
        val feature = when (key.category) {
            "items" -> WorkspaceFeature.ITEM_EDITOR
            "ores" -> WorkspaceFeature.ORE_EDITOR
            "shops" -> WorkspaceFeature.SHOP_EDITOR
            "recipes" -> WorkspaceFeature.RECIPE_EDITOR
            else -> { completed(false); return }
        }
        val workspace = WorkspaceManager.requireWorkspace()
        if (workspace.scene?.let(ShortcutManager::isComposing) == true) { completed(false); return }
        workspace.openDocumentFeature(feature)
        val view = activeEditors[feature] ?: run { completed(false); return }
        view.openDocument(key) { opened ->
            if (!opened || !save) completed(opened)
            else view.onSave(key.id) { outcome -> completed(outcome in setOf(EditorView.SaveOutcome.SAVED,
                EditorView.SaveOutcome.SAVED_WITH_ERRORS, EditorView.SaveOutcome.NOTHING_TO_SAVE)) }
        }
    }

    internal fun openDocumentIds(source: EditorDocumentSource, category: String): Set<String> =
        activeEditors.values.flatMapTo(mutableSetOf()) { it.openDocumentIds(source, category) }

    val hasRunningSave: Boolean
        get() = activeEditors.values.any { it.saveRunning }

    /** JavaFXスレッドで全エディターの退避完了を待って閉じます。失敗時は全画面を保持します。 */
    fun closeAll(onComplete: (Boolean) -> Unit) {
        prepareCloseAll({}, { success ->
            if (success) completeCloseAll()
            onComplete(success)
        })
    }

    /**
     * 実行中操作の完了を待ち、全画面の編集内容を退避します。キャッシュと表示領域は保持します。
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
                editors[index].prepareClose { success ->
                    if (success) next(index + 1)
                    else { cancelCloseAll(); onComplete(false) }
                }
            }
            next(0)
        }
        val wait = Timeline()
        wait.keyFrames.add(KeyFrame(Duration.millis(50.0), {
            if (EditorSession.dataService?.activeSaves.let { it == null || it == 0 } &&
                activeEditors.values.none { it.closeBusy } && WorkspaceManager.current?.uploadRunning != true) {
                wait.stop()
                prepare()
            }
        }))
        wait.cycleCount = Timeline.INDEFINITE
        wait.play()
    }

    internal fun cancelCloseAll() {
        activeEditors.values.forEach { it.cancelClose() }
        closingAll = false
    }

    internal fun completeCloseAll() {
        val editors = activeEditors.toMap()
        activeEditors.clear()
        editors.forEach { (key, editor) -> editor.completeClose(); WorkspaceManager.completeClose(key) }
        closingAll = false
    }
}
