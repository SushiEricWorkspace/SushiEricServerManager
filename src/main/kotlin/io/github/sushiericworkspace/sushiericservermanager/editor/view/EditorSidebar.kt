package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.PublicId
import io.github.sushiericworkspace.sushiericservermanager.ui.AppTooltip
import javafx.scene.Node
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.OverrunStyle
import javafx.scene.input.ClipboardContent
import javafx.scene.input.DataFormat
import javafx.scene.input.MouseButton
import javafx.scene.input.TransferMode
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region

/** 表示中のセルだけを保持し、データ操作をエディターへ委譲するサイドバーです。 */
internal class EditorSidebar(
    noResultsText: String,
    private val stateFor: (String) -> SidebarDataState,
    private val onSelect: (String) -> Unit,
    private val createDataMenu: (String) -> ContextMenu,
    private val createDirectoryMenu: (String) -> ContextMenu,
    private val onMove: (String, String) -> Boolean,
    private val expandedState: MutableMap<String, Boolean>,
    private val onExpandedStateChanged: () -> Unit,
    private val onExpansionChanged: () -> Unit,
    private val previewData: (Node, String?) -> Unit = { _, _ -> }
) : ListView<SidebarRow>() {
    private val visibleCells = mutableSetOf<SidebarCell>()
    private val visibleDataCells = mutableMapOf<String, SidebarCell>()

    init {
        styleClass.add("sidebar-list")
        fixedCellSize = 32.0
        minHeight = 0.0
        isFocusTraversable = false
        selectionModel = null
        placeholder = Label(noResultsText).apply { styleClass.add("sidebar-no-results-label") }
        setCellFactory { SidebarCell() }
        setOnDragOver { event ->
            if (event.dragboard.hasContent(DATA_ID)) event.acceptTransferModes(TransferMode.MOVE)
            event.consume()
        }
        setOnDragDropped { event ->
            val id = event.dragboard.getContent(DATA_ID) as? String
            event.isDropCompleted = id != null && !isSameSidebarDirectory(id, "") && onMove(id, "")
            event.consume()
        }
    }

    fun showRows(rows: List<SidebarRow>) {
        visibleCells.toList().forEach { it.closeMenu() }
        items.setAll(rows)
    }

    fun refreshData(id: String) {
        visibleDataCells[id]?.render()
    }

    private inner class SidebarCell : ListCell<SidebarRow>() {
        private val disclosure = Label().apply { styleClass.add("sidebar-directory-disclosure") }
        private val prefix = Label()
        private val name = Label().apply {
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
            textOverrun = OverrunStyle.ELLIPSIS
            HBox.setHgrow(this, Priority.ALWAYS)
        }
        private val error = Label("⚠").apply { styleClass.add("sidebar-error-mark") }
        private val validation = Label().apply { styleClass.add("sidebar-validation-status") }
        private val warning = Label("⚠").apply { styleClass.add("sidebar-warning-mark") }
        private val content = HBox(5.0, disclosure, prefix, name, validation, error, warning).apply {
            alignment = Pos.CENTER_LEFT
            maxWidth = Double.MAX_VALUE
        }
        private val button = Button().apply {
            isFocusTraversable = false
            isMnemonicParsing = false
            maxWidth = Double.MAX_VALUE
            minWidth = 0.0
            graphic = content
            HBox.setHgrow(this, Priority.ALWAYS)
            setOnAction {
                when (val row = item) {
                    is SidebarRow.Data -> onSelect(row.fullId)
                    is SidebarRow.Directory -> {
                        expandedState[row.fullPath] = !row.expanded
                        onExpandedStateChanged()
                        onExpansionChanged()
                    }
                    null -> Unit
                }
            }
            setOnContextMenuRequested { event ->
                closeMenu()
                val menu = when (val row = item) {
                    is SidebarRow.Data -> createDataMenu(row.fullId)
                    is SidebarRow.Directory -> createDirectoryMenu(row.fullPath)
                    null -> null
                }
                contextMenu = menu
                menu?.show(this, event.screenX, event.screenY)
                event.consume()
            }
            setOnDragDetected { event ->
                val row = item
                if (row is SidebarRow.Data && event.button == MouseButton.PRIMARY) {
                    startDragAndDrop(TransferMode.MOVE).setContent(
                        ClipboardContent().apply { put(DATA_ID, row.fullId) }
                    )
                }
                event.consume()
            }
        }
        private val guides = HBox()
        private val rowBox = HBox(guides, button).apply {
            alignment = Pos.CENTER_LEFT
            prefWidthProperty().bind(this@SidebarCell.widthProperty().subtract(2.0))
        }
        private var boundId: String? = null

        init {
            padding = Insets.EMPTY
            setOnDragOver { event ->
                when (item) {
                    is SidebarRow.Directory -> {
                        if (event.dragboard.hasContent(DATA_ID)) event.acceptTransferModes(TransferMode.MOVE)
                        event.consume()
                    }
                    is SidebarRow.Data -> event.consume()
                    null -> Unit
                }
            }
            setOnDragDropped { event ->
                when (val row = item) {
                    is SidebarRow.Directory -> {
                        val id = event.dragboard.getContent(DATA_ID) as? String
                        event.isDropCompleted = id != null &&
                            !isSameSidebarDirectory(id, row.fullPath) && onMove(id, row.fullPath)
                        event.consume()
                    }
                    is SidebarRow.Data -> { event.isDropCompleted = false; event.consume() }
                    null -> Unit
                }
            }
        }

        override fun updateItem(row: SidebarRow?, empty: Boolean) {
            visibleCells.remove(this)
            boundId?.let { visibleDataCells.remove(it, this) }
            boundId = null
            closeMenu()
            super.updateItem(row, empty)
            text = null
            if (empty || row == null) {
                graphic = null
                button.id = null
                button.tooltip = null
                previewData(button, null)
                button.accessibleText = null
                button.styleClass.removeAll(SidebarDataState.STYLE_CLASSES + "sidebar-directory-button")
                return
            }
            visibleCells.add(this)
            if (row is SidebarRow.Data) {
                boundId = row.fullId
                visibleDataCells[row.fullId] = this
            }
            graphic = rowBox
            render()
        }

        fun closeMenu() {
            button.contextMenu?.hide()
            button.contextMenu = null
        }

        fun render() {
            val row = item ?: return
            closeMenu()
            if (guides.children.size != row.depth) guides.children.setAll((0 until row.depth).map {
                Region().apply {
                    minWidth = 14.0
                    prefWidth = 14.0
                    minHeight = 32.0
                    styleClass.add("sidebar-tree-guide")
                }
            })
            name.text = row.name
            name.styleClass.setAll("sidebar-data-name")
            button.styleClass.removeAll(SidebarDataState.STYLE_CLASSES + "sidebar-directory-button")
            prefix.styleClass.clear()
            val directory = row as? SidebarRow.Directory
            disclosure.isVisible = directory != null
            disclosure.isManaged = directory != null
            if (directory != null) {
                button.id = null
                button.styleClass.add("sidebar-directory-button")
                disclosure.text = sidebarDisclosureSymbol(directory.expanded)
                prefix.isVisible = false
                prefix.isManaged = false
                error.isVisible = false
                error.isManaged = false
                validation.isVisible = false
                validation.isManaged = false
                warning.isVisible = false
                warning.isManaged = false
                button.accessibleText = directory.fullPath
                button.tooltip = null
                previewData(button, null)
            } else if (row is SidebarRow.Data) {
                val state = stateFor(row.fullId)
                button.id = row.fullId
                button.styleClass.addAll(state.styleClasses)
                prefix.text = if (state.pendingDeletion) "－" else "＋"
                prefix.styleClass.add(if (state.pendingDeletion) "sidebar-pending-deletion-mark" else "sidebar-local-only-mark")
                prefix.isVisible = state.pendingDeletion || state.localOnly
                prefix.isManaged = prefix.isVisible
                if (state.modified) name.styleClass.add("sidebar-data-modified")
                validation.text = state.validationStatus.text
                validation.isVisible = state.validationStatus != SidebarValidationStatus.CHECKED
                validation.isManaged = validation.isVisible
                error.isVisible = state.hasErrors
                error.isManaged = state.hasErrors
                warning.isVisible = state.hasWarnings
                warning.isManaged = state.hasWarnings
                button.accessibleText = listOfNotNull(PublicId.normalizeForLoad(row.fullId), state.description()).joinToString(" / ")
                button.tooltip = state.description()?.let(AppTooltip::create)
                previewData(button, row.fullId)
            }
        }
    }

    companion object {
        private val DATA_ID = DataFormat("application/x-sushieric-data-id")
    }
}
