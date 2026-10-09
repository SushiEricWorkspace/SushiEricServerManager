package io.github.sushiericworkspace.sushiericservermanager.ui.dialog

import io.github.sushiericworkspace.common.data.item.model.ItemBaseDataView
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.view.SidebarTreeNode
import io.github.sushiericworkspace.sushiericservermanager.editor.view.buildSidebarTree
import io.github.sushiericworkspace.sushiericservermanager.ui.AppTooltip
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.layout.VBox
import javafx.stage.Window
import javafx.scene.input.MouseButton

/** 選択解除とキャンセルを区別し、キャンセルでは現在の参照を変更しません。 */
internal sealed interface ItemTreeSelection {
    data class Selected(val id: ItemInternalId) : ItemTreeSelection
    data object Cleared : ItemTreeSelection
}

/** 公開IDのディレクトリ階層を表示し、保存用の内部IDを返す選択モーダルです。 */
internal object ItemTreeSelectionDialog {
    fun show(owner: Window?, items: Collection<ItemBaseDataView>, current: ItemInternalId?): ItemTreeSelection? {
        val byPublicId = items.associateBy { it.id }
        val currentPublicId = items.firstOrNull { it.internalId == current }?.id
        val select = ButtonType("選択", ButtonBar.ButtonData.OK_DONE)
        val clear = ButtonType("選択を解除", ButtonBar.ButtonData.OTHER)
        val dialog = Dialog<ItemTreeSelection>().apply {
            title = "アイテムを選択"
            owner?.let(::initOwner)
            dialogPane.buttonTypes.setAll(select, clear, ButtonType.CANCEL)
            dialogPane.stylesheets.add(ItemTreeSelectionDialog::class.java.getResource(AppScreen.WIDGETS_ONLY.css)!!.toExternalForm())
        }
        val tree = TreeView<SidebarTreeNode>().apply {
            styleClass.addAll("editor-tree-view", "item-selection-tree-view")
            id = "item-selection-tree"
            isShowRoot = false
            prefHeight = 420.0
            setCellFactory {
                object : TreeCell<SidebarTreeNode>() {
                    override fun updateItem(item: SidebarTreeNode?, empty: Boolean) {
                        super.updateItem(item, empty)
                        graphic = null
                        text = if (empty || item == null) null else item.name
                        tooltip = if (empty || item == null) null else AppTooltip.create(
                            when (item) {
                                is SidebarTreeNode.Data -> item.fullId
                                is SidebarTreeNode.Directory -> item.fullPath
                            }
                        )
                    }
                }
            }
        }
        val search = TextField().apply { promptText = "公開IDで検索"; id = "item-selection-search" }
        val selectedLabel = Label().apply { isWrapText = true }
        val emptyLabel = Label("該当するアイテムがありません")
        fun selectedId(): ItemInternalId? = (tree.selectionModel.selectedItem?.value as? SidebarTreeNode.Data)
            ?.let { byPublicId[it.fullId]?.internalId }
        fun refreshSelection() {
            dialog.dialogPane.lookupButton(select).isDisable = selectedId() == null
            selectedLabel.text = (tree.selectionModel.selectedItem?.value as? SidebarTreeNode.Data)?.fullId ?: "アイテムを選択してください"
        }
        fun rebuild() {
            val selectedPublicId = (tree.selectionModel.selectedItem?.value as? SidebarTreeNode.Data)?.fullId ?: currentPublicId
            val root = TreeItem<SidebarTreeNode>(SidebarTreeNode.Directory("", "", emptyList())).apply { isExpanded = true }
            fun node(value: SidebarTreeNode): TreeItem<SidebarTreeNode> = TreeItem(value).apply {
                if (value is SidebarTreeNode.Directory) {
                    isExpanded = true
                    children.setAll(value.children.map(::node))
                }
            }
            root.children.setAll(buildSidebarTree(byPublicId.keys, query = search.text.orEmpty()).map(::node))
            tree.root = root
            fun find(item: TreeItem<SidebarTreeNode>): TreeItem<SidebarTreeNode>? {
                val data = item.value as? SidebarTreeNode.Data
                return if (data != null && data.fullId == selectedPublicId) item
                else item.children.firstNotNullOfOrNull(::find)
            }
            find(root)?.let(tree.selectionModel::select)
            emptyLabel.isVisible = root.children.isEmpty()
            emptyLabel.isManaged = emptyLabel.isVisible
            refreshSelection()
        }
        search.textProperty().addListener { _, _, _ -> rebuild() }
        tree.selectionModel.selectedItemProperty().addListener { _, _, _ -> refreshSelection() }
        tree.setOnMouseClicked { event ->
            if (event.button == MouseButton.PRIMARY && event.clickCount == 2 && selectedId() != null) {
                dialog.result = ItemTreeSelection.Selected(requireNotNull(selectedId()))
                dialog.close()
            }
        }
        dialog.dialogPane.content = VBox(10.0, search, tree, emptyLabel, selectedLabel).apply { prefWidth = 520.0 }
        dialog.setResultConverter { button ->
            when (button) {
                select -> selectedId()?.let(ItemTreeSelection::Selected)
                clear -> ItemTreeSelection.Cleared
                else -> null
            }
        }
        rebuild()
        return dialog.showAndWait().orElse(null)
    }
}
