package io.github.sushiericworkspace.sushiericservermanager.editor.view

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * サイドバーへ並べるIDの統合結果を検証する。
 */
class SidebarIdsTest {

    @Test
    fun `サーバー側の並び順をそのまま保つ`() {
        assertEquals(
            listOf("b", "a", "c"),
            mergeSidebarIds(listOf("b", "a", "c"), emptyList())
        )
    }

    @Test
    fun `サーバーに存在しないIDを名前順で末尾へ追加する`() {
        assertEquals(
            listOf("b", "a", "new_x", "new_y"),
            mergeSidebarIds(listOf("b", "a"), listOf("new_y", "b", "new_x"))
        )
    }

    @Test
    fun `重複したローカルのIDは1つにまとめる`() {
        assertEquals(
            listOf("a", "z"),
            mergeSidebarIds(listOf("a"), listOf("z", "z"))
        )
    }

    @Test
    fun `サーバー側が空でもローカルのIDを並べる`() {
        assertEquals(
            listOf("a", "b"),
            mergeSidebarIds(emptyList(), listOf("b", "a"))
        )
    }
}
