package io.github.sushiericworkspace.sushiericservermanager.editor.view

import kotlin.test.Test
import kotlin.test.assertEquals

class ManagedDataEditorViewTest {

    @Test
    fun `公開IDの表示値を大文字小文字を区別せず絞り込む`() {
        val ids = listOf("iron_ore", "deep_gold", "stone")

        assertEquals(listOf("deep_gold"), filterManagedDataIds(ids, "GOLD"))
        assertEquals(ids, filterManagedDataIds(ids, "  "))
    }

    @Test
    fun `サーバーに存在しないデータだけをローカル限定と判定する`() {
        val remoteIds = setOf("saved", "directory.saved")

        assertEquals(false, isLocalOnlyData("saved", remoteIds))
        assertEquals(true, isLocalOnlyData("draft", remoteIds))
    }

    @Test
    fun `サーバーに存在しないディレクトリだけをローカル限定と判定する`() {
        val remoteIds = setOf("saved", "remote.saved")
        val remoteDirectories = setOf("remote", "empty")

        assertEquals(
            true,
            isLocalOnlyDirectory("draft", listOf("draft.one"), remoteIds, remoteDirectories)
        )
        assertEquals(
            false,
            isLocalOnlyDirectory("remote", listOf("remote.saved", "remote.draft"), remoteIds, remoteDirectories)
        )
        assertEquals(
            false,
            isLocalOnlyDirectory("empty", emptyList(), remoteIds, remoteDirectories)
        )
    }
}
