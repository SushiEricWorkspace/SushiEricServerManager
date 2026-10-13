package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.ItemEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.ore.OreEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe.RecipeEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.shop.ShopEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptor
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.TextField
import javafx.stage.Stage
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@EnabledOnOs(OS.WINDOWS)
class EditorSidebarIntegrationTest {
    @Test
    fun `四種のエディターで実FXMLの検索と選択を接続し非表示IDの状態を保持する`() {
        checkEditor(EditorDataDescriptors.item, ::ItemEditorLogic)
        checkEditor(EditorDataDescriptors.ore, ::OreEditorLogic)
        checkEditor(EditorDataDescriptors.recipe, ::RecipeEditorLogic)
        checkEditor(EditorDataDescriptors.shop, ::ShopEditorLogic)
    }

    private fun <T : ManagedData<T, *>> checkEditor(
        descriptor: EditorDataDescriptor<T>,
        factory: (MainController, EditorDataService) -> EditorView<T>
    ) {
        val directory = createTempDirectory("sidebar-integration").toFile()
        val store = InMemoryEditorDataStore("sidebar-test")
        listOf("group.one", "group.two").forEach { id -> store.save(descriptor, id, descriptor.createDefault(id)) }
        val service = EditorDataService(store, directory)
        try {
            fx {
                val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
                val root = loader.load<Parent>()
                val main = loader.getController<MainController>()
                val stage = Stage().apply {
                    scene = Scene(root, 1560.0, 720.0).apply {
                        stylesheets.add(assertNotNull(javaClass.getResource(AppScreen.BASE.css)).toExternalForm())
                    }
                    show()
                }
                val logic = factory(main, service)
                try {
                    logic.setupSidebar(main.sidebarContainer, "group.one")
                    root.applyCss(); root.layout()
                    val sidebar = root.lookup(".sidebar-list") as EditorSidebar
                    val search = root.lookup(".sidebar-search-field") as TextField
                    assertEquals(2, sidebar.items.filterIsInstance<SidebarRow.Data>().size)
                    search.text = "TWO"
                    root.layout()
                    assertEquals(listOf("group.two"), sidebar.items.filterIsInstance<SidebarRow.Data>().map { it.fullId })
                    val button = root.lookupAll(".button").filterIsInstance<Button>().single { it.id == "group.two" } as Button
                    button.fire()
                    assertTrue(logic.sidebarState("group.two").selected)
                    search.text = "absent"
                    root.layout()
                    assertTrue(sidebar.items.isEmpty())
                    assertTrue(logic.sidebarState("group.two").selected)
                    search.text = ""
                    root.layout()
                    assertTrue(logic.sidebarState("group.two").selected)
                    assertTrue((root.lookupAll(".button").filterIsInstance<Button>().single { it.id == "group.two" } as Button).styleClass.contains("button-selected"))
                } finally { logic.onClose(); stage.close() }
            }
        } finally {
            service.itemReferences.close()
            service.closeIo()
            directory.deleteRecursively()
        }
    }

    private fun fx(action: () -> Unit) {
        val ready = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
        catch (_: IllegalStateException) { ready.countDown() }
        assertTrue(ready.await(20, TimeUnit.SECONDS))
        val task = FutureTask(action)
        Platform.runLater(task)
        task.get(30, TimeUnit.SECONDS)
    }
}
