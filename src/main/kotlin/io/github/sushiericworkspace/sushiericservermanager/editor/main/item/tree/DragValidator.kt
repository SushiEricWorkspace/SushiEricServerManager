package io.github.sushiericworkspace.sushiericservermanager.editor.main.item.tree

import io.github.sushiericworkspace.sushiericservermanager.editor.tree.TreeDragValidator
import javafx.scene.control.TreeItem

/**
 * Itemエディタ専用のドラッグ可否判定。
 */
object ItemTreeDragValidator : TreeDragValidator<TreeRow> {
    override fun canDrop(source: TreeItem<TreeRow>?, target: TreeItem<TreeRow>?): Boolean {
        if (source == null || target == null || source == target) return false

        val sVal = source.value
        val tVal = target.value

        if (sVal is TreeRow.Folder && tVal is TreeRow.Folder) {
            if (sVal is TreeRow.Folder.LoreLine && tVal is TreeRow.Folder.LoreLine) {
                return source.parent == target.parent
            }

            if (sVal is TreeRow.Folder.LoreSection && tVal is TreeRow.Folder.LoreSection) {
                return sVal.lineIndex == tVal.lineIndex && source.parent == target.parent
            }
        }

        return false
    }
}