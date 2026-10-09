package io.github.sushiericworkspace.sushiericservermanager.ui.dialog

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import javafx.concurrent.Task
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.ProgressIndicator
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage

/** 最新の材料・レシピ取得を含む保存を別スレッドで実行し、処理中の再編集を防ぎます。 */
internal object RecipeSaveProgressDialog {
    fun save(owner: Stage?, dataId: String, operation: () -> StoreResult<Unit>): StoreResult<Unit> {
        var result: StoreResult<Unit> = StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, dataId))
        val stage = Stage().apply {
            title = "レシピの検証・保存"
            if (owner != null) initOwner(owner)
            initModality(if (owner == null) Modality.APPLICATION_MODAL else Modality.WINDOW_MODAL)
            isResizable = false
            scene = Scene(createRecipeSaveContent()).apply {
                RecipeSaveProgressDialog::class.java.getResource(AppScreen.WIDGETS_ONLY.css)?.toExternalForm()?.let(stylesheets::add)
            }
            setOnCloseRequest { it.consume() }
        }
        val task = object : Task<StoreResult<Unit>>() {
            override fun call(): StoreResult<Unit> = operation()
        }
        task.setOnSucceeded { result = task.value; stage.hide() }
        task.setOnFailed {
            result = StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, dataId, cause = task.exception))
            stage.hide()
        }
        stage.setOnShown { Thread(task, "recipe-editor-save").apply { isDaemon = true; start() } }
        stage.showAndWait()
        return result
    }
}

/** 検証中のStageにも共通の暗色背景を適用します。 */
internal fun createRecipeSaveContent(): VBox = VBox(16.0,
    Label("参照・材料の重複を確認して保存しています..."), ProgressIndicator()
).apply {
    styleClass.add("common-root")
    padding = Insets(24.0)
    alignment = Pos.CENTER
}
