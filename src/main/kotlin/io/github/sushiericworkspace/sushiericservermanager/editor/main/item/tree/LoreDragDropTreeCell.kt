package io.github.sushiericworkspace.sushiericservermanager.editor.main.item.tree

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.ItemEditorFactory
import io.github.sushiericworkspace.sushiericservermanager.editor.tree.EditorContextMenuFactory
import io.github.sushiericworkspace.sushiericservermanager.editor.tree.EditorFolderGraphicFactory
import javafx.scene.control.TextInputControl
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
    contextMenuFactory: EditorContextMenuFactory<TreeRow>? = null,
    retainDraft: (String, TextInputControl, (String) -> Boolean) -> Unit = { _, _, _ -> },
    moveDraft: (String, String?) -> Unit = { _, _ -> }
) : EditorTreeCell<TreeRow>(
    graphicFactory = ItemEditorFactory(itemData, refreshSidebarState, recordHistorySnapshot, retainDraft = retainDraft, moveDraft = moveDraft),
    dragValidator = ItemTreeDragValidator,
    moveHandler = ItemTreeMoveHandler(itemData, refreshSidebarState, loreTreeUiIdMemory),
    onRefresh = onRefresh,
    folderGraphicFactory = folderGraphicFactory,
    contextMenuFactory = contextMenuFactory
)
