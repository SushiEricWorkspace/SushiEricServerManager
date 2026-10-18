package io.github.sushiericworkspace.sushiericservermanager.ui.shortcut

import io.github.sushiericworkspace.sushiericservermanager.config.OS
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination

/** 設定の未登録は既定値、空文字は解除、解析不能な値は無効な割当として扱います。 */
object ShortcutBindings {
    fun resolve(shortcut: EditorShortcut, overrides: Map<String, String>): KeyCodeCombination? {
        val value = overrides[shortcut.name] ?: return shortcut.combination
        if (value.isEmpty()) return null
        return try { KeyCombination.valueOf(value) as? KeyCodeCombination } catch (_: IllegalArgumentException) { null }
    }

    /** 同じ有効範囲との衝突と、標準編集・入力方式に予約されたキーを日本語で返します。 */
    fun problem(shortcut: EditorShortcut, key: KeyCodeCombination, overrides: Map<String, String>, mac: Boolean = OS.isMac): String? {
        if (!shortcut.available) return "この操作は現在利用できません。"
        if (listOf(key.shift, key.control, key.alt, key.meta, key.shortcut).any { it == KeyCombination.ModifierValue.ANY }) return "修飾キーの任意指定は使用できません。"
        val keySignature = signature(key, mac)
        val shortcutDown = key.shortcut == KeyCombination.ModifierValue.DOWN ||
            (if (mac) key.meta else key.control) == KeyCombination.ModifierValue.DOWN
        if (!key.code.isFunctionKey && !shortcutDown) return "Command／Controlを含むキー、またはファンクションキーを指定してください。"
        if (key.code in setOf(KeyCode.SPACE, KeyCode.CONVERT, KeyCode.NONCONVERT, KeyCode.KANA, KeyCode.KANJI, KeyCode.INPUT_METHOD_ON_OFF)) return "入力方式の切替キーは使用できません。"
        if (shortcutDown && (key.code in setOf(KeyCode.C, KeyCode.X, KeyCode.V, KeyCode.A) ||
            key.code in setOf(KeyCode.Z, KeyCode.Y) && shortcut !in setOf(EditorShortcut.UNDO, EditorShortcut.REDO, EditorShortcut.REDO_ALTERNATIVE))) return "テキストの標準編集に予約されたキーです。"
        val conflict = EditorShortcut.entries.firstOrNull {
            it != shortcut && it.available && it.scopes.any(shortcut.scopes::contains) &&
                resolve(it, overrides)?.let { other -> signature(other, mac) == keySignature } == true
        }
        return conflict?.let { "「${it.displayName}」と同じ有効範囲で競合しています。" }
    }

    /** Shortcut修飾とOSの実修飾を同一視します。 */
    internal fun signature(key: KeyCodeCombination, mac: Boolean): List<Any> = listOf(
        key.code,
        key.shift == KeyCombination.ModifierValue.DOWN,
        key.control == KeyCombination.ModifierValue.DOWN || (!mac && key.shortcut == KeyCombination.ModifierValue.DOWN),
        key.alt == KeyCombination.ModifierValue.DOWN,
        key.meta == KeyCombination.ModifierValue.DOWN || (mac && key.shortcut == KeyCombination.ModifierValue.DOWN)
    )
}
