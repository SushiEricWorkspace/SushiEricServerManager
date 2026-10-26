package io.github.sushiericworkspace.sushiericservermanager.editor.component

import io.github.sushiericworkspace.sushiericdatamodel.data.core.validation.SushiEricValidationError
import io.github.sushiericworkspace.sushiericdatamodel.data.drop.model.DropItemDataView
import io.github.sushiericworkspace.sushiericdatamodel.data.drop.model.mutable.MutableDropItemData
import io.github.sushiericworkspace.sushiericdatamodel.data.drop.validation.DropItemValidator
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseDataView
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.render.ItemPreviewService
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.util.Duration
import javafx.scene.input.ClipboardContent
import javafx.scene.input.TransferMode
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.control.TextInputControl
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Callback
import java.math.BigDecimal
import java.math.RoundingMode

internal data class DropItemChoice(
    val internalId: ItemInternalId,
    val publicId: String?
) {
    val displayText: String
        get() = publicId ?: "未解決: ${internalId.value}"
}

internal class DropItemCatalog(items: Collection<ItemBaseDataView>) {
    val byInternalId = items.associateBy { it.internalId }
    val choices: List<DropItemChoice> = items
        .map { DropItemChoice(it.internalId, it.id) }
        .sortedBy { it.publicId }

    val availableInternalIds: Set<ItemInternalId> = choices
        .mapTo(mutableSetOf(), DropItemChoice::internalId)

    fun resolve(internalId: ItemInternalId?): DropItemChoice? =
        choices.firstOrNull { it.internalId == internalId }

    fun resolve(publicId: String): DropItemChoice? =
        choices.firstOrNull { it.publicId == publicId }
}

internal fun selectDropItemByPublicId(
    dropItem: MutableDropItemData,
    publicId: String,
    catalog: DropItemCatalog
): Boolean {
    val selected = catalog.resolve(publicId) ?: return false
    dropItem.itemId = selected.internalId
    return true
}

internal fun dropItemValidationErrors(
    dropItem: DropItemDataView,
    catalog: DropItemCatalog
): List<SushiEricValidationError> =
    DropItemValidator(dropItem, catalog.availableInternalIds).validate()

