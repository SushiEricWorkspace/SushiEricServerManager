package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceManager
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceView
import javafx.scene.Scene
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import io.github.sushiericworkspace.sushiericservermanager.config.*
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.*
import javafx.application.Platform
import javafx.event.Event
import javafx.scene.control.*
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.PickResult
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Stage
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WorkspaceViewTest {
    @Test fun `分割と移動で同じNodeの入力と選択範囲を保持しdisposeしない`() {
        startFx()
        val stage = fx { workspaceStage() }
        var disposed = 0
        try {
            fx {
                val workspace = WorkspaceManager.requireWorkspace()
                val field = TextField("編集中の文字")
                val root = VBox(field)
                workspace.show(WorkspaceFeature.ITEM_EDITOR, root, dispose = { disposed++ })
                workspace.show(WorkspaceFeature.RECIPE_EDITOR, VBox(Label("レシピ")))
                stage.scene.root.applyCss(); stage.scene.root.layout()
                field.requestFocus(); field.selectRange(1, 4)
                assertSame(field, stage.scene.focusOwner)
                val heading = (root.parent as javafx.scene.layout.BorderPane).top
                Event.fireEvent(heading, MouseEvent(MouseEvent.MOUSE_PRESSED, 10.0, 10.0, 10.0, 10.0, MouseButton.PRIMARY, 1,
                    false, false, false, false, true, false, false, false, false, true, PickResult(heading, 10.0, 10.0)))
                assertSame(field, stage.scene.focusOwner)
                assertTrue(workspace.move(WorkspaceFeature.ITEM_EDITOR, WorkspaceFeature.RECIPE_EDITOR, DockSide.BOTTOM))
                Event.fireEvent(heading, MouseEvent(MouseEvent.MOUSE_CLICKED, 10.0, 10.0, 10.0, 10.0, MouseButton.PRIMARY, 1,
                    false, false, false, false, false, false, false, false, false, false, PickResult(heading, 10.0, 10.0)))
                assertSame(stage.scene, root.scene)
                assertSame(root, field.parent)
                assertEquals("編集中の文字", field.text)
                assertSame(field, stage.scene.focusOwner)
                assertEquals(1, field.anchor); assertEquals(4, field.caretPosition)
                assertEquals(0, disposed)
                assertTrue(workspace.focus(WorkspaceFeature.ITEM_EDITOR))
                assertEquals(1, workspace.titles().count { it == WorkspaceFeature.ITEM_EDITOR })
            }
        } finally { fx { stage.close() } }
        assertEquals(1, disposed)
    }

    @Test fun `共有Sceneの領域切替でメニュー同一性とShortcut宛先を合わせる`() {
        startFx()
        val stage = fx { workspaceStage() }
        try {
            fx {
                val workspace = WorkspaceManager.requireWorkspace()
                val frame = stage.scene.root as AppFrame
                var first = 0; var second = 0
                fun editor(feature: WorkspaceFeature, action: () -> Unit): Pair<VBox, MenuItem> {
                    val save = MenuItem("保存").apply { id = "save-${feature.name}" }
                    val actions = HBox(MenuButton("ファイル", null, save).apply { styleClass.add("editor-menu-button") },
                        MenuButton("編集").apply { styleClass.add("editor-menu-button") })
                    val root = VBox(actions, TextField())
                    workspace.show(feature, root)
                    ShortcutManager.register(root, ShortcutScope.EDITOR, EditorShortcut.SAVE) { action(); true }
                    save.setOnAction { ShortcutManager.execute(root, EditorShortcut.SAVE) }
                    workspace.editorMenus(root) { frame.useEditorMenus(actions) {} }
                    return root to save
                }
                val (a, saveA) = editor(WorkspaceFeature.ITEM_EDITOR) { first++ }
                val (b, saveB) = editor(WorkspaceFeature.RECIPE_EDITOR) { second++ }
                val menu = stage.scene.root.lookup("#applicationMenu") as MenuBar
                fun press(root: VBox) = Event.fireEvent(root, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.S, false, !OS.isMac, false, OS.isMac))
                workspace.focus(WorkspaceFeature.ITEM_EDITOR); press(a)
                assertEquals(1, first); assertEquals(0, second)
                assertSame(saveA, menu.menus.first().items.first())
                workspace.focus(WorkspaceFeature.RECIPE_EDITOR); press(b)
                assertEquals(1, first); assertEquals(1, second)
                assertSame(saveB, menu.menus.first().items.first())
                workspace.focus(WorkspaceFeature.ITEM_EDITOR); saveA.fire()
                assertEquals(2, first)
                workspace.show(WorkspaceFeature.DASHBOARD, VBox(Label("Dashboard")))
                workspace.focus(WorkspaceFeature.DASHBOARD); menu.requestFocus()
                assertFalse(ShortcutManager.execute(a, EditorShortcut.SAVE))
                assertFalse(menu.menus.first().items.any { it === saveA || it === saveB })
                assertEquals(2, first); assertEquals(1, second)
                ShortcutManager.unregister(a); ShortcutManager.unregister(b)
            }
        } finally { fx { stage.close() } }
    }

    @Test fun `退避完了までは領域を残し実closeだけが一度disposeする`() {
        startFx()
        val stage = fx { workspaceStage() }
        var disposed = 0
        try {
            fx {
                val workspace = WorkspaceManager.requireWorkspace()
                var completed: ((Boolean) -> Unit)? = null
                val root = VBox(TextField("未保存"))
                workspace.show(WorkspaceFeature.ITEM_EDITOR, root, close = { completed = it }, dispose = { disposed++ })
                assertTrue(workspace.close(WorkspaceFeature.ITEM_EDITOR))
                assertTrue(workspace.contains(root)); assertEquals(0, disposed)
                assertTrue(workspace.move(WorkspaceFeature.ITEM_EDITOR, WorkspaceFeature.HOME, DockSide.BOTTOM))
                assertTrue(workspace.contains(root)); assertEquals(0, disposed)
                completed!!(true)
                assertFalse(workspace.contains(root)); assertEquals(1, disposed)
                assertFalse(workspace.close(WorkspaceFeature.ITEM_EDITOR))
            }
        } finally { fx { stage.close() } }
        assertEquals(1, disposed)
    }

    @Test fun `再表示時に保存した分割比率とID順序選択を同じprofileへ復元する`() {
        startFx()
        fx {
            val previous = AppSettingsManager.load()
            val key = "workspace-test-profile"
            fun create(): Stage {
                val home = VBox(Label("ホーム"))
                val frame = AppFrame(AppScreen.HOME, home)
                lateinit var workspace: WorkspaceView
                workspace = WorkspaceView(frame, home, key, { feature -> workspace.show(feature, VBox(Label(feature.title))) }, { true }, {})
                frame.center = workspace
                return Stage().apply { scene = Scene(frame, 1300.0, 800.0); show() }
            }
            try {
                val first = create()
                val initial = WorkspaceManager.requireWorkspace()
                val item = VBox(TextField("保持"))
                initial.show(WorkspaceFeature.ITEM_EDITOR, item)
                initial.show(WorkspaceFeature.RECIPE_EDITOR, VBox(Label("レシピ")))
                initial.tabs(item, IdTabsState(listOf("beta", "alpha"), "alpha"))
                assertTrue(initial.move(WorkspaceFeature.RECIPE_EDITOR, WorkspaceFeature.ITEM_EDITOR, DockSide.BOTTOM))
                val split = initial.center as SplitPane
                split.setDividerPositions(0.3)
                val expected = initial.layout
                first.close()
                assertEquals(expected, AppSettingsManager.load().workspaceLayouts[key])
                val second = create()
                try {
                    val restored = WorkspaceManager.requireWorkspace().layout
                    fun compare(first: WorkspaceNode?, second: WorkspaceNode?) {
                        if (first is WorkspaceNode.Split && second is WorkspaceNode.Split) {
                            assertEquals(first.direction, second.direction)
                            assertEquals(first.ratio, second.ratio, 0.001)
                            compare(first.first, second.first); compare(first.second, second.second)
                        } else assertEquals(first, second)
                    }
                    compare(expected.root, restored.root)
                    assertEquals(expected.documents, restored.documents)
                    assertEquals(expected.active, restored.active)
                    assertEquals(3, WorkspaceManager.requireWorkspace().titles().size)
                } finally { second.close() }
            } finally { AppSettingsManager.save(previous) }
        }
    }

    @Test fun `Scene差替で旧workspaceを一度解放し新しいworkspaceを取得できる`() {
        startFx()
        fx {
            val stage = workspaceStage()
            var disposed = 0
            WorkspaceManager.requireWorkspace().show(WorkspaceFeature.DASHBOARD, VBox(Label("監視")), dispose = { disposed++ })
            stage.scene = Scene(VBox(Label("接続終了後")))
            assertNull(WorkspaceManager.current)
            assertEquals(1, disposed)
            val next = workspaceStage()
            try { assertNotNull(WorkspaceManager.current) }
            finally { next.close(); stage.close() }
            assertNull(WorkspaceManager.current)
            assertEquals(1, disposed)
        }
    }

    @Test fun `1400幅の初期四機能は大きい領域へ縦横分割し既存sidebarの隣へ本文を残す`() {
        startFx()
        val stage = fx { workspaceStage(width = 1400.0) }
        try {
            fx {
                val workspace = WorkspaceManager.requireWorkspace()
                val roots = mutableListOf<Parent>()
                val input = TextField("初期分割でも保持")
                for (feature in listOf(WorkspaceFeature.ITEM_EDITOR, WorkspaceFeature.ORE_EDITOR, WorkspaceFeature.RECIPE_EDITOR)) {
                    val root = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!)).load<Parent>()
                    workspace.show(feature, root)
                    roots += root
                    if (feature == WorkspaceFeature.ITEM_EDITOR) (root.lookup("#mainContentContainer") as HBox).children.add(input)
                }
                val panes = workspace.lookupAll(".workspace-feature").filterIsInstance<javafx.scene.layout.BorderPane>()
                assertEquals(4, panes.size)
                assertTrue(panes.all { it.width >= 630.0 && it.height >= 210.0 })
                for (root in roots) {
                    assertTrue((root.lookup("#sidebarContainer") as VBox).width >= 410.0)
                    assertTrue((root.lookup("#mainScroll") as ScrollPane).width > 100.0)
                }
                assertEquals("初期分割でも保持", input.text)
                assertSame(stage.scene, input.scene)
                val before = workspace.layout.root
                workspace.focus(WorkspaceFeature.ITEM_EDITOR)
                assertSame(before, workspace.layout.root)
                stage.width = 500.0
                stage.scene.root.applyCss(); stage.scene.root.layout()
                assertTrue(stage.width <= 501.0)
                assertEquals(4, workspace.titles().size)
            }
        } finally { fx { stage.close() } }
    }

    private fun startFx() {
        val isolated = System.getProperty("sushieric.test.configRoot") ?: error("隔離したGradle testを使用してください")
        assertTrue(Path.of(OS.dataConfigBase).toAbsolutePath().normalize().startsWith(Path.of(isolated).toAbsolutePath().normalize()))
        val ready = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
        catch (_: IllegalStateException) { Platform.runLater { Platform.setImplicitExit(false); ready.countDown() } }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
    }
    private fun <T> fx(action: () -> T): T {
        val task = FutureTask(action); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS)
    }
}
