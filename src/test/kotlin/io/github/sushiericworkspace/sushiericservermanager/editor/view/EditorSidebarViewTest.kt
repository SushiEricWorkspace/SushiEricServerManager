package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.ListCell
import javafx.scene.control.MenuItem
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@EnabledOnOs(OS.WINDOWS)
class EditorSidebarViewTest {
    @Test
    fun `実FXMLで一万件のセルが限定され末尾のIDと状態を再利用して表示する`() = onFxThread {
        val root = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!)).load<Parent>()
        val selected = mutableListOf<String>()
        val states = mutableMapOf<String, SidebarDataState>()
        val sidebar = sidebar(states, selected)
        val container = root.lookup("#sidebarContainer") as VBox
        container.children.setAll(sidebar)
        VBox.setVgrow(sidebar, Priority.ALWAYS)
        val scene = Scene(root, 1560.0, 720.0)
        scene.stylesheets.add(assertNotNull(javaClass.getResource(AppScreen.BASE.css)).toExternalForm())
        val stage = Stage().apply { this.scene = scene; show() }
        try {
            val rows = (0 until 10000).map { SidebarRow.Data("item_$it", "item_$it", 0) }
            sidebar.showRows(rows)
            root.applyCss(); root.layout()
            val initialCells = sidebar.lookupAll(".list-cell").filterIsInstance<ListCell<*>>()
            assertTrue(initialCells.size in 1..50, "全件分のセルを生成しています: ${initialCells.size}")
            val first = sidebar.lookup("#item_0") as Button
            first.fire()
            assertEquals(listOf("item_0"), selected)
            states["item_9999"] = SidebarDataState(true, true, true, true, localOnly = true)
            sidebar.scrollTo(9999)
            root.layout()
            val last = sidebar.lookup("#item_9999") as Button
            assertTrue(last.styleClass.containsAll(listOf("button-selected", "button-modified", "button-warning", "button-invalid", "button-local-only")))
            assertTrue(last.accessibleText.contains("item_9999"))
            last.fire()
            assertEquals("item_9999", selected.last())
            sidebar.scrollTo(0)
            root.layout()
            val restored = sidebar.lookup("#item_0") as Button
            assertFalse(restored.styleClass.contains("button-invalid"))
            assertEquals("item_0", restored.accessibleText)
            assertEquals(10000, sidebar.items.size)
            assertTrue(sidebar.lookupAll(".list-cell").size <= 50)
        } finally { stage.close() }
    }

    @Test
    fun `セルを別IDと空行へ再利用しても表示状態と操作を持ち越さない`() = onFxThread {
        val selected = mutableListOf<String>()
        val states = mutableMapOf("first" to SidebarDataState(true, true, false, true))
        val sidebar = sidebar(states, selected)
        val cell = sidebar.cellFactory.call(sidebar)
        val update = cell.javaClass.getDeclaredMethod("updateItem", SidebarRow::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        update.invoke(cell, SidebarRow.Data("first", "first", 0), false)
        fun button(): Button = (cell.graphic as javafx.scene.layout.HBox).children.filterIsInstance<Button>().single()
        val reused = button()
        assertTrue(reused.styleClass.contains("button-invalid"))
        reused.contextMenu = ContextMenu(MenuItem("旧操作"))
        update.invoke(cell, SidebarRow.Directory("folder", "folder", 0, true), false)
        assertEquals(null, reused.contextMenu)
        assertFalse(reused.styleClass.contains("button-invalid"))
        update.invoke(cell, SidebarRow.Data("second", "second", 1), false)
        assertEquals(reused, button())
        reused.fire()
        assertEquals(listOf("second"), selected)
        assertEquals("second", reused.accessibleText)
        assertEquals(null, reused.tooltip)
        update.invoke(cell, null, true)
        assertEquals(null, cell.graphic)
        assertEquals(null, reused.id)
        reused.fire()
        assertEquals(listOf("second"), selected)
    }

    private fun sidebar(states: Map<String, SidebarDataState>, selected: MutableList<String>) = EditorSidebar(
        "該当なし", { states[it] ?: SidebarDataState(false, false, false, false) },
        { selected.add(it) }, { ContextMenu(MenuItem(it)) }, { ContextMenu(MenuItem(it)) },
        { _, _ -> false }, mutableMapOf(), {}, {}
    )

    private fun onFxThread(action: () -> Unit) {
        val started = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); started.countDown() } }
        catch (_: IllegalStateException) { started.countDown() }
        assertTrue(started.await(20, TimeUnit.SECONDS))
        val task = FutureTask(action)
        Platform.runLater(task)
        task.get(30, TimeUnit.SECONDS)
    }
}
