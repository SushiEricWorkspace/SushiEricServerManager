package io.github.sushiericworkspace.sushiericservermanager.editor.main.item.tree

import io.github.sushiericworkspace.sushiericdatamodel.data.item.LoreLineEditor
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.tree.EditorTreeBuilder
import javafx.scene.control.TreeItem

class ItemTreeBuilder(
    private val itemData: MutableItemBaseData,
    private val expandedMap: MutableMap<String, Boolean>,
    private val lineUiIds: List<String>,
    private val getSectionUiIds: (lineUiId: String, sectionSize: Int) -> List<String>
) : EditorTreeBuilder<TreeRow> {

    // ルート全体の構造を再構築する
    override fun rebuildRoot(rootItem: TreeItem<TreeRow>) {
        rootItem.children.clear()

        val lineSize = LoreLineEditor(itemData.display, 0).getLineSize()

        val loreItems = List(lineSize) { index -> createLoreLineItem(index) }

        val loreFolder = folderItem(
            row = TreeRow.Folder.Lore,
            key = "lore",
            defaultExpanded = false
        ).apply {
            children.addAll(loreItems)
        }

        val displayFolder = folderItem(
            row = TreeRow.Folder.Display,
            key = "display",
            defaultExpanded = false
        ).apply {
            children.addAll(
                TreeItem(TreeRow.Editor.DisplayName),
                loreFolder
            )
        }

        rootItem.children.addAll(
            TreeItem(TreeRow.Editor.SushiEricRarity),
            folderItem(TreeRow.Folder.Detail, "Detail", false).apply { children.addAll(
                TreeItem(TreeRow.Editor.DetailContent)
            ) },
            folderItem(TreeRow.Folder.Stats, "Stats", false).apply { children.addAll(
                TreeItem(TreeRow.Editor.StatsContent)
            ) },
            displayFolder,
            TreeItem(TreeRow.Editor.Comment)
        )
    }

    // 特定の行のセクション構造だけを再構築する
    fun rebuildLoreLine(lineItem: TreeItem<TreeRow>, lineIndex: Int) {
        lineItem.children.clear()

        val lineSystem = LoreLineEditor(itemData.display, lineIndex)
        val lineUiId = lineUiIds[lineIndex]

        val sectionSize = lineSystem.getSectionSize()
        val sectionUiIds = getSectionUiIds(lineUiId, sectionSize)

        val sectionItems = List(sectionSize) { sIdx ->
            val sectionUiId = sectionUiIds[sIdx]

            folderItem(
                row = TreeRow.Folder.LoreSection(
                    lineIndex = lineIndex,
                    lineUiId = lineUiId,
                    sectionIndex = sIdx,
                    sectionUiId = sectionUiId
                ),
                key = "lore-section-$sectionUiId",
                defaultExpanded = false
            ).apply {
                children.add(TreeItem(TreeRow.Editor.LoreContent(lineIndex, sIdx)))
            }
        }

        lineItem.children.addAll(sectionItems)
    }

    private fun createLoreLineItem(index: Int): TreeItem<TreeRow> {
        val lineUiId = lineUiIds[index]

        val lineItem = folderItem(
            row = TreeRow.Folder.LoreLine(
                lineIndex = index,
                lineUiId = lineUiId
            ),
            key = "lore-line-$lineUiId",
            defaultExpanded = false
        )

        rebuildLoreLine(lineItem, index)

        return lineItem
    }

    private fun folderItem(
        row: TreeRow,
        key: String,
        defaultExpanded: Boolean = false
    ): TreeItem<TreeRow> {
        return TreeItem(row).apply {
            isExpanded = expandedMap[key] ?: defaultExpanded

            expandedProperty().addListener { _, _, expanded ->
                expandedMap[key] = expanded
            }
        }
    }
}
