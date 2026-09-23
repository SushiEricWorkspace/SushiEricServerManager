package io.github.sushiericworkspace.sushiericservermanager.ui.shortcut

import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination

/**
 * エディタで使用するショートカットキーの種類。
 *
 * 今は保存のみだが、今後「検索」「新規作成」「削除」などを追加する場合は、
 * このenumに項目を増やす。
 */
enum class EditorShortcut(
    val displayName: String,
    val combination: KeyCodeCombination
) {
    SAVE(
        displayName = "保存",
        combination = KeyCodeCombination(
            KeyCode.S,
            KeyCombination.SHORTCUT_DOWN
        )
    ),
    UNDO(
        displayName = "元に戻す",
        combination = KeyCodeCombination(
            KeyCode.Z,
            KeyCombination.SHORTCUT_DOWN
        )
    ),
    REDO(
        displayName = "やり直す",
        combination = KeyCodeCombination(
            KeyCode.Z,
            KeyCombination.SHORTCUT_DOWN,
            KeyCombination.SHIFT_DOWN
        )
    ),
    REDO_ALTERNATIVE(
        displayName = "やり直す",
        combination = KeyCodeCombination(
            KeyCode.Y,
            KeyCombination.CONTROL_DOWN
        )
    )
}
