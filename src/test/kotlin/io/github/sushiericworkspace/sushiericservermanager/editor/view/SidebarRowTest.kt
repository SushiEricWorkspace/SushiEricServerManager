package io.github.sushiericworkspace.sushiericservermanager.editor.view

import kotlin.test.Test
import kotlin.test.assertEquals

class SidebarRowTest {
    @Test
    fun `折り畳んだ子を行へ含めず検索解除後も展開状態を維持する`() {
        val ids = listOf("combat.sword.one", "combat.axe.two", "root")
        val expanded = mapOf("combat.sword" to false)
        fun rows(query: String) = buildSidebarRows(ids, listOf("empty"), query, SidebarDisplayMode.TREE, expanded)
        assertEquals(
            listOf(
                SidebarRow.Directory("combat", "combat", 0, true),
                SidebarRow.Directory("combat.axe", "axe", 1, true),
                SidebarRow.Data("combat.axe.two", "two", 2),
                SidebarRow.Directory("combat.sword", "sword", 1, false),
                SidebarRow.Directory("empty", "empty", 0, true),
                SidebarRow.Data("root", "root", 0)
            ), rows("")
        )
        assertEquals(listOf(
            SidebarRow.Directory("combat", "combat", 0, true),
            SidebarRow.Directory("combat.sword", "sword", 1, false)
        ), rows("SWORD"))
        assertEquals(emptyList(), rows("absent"))
        assertEquals(false, (rows("")[3] as SidebarRow.Directory).expanded)
    }

    @Test
    fun `平坦表示は完全IDを表示し階層と展開状態に左右されない`() {
        assertEquals(
            listOf(SidebarRow.Data("a_z", "a_z", 0), SidebarRow.Data("folder.b", "folder.b", 0)),
            buildSidebarRows(listOf("folder.b", "a_z"), listOf("empty"), " ", SidebarDisplayMode.FLAT, mapOf("folder" to false))
        )
    }
}
