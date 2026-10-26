package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.app.NotificationState
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.editor.render.ItemPreviewService
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.ConflictValueFormatter
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.DataFieldPath
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentKey
import javafx.beans.binding.Bindings
import javafx.event.ActionEvent
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.WindowEvent

internal data class SavedUploadConfirmation(
    val localPaths: Map<EditorDocumentKey, Set<DataFieldPath>>,
    val confirmUnknownBaseline: Boolean
)

/** 確認中も対象外文書を操作できる、保存済み世代のアップロード確認です。 */
internal object SavedUploadDialog {
    fun show(owner: Stage?, targetName: String, preview: SavedUploadPreview, previews: ItemPreviewService): SavedUploadConfirmation? {
        val apply = ButtonType("確認した保存済み内容をアップロード", ButtonBar.ButtonData.OK_DONE)
        val unknown = preview.candidates.any { !it.baselineKnown }
        val unknownChoice = CheckBox("比較元がない文書の内容と宛先を確認しました").apply { isSelected = !unknown }
        val groups = linkedMapOf<Pair<EditorDocumentKey, DataFieldPath>, ToggleGroup>()
        val rows = VBox(12.0)
        rows.children += Label("宛先: " + targetName)
        rows.children += Label("編集中の未保存内容は送信しません。対象文書だけを固定し、他の文書は操作できます。").apply { isWrapText = true }
        preview.candidates.forEach { candidate ->
            val operation = when {
                candidate.input.deleted -> "削除"
                candidate.relocationKey != null -> "改名・移動"
                candidate.before == null -> "追加"
                else -> "更新"
            }
            val source = candidate.relocationKey?.id ?: candidate.input.key.id
            val destination = if (candidate.input.deleted && candidate.relocationKey != null)
                "（" + candidate.targetKey.id + " が未生成のまま不在であることも確認）"
                else if (source != candidate.targetKey.id) " → " + candidate.targetKey.id else ""
            rows.children += Label(operation + ": " + candidate.targetKey.category + " / " + source + destination)
            rows.children += previews.review(candidate.previews)
            if (!candidate.baselineKnown) rows.children += Label("サーバー基準未取得: 自動統合しません")
            for (conflict in candidate.merge.conflicts) {
                val group = ToggleGroup()
                groups[candidate.input.key to conflict.path] = group
                rows.children += VBox(6.0,
                    Label(conflict.displayName),
                    Label("比較元: " + ConflictValueFormatter.format(conflict.baseValue)).apply { isWrapText = true },
                    RadioButton("保存済み: " + ConflictValueFormatter.format(conflict.localValue)).apply {
                        toggleGroup = group
                        userData = true
                        isWrapText = true
                    },
                    RadioButton("サーバー: " + ConflictValueFormatter.format(conflict.remoteValue)).apply {
                        toggleGroup = group
                        userData = false
                        isWrapText = true
                    })
            }
        }
        if (unknown) rows.children += unknownChoice
        rows.children += Label("確認後のサーバー変更を完全に排除するものではありません。結果不明の工程は再送せず、読戻しで確認します。").apply { isWrapText = true }
        val notifications = EditorSession.notifications
        val conflictScope = notifications.scope(
            preview.candidates.map { it.input.key.source.directory }.distinct().sorted().joinToString(",") + " → " +
                preview.candidates.map { it.targetKey.source.directory }.distinct().sorted().joinToString(","),
            "アップロード競合", preview.candidates.map { it.targetKey.category + "/" + it.targetKey.id }.sorted().joinToString(","))
        val hasConflict = groups.isNotEmpty() || unknown
        if (hasConflict) notifications.condition(conflictScope, "conflict", "保存済み内容とサーバーの比較を確認してください。")
        else notifications.resolve(conflictScope, "conflict")
        val confirmation = Dialog<SavedUploadConfirmation>().apply {
            title = "アップロードの確認"
            initModality(Modality.NONE)
            owner?.let(::initOwner)
            dialogPane.stylesheets += SavedUploadDialog::class.java.getResource(AppScreen.WIDGETS_ONLY.css)!!.toExternalForm()
            dialogPane.buttonTypes.addAll(apply, ButtonType.CANCEL)
            dialogPane.content = ScrollPane(rows).apply {
                isFitToWidth = true
                prefViewportWidth = 780.0
                prefViewportHeight = 520.0
                rows.padding = Insets(12.0)
            }
            val dependencies = groups.values.map { it.selectedToggleProperty() } + unknownChoice.selectedProperty()
            dialogPane.lookupButton(apply).disableProperty().bind(Bindings.createBooleanBinding({
                !unknownChoice.isSelected || groups.values.any { it.selectedToggle == null }
            }, *dependencies.toTypedArray()))
            setResultConverter { button ->
                if (button != apply) null else SavedUploadConfirmation(
                    preview.candidates.associate { candidate ->
                        candidate.input.key to candidate.merge.conflicts.filter { conflict ->
                            groups[candidate.input.key to conflict.path]?.selectedToggle?.userData == true
                        }.mapTo(mutableSetOf()) { it.path }
                    }, unknownChoice.isSelected)
            }
        }.showAndWait().orElse(null)
        if (confirmation != null) notifications.resolve(conflictScope, "conflict")
        return confirmation
    }
}

