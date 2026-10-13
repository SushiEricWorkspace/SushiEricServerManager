package io.github.sushiericworkspace.sushiericservermanager.editor.main.item.tree

import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.ItemEditorFactory
import io.github.sushiericworkspace.sushiericservermanager.editor.tree.EditorContextMenuFactory
import io.github.sushiericworkspace.sushiericservermanager.editor.tree.EditorFolderGraphicFactory
import io.github.sushiericworkspace.sushiericservermanager.editor.tree.EditorTreeCell

/**
 * Itemエディタ用TreeCell。
 *
 * 実装本体は editor.tree.EditorTreeCell に共通化し、
 * Item固有の Graphic / Drag判定 / Move処理だけを注入する。
 */
class LoreDragDropTreeCell(
    itemData: MutableItemBaseData,
    refreshSidebarState: (String) -> Unit,
    recordHistorySnapshot: (String) -> Unit = refreshSidebarState,
    onRefresh: (TreeRow) -> Unit,
    loreTreeUiIdMemory: LoreTreeUiIdMemory,
    folderGraphicFactory: EditorFolderGraphicFactory<TreeRow>? = null,
    contextMenuFactory: EditorContextMenuFactory<TreeRow>? = null
) : EditorTreeCell<TreeRow>(
    graphicFactory = ItemEditorFactory(itemData, refreshSidebarState, recordHistorySnapshot),
    dragValidator = ItemTreeDragValidator,
    moveHandler = ItemTreeMoveHandler(itemData, refreshSidebarState, loreTreeUiIdMemory),
    onRefresh = onRefresh,
    folderGraphicFactory = folderGraphicFactory,
    contextMenuFactory = contextMenuFactory
)
