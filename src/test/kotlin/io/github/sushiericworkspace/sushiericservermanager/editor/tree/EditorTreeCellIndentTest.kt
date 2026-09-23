package io.github.sushiericworkspace.sushiericservermanager.editor.tree

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EditorTreeCellIndentTest {
    @Test
    fun `ルートを隠す場合はルート直下を深さ0にする`() {
        assertEquals(0, indentGuideDepth(cellLevel = 1, showRoot = false))
        assertEquals(1, indentGuideDepth(cellLevel = 2, showRoot = false))
        assertEquals(2, indentGuideDepth(cellLevel = 3, showRoot = false))
    }

    @Test
    fun `ルートを表示する場合はレベルをそのまま深さにする`() {
        assertEquals(0, indentGuideDepth(cellLevel = 0, showRoot = true))
        assertEquals(1, indentGuideDepth(cellLevel = 1, showRoot = true))
    }

    @Test
    fun `空セルなど不正なレベルでも深さは0以上にする`() {
        assertEquals(0, indentGuideDepth(cellLevel = -1, showRoot = false))
        assertEquals(0, indentGuideDepth(cellLevel = 0, showRoot = false))
    }

    @Test
    fun `深さ0では階層の線を引かない`() {
        assertNull(indentGuideStyleClass(0))
        assertNull(indentGuideStyleClass(-1))
    }

    @Test
    fun `深さに対応するスタイルクラスを返す`() {
        assertEquals("tree-indent-1", indentGuideStyleClass(1))
        assertEquals("tree-indent-3", indentGuideStyleClass(3))
    }

    @Test
    fun `用意した深さを超えても同じスタイルクラスにする`() {
        assertEquals("tree-indent-6", indentGuideStyleClass(6))
        assertEquals("tree-indent-6", indentGuideStyleClass(9))
    }
}