internal fun formatDropItemExpectedValue(dropItem: DropItemDataView): String {
    val percentage = BigDecimal.valueOf(dropItem.expectedValue() * 100.0)
        .setScale(2, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
    return "期待値: $percentage%"
}

internal fun moveDropItem(
    dropItems: MutableList<MutableDropItemData>,
    fromIndex: Int,
    toIndex: Int
): Boolean {
    if (fromIndex !in dropItems.indices || toIndex !in dropItems.indices || fromIndex == toIndex) {
        return false
    }
    val moved = dropItems.removeAt(fromIndex)
    dropItems.add(toIndex, moved)
    return true
}

/** ドロップアイテム一覧を編集し、確定時だけ元のリストへ反映するモーダルです。 */
internal object DropItemEditorDialog {

    fun show(
        owner: Window,
        dropItems: MutableList<MutableDropItemData>,
        items: Collection<ItemBaseDataView>,
        previews: ItemPreviewService,
        canApply: () -> Boolean = { true },
        onConfirmed: () -> Unit
    ) {
        val stage = Stage().apply {
            title = "ドロップアイテム編集"
            initOwner(owner)
            initModality(Modality.WINDOW_MODAL)
        }
        val catalog = DropItemCatalog(items)
        val original = dropItems.map { it.deepCopy() }
        val draft = DropItemDraft(dropItems)
        val editingDropItems = draft.items
        var draggedIndex: Int? = null
        val dragToken = java.util.UUID.randomUUID().toString()
        var scrollDirection = 0.0
        val scrollPane = ScrollPane()
        val autoScroll = Timeline(KeyFrame(Duration.millis(80.0), {
            scrollPane.vvalue = (scrollPane.vvalue + scrollDirection * .04).coerceIn(0.0, 1.0)
        })).apply { cycleCount = Timeline.INDEFINITE }
        fun commitInput() { stage.scene?.root?.requestFocus(); draft.record() }
        val listBox = VBox(8.0).apply {
            styleClass.addAll("editor-row-vbox", "drop-item-list-box")
            maxWidth = Double.MAX_VALUE
        }

        fun rebuildList() {
            listBox.children.clear()
            if (editingDropItems.isEmpty()) {
                listBox.children.add(
                    Label("ドロップアイテムがありません").apply {
                        styleClass.add("editor-label")
                    }
                )
                return
            }

            editingDropItems.forEachIndexed { index, dropItem ->
                listBox.children.add(
                    createDropItemRow(
                        dropItem = dropItem,
                        catalog = catalog,
                        previews = previews,
                        canMoveUp = index > 0,
                        canMoveDown = index < editingDropItems.lastIndex,
                        onMoveUp = {
                            commitInput()
                            draft.move(index, index - 1)
                            rebuildList()
                        },
                        onMoveDown = {
                            commitInput()
                            draft.move(index, index + 1)
                            rebuildList()
                        },
                        onDelete = {
                            commitInput()
                            editingDropItems.removeAt(index)
                            draft.record()
                            rebuildList()
                        }
                    ).apply {
                        setOnDragDetected { event ->
                            commitInput()
                            draggedIndex = index
                            startDragAndDrop(TransferMode.MOVE).setContent(ClipboardContent().apply { putString(dragToken) })
                            event.consume()
                        }
                        setOnDragOver { event ->
                            if (event.dragboard.string == dragToken && draggedIndex != null) event.acceptTransferModes(TransferMode.MOVE)
                            event.consume()
                        }
                        setOnDragDropped { event ->
                            val from = draggedIndex
                            event.isDropCompleted = event.dragboard.string == dragToken && from != null && draft.move(from, index)
                            if (event.isDropCompleted) rebuildList()
                            draggedIndex = null
                            autoScroll.stop()
                            event.consume()
                        }
                        setOnDragDone { draggedIndex = null; autoScroll.stop() }
                    }
                )
            }
        }

        val addButton = Button("追加").apply {
            isFocusTraversable = false
            styleClass.add("btn-success")
            setOnAction {
                commitInput()
                editingDropItems.add(
                    MutableDropItemData(itemId = catalog.choices.firstOrNull()?.internalId)
                )
                draft.record()
                rebuildList()
            }
        }

        val contentRoot = VBox(12.0).apply {
            padding = Insets(15.0)
            prefWidth = 860.0
            prefHeight = 500.0
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
            styleClass.addAll("editor-row-vbox", "drop-item-dialog-root")
            children.addAll(
                Label("現在のドロップアイテム:").apply {
                    styleClass.add("editor-label-highlight")
                },
                scrollPane.apply {
                    content = listBox
                    hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
                    vbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
                    addEventFilter(javafx.scene.input.DragEvent.DRAG_OVER) { event ->
                        if (draggedIndex != null && event.dragboard.string == dragToken) {
                            val y = sceneToLocal(event.sceneX, event.sceneY).y
                            scrollDirection = when { y < 32 -> -1.0; y > height - 32 -> 1.0; else -> 0.0 }
                            if (scrollDirection == 0.0) autoScroll.stop() else autoScroll.play()
                        }
                    }
                    styleClass.add("drop-item-list-scroll")
                    isFitToWidth = true
                    prefHeight = 350.0
                    maxWidth = Double.MAX_VALUE
                    VBox.setVgrow(this, Priority.ALWAYS)
                },
                addButton,
                HBox(
                    Button("キャンセル").apply {
                        isFocusTraversable = false
                        styleClass.add("btn-cancel")
                        setOnAction { stage.close() }
                    },
                    Button("OK").apply {
                        isFocusTraversable = false
                        styleClass.add("btn-primary")
                        setOnAction {
                            commitInput()
                            if (!canApply() || dropItems != original) { stage.close(); return@setOnAction }
                            if (dropItems == editingDropItems) { stage.close(); return@setOnAction }
                            dropItems.clear()
                            dropItems.addAll(editingDropItems.map { it.deepCopy() })
                            onConfirmed()
                            stage.close()
                        }
                    }
                ).apply {
                    alignment = Pos.CENTER_RIGHT
                    styleClass.addAll("editor-row-hbox", "dialog-actions")
                }
            )
        }

        rebuildList()
        stage.scene = Scene(
            StackPane(contentRoot).apply {
                styleClass.add("drop-item-scene-root")
                padding = Insets(0.0)
            },
            860.0,
            540.0
        ).apply {
            fill = Color.web("#18191A")
            stylesheets.add(
                DropItemEditorDialog::class.java
                    .getResource(AppScreen.WIDGETS_ONLY.css)!!
                    .toExternalForm()
            )
        }
        stage.scene.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (event.isShortcutDown && stage.scene.focusOwner !is TextInputControl) {
                val handled = when {
                    event.code == KeyCode.Z && !event.isShiftDown -> { commitInput(); draft.undo() }
                    event.code == KeyCode.Y || event.code == KeyCode.Z && event.isShiftDown -> draft.redo()
                    else -> false
                }
                if (handled) { rebuildList(); event.consume() }
            }
        }
        stage.setOnHidden { autoScroll.stop(); draggedIndex = null }
        stage.showAndWait()
    }

    private fun createDropItemRow(
        dropItem: MutableDropItemData,
        catalog: DropItemCatalog,
        previews: ItemPreviewService,
        canMoveUp: Boolean,
        canMoveDown: Boolean,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
        onDelete: () -> Unit
    ): VBox {
        val validationBox = VBox(2.0)
        val expectedValueLabel = Label().apply {
            styleClass.add("drop-item-expected-value")
        }

        fun refreshStatus() {
            expectedValueLabel.text = formatDropItemExpectedValue(dropItem)
            validationBox.children.setAll(
                dropItemValidationErrors(dropItem, catalog).map { error ->
                    Label(error.message).apply {
                        styleClass.add(if (error.isWarning) "warning-label" else "error-label")
                        isWrapText = true
                    }
                }
            )
        }

        val itemSelector = createItemSelector(dropItem, catalog, previews, ::refreshStatus)
        val countSpinner = EditorSpinnerFactory.intSpinner(
            initialValue = dropItem.n,
            min = 1,
            max = 999999,
            step = 1,
            prefWidth = 90.0
        ) { value ->
            dropItem.n = value
            refreshStatus()
        }
        val probabilitySpinner = EditorSpinnerFactory.doubleSpinner(
            initialValue = dropItem.p,
            min = 0.0,
            max = 1.0,
            step = 0.01,
            prefWidth = 100.0,
            decimalPlaces = 2
        ) { value ->
            dropItem.p = value
            refreshStatus()
        }

        val actionButtons = HBox(
            moveButton("▲", "上へ移動", canMoveUp, onMoveUp),
            moveButton("▼", "下へ移動", canMoveDown, onMoveDown),
            Button("削除").apply {
                isFocusTraversable = false
                styleClass.addAll("btn-danger", "drop-item-delete-button")
                setOnAction { onDelete() }
            }
        ).apply {
            spacing = 4.0
            alignment = Pos.CENTER_LEFT
            minWidth = Region.USE_PREF_SIZE
        }
        val itemRow = HBox(8.0).apply {
            styleClass.add("drop-item-row-top")
            alignment = Pos.CENTER_LEFT
            maxWidth = Double.MAX_VALUE
            children.addAll(
                Label("アイテム:").apply { minWidth = Region.USE_PREF_SIZE },
                itemSelector,
                actionButtons
            )
            HBox.setHgrow(itemSelector, Priority.ALWAYS)
        }
        val valueRow = HBox(8.0).apply {
            styleClass.add("drop-item-row-values")
            alignment = Pos.CENTER_LEFT
            children.addAll(
                Label("試行回数 n:").apply { minWidth = Region.USE_PREF_SIZE },
                countSpinner,
                Label("成功確率 p:").apply { minWidth = Region.USE_PREF_SIZE },
                probabilitySpinner,
                expectedValueLabel
            )
        }

        refreshStatus()
        return VBox(8.0, itemRow, valueRow, validationBox).apply {
            styleClass.add("drop-item-row")
            maxWidth = Double.MAX_VALUE
        }
    }

    private fun createItemSelector(
        dropItem: MutableDropItemData,
        catalog: DropItemCatalog,
        previews: ItemPreviewService,
        onChanged: () -> Unit
    ): VBox {
        val unresolvedChoice = dropItem.itemId
            ?.takeIf { catalog.resolve(it) == null }
            ?.let { DropItemChoice(it, null) }
        val allChoices = listOfNotNull(unresolvedChoice) + catalog.choices
        val selector = SearchableComboBox(
            allChoices = allChoices,
            initialValue = allChoices.firstOrNull { it.internalId == dropItem.itemId },
            promptText = "独自IDを検索",
            displayText = DropItemChoice::displayText,
            searchTexts = { choice -> listOf(choice.displayText, choice.internalId.value) },
            onSelected = { selected ->
                dropItem.itemId = selected.internalId
                onChanged()
            }
        ).apply {
            minWidth = 180.0
            maxWidth = Double.MAX_VALUE
            comboBox.prefWidth = 360.0
            fun itemCell() =
                object : ListCell<DropItemChoice>() {
                    override fun updateItem(item: DropItemChoice?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = if (empty || item == null) null else item.displayText
                        styleClass.remove("warning-label")
                        if (!empty && item?.publicId == null) styleClass.add("warning-label")
                        val id = item?.takeUnless { empty }?.internalId
                        previews.install(this, id?.let(catalog.byInternalId::get), id)
                    }
                }
            comboBox.cellFactory = Callback { itemCell() }
            comboBox.buttonCell = itemCell()
        }
        return selector
    }

    private fun moveButton(
        text: String,
        tooltipText: String,
        enabled: Boolean,
        action: () -> Unit
    ): Button = Button(text).apply {
        isFocusTraversable = false
        isMnemonicParsing = false
        isDisable = !enabled
        tooltip = Tooltip(tooltipText)
        styleClass.add("drop-item-move-button")
        setOnAction { action() }
    }
}
