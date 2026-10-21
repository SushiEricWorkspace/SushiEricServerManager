package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentKey
import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentSnapshot
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import javafx.application.Platform
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.ComboBox
import javafx.scene.layout.VBox
import javafx.scene.control.Label
import javafx.stage.Modality
import javafx.stage.Stage
import org.slf4j.LoggerFactory

/** 自己操作の開始revisionと一致する画面だけが、結果の文書versionを受け取ります。 */
internal data class SavedUploadPublication(
    val operation: SavedUploadOperation,
    val snapshot: LocalDocumentSnapshot,
    val observed: Map<EditorDocumentKey, Long>
)

/** EditorとMod設定で共用する、固定saved確認から同要求読戻しまでの画面操作です。 */
internal class SavedUploadAction(
    private val origin: EditorDataService,
    private val target: EditorDataService,
    private val owner: Stage?,
    private val isActive: () -> Boolean,
    private val prepare: ((Boolean) -> Unit) -> Unit,
    private val onIdle: () -> Unit
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val held = mutableSetOf<EditorDocumentKey>()
    private var observed: Map<EditorDocumentKey, Long> = emptyMap()
    private var requestId: String? = null
    @Volatile private var cancelled = false
    var busy = false
        private set

    fun cancel() { cancelled = true }

    fun startCategory(category: String, readbackOnly: Boolean = false) {
        if (busy) return
        busy = true
        origin.submitIo { origin.documents.read() }.whenComplete { loaded, error ->
            Platform.runLater {
                if (error != null) { failed(StoreError(StoreErrorCode.IO_ERROR, cause = error)); return@runLater }
                if (loaded is StoreResult.Failure) { failed(loaded.error); return@runLater }
                if (!isActive() || cancelled) { finish(); return@runLater }
                val records = (loaded as? StoreResult.Success)?.value?.documents
                    ?: run { failed(StoreError(StoreErrorCode.IO_ERROR)); return@runLater }
                val selections = records.filter { it.key.category == category &&
                    (readbackOnly || it.saved != null || it.savedDeleted) &&
                    (readbackOnly || !origin.isRemote || it.unreflected != false || it.savedPending != null)
                }.map { SavedUploadSelection(it.key) }
                busy = false
                if (selections.isEmpty()) { finish(); message("アップロード", "対象の保存済み差分はありません。"); return@runLater }
                if (readbackOnly) readback(selections) else start(selections)
            }
        }
    }

    fun start(selections: List<SavedUploadSelection>) {
        if (!begin(selections)) return
        prepare { ready ->
            if (!ready) failed(StoreError(StoreErrorCode.IO_ERROR, detail = "未保存内容を退避できません。現在の文書を保持しています。"))
            else if (!isActive() || cancelled) finish()
            else preview(selections)
        }
    }

    private fun preview(selections: List<SavedUploadSelection>) {
        val planner = SavedUploadPlanner(origin, target)
        target.submitIo { planner.preview(selections) }.whenComplete { result, error ->
            Platform.runLater {
                if (error != null) { failed(StoreError(StoreErrorCode.IO_ERROR, cause = error)); return@runLater }
                when (result) {
                    is StoreResult.Failure -> failed(result.error)
                    is StoreResult.Success -> {
                        val preview = result.value
                        if (!isActive() || cancelled) { finish(); return@runLater }
                        if (!freeze((preview.inputs.map { it.key } + preview.versions.map { it.key }).toSet())) {
                            failed(StoreError(StoreErrorCode.IO_ERROR, detail = "同じ対象を別の操作が使用しています。"))
                            return@runLater
                        }
                        if (preview.candidates.isEmpty()) {
                            finish()
                            message("アップロード", "この宛先に反映する保存済み差分はありません。")
                            return@runLater
                        }
                        val choice = SavedUploadDialog.show(owner, target.currentProfileName.orEmpty(), preview)
                        if (choice == null || !isActive() || cancelled) { finish(); return@runLater }
                        observed = preview.versions.associate { it.key to it.revision }
                        val progress = SavedUploadProgress(owner).also { it.show() }
                        target.submitIo {
                            when (val built = planner.build(preview, choice.localPaths, choice.confirmUnknownBaseline)) {
                                is StoreResult.Failure -> built
                                is StoreResult.Success -> {
                                    requestId = built.value.id
                                    SavedUploadExecutor(origin, target).execute(built.value,
                                        cancelled = { cancelled || progress.cancelled },
                                        onProgress = { current -> Platform.runLater { progress.update(current) } })
                                }
                            }
                        }.whenComplete { completed, failure -> complete(completed, failure, progress) }
                    }
                    null -> failed(StoreError(StoreErrorCode.IO_ERROR))
                }
            }
        }
    }

    /** 既存の結果不明台帳だけをユーザー確認後に読取照合し、通信へ再送しません。 */
    fun readback(selections: List<SavedUploadSelection>, operationId: String? = null) {
        if (!begin(selections, freezeSelected = false)) return
        val keys = selections.mapTo(mutableSetOf()) { it.key }
        target.submitIo { target.documents.read() }.whenComplete { loaded, error ->
            Platform.runLater {
                if (error != null) { failed(StoreError(StoreErrorCode.IO_ERROR, cause = error)); return@runLater }
                if (loaded is StoreResult.Failure) { failed(loaded.error); return@runLater }
                val snapshot = (loaded as? StoreResult.Success)?.value
                    ?: run { failed(StoreError(StoreErrorCode.IO_ERROR)); return@runLater }
                target.updateUploadState(snapshot)
                val operations = snapshot.uploads.filter { operation ->
                    !operation.complete && (operationId == null || operation.id == operationId) && operation.inputs.any { it.key in keys }
                }
                if (operations.isEmpty()) { finish(); message("読戻し", "選択文書に結果不明の操作はありません。"); return@runLater }
                val operation = chooseOperation(operations)
                if (operation == null || !isActive() || cancelled) { finish(); return@runLater }
                if (!operation.needsReadback) {
                    finish()
                    message("旧要求の結果", operation.steps.joinToString("\n") {
                        it.key.id + ": " + it.state.displayName + (it.detail?.let { detail -> "\n" + detail } ?: "")
                    } +
                        "\n編集中の操作が旧要求と一致しない場合、推測した別要求では再送しません。ローカル文書と台帳を保持してください。")
                    return@runLater
                }
                requestId = operation.id
                observed = operation.targetRevisions.associate { it.key to it.revision }
                if (!freeze((operation.inputs.map { it.key } + operation.targetRevisions.map { it.key }).toSet())) {
                    failed(StoreError(StoreErrorCode.IO_ERROR, detail = "同じ対象を別の操作が使用しています。"))
                    return@runLater
                }
                val accepted = Dialog<ButtonType>().apply {
                    title = "結果不明の読戻し"
                    initModality(Modality.NONE)
                    this@SavedUploadAction.owner?.let(::initOwner)
                    dialogPane.stylesheets += SavedUploadAction::class.java.getResource(AppScreen.WIDGETS_ONLY.css)!!.toExternalForm()
                    dialogPane.content = Label("保存済みの同じ要求を読取だけで照合します。通信への書込・自動再送は行いません。").apply { isWrapText = true }
                    dialogPane.buttonTypes.addAll(ButtonType("読戻しで確認", ButtonBar.ButtonData.OK_DONE), ButtonType.CANCEL)
                }.showAndWait().orElse(ButtonType.CANCEL).buttonData == ButtonBar.ButtonData.OK_DONE
                if (!accepted || !isActive() || cancelled) { finish(); return@runLater }
                val progress = SavedUploadProgress(owner).also { it.show() }
                target.submitIo { SavedUploadExecutor(origin, target).readback(operation) }
                    .whenComplete { completed, failure -> complete(completed, failure, progress) }
            }
        }
    }

    private fun chooseOperation(operations: List<SavedUploadOperation>): SavedUploadOperation? {
        val select = ButtonType("この要求を確認", ButtonBar.ButtonData.OK_DONE)
        val choices = ComboBox<String>().apply {
            promptText = "照合する保存済み要求を選択してください"
            maxWidth = Double.MAX_VALUE
            items.addAll(operations.map { operation ->
                operation.id + " / " + operation.steps.joinToString("、") {
                    it.key.id + ": " + it.state.displayName
                }
            })
        }
        return Dialog<SavedUploadOperation>().apply {
            title = "アップロード要求の選択"
            initModality(Modality.NONE)
            this@SavedUploadAction.owner?.let(::initOwner)
            dialogPane.stylesheets += SavedUploadAction::class.java.getResource(AppScreen.WIDGETS_ONLY.css)!!.toExternalForm()
            dialogPane.content = VBox(12.0,
                Label("永続台帳の要求を選択します。別の要求を作成したり、自動再送したりしません。").apply { isWrapText = true },
                choices)
            dialogPane.prefWidth = 720.0
            dialogPane.buttonTypes.addAll(select, ButtonType.CANCEL)
            dialogPane.lookupButton(select).disableProperty().bind(choices.selectionModel.selectedIndexProperty().lessThan(0))
            setResultConverter { button ->
                if (button == select) operations[choices.selectionModel.selectedIndex] else null
            }
        }.showAndWait().orElse(null)
    }

    private fun begin(selections: List<SavedUploadSelection>, freezeSelected: Boolean = true): Boolean {
        if (busy || selections.isEmpty()) return false
        if (!target.isRemote) { message("アップロード", "接続中のサーバーがありません。ローカル保存は引き続き利用できます。"); return false }
        val keys = selections.flatMap { listOf(it.key, it.key.copy(source = target.documentSource, id = it.targetId)) }.toSet()
        if (freezeSelected && !freeze(keys)) return false
        busy = true
        return true
    }

    private fun freeze(keys: Set<EditorDocumentKey>): Boolean {
        val added = keys - held
        if (added.any { it in target.uploadingDocuments }) return false
        target.uploadingDocuments.addAll(added)
        held.addAll(added)
        return true
    }

    private fun complete(
        result: StoreResult<SavedUploadOperation>?, error: Throwable?, progress: SavedUploadProgress
    ) {
        target.submitIo { target.documents.read() }.whenComplete { loaded, readError ->
            Platform.runLater {
                progress.finish()
                if (loaded is StoreResult.Success) {
                    target.updateUploadState(loaded.value)
                    loaded.value.uploads.singleOrNull { it.id == requestId }?.let { recorded ->
                        target.publishUploadResult(SavedUploadPublication(recorded, loaded.value, observed))
                    }
                } else target.unresolvedUploadDocuments.addAll(held)
                if (error != null || readError != null) {
                    failed(StoreError(StoreErrorCode.IO_ERROR, cause = error ?: readError))
                } else if (loaded is StoreResult.Failure) {
                    failed(loaded.error)
                } else when (result) {
                    is StoreResult.Failure -> failed(result.error)
                    is StoreResult.Success -> {
                        val operation = result.value
                        finish()
                        if (isActive()) message("アップロード結果", operation.steps.joinToString("\n") { step ->
                            step.key.id + ": " + step.state.displayName + (step.detail?.let { "\n" + it } ?: "")
                        })
                    }
                    null -> failed(StoreError(StoreErrorCode.IO_ERROR))
                }
            }
        }
    }

    private fun failed(error: StoreError) {
        logger.error("アップロードを完了できません: {}", error.detail ?: error.code, error.cause)
        finish()
        if (isActive()) message("アップロードできません", error.detail ?: error.code.toString())
    }

    private fun finish() {
        target.uploadingDocuments.removeAll(held)
        held.clear()
        busy = false
        onIdle()
    }

    private fun message(titleText: String, text: String) {
        Dialog<ButtonType>().apply {
            title = titleText
            initModality(Modality.NONE)
            this@SavedUploadAction.owner?.let(::initOwner)
            dialogPane.stylesheets += SavedUploadAction::class.java.getResource(AppScreen.WIDGETS_ONLY.css)!!.toExternalForm()
            dialogPane.content = Label(text).apply { isWrapText = true }
            dialogPane.prefWidth = 560.0
            dialogPane.buttonTypes += ButtonType.OK
        }.show()
    }
}
