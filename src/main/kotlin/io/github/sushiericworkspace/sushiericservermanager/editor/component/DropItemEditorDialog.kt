package io.github.sushiericworkspace.sushiericservermanager.editor.component

import io.github.sushiericworkspace.common.data.core.validation.SushiEricValidationError
import io.github.sushiericworkspace.common.data.drop.model.DropItemDataView
import io.github.sushiericworkspace.common.data.drop.model.mutable.MutableDropItemData
import io.github.sushiericworkspace.common.data.drop.validation.DropItemValidator
import io.github.sushiericworkspace.common.data.item.model.ItemBaseDataView
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
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
import javafx.util.StringConverter
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

internal fun filterDropItemChoices(
    choices: List<DropItemChoice>,
    query: String
): List<DropItemChoice> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return choices
    return choices.filter {
        it.displayText.contains(normalizedQuery, ignoreCase = true) ||
            it.internalId.value.contains(normalizedQuery, ignoreCase = true)
    }
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
        onConfirmed: () -> Unit
    ) {
        val stage = Stage().apply {
            title = "ドロップアイテム編集"
            initOwner(owner)
            initModality(Modality.WINDOW_MODAL)
        }
        val catalog = DropItemCatalog(items)
        val editingDropItems = dropItems.mapTo(mutableListOf()) { it.deepCopy() }
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
                        canMoveUp = index > 0,
                        canMoveDown = index < editingDropItems.lastIndex,
                        onMoveUp = {
                            moveDropItem(editingDropItems, index, index - 1)
                            rebuildList()
                        },
                        onMoveDown = {
                            moveDropItem(editingDropItems, index, index + 1)
                            rebuildList()
                        },
                        onDelete = {
                            editingDropItems.removeAt(index)
                            rebuildList()
                        }
                    )
                )
            }
        }

        val addButton = Button("追加").apply {
            isFocusTraversable = false
            styleClass.add("btn-success")
            setOnAction {
                editingDropItems.add(
                    MutableDropItemData(itemId = catalog.choices.firstOrNull()?.internalId)
                )
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
                ScrollPane(listBox).apply {
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
        stage.showAndWait()
    }

    private fun createDropItemRow(
        dropItem: MutableDropItemData,
        catalog: DropItemCatalog,
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

        val itemSelector = createItemSelector(dropItem, catalog, ::refreshStatus)
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
        onChanged: () -> Unit
    ): VBox {
        val unresolvedChoice = dropItem.itemId
            ?.takeIf { catalog.resolve(it) == null }
            ?.let { DropItemChoice(it, null) }
        val allChoices = listOfNotNull(unresolvedChoice) + catalog.choices
        var updating = false

        val comboBox = ComboBox<DropItemChoice>().apply {
            isEditable = false
            minWidth = 180.0
            prefWidth = 360.0
            maxWidth = Double.MAX_VALUE
            converter = object : StringConverter<DropItemChoice>() {
                override fun toString(choice: DropItemChoice?): String = choice?.displayText.orEmpty()

                override fun fromString(text: String?): DropItemChoice? =
                    allChoices.firstOrNull { it.publicId == text || it.displayText == text }
            }
            cellFactory = Callback {
                object : ListCell<DropItemChoice>() {
                    override fun updateItem(item: DropItemChoice?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = if (empty || item == null) null else item.displayText
                        styleClass.remove("warning-label")
                        if (!empty && item?.publicId == null) styleClass.add("warning-label")
                    }
                }
            }
            items.setAll(allChoices)
            value = allChoices.firstOrNull { it.internalId == dropItem.itemId }

            valueProperty().addListener { _, _, selected ->
                if (updating || selected == null) return@addListener
                dropItem.itemId = selected.internalId
                onChanged()
            }
        }
        val searchField = TextField().apply {
            promptText = "独自IDを検索"
            maxWidth = Double.MAX_VALUE
            textProperty().addListener { _, _, query ->
                val filtered = filterDropItemChoices(allChoices, query)
                val displayChoices = filtered.ifEmpty { allChoices }
                val selected = displayChoices.firstOrNull { it.internalId == dropItem.itemId }
                    ?: displayChoices.firstOrNull()
                updating = true
                try {
                    comboBox.items.setAll(displayChoices)
                    comboBox.value = selected
                } finally {
                    updating = false
                }
                if (selected != null) {
                    dropItem.itemId = selected.internalId
                    onChanged()
                }
            }
        }
        return VBox(6.0, searchField, comboBox).apply {
            minWidth = 180.0
            maxWidth = Double.MAX_VALUE
        }
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
