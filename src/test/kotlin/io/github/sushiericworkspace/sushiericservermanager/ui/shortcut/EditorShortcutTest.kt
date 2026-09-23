package io.github.sushiericworkspace.sushiericservermanager.ui.shortcut

import kotlin.test.Test
import kotlin.test.assertEquals

class EditorShortcutTest {

    @Test
    fun `ショートカットキーが重複しない`() {
        val shortcuts = EditorShortcut.entries

        assertEquals(
            shortcuts.size,
            shortcuts.map(EditorShortcut::combination).distinct().size
        )
    }

    @Test
    fun `すべてのメニュー項目に表示名とショートカットキーが定義されている`() {
        EditorShortcut.entries.forEach { shortcut ->
            assertEquals(false, shortcut.displayName.isBlank(), shortcut.name)
            assertEquals(false, shortcut.combination.displayText.isBlank(), shortcut.name)
        }
    }
}
