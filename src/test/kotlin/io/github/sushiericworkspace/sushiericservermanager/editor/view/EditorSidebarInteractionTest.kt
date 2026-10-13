package io.github.sushiericworkspace.sushiericservermanager.editor.view

import javafx.application.Platform
import javafx.geometry.Point2D
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.input.ContextMenuEvent
import javafx.scene.input.MouseButton
import javafx.scene.robot.Robot
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
class EditorSidebarInteractionTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "sidebar.nativeRobot", matches = "true")
    fun `実ドラッグでディレクトリとルートへ移動し同一ディレクトリを拒否する`() {
        val moved = mutableListOf<Pair<String, String>>()
        val events = mutableListOf<String>()
        lateinit var sidebar: EditorSidebar
        lateinit var stage: Stage
        lateinit var robot: Robot
        lateinit var originalPointer: Point2D
        fx {
            sidebar = EditorSidebar("該当なし", { SidebarDataState(false, false, false, false) }, {},
                { ContextMenu(MenuItem(it)) }, { ContextMenu(MenuItem(it)) },
                { id, directory -> moved.add(id to directory); true }, mutableMapOf(), {}, {})
            sidebar.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED) { events.add("pressed:" + it.target.javaClass.simpleName) }
            sidebar.addEventFilter(javafx.scene.input.MouseEvent.DRAG_DETECTED) { events.add("detected:" + it.button) }
            sidebar.addEventFilter(javafx.scene.input.DragEvent.ANY) { events.add(it.eventType.name + ":" + it.target.javaClass.simpleName) }
            sidebar.showRows(listOf(
                SidebarRow.Directory("destination", "destination", 0, true),
                SidebarRow.Directory("source", "source", 0, true),
                SidebarRow.Data("source.item", "item", 0)
            ))
            stage = Stage().apply { scene = Scene(sidebar, 450.0, 600.0); show() }
            sidebar.applyCss(); sidebar.layout()
            robot = Robot()
            originalPointer = robot.mousePosition
            stage.toFront(); stage.requestFocus()
        }
        Thread.sleep(300)
        try {
            fun point(index: Int): Point2D = fx {
                val cell = sidebar.lookupAll(".list-cell").filterIsInstance<javafx.scene.control.ListCell<*>>()
                    .single { it.index == index }
                val bounds = cell.localToScreen(cell.boundsInLocal)
                Point2D(bounds.minX + 180, bounds.minY + bounds.height / 2)
            }
            fun drag(target: Point2D) {
                val source = point(2)
                events.add("source:" + source + "; target:" + target)
                fx { robot.mouseMove(source) }
                Thread.sleep(100)
                fx { events.add("pointer:" + robot.mousePosition + "; focused:" + stage.isFocused); robot.mousePress(MouseButton.PRIMARY) }
                Thread.sleep(80)
                fx { robot.mouseMove(source.add(16.0, 0.0)) }
                Thread.sleep(80)
                fx { robot.mouseMove(target) }
                Thread.sleep(80)
                fx { robot.mouseRelease(MouseButton.PRIMARY) }
                Thread.sleep(150)
            }
            drag(point(0))
            assertEquals(listOf("source.item" to "destination"), fx { moved.toList() }, fx { events.joinToString() })
            drag(point(1))
            assertEquals(1, fx { moved.size })
            drag(fx { sidebar.localToScreen(180.0, 400.0) })
            assertEquals(listOf("source.item" to "destination", "source.item" to ""), fx { moved.toList() })
        } finally {
            fx { robot.mouseRelease(MouseButton.PRIMARY); robot.mouseMove(originalPointer); stage.close() }
        }
    }

    @Test
    fun `右クリックの操作IDを現在行から解決しディレクトリメニューも一覧更新で閉じる`() = fx {
        val menuIds = mutableListOf<String>()
        val sidebar = EditorSidebar("該当なし", { SidebarDataState(false, false, false, false) }, {},
            { id -> menuIds.add(id); ContextMenu(MenuItem(id)) },
            { id -> menuIds.add(id); ContextMenu(MenuItem(id)) },
            { _, _ -> false }, mutableMapOf(), {}, {})
        sidebar.showRows(listOf(SidebarRow.Directory("folder", "folder", 0, true)))
        val stage = Stage().apply { scene = Scene(sidebar, 450.0, 600.0); show() }
        try {
            sidebar.applyCss(); sidebar.layout()
            val button = sidebar.lookupAll(".button").filterIsInstance<Button>().single()
            val bounds = button.localToScreen(button.boundsInLocal)
            button.fireEvent(ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                1.0, 1.0, bounds.minX + 10, bounds.minY + 10, false, null))
            assertEquals(listOf("folder"), menuIds)
            val menu = assertNotNull(button.contextMenu)
            assertTrue(menu.isShowing)
            sidebar.showRows(listOf(SidebarRow.Data("other", "other", 0)))
            assertFalse(menu.isShowing)
            sidebar.layout()
            val other = sidebar.lookupAll(".button").filterIsInstance<Button>().single()
            other.fireEvent(ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                1.0, 1.0, bounds.minX + 10, bounds.minY + 10, false, null))
            assertEquals(listOf("folder", "other"), menuIds)
        } finally { stage.close() }
    }

    private fun <T> fx(action: () -> T): T {
        val ready = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
        catch (_: IllegalStateException) { ready.countDown() }
        assertTrue(ready.await(20, TimeUnit.SECONDS))
        val task = FutureTask(action)
        Platform.runLater(task)
        return task.get(30, TimeUnit.SECONDS)
    }
}
