package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.config.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.test.*

class WorkspaceLayoutTest {
    @Test fun `機能の再選択は重複せず分割移動でも全機能を保持する`() {
        val before = WorkspaceLayout().open(WorkspaceFeature.HOME).open(WorkspaceFeature.ITEM_EDITOR).open(WorkspaceFeature.DASHBOARD)
        val selected = before.open(WorkspaceFeature.ITEM_EDITOR)
        assertSame(before.root, selected.root)
        val moved = selected.root!!.dock(WorkspaceFeature.ITEM_EDITOR, WorkspaceFeature.DASHBOARD, DockSide.TOP)
        assertEquals(before.root!!.features().toSet(), moved.features().toSet())
        assertEquals(3, moved.features().size)
        val split = moved as WorkspaceNode.Split
        assertEquals(SplitDirection.VERTICAL, (split.second as WorkspaceNode.Split).direction)
        assertEquals(moved, moved.dock(WorkspaceFeature.ITEM_EDITOR, WorkspaceFeature.RECIPE_EDITOR, DockSide.RIGHT))
    }

    @Test fun `領域を閉じると単独の分割を縮約し選択を残る機能へ移す`() {
        val layout = WorkspaceLayout().open(WorkspaceFeature.HOME).open(WorkspaceFeature.ITEM_EDITOR)
        assertEquals(WorkspaceNode.Feature(WorkspaceFeature.HOME), layout.close(WorkspaceFeature.ITEM_EDITOR).root)
        assertEquals(WorkspaceFeature.HOME, layout.close(WorkspaceFeature.ITEM_EDITOR).active)
        assertNull(layout.close(WorkspaceFeature.HOME).close(WorkspaceFeature.ITEM_EDITOR).root)
    }

    @Test fun `IDの順序と選択を別に保持し閉じた位置の隣へ移る`() {
        val tabs = IdTabsState().open("a").open("b").open("c").open("b")
        assertEquals(listOf("a", "b", "c"), tabs.ids)
        assertEquals("b", tabs.selected)
        val moved = tabs.move("b", 0)
        assertEquals(listOf("b", "a", "c"), moved.ids)
        assertEquals("b", moved.selected)
        assertEquals("a", moved.close("b").selected)
        assertNull(IdTabsState().open("a").close("a").selected)
    }

    @Test fun `profile別配置とID状態を既存設定とともに往復できる`() {
        val first = WorkspaceLayout().open(WorkspaceFeature.ITEM_EDITOR).copy(documents = mapOf(WorkspaceFeature.ITEM_EDITOR to IdTabsState(listOf("a", "b"), "b")))
        val second = WorkspaceLayout().open(WorkspaceFeature.RECIPE_EDITOR)
        val settings = AppSettings(theme = AppTheme.LIGHT, shortcuts = mapOf("SAVE" to "F6"), workspaceLayouts = mapOf("first" to first, "second" to second))
        assertEquals(settings, Json.decodeFromString<AppSettings>(Json.encodeToString(settings)))
        assertTrue(Json.decodeFromString<AppSettings>("{\"theme\":\"LIGHT\",\"shortcuts\":{\"SAVE\":\"F6\"}}").workspaceLayouts.isEmpty())
    }
}
