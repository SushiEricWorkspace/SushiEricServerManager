package io.github.sushiericworkspace.sushiericservermanager.ui.dialog

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.ConflictValueFormatter
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.DataConflict
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.DataFieldPath
import io.github.sushiericworkspace.sushiericservermanager.editor.view.createPublicIdDisplay
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Button
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.shape.Line
import javafx.stage.Stage

object MergeConflictDialog {
    fun show(
        owner: Stage?,
        dataId: String,
        conflicts: List<DataConflict>
    ): Set<DataFieldPath>? {
        if (conflicts.isEmpty()) return emptySet()
        val applyType = ButtonType("選択内容を適用", ButtonBar.ButtonData.OK_DONE)
        val selection = MergeConflictSelectionModel(conflicts)
        val choiceControls = linkedMapOf<DataFieldPath, ConflictChoiceControls>()
        val conflictList = VBox(10.0).apply {
            children.addAll(conflicts.map { conflict ->
                createConflictRow(conflict, selection).also { row ->
                    choiceControls[conflict.path] = row.choiceControls
                }.container
            })
        }
        return Dialog<Set<DataFieldPath>>().apply {
            title = "保存競合の解決"
            dialogPane.header = HBox(4.0, createPublicIdDisplay(dataId), Label("で競合したフィールドだけを表示しています")).apply {
                alignment = Pos.CENTER_LEFT
            }
            owner?.let(::initOwner)
            dialogPane.buttonTypes.addAll(applyType, ButtonType.CANCEL)
            dialogPane.stylesheets.add(
                MergeConflictDialog::class.java.getResource(AppScreen.WIDGETS_ONLY.css)!!.toExternalForm()
            )
            dialogPane.content = VBox(10.0).apply {
                padding = Insets(8.0)
                prefWidth = 860.0
                children.addAll(
                    Label("各フィールドで保存する値を選択してください。"),
                    HBox(8.0).apply {
                        alignment = Pos.CENTER_LEFT
                        children.addAll(
                            Button("すべてローカルを採用").apply {
                                setOnAction {
                                    selection.selectAll(MergeConflictChoice.LOCAL)
                                    choiceControls.values.forEach { it.select(MergeConflictChoice.LOCAL) }
                                }
                            },
                            Button("すべてサーバーを採用").apply {
                                setOnAction {
                                    selection.selectAll(MergeConflictChoice.REMOTE)
                                    choiceControls.values.forEach { it.select(MergeConflictChoice.REMOTE) }
                                }
                            }
                        )
                    },
                    ScrollPane(conflictList).apply {
                        isFitToWidth = true
                        prefHeight = 480.0
                        VBox.setVgrow(this, Priority.ALWAYS)
                    }
                )
            }
            setResultConverter { button ->
                if (button == applyType) {
                    selection.selectedLocalPaths()
                } else {
                    null
                }
            }
        }.showAndWait().orElse(null)
    }

    private fun createConflictRow(
        conflict: DataConflict,
        selection: MergeConflictSelectionModel
    ): ConflictRow {
        val localChoice = valueChoiceButton("ローカル", conflict.localValue)
        val remoteChoice = valueChoiceButton("サーバー", conflict.remoteValue)
        val localLine = Line().apply {
            styleClass.add("merge-conflict-branch-line")
            isMouseTransparent = true
        }
        val remoteLine = Line().apply {
            styleClass.add("merge-conflict-branch-line")
            isMouseTransparent = true
        }
        val choices = VBox(10.0, localChoice, remoteChoice).apply {
            HBox.setHgrow(this, Priority.ALWAYS)
        }
        val branch = Pane().apply {
            styleClass.add("merge-conflict-branch")
            minWidth = 64.0
            prefWidth = 64.0
            maxWidth = 64.0
            minHeight = 120.0
            children.addAll(
                Line().apply {
                    styleClass.add("merge-conflict-branch-stem")
                    startX = 0.0
                    endX = 16.0
                    startYProperty().bind(heightProperty().divide(2.0))
                    endYProperty().bind(heightProperty().divide(2.0))
                },
                localLine.apply {
                    startX = 16.0
                    endXProperty().bind(widthProperty())
                    startYProperty().bind(heightProperty().divide(2.0))
                    endYProperty().bind(
                        choices.layoutYProperty()
                            .add(localChoice.layoutYProperty())
                            .add(localChoice.heightProperty().divide(2.0))
                    )
                },
                remoteLine.apply {
                    startX = 16.0
                    endXProperty().bind(widthProperty())
                    startYProperty().bind(heightProperty().divide(2.0))
                    endYProperty().bind(
                        choices.layoutYProperty()
                            .add(remoteChoice.layoutYProperty())
                            .add(remoteChoice.heightProperty().divide(2.0))
                    )
                }
            )
        }
        val controls = ConflictChoiceControls(localChoice, remoteChoice, localLine, remoteLine)
        localChoice.setOnAction {
            selection.select(conflict.path, MergeConflictChoice.LOCAL)
            controls.select(MergeConflictChoice.LOCAL)
        }
        remoteChoice.setOnAction {
            selection.select(conflict.path, MergeConflictChoice.REMOTE)
            controls.select(MergeConflictChoice.REMOTE)
        }
        controls.select(MergeConflictChoice.LOCAL)

        return ConflictRow(
            container = VBox(8.0).apply {
                styleClass.add("merge-conflict-row")
                children.addAll(
                    Label(conflict.displayName).apply {
                        styleClass.add("merge-conflict-field-name")
                    },
                    HBox(
                        valueLabel("編集開始時", conflict.baseValue).apply {
                            prefWidth = 250.0
                            maxWidth = 250.0
                        },
                        branch,
                        choices
                    ).apply {
                        alignment = Pos.CENTER_LEFT
                        styleClass.add("merge-conflict-branches")
                    }
                )
            },
            choiceControls = controls
        )
    }

    private fun valueLabel(heading: String, value: Any?) = Label(
        "$heading\n${ConflictValueFormatter.format(value)}"
    ).apply {
        styleClass.add("merge-conflict-value")
        isWrapText = true
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        minHeight = Region.USE_PREF_SIZE
    }

    private fun valueChoiceButton(heading: String, value: Any?) = Button(
        "$heading\n${ConflictValueFormatter.format(value)}"
    ).apply {
        styleClass.add("merge-conflict-choice")
        isMnemonicParsing = false
        alignment = Pos.CENTER_LEFT
        isWrapText = true
        minWidth = 0.0
        maxWidth = Double.MAX_VALUE
        minHeight = Region.USE_PREF_SIZE
    }

    private data class ConflictRow(
        val container: VBox,
        val choiceControls: ConflictChoiceControls
    )

    private class ConflictChoiceControls(
        private val local: Button,
        private val remote: Button,
        private val localLine: Line,
        private val remoteLine: Line
    ) {
        fun select(choice: MergeConflictChoice) {
            local.styleClass.remove("selected")
            remote.styleClass.remove("selected")
            localLine.styleClass.remove("selected")
            remoteLine.styleClass.remove("selected")

            when (choice) {
                MergeConflictChoice.LOCAL -> {
                    local.styleClass.add("selected")
                    localLine.styleClass.add("selected")
                }

                MergeConflictChoice.REMOTE -> {
                    remote.styleClass.add("selected")
                    remoteLine.styleClass.add("selected")
                }
            }
        }
    }
}