/** 取消は次の未実行工程にだけ作用し、進行中の結果確認を待ちます。 */
internal class SavedUploadProgress(owner: Stage?, source: String = "") {
    private val notifications = EditorSession.notifications
    private val scope = notifications.scope(source, "アップロード")
    private var operationNotice: NotificationState.Operation? = null
    private val requests = mutableMapOf<String, NotificationState.Operation>()

    @Synchronized fun attach(operation: SavedUploadOperation) {
        if (!running || operation.id == operationNotice?.scope?.target) return
        operationNotice?.let(notifications::finish)
        operationNotice = notifications.start(scope.copy(target = operation.id), "upload:${operation.id}", "反映確認中")
        requests[operation.id] = operationNotice!!
    }

    @Volatile var cancelled: Boolean = false
        private set
    private var running = true
    private val label = Label("確認した保存済み内容を反映しています…").apply { isWrapText = true }
    private val closeRequest = EventHandler<WindowEvent> { event ->
        if (running) { requestCancel(); event.consume() }
    }
    private val dialog = Dialog<ButtonType>().apply {
        title = "アップロード"
        initModality(Modality.NONE)
        owner?.let(::initOwner)
        dialogPane.stylesheets += SavedUploadProgress::class.java.getResource(AppScreen.WIDGETS_ONLY.css)!!.toExternalForm()
        dialogPane.content = label
        dialogPane.prefWidth = 520.0
        dialogPane.buttonTypes += ButtonType.CANCEL
        dialogPane.lookupButton(ButtonType.CANCEL).addEventFilter(ActionEvent.ACTION) { event ->
            requestCancel()
            event.consume()
        }
        setOnCloseRequest { event -> if (running) { requestCancel(); event.consume() } }
    }

    @Synchronized private fun requestCancel() {
        cancelled = true
        label.text = "取消を要求しました。進行中の反映確認が終わるまでお待ちください。"
        operationNotice?.let { notifications.update(it, "取消要求済み・進行中の反映確認を待っています") }
    }

    fun show() {
        dialog.show()
        dialog.dialogPane.scene.window.apply {
            removeEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, closeRequest)
            addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, closeRequest)
        }
    }

    @Synchronized fun update(operation: SavedUploadOperation) {
        if (!running) return
        val notice = requests[operation.id] ?: return
        val confirmed = operation.steps.count { it.state == SavedUploadState.CONFIRMED }
        notice.let { notifications.update(it, if (cancelled) "取消要求済み・反映確認" else "反映確認", confirmed, operation.steps.size) }
        label.text = "反映確認 " + confirmed + " / " + operation.steps.size +
            if (cancelled) "（取消要求済み）" else ""
    }

    @Synchronized fun finish(recorded: List<SavedUploadOperation> = emptyList()) {
        running = false
        operationNotice?.let(notifications::finish)
        requests.forEach { (id, notice) ->
            val result = recorded.singleOrNull { it.id == id }
            val target = scope.copy(target = id)
            if (result?.complete == true) {
                if (notifications.result(notice, "upload:$id", null))
                    notifications.toast(target, "全件の反映を確認しました", NotificationState.Severity.SUCCESS)
            } else {
                val text = result?.steps?.joinToString("、") { "${it.key.id}: ${it.state.displayName}" }
                    ?: "結果を確認できません。固定要求を保持し、読戻しで確認してください。"
                notifications.result(notice, "upload:$id", text)
            }
        }
        dialog.dialogPane.scene?.window?.removeEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, closeRequest)
        dialog.close()
    }
}
