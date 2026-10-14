package io.github.sushiericworkspace.sushiericservermanager.editor.main.item.tree

import io.github.sushiericworkspace.sushiericdatamodel.data.item.LoreLineEditor
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.tree.TreeMoveHandler
import javafx.scene.control.TreeItem

/**
 * Itemエディタ専用のTreeItem移動処理。
 *
 * Tree上の移動を、MutableItemBaseData.display.lore の実データ移動に変換する。
 */
class ItemTreeMoveHandler(
    private val itemData: MutableItemBaseData,
    private val refreshSidebarState: (String) -> Unit,
    private val loreTreeUiIdMemory: LoreTreeUiIdMemory
) : TreeMoveHandler<TreeRow> {

    override fun move(source: TreeItem<TreeRow>, target: TreeItem<TreeRow>): Boolean {
        val sourceVal = source.value as? TreeRow.Folder ?: return false
        val targetVal = target.value as? TreeRow.Folder ?: return false

        val parentItem = target.parent ?: return false
        val children = parentItem.children
        val sourceIndex = children.indexOf(source)
        val targetIndex = children.indexOf(target)

        if (sourceIndex == -1 || targetIndex == -1) return false

        when (sourceVal) {
            is TreeRow.Folder.LoreLine -> {
                if (targetVal !is TreeRow.Folder.LoreLine) return false

                LoreLineEditor(itemData.display, sourceIndex).moveTo(targetIndex)

                loreTreeUiIdMemory.lineMoved(
                    itemId = itemData.id,
                    fromIndex = sourceIndex,
                    toIndex = targetIndex
                )
            }

            is TreeRow.Folder.LoreSection -> {
                if (targetVal !is TreeRow.Folder.LoreSection) return false

                LoreLineEditor(itemData.display, sourceVal.lineIndex)
                    .section(sourceIndex)
                    .moveTo(targetIndex)

                loreTreeUiIdMemory.sectionMoved(
                    itemId = itemData.id,
                    lineUiId = sourceVal.lineUiId,
                    fromSectionIndex = sourceIndex,
                    toSectionIndex = targetIndex
                )
            }

            else -> return false
        }

        refreshSidebarState(itemData.id)
        return true
    }
}
