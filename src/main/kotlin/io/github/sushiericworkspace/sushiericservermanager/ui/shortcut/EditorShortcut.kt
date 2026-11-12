package io.github.sushiericworkspace.sushiericservermanager.ui.shortcut

import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination

/** 操作IDと既定キー。有効範囲が重なる操作だけをキー競合として扱います。 */
enum class EditorShortcut(
    val displayName: String,
    val combination: KeyCodeCombination?,
    val scopes: Set<ShortcutScope> = setOf(ShortcutScope.EDITOR),
    val available: Boolean = true
) {
    SAVE(
        displayName = "ローカル保存",
        combination = KeyCodeCombination(
            KeyCode.S,
            KeyCombination.SHORTCUT_DOWN
        ),
        scopes = setOf(ShortcutScope.EDITOR, ShortcutScope.MOD_CONFIG)
    ),
    SAVE_ALL(
        displayName = "すべてローカル保存",
        combination = KeyCodeCombination(
            KeyCode.S,
            KeyCombination.SHORTCUT_DOWN,
            KeyCombination.SHIFT_DOWN
        )
    ),
    SYNC(
        displayName = "サーバーから取得",
        combination = KeyCodeCombination(
            KeyCode.R,
            KeyCombination.SHORTCUT_DOWN
        )
    ),
    SYNC_ALL(
        displayName = "サーバーからすべて取得",
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
     * サーバーから取得（SHORTCUT+R）とは別の画面で使うため、一般的な再読み込みのキーを割り当てます。
     */
    RELOAD(
        displayName = "再読み込み",
        combination = KeyCodeCombination(KeyCode.F5),
        scopes = setOf(ShortcutScope.MOD_CONFIG)
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
        displayName = "やり直す（別キー）",
        combination = KeyCodeCombination(
            KeyCode.Y,
            KeyCombination.SHORTCUT_DOWN
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
    ),
    IMPORT_LOCAL("ローカルデータを取り込む", null),
    UPLOAD("保存済み内容をサーバーへアップロード…", null,
        scopes = setOf(ShortcutScope.EDITOR, ShortcutScope.MOD_CONFIG))
}

/** 同時に操作を受け付ける機能の種類です。文書の識別は登録先Nodeで行います。 */
enum class ShortcutScope(val displayName: String) { EDITOR("エディター"), MOD_CONFIG("Mod設定") }
