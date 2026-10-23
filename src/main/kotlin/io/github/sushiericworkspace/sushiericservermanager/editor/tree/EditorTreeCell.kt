package io.github.sushiericworkspace.sushiericservermanager.editor.tree

import javafx.animation.Timeline
import javafx.animation.KeyFrame
import javafx.util.Duration
import javafx.scene.control.TreeCell
import javafx.scene.control.TreeItem
import javafx.scene.input.ClipboardContent
import javafx.scene.input.MouseEvent
import javafx.scene.input.TransferMode

/**
 * エディタ用 TreeView の共通セル。
 *
 * - Folder 行は text として表示
 * - Editor 行は EditorGraphicFactory に Node 生成を委譲
 * - ドラッグ可否は TreeDragValidator に委譲
 * - 実データの移動は TreeMoveHandler に委譲
 */
private const val INDENT_GUIDE_STYLE_PREFIX = "tree-indent-"

/** 階層の線を用意しているインデントの深さの上限です。これより深い行は同じ線数で表示します。 */
private const val MAX_INDENT_GUIDE_DEPTH = 6

/** ルートを除いた表示上の深さを返します。ルート直下の行は`0`です。 */
internal fun indentGuideDepth(cellLevel: Int, showRoot: Boolean): Int {
    val rootOffset = if (showRoot) 0 else 1

    return (cellLevel - rootOffset).coerceAtLeast(0)
}

/** 深さに対応する階層の線のスタイルクラスを返します。線が不要な深さでは`null`です。 */
internal fun indentGuideStyleClass(depth: Int): String? {
    if (depth <= 0) return null

    return INDENT_GUIDE_STYLE_PREFIX + depth.coerceAtMost(MAX_INDENT_GUIDE_DEPTH)
}

open class EditorTreeCell<R : EditorTreeRow>(
    private val graphicFactory: EditorGraphicFactory<R>,
    private val dragValidator: TreeDragValidator<R>,
    private val moveHandler: TreeMoveHandler<R>,
    private val onRefresh: (R) -> Unit,
    private val folderGraphicFactory: EditorFolderGraphicFactory<R>? = null,
    private val contextMenuFactory: EditorContextMenuFactory<R>? = null,
    private val beforeDrag: () -> Boolean = { true },
    private val beforeDrop: () -> Boolean = { true }
) : TreeCell<R>() {

    companion object {
        private var draggedTreeItem: TreeItem<*>? = null
        private var draggedTreeView: javafx.scene.control.TreeView<*>? = null
        private var activeScrollCell: EditorTreeCell<*>? = null
    }

    private var scrollDirection = 0
    private val edgeScroll = Timeline(KeyFrame(Duration.millis(80.0), {
        val tree = treeView
        if (draggedTreeItem == null || tree !== draggedTreeView || scene == null || tree?.scene?.window?.isShowing != true || activeScrollCell !== this) {
            stopScrolling()
        } else tree.scrollTo((index + scrollDirection).coerceIn(0, (tree.expandedItemCount - 1).coerceAtLeast(0)))
    })).apply { cycleCount = Timeline.INDEFINITE }

    private fun stopScrolling() {
        edgeScroll.stop()
        scrollDirection = 0
        if (activeScrollCell === this) activeScrollCell = null
    }

    init {
        sceneProperty().addListener { _, _, next -> if (next == null) stopScrolling() }
        setOnDragExited { stopScrolling() }
        if (!styleClass.contains("custom-tree-cell")) {
            styleClass.add("custom-tree-cell")
        }

        addEventFilter(MouseEvent.MOUSE_PRESSED) {
            treeView?.selectionModel?.clearSelection()
        }

        addEventFilter(MouseEvent.MOUSE_RELEASED) {
            treeView?.selectionModel?.clearSelection()
        }

        setOnDragDetected { event ->
            val currentItem = treeItem ?: return@setOnDragDetected
            if (currentItem.value.kind == EditorTreeRow.Kind.Folder && beforeDrag()) {
                activeScrollCell?.stopScrolling()
                draggedTreeItem = currentItem
                draggedTreeView = treeView
                startDragAndDrop(TransferMode.MOVE).setContent(
                    ClipboardContent().apply { putString("") }
                )
                event.consume()
            }
        }

        setOnDragOver { event ->
            val source = castDraggedTreeItem()
            val target = treeItem

            if (treeView === draggedTreeView && dragValidator.canDrop(source, target)) {
                event.acceptTransferModes(TransferMode.MOVE)
                val tree = treeView
                val y = tree.sceneToLocal(event.sceneX, event.sceneY).y
                scrollDirection = when { y < 32 -> -1; y > tree.height - 32 -> 1; else -> 0 }
                if (scrollDirection == 0) stopScrolling() else {
                    if (activeScrollCell !== this) activeScrollCell?.stopScrolling()
                    activeScrollCell = this
                    edgeScroll.play()
                }
            } else stopScrolling()
            event.consume()
        }

        setOnDragDropped { event ->
            val source = castDraggedTreeItem()
            val target = treeItem

            val success = if (source != null && target != null && beforeDrop()) {
                try {
                    val moved = moveHandler.move(source, target)
                    if (moved) {
                        target.parent?.value?.let(onRefresh)
                    }
                    moved
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            } else {
                false
            }

            activeScrollCell?.stopScrolling()
            draggedTreeItem = null
            draggedTreeView = null
            event.isDropCompleted = success
            event.consume()
        }

        setOnDragDone { event ->
            draggedTreeItem = null
            draggedTreeView = null
            activeScrollCell?.stopScrolling()
            event.consume()
        }
    }

    override fun updateItem(row: R?, empty: Boolean) {
        stopScrolling()
        super.updateItem(row, empty)

        styleClass.removeAll("folder-cell", "item-cell")
        styleClass.removeIf { it.startsWith(INDENT_GUIDE_STYLE_PREFIX) }

        if (empty || row == null) {
            text = null
            graphic = null
            contextMenu = null
            style = ""
            return
        }

        contextMenu = contextMenuFactory?.createContextMenu(row)

        /*
         * 入れ子は余白だけでは追いにくいため、深さに応じた階層の線をCSSで引く。
         * TreeViewのインデントは内部で確保されるため、深さごとのスタイルクラスで表す。
         */
        indentGuideStyleClass(
            indentGuideDepth(
                cellLevel = treeView?.getTreeItemLevel(treeItem) ?: 0,
                showRoot = treeView?.isShowRoot ?: true
            )
        )?.let(styleClass::add)

        when (row.kind) {
            EditorTreeRow.Kind.Folder -> {
                styleClass.add("folder-cell")

                val folderGraphic = folderGraphicFactory?.createFolderGraphic(row)

                if (folderGraphic != null) {
                    text = null
                    graphic = folderGraphic
                } else {
                    text = row.label
                    graphic = null
                }
            }

            EditorTreeRow.Kind.Editor -> {
                styleClass.add("item-cell")
                text = null
                graphic = graphicFactory.createGraphic(row)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun castDraggedTreeItem(): TreeItem<R>? {
        return draggedTreeItem as? TreeItem<R>
    }
}
