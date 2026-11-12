package io.github.sushiericworkspace.sushiericservermanager.editor.component

import io.github.sushiericworkspace.sushiericdatamodel.data.drop.model.mutable.MutableDropItemData
import kotlin.test.*

class DropItemDraftTest {
    @Test
    fun 入力確定と一ドロップを別履歴としてdeepcopyでUndoRedoする() {
        val parent=listOf(MutableDropItemData(n=1),MutableDropItemData(n=2))
        val d=DropItemDraft(parent);d.items[0].n=9
        assertTrue(d.move(0,1));assertEquals(listOf(2,9),d.items.map{it.n})
        assertTrue(d.undo());assertEquals(listOf(9,2),d.items.map{it.n})
        assertTrue(d.undo());assertEquals(listOf(1,2),d.items.map{it.n})
        assertTrue(d.redo());assertTrue(d.redo());assertEquals(listOf(2,9),d.items.map{it.n})
        d.items[0].n=88;assertEquals(listOf(1,2),parent.map{it.n})
    }
    @Test
    fun 無効な移動と取消draftは親を変えず履歴を増やさない() {
        val parent=listOf(MutableDropItemData(n=3));val d=DropItemDraft(parent)
        assertFalse(d.move(0,0));assertFalse(d.undo());d.items.clear();d.record()
        assertEquals(3,parent.single().n);assertTrue(d.undo());assertEquals(3,d.items.single().n)
    }
    @Test
    fun Undo後の新しい入力確定は古いRedoへ戻らない() {
        val d=DropItemDraft(listOf(MutableDropItemData(n=1)))
        d.items[0].n=2;d.record();assertTrue(d.undo());d.items[0].n=3;assertFalse(d.redo())
        assertTrue(d.undo());assertEquals(1,d.items.single().n)
    }
}
