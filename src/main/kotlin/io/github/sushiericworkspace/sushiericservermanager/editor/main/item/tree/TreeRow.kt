package io.github.sushiericworkspace.sushiericservermanager.editor.main.item.tree

import io.github.sushiericworkspace.sushiericservermanager.editor.tree.EditorTreeContext
import io.github.sushiericworkspace.sushiericservermanager.editor.tree.EditorTreeRow

/**
 * Itemエディタ専用のTree行定義。
 *
 * Folder / Editor / context / label という構造は EditorTreeRow として共通化し、
 * LoreLine や LoreSection などの具体的な行は Item 側で定義する。
 */
sealed interface TreeRow : EditorTreeRow {
    override val context: EditContext

    sealed class EditContext : EditorTreeContext {
        object Global : EditContext()
        data class Line(val lineIndex: Int) : EditContext()
        data class Section(val lineIndex: Int, val sectionIndex: Int) : EditContext()
    }

    sealed class Folder(
        override val label: String,
        override val context: EditContext
    ) : TreeRow {
        override val kind: EditorTreeRow.Kind = EditorTreeRow.Kind.Folder

        object Display : Folder("表示", EditContext.Global)
        object Lore : Folder("Lore", EditContext.Global)

        data class LoreLine(
            val lineIndex: Int,
            val lineUiId: String
        ) : Folder("${lineIndex + 1} 行目", EditContext.Line(lineIndex))

        data class LoreSection(
            val lineIndex: Int,
            val lineUiId: String,
            val sectionIndex: Int,
            val sectionUiId: String
        ) : Folder(
            "セクション ${sectionIndex + 1}",
            EditContext.Section(lineIndex, sectionIndex)
        )

        object Stats : Folder("ステータス", EditContext.Global)
        object Detail : Folder("詳細", EditContext.Global)
    }

    sealed class Editor(
        override val context: EditContext
    ) : TreeRow {
        override val label: String get() = ""
        override val kind: EditorTreeRow.Kind = EditorTreeRow.Kind.Editor

        object DisplayName : Editor(EditContext.Global)
        object SushiEricRarity : Editor(EditContext.Global)
        object DetailContent : Editor(EditContext.Global)
        object StatsContent : Editor(EditContext.Global)
        data class LoreContent(val lineIndex: Int, val sectionIndex: Int) : Editor(EditContext.Section(lineIndex, sectionIndex))
        object Comment : Editor(EditContext.Global)
    }
}
