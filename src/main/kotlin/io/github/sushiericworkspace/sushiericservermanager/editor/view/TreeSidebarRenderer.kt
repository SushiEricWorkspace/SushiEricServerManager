package io.github.sushiericworkspace.sushiericservermanager.editor.view

import javafx.event.EventHandler
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ContentDisplay
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.OverrunStyle
import javafx.scene.input.ClipboardContent
import javafx.scene.input.DataFormat
import javafx.scene.input.TransferMode
import javafx.scene.input.MouseButton
import javafx.scene.layout.Region
import javafx.scene.layout.VBox

/** エディターが選択できるサイドバー表示方式です。 */
enum class SidebarDisplayMode { TREE, FLAT }

/** 従来どおり完全IDを平坦に並べる共通レンダラーです。 */
internal class FlatSidebarRenderer(
    private val createDataButton: (String) -> Button
) {
    fun render(ids: Collection<String>, query: String): List<Node> =
        filterManagedDataIds(ids.toList().sorted(), query).map(createDataButton)
}

/** ツリー型サイドバーのJavaFXノードを構築します。 */
internal class TreeSidebarRenderer(
    private val createDataButton: (String) -> Button,
    private val createDirectoryMenu: (String) -> ContextMenu,
    private val onMove: (String, String) -> Boolean,
    private val expandedState: MutableMap<String, Boolean>,
    private val onExpandedStateChanged: () -> Unit
) {
    fun render(nodes: List<SidebarTreeNode>): List<Node> = nodes.map(::renderNode)

    fun enableRootDrop(target: VBox) = enableDrop(target, "")

    private fun renderNode(node: SidebarTreeNode): Node = when (node) {
        is SidebarTreeNode.Data -> createDataButton(node.fullId).apply {
            properties[DISPLAY_NAME_KEY] = node.name
            setOnDragDetected {
                if (it.button != MouseButton.PRIMARY) {
                    it.consume()
                    return@setOnDragDetected
                }
                val dragboard = startDragAndDrop(TransferMode.MOVE)
                dragboard.setContent(ClipboardContent().apply { put(DATA_ID, node.fullId) })
                it.consume()
            }
        }
        is SidebarTreeNode.Directory -> {
            val children = VBox().apply {
                styleClass.add("sidebar-tree-children")
                this.children.setAll(node.children.map(::renderNode))
            }
            val initiallyExpanded = expandedState[node.fullPath] ?: true
            children.isManaged = initiallyExpanded
            children.isVisible = initiallyExpanded
            val disclosure = Label(sidebarDisclosureSymbol(initiallyExpanded)).apply {
                minWidth = Region.USE_PREF_SIZE
                styleClass.add("sidebar-directory-disclosure")
            }
            val directoryButton = Button(node.name).apply {
                maxWidth = Double.MAX_VALUE
                alignment = Pos.CENTER_LEFT
                contentDisplay = ContentDisplay.LEFT
                graphic = disclosure
                graphicTextGap = 4.0
                isFocusTraversable = false
                textOverrun = OverrunStyle.ELLIPSIS
                styleClass.add("sidebar-directory-button")
                contextMenu = createDirectoryMenu(node.fullPath)
                onAction = EventHandler {
                    children.isManaged = !children.isManaged
                    children.isVisible = children.isManaged
                    expandedState[node.fullPath] = children.isManaged
                    onExpandedStateChanged()
                    disclosure.text = sidebarDisclosureSymbol(children.isManaged)
                }
            }
            enableDrop(directoryButton, node.fullPath)
            VBox(directoryButton, children).apply { styleClass.add("sidebar-directory") }
        }
    }

    private fun enableDrop(target: Node, directory: String) {
        target.setOnDragOver { event ->
            if (event.dragboard.hasContent(DATA_ID)) event.acceptTransferModes(TransferMode.MOVE)
            event.consume()
        }
        target.setOnDragDropped { event ->
            val id = event.dragboard.getContent(DATA_ID) as? String
            event.isDropCompleted = id != null &&
                !isSameSidebarDirectory(id, directory) &&
                onMove(id, directory)
            event.consume()
        }
    }

    companion object {
        private val DATA_ID = DataFormat("application/x-sushieric-data-id")
        const val DISPLAY_NAME_KEY = "sidebarDisplayName"
    }
}

internal fun sidebarDisclosureSymbol(expanded: Boolean): String = if (expanded) "▾" else "▸"
