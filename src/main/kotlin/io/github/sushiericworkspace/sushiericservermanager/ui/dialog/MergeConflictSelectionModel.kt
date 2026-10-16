package io.github.sushiericworkspace.sushiericservermanager.ui.dialog

import io.github.sushiericworkspace.sushiericservermanager.editor.merge.DataConflict
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.DataFieldPath

internal enum class MergeConflictChoice {
    LOCAL,
    REMOTE
}

/**
 * 保存競合ダイアログの採用先を保持します。
 *
 * ダイアログ表示時は、従来の動作と同じくすべてローカル値を初期選択します。
 */
internal class MergeConflictSelectionModel(conflicts: List<DataConflict>) {
    private val choices = conflicts.associateTo(linkedMapOf()) {
        it.path to MergeConflictChoice.LOCAL
    }

    fun select(path: DataFieldPath, choice: MergeConflictChoice) {
        require(path in choices) { "競合一覧に存在しないパスです: $path" }
        choices[path] = choice
    }

    fun selectAll(choice: MergeConflictChoice) {
        choices.keys.forEach { choices[it] = choice }
    }

    fun selectedLocalPaths(): Set<DataFieldPath> = choices
        .filterValues { it == MergeConflictChoice.LOCAL }
        .keys
        .toSet()
}
