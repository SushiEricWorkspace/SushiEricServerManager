package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.common.data.core.identity.PublicId
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Stage

/**
 * ダイアログで確定した、アップロードの内容です。
 *
 * @property candidates 選択したデータ。宛先を反映した状態です。
 * @property destination 宛先ディレクトリの完全ID。空の場合はルート。
 */
data class OfflineUploadSelection(
    val candidates: List<OfflineUploadCandidate>,
    val destination: String
) {
    val keys: Set<UploadKey>
        get() = candidates.mapTo(linkedSetOf()) { it.key }
}

/** アップロードするデータと宛先ディレクトリを選ぶダイアログです。 */
object OfflineUploadDialog {
    /**
     * 候補を表示して、アップロードするデータと宛先を選んでもらいます。
     *
     * 宛先を入力するたびに、サーバー上のIDと新規・上書きの状態を[planUpload]で更新します。
     * 宛先の指定が不正な間と、選択が0件の間は、アップロードできません。
     *
     * @param forEditing 保存せずエディターへ読み込む操作として表示する場合はtrue。
     * @return 確定した内容。取り消した場合はnull。
     */
    fun select(
        owner: Stage?,
        profileName: String,
        category: UploadDataCategory,
        scan: UploadScanResult.Success,
        forEditing: Boolean = false
    ): OfflineUploadSelection? {
        val uploadType = ButtonType(if (forEditing) "エディターへ読み込む" else "アップロード", ButtonBar.ButtonData.OK_DONE)
        val countLabel = Label()
        val destinationField = TextField().apply {
            promptText = "宛先ディレクトリ（空の場合はルート。例: event.weapons）"
            HBox.setHgrow(this, javafx.scene.layout.Priority.ALWAYS)
        }
        val destinationError = Label().apply { styleClass.add("error-label") }
        val list = VBox(8.0)

        var candidates = planUpload(scan, "")
        val selected = candidates.filter { it.selectable }.mapTo(linkedSetOf()) { it.key }
        val checks = linkedMapOf<OfflineUploadCandidate, CheckBox>()
        lateinit var dialogUploadButton: Node

        fun destination(): String = destinationField.text.trim()

        fun updateState() {
            val valid = isValidUploadDestination(destination())
            destinationError.text = if (valid) "" else PublicId.DESCRIPTION
            val count = checks.keys.count { it.key in selected && it.selectable }
            countLabel.text = "$count 件を選択中"
            dialogUploadButton.isDisable = !valid || count == 0
        }

        fun rebuild() {
            candidates = planUpload(scan, destination())
            checks.clear()
            candidates.forEach { candidate ->
                checks[candidate] = CheckBox(candidateLabel(candidate)).apply {
                    isDisable = !candidate.selectable
                    isSelected = candidate.selectable && candidate.key in selected
                    selectedProperty().addListener { _, _, now ->
                        if (now) selected += candidate.key else selected -= candidate.key
                        updateState()
                    }
                }
            }
            list.children.setAll(checks.values)
            updateState()
        }

        val dialog = Dialog<OfflineUploadSelection>().apply {
            title = if (forEditing) "ローカルデータを読み込む" else "ローカルデータをアップロード"
            headerText = if (forEditing) {
                "読み込み先: $profileName（${category.displayName}）。サーバーへの反映は保存時に行います"
            } else {
                "アップロード先: $profileName（${category.displayName}）"
            }
            owner?.let(::initOwner)
            dialogPane.buttonTypes.addAll(uploadType, ButtonType.CANCEL)
            dialogPane.stylesheets.add(
                OfflineUploadDialog::class.java.getResource(AppScreen.WIDGETS_ONLY.css)!!.toExternalForm()
            )
            dialogPane.content = VBox(10.0).apply {
                padding = Insets(8.0)
                prefWidth = 620.0
                children.addAll(
                    HBox(8.0, Label("宛先"), destinationField).apply { alignment = Pos.CENTER_LEFT },
                    destinationError,
                    HBox(8.0,
                        Button("全選択").apply {
                            styleClass.add("btn-secondary")
                            setOnAction {
                                selected += checks.keys.filter { it.selectable }.map { it.key }
                                checks.values.filterNot { it.isDisable }.forEach { it.isSelected = true }
                                updateState()
                            }
                        },
                        Button("全解除").apply {
                            styleClass.add("btn-secondary")
                            setOnAction {
                                selected.clear()
                                checks.values.forEach { it.isSelected = false }
                                updateState()
                            }
                        },
                        countLabel
                    ).apply { alignment = Pos.CENTER_LEFT },
                    ScrollPane(list).apply {
                        isFitToWidth = true
                        prefHeight = 360.0
                    }
                )
            }
            setResultConverter { button ->
                if (button == uploadType) {
                    OfflineUploadSelection(
                        candidates = checks.keys.filter { it.key in selected && it.selectable },
                        destination = destination()
                    )
                } else {
                    null
                }
            }
        }
        dialogUploadButton = dialog.dialogPane.lookupButton(uploadType)
        destinationField.textProperty().addListener { _, _, _ -> rebuild() }
        rebuild()
        return dialog.showAndWait().orElse(null)
    }

    private fun candidateLabel(candidate: OfflineUploadCandidate): String {
        val state = when (candidate.state) {
            UploadCandidateState.NEW -> "新規"
            UploadCandidateState.OVERWRITE -> "上書き"
            UploadCandidateState.UNAVAILABLE ->
                "選択不可: ${candidate.error?.detail ?: candidate.error?.code}"
        }
        val migration = if (candidate.requiresFormatUpdate) "、形式更新対象" else ""
        val target = if (candidate.targetId == candidate.key.id) "" else " → ${candidate.targetId}"
        return "${candidate.key.id}$target（$state$migration）"
    }
}
