package io.github.sushiericworkspace.sushiericservermanager.editor.view

import kotlin.test.Test
import kotlin.test.assertEquals

class SidebarTreeModelTest {
    @Test
    fun `直下と多階層のIDからツリーを構築する`() {
        val tree = buildSidebarTree(
            ids = listOf("test", "combat.sword.strong_sword", "combat.sword.test_sword")
        )

        assertEquals(
            listOf(
                SidebarTreeNode.Directory(
                    "combat",
                    "combat",
                    listOf(
                        SidebarTreeNode.Directory(
                            "sword",
                            "combat.sword",
                            listOf(
                                SidebarTreeNode.Data("strong_sword", "combat.sword.strong_sword"),
                                SidebarTreeNode.Data("test_sword", "combat.sword.test_sword")
                            )
                        )
                    )
                ),
                SidebarTreeNode.Data("test", "test")
            ),
            tree
        )
    }

    @Test
    fun `明示的な空ディレクトリを保持する`() {
        val tree = buildSidebarTree(emptyList(), listOf("combat.sword", "empty"))

        assertEquals(
            listOf(
                SidebarTreeNode.Directory(
                    "combat",
                    "combat",
                    listOf(SidebarTreeNode.Directory("sword", "combat.sword", emptyList()))
                ),
                SidebarTreeNode.Directory("empty", "empty", emptyList())
            ),
            tree
        )
    }

    @Test
    fun `検索結果とその親ディレクトリだけを表示する`() {
        val tree = buildSidebarTree(
            ids = listOf("combat.sword.test_sword", "combat.axe.test_axe", "test"),
            directories = listOf("empty"),
            query = "SWORD"
        )

        assertEquals(
            listOf(
                SidebarTreeNode.Directory(
                    "combat",
                    "combat",
                    listOf(
                        SidebarTreeNode.Directory(
                            "sword",
                            "combat.sword",
                            listOf(SidebarTreeNode.Data("test_sword", "combat.sword.test_sword"))
                        )
                    )
                )
            ),
            tree
        )
    }

    @Test
    fun `ディレクトリ削除対象を完全IDで列挙する`() {
        assertEquals(
            listOf("combat.sword.strong_sword", "combat.sword.test_sword"),
            idsInSidebarDirectory(
                listOf("combat.sword.test_sword", "combat.axe.test_axe", "combat.sword.strong_sword"),
                "combat.sword"
            )
        )
    }

    @Test
    fun `同じディレクトリへのドロップを判定する`() {
        assertEquals(true, isSameSidebarDirectory("combat.sword.break_sword", "combat.sword"))
        assertEquals(false, isSameSidebarDirectory("combat.sword.break_sword", "combat"))
        assertEquals(true, isSameSidebarDirectory("break_sword", ""))
    }
}
