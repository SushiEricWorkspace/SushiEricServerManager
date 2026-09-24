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
    SAVE_ALL(
        displayName = "すべて保存",
        combination = KeyCodeCombination(
            KeyCode.S,
            KeyCombination.SHORTCUT_DOWN,
            KeyCombination.SHIFT_DOWN
        )
    ),
    SYNC(
        displayName = "同期",
        combination = KeyCodeCombination(
            KeyCode.R,
            KeyCombination.SHORTCUT_DOWN
        )
    ),
    SYNC_ALL(
        displayName = "すべて同期",
        combination = KeyCodeCombination(
            KeyCode.R,
            KeyCombination.SHORTCUT_DOWN,
            KeyCombination.SHIFT_DOWN
        )
    ),
    CREATE_DATA(
        displayName = "新規データ作成",
        combination = KeyCodeCombination(
            KeyCode.N,
            KeyCombination.SHORTCUT_DOWN
        )
    ),
    CREATE_DIRECTORY(
        displayName = "新規ディレクトリ作成",
        combination = KeyCodeCombination(
            KeyCode.N,
            KeyCombination.SHORTCUT_DOWN,
            KeyCombination.SHIFT_DOWN
        )
    ),
    /**
     * 保存内容を再読み込みします。
     *
     * 同期（SHORTCUT+R）とは別の画面で使うため、一般的な再読み込みのキーを割り当てます。
     */
    RELOAD(
        displayName = "再読み込み",
        combination = KeyCodeCombination(KeyCode.F5)
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
    ),
    REPAIR_WARNING(
        displayName = "警告を修正",
        combination = KeyCodeCombination(
            KeyCode.W,
            KeyCombination.SHORTCUT_DOWN,
            KeyCombination.ALT_DOWN
        )
    ),
    REPAIR_ALL_WARNINGS(
        displayName = "全警告を修正",
        combination = KeyCodeCombination(
            KeyCode.W,
            KeyCombination.SHORTCUT_DOWN,
            KeyCombination.ALT_DOWN,
            KeyCombination.SHIFT_DOWN
        )
    ),
    FOCUS_ERROR(
        displayName = "エラーを修正",
        combination = KeyCodeCombination(
            KeyCode.E,
            KeyCombination.SHORTCUT_DOWN,
            KeyCombination.ALT_DOWN
        )
    ),
    FOCUS_ALL_ERRORS(
        displayName = "全エラーを修正",
        combination = KeyCodeCombination(
            KeyCode.E,
            KeyCombination.SHORTCUT_DOWN,
            KeyCombination.ALT_DOWN,
            KeyCombination.SHIFT_DOWN
        )
    )
}
