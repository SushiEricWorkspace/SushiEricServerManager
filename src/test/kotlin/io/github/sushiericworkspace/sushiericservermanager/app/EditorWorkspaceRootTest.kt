package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceManager
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceView
import io.github.sushiericworkspace.sushiericservermanager.config.WorkspaceFeature
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.ItemEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.ore.OreEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.shop.ShopEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe.RecipeEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.fx
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.startFx
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorView
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.EditorShortcut
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.ShortcutManager
import javafx.event.Event
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.control.ScrollPane
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.PickResult
import javafx.scene.layout.BorderPane
import javafx.scene.layout.VBox
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.io.File
import org.junit.jupiter.api.io.TempDir
import kotlin.test.*

class EditorWorkspaceRootTest {
    @TempDir lateinit var directory: File

    private data class Editor(
        val feature: WorkspaceFeature, val root: Parent, val main: MainController,
        val logic: EditorView<*>, val items: List<MenuItem>, val target: Any,
        val actions: Map<*, *>
    )

    @Test fun `実FXMLの四Editorはクリックと機能切替後も共通メニューと操作宛先を保持する`() {
        startFx()
        val service = EditorDataService(InMemoryEditorDataStore(), directory)
        val editors = mutableListOf<Editor>()
        val (scene, workspace) = fx {
            val home = VBox()
            val frame = AppFrame(AppScreen.HOME, home)
            val workspace = WorkspaceView(frame, home, null, {}, { true }, {})
            frame.center = workspace
            val scene = Scene(frame)
            WorkspaceManager.acquire(workspace)
            scene to workspace
        }
        try {
            fx {
                val factories: List<Pair<WorkspaceFeature, (MainController) -> EditorView<*>>> = listOf(
                    WorkspaceFeature.ITEM_EDITOR to { ItemEditorLogic(it, service) },
                    WorkspaceFeature.ORE_EDITOR to { OreEditorLogic(it, service) },
                    WorkspaceFeature.SHOP_EDITOR to { ShopEditorLogic(it, service) },
                    WorkspaceFeature.RECIPE_EDITOR to { RecipeEditorLogic(it, service) }
                )
                for ((feature, factory) in factories) {
                    val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
                    val root = loader.load<Parent>()
                    val main = loader.getController<MainController>()
                    val logic = factory(main)
                    workspace.show(feature, root)
                    logic.setupActions(main.actionButtonContainer)
                    scene.root.applyCss()
                    val file = (scene.lookup("#applicationMenu") as MenuBar).menus.first()
                    val ids = setOf("action-save", "action-save_all", "action-sync", "action-create_data", "action-upload")
                    val items = file.items.filter { it.id in ids || it.text?.contains("結果を確認・読戻し") == true }
                    assertEquals(ids, items.mapNotNull { it.id }.toSet())
                    assertEquals(ids.size + 1, items.size)
                    val state = shortcutState(scene)
                    val target = assertNotNull(assertIs<Map<*, *>>(field(state, "targets"))[main.shortcutRoot])
                    val actions = assertIs<Map<*, *>>(field(target, "actions")).toMap()
                    assertTrue(actions.keys.containsAll(listOf(EditorShortcut.SAVE, EditorShortcut.SYNC,
                        EditorShortcut.CREATE_DATA, EditorShortcut.UPLOAD)))
                    editors += Editor(feature, root, main, logic, items, target, actions)
                }
                for (editor in editors) {
                    workspace.focus(editor.feature)
                    checkSelection(scene, editor)
                    click(editor.main.mainContentContainer, MouseEvent.MOUSE_PRESSED)
                    checkSelection(scene, editor)
                    workspace.focus(WorkspaceFeature.HOME)
                    assertNull(field(shortcutState(scene), "active"))
                    assertTrue((scene.lookup("#applicationMenu") as MenuBar).menus.first().items.none { it in editor.items })
                    workspace.focus(editor.feature)
                    checkSelection(scene, editor)
                    val heading = (editor.root.parent as BorderPane).top
                    click(heading, MouseEvent.MOUSE_CLICKED)
                    checkSelection(scene, editor)
                    assertSame(editor.root, editor.main.shortcutRoot)
                    assertIs<ScrollPane>(editor.root)
                    assertTrue(workspace.contains(editor.root))
                }
                assertNull(scene.window, "Stageを生成・表示せず検証すること")
            }
        } finally {
            for (editor in editors) {
                val closed = CompletableFuture<Boolean>()
                fx { editor.logic.onClose { closed.complete(it) } }
                assertTrue(closed.get(15, TimeUnit.SECONDS))
            }
            fx {
                (scene.root as AppFrame).center = null
                scene.root = VBox()
                WorkspaceManager.release(workspace)
            }
            service.closeIo()
        }
    }

    private fun checkSelection(scene: Scene, editor: Editor) {
        val items = (scene.lookup("#applicationMenu") as MenuBar).menus.first().items
        editor.items.forEach { expected -> assertTrue(items.any { it === expected }, editor.feature.name + ": " + expected.text) }
        val active = field(shortcutState(scene), "active")
        assertSame(editor.target, active)
        assertSame(editor.main.shortcutRoot, field(assertNotNull(active), "root"))
        assertEquals(editor.actions, field(active, "actions"))
    }

    // 非表示Sceneでは実行ガードが作用するため、実登録した操作と選択先の同一性を照合する。
    private fun shortcutState(scene: Scene): Any =
        assertNotNull(scene.properties[field(ShortcutManager, "sceneKey")])

    private fun field(owner: Any, name: String): Any? =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun click(node: javafx.scene.Node, type: javafx.event.EventType<MouseEvent>) {
        Event.fireEvent(node, MouseEvent(type, 1.0, 1.0, 1.0, 1.0, MouseButton.PRIMARY, 1,
            false, false, false, false, type == MouseEvent.MOUSE_PRESSED, false, false, false, false, true,
            PickResult(node, 1.0, 1.0)))
    }
}
