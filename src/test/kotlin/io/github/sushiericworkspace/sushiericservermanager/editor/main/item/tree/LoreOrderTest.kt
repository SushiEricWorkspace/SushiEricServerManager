package io.github.sushiericworkspace.sushiericservermanager.editor.main.item.tree

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutablePlainTextLoreSection
import io.github.sushiericworkspace.sushiericservermanager.editor.history.EditorDataHistory
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentKey
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentSource
import javafx.scene.control.TreeItem
import kotlin.test.*

/** TreeItemと実DnD移動handlerだけを使い、ToolkitやStageを起動しません。 */
class LoreOrderTest {
    private val key = EditorDocumentKey(EditorDocumentSource.Local, "item", "item")

    @Test
    fun 行の入力確定後の一dropは一Undoとなり行と配下sectionのUIIDも移動する() {
        val data = MutableItemBaseData(id = "item")
        data.display.mutableLore.addAll(listOf(mutableListOf(MutablePlainTextLoreSection("A")), mutableListOf(MutablePlainTextLoreSection("B"))))
        val memory = LoreTreeUiIdMemory()
        val ids = memory.getLineIds(data.id, 2).toList()
        val sections = memory.getSectionIds(data.id, ids[0], 1).toList()
        val history = EditorDataHistory<MutableItemBaseData>({ it.deepCopy() })
        history.initialize(key, data)
        (data.display.mutableLore[0][0] as MutablePlainTextLoreSection).text = "入力確定"
        history.record(key, data)
        val root = TreeItem<TreeRow>(TreeRow.Folder.Lore)
        root.children.setAll(ids.mapIndexed { i, id -> TreeItem<TreeRow>(TreeRow.Folder.LoreLine(i, id)) })
        var records = 0
        val handler = ItemTreeMoveHandler(data, { records++; history.record(key, data) }, memory)
        assertTrue(ItemTreeDragValidator.canDrop(root.children[0], root.children[1]))
        assertTrue(handler.move(root.children[0], root.children[1]))
        assertEquals(1, records)
        assertEquals(ids.reversed(), memory.getLineIds(data.id, 2))
        assertEquals(sections, memory.getSectionIds(data.id, ids[0], 1))
        val beforeDrop = assertNotNull(history.undo(key, data))
        assertEquals("入力確定", (beforeDrop.display.mutableLore[0][0] as MutablePlainTextLoreSection).text)
        val beforeInput = assertNotNull(history.undo(key, beforeDrop))
        assertEquals("A", (beforeInput.display.mutableLore[0][0] as MutablePlainTextLoreSection).text)
        val redoneInput = assertNotNull(history.redo(key, beforeInput))
        val redoneDrop = assertNotNull(history.redo(key, redoneInput))
        assertEquals(data, redoneDrop)
    }

    @Test
    fun 同じ行のsectionだけ移動でき取消と別行targetは履歴を増やさない() {
        val data = MutableItemBaseData(id = "item")
        data.display.mutableLore.addAll(listOf(mutableListOf(MutablePlainTextLoreSection("A"), MutablePlainTextLoreSection("B")), mutableListOf(MutablePlainTextLoreSection("C"))))
        val memory = LoreTreeUiIdMemory()
        val lines = memory.getLineIds(data.id, 2).toList()
        val ids = memory.getSectionIds(data.id, lines[0], 2).toList()
        val first = TreeItem<TreeRow>(TreeRow.Folder.LoreLine(0, lines[0]))
        first.children.setAll(ids.mapIndexed { i, id -> TreeItem<TreeRow>(TreeRow.Folder.LoreSection(0, lines[0], i, id)) })
        val other = TreeItem<TreeRow>(TreeRow.Folder.LoreSection(1, lines[1], 0, "other"))
        var records = 0
        val handler = ItemTreeMoveHandler(data, { records++ }, memory)
        assertFalse(ItemTreeDragValidator.canDrop(first.children[0], other))
        assertFalse(handler.move(first.children[0], other))
        assertEquals(0, records)
        assertTrue(handler.move(first.children[0], first.children[1]))
        assertEquals(1, records)
        assertEquals(listOf("B", "A"), data.display.mutableLore[0].map { (it as MutablePlainTextLoreSection).text })
        assertEquals(ids.reversed(), memory.getSectionIds(data.id, lines[0], 2))
    }
    @Test
    fun 重複行とsectionのID順序も同じ履歴境界で戻り別文書と共有しない() {
        data class State(val data: MutableItemBaseData, val ids: Map<String, List<String>>)
        val data = MutableItemBaseData(id = "item")
        repeat(2) { data.display.mutableLore.add(mutableListOf(MutablePlainTextLoreSection("same"), MutablePlainTextLoreSection("same"))) }
        val memory = LoreTreeUiIdMemory()
        val initial = memory.snapshot(data.id, listOf(2, 2))
        val history = EditorDataHistory<State>({ State(it.data.deepCopy(), it.ids.mapValues { entry -> entry.value.toList() }) })
        history.initialize(key, State(data, initial))
        memory.lineMoved(data.id, 0, 1)
        val movedLine = memory.snapshot(data.id, listOf(2, 2))
        assertTrue(history.record(key, State(data, movedLine)))
        assertEquals(data, history.undo(key, State(data, movedLine))!!.data)
        val restored = assertNotNull(history.redo(key, State(data, initial)))
        assertEquals(movedLine, restored.ids)
        memory.restore(data.id, initial)
        val line = initial.getValue("lines").first()
        memory.sectionMoved(data.id, line, 0, 1)
        val movedSection = memory.snapshot(data.id, listOf(2, 2))
        assertNotEquals(initial, movedSection)
        memory.restore("other", movedSection)
        memory.sectionMoved("other", line, 0, 1)
        assertEquals(movedSection, memory.snapshot(data.id, listOf(2, 2)))
        memory.restore(data.id, initial)
        assertEquals(initial, memory.snapshot(data.id, listOf(2, 2)))
    }

}
