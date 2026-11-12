package io.github.sushiericworkspace.sushiericservermanager.ui.shortcut

import io.github.sushiericworkspace.sushiericservermanager.config.AppSettings
import io.github.sushiericworkspace.sushiericservermanager.config.AppTheme
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.*

class EditorShortcutTest {
    @Test fun `既定キーは重複せず未接続IDにキーはない`() {
        val assigned = EditorShortcut.entries.mapNotNull { it.combination }
        assertEquals(assigned.size, assigned.distinct().size)
        EditorShortcut.entries.forEach { assertTrue(it.displayName.isNotBlank()); if (!it.available) assertNull(it.combination) }
    }

    @Test fun `登録解除復元と解析不能なキーを区別する`() {
        assertEquals(EditorShortcut.SAVE.combination, ShortcutBindings.resolve(EditorShortcut.SAVE, emptyMap()))
        assertNull(ShortcutBindings.resolve(EditorShortcut.SAVE, mapOf("SAVE" to "")))
        assertNull(ShortcutBindings.resolve(EditorShortcut.SAVE, mapOf("SAVE" to "unknown key value")))
        assertEquals(KeyCode.F6, ShortcutBindings.resolve(EditorShortcut.SAVE, mapOf("SAVE" to "F6"))?.code)
    }

    @Test fun `同じscopeだけ競合し解除済み操作とは競合しない`() {
        val f5 = KeyCodeCombination(KeyCode.F5)
        assertNull(ShortcutBindings.problem(EditorShortcut.CREATE_DATA, f5, emptyMap(), false))
        assertNotNull(ShortcutBindings.problem(EditorShortcut.SAVE, f5, emptyMap(), false))
        assertNull(ShortcutBindings.problem(EditorShortcut.SAVE, f5, mapOf("RELOAD" to ""), false))
    }

    @Test fun `CommandとControlの実キーとShortcut修飾を同一視する`() {
        val shortcut = KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN)
        val meta = KeyCodeCombination(KeyCode.S, KeyCombination.META_DOWN)
        val control = KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN)
        assertEquals(ShortcutBindings.signature(shortcut, true), ShortcutBindings.signature(meta, true))
        assertEquals(ShortcutBindings.signature(shortcut, false), ShortcutBindings.signature(control, false))
        assertNotNull(ShortcutBindings.problem(EditorShortcut.CREATE_DATA, meta, emptyMap(), true))
        assertNotNull(ShortcutBindings.problem(EditorShortcut.CREATE_DATA, control, emptyMap(), false))
    }

    @Test fun `文字入力と標準編集と入力方式のキーを予約する`() {
        assertNotNull(ShortcutBindings.problem(EditorShortcut.SAVE, KeyCodeCombination(KeyCode.A), emptyMap(), false))
        for (code in listOf(KeyCode.C, KeyCode.Z, KeyCode.SPACE)) assertNotNull(ShortcutBindings.problem(EditorShortcut.SAVE, KeyCodeCombination(code, KeyCombination.SHORTCUT_DOWN), emptyMap(), false))
        assertTrue(EditorShortcut.UPLOAD.available)
        assertNull(EditorShortcut.UPLOAD.combination)
        assertNull(ShortcutBindings.problem(EditorShortcut.UPLOAD, KeyCodeCombination(KeyCode.F6), emptyMap(), false))
    }

    @Test fun `既存設定の既定値と他項目を保持してキー差分を保存する`() {
        val original = Json.decodeFromString<AppSettings>("""{"monitorIntervalTicks":40,"theme":"LIGHT"}""")
        assertTrue(original.shortcuts.isEmpty())
        val restored = Json.decodeFromString<AppSettings>(Json.encodeToString(original.copy(shortcuts = mapOf("SAVE" to "F6", "SYNC" to ""))))
        assertEquals(40, restored.monitorIntervalTicks)
        assertEquals(AppTheme.LIGHT, restored.theme)
        assertEquals(mapOf("SAVE" to "F6", "SYNC" to ""), restored.shortcuts)
    }
}
