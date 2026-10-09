package io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe

import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.common.data.recipe.model.RecipeSize
import io.github.sushiericworkspace.common.data.recipe.model.mutable.MutableRecipeData
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.TextField
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.DialogPane
import javafx.scene.control.TreeView
import javafx.scene.control.TreeCell
import javafx.stage.Window
import javafx.scene.paint.Color
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.view.SidebarTreeNode
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.ItemTreeSelectionDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.ItemTreeSelection
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.createRecipeSaveContent
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

/** WindowsのJavaFXでFXMLと入力を確認します。実サーバーやユーザーの保存データには触れません。 */
@EnabledOnOs(OS.WINDOWS)
class RecipeEditorViewTest {
    @Test
    fun `FXMLをロードして選択ボタンを表示し盤サイズと完成数を編集できる`() = onFxThread {
        val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
        val root = loader.load<Parent>()
        val main = loader.getController<MainController>()
        val scene = Scene(root, 1560.0, 720.0)
        scene.stylesheets.add(assertNotNull(javaClass.getResource(AppScreen.BASE.css)).toExternalForm())
        val logic = RecipeEditorLogic(main, EditorDataService(InMemoryEditorDataStore()))
        val ingredient = ItemInternalId("test-ingredient")
        val data = MutableRecipeData(id = "test.recipe",
            ingredients = RecipeIngredients.Shaped(mapOf(0 to ingredient, 24 to ingredient)))
        RecipeEditorLogic::class.java.getDeclaredMethod("setupMainContent", MutableRecipeData::class.java)
            .apply { isAccessible = true }.invoke(logic, data)
        root.applyCss(); root.layout()
        val material = assertIs<Button>(root.lookup("#recipe-ingredient-0"))
        assertTrue(material.text.contains("test-ingredient"))
        val result = assertIs<Button>(root.lookup("#recipe-result"))
        assertEquals("アイテムを選択...", result.text)
        val count = assertIs<TextField>(root.lookup("#recipe-count"))
        count.text = "2"
        assertEquals(2, data.resultCount)
        count.text = "9999999999999999"
        assertEquals(2, data.resultCount)
        @Suppress("UNCHECKED_CAST")
        val size = root.lookup("#recipe-size") as ComboBox<RecipeSize>
        size.value = RecipeSize.FIVE_BY_FIVE
        root.applyCss(); root.layout()
        assertNotNull(root.lookup("#recipe-ingredient-24"))
        val end = assertIs<Button>(root.lookup("#recipe-ingredient-24"))
        assertTrue(end.text.contains("test-ingredient"))
        val shaped = assertIs<CheckBox>(root.lookup("#recipe-shaped"))
        shaped.fire()
        assertEquals(listOf(ItemInternalId("test-ingredient"), ItemInternalId("test-ingredient")), assertIs<RecipeIngredients.Shapeless>(data.ingredients).items)
    }

    @Test
    fun `検証中の内容全体に暗色背景が適用される`() = onFxThread {
        val content = createRecipeSaveContent()
        val scene = Scene(content)
        scene.stylesheets.add(assertNotNull(javaClass.getResource(AppScreen.WIDGETS_ONLY.css)).toExternalForm())
        content.applyCss()
        val color = assertIs<Color>(assertNotNull(content.background).fills.single().fill)
        assertTrue(color.red < 0.3 && color.green < 0.3 && color.blue < 0.3)
    }

    @Test
    fun `アイテム選択で階層と現在の選択を表示し選択解除とキャンセルを区別する`() = onFxThread {
        val item = MutableItemBaseData(id = "weapons.swords.test", internalId = ItemInternalId("selected-item"))
        var interactionFailure: Throwable? = null
        fun act(buttonText: String, check: (DialogPane) -> Unit = {}) {
            Platform.runLater {
                val pane = Window.getWindows().mapNotNull { it.scene?.root as? DialogPane }.single()
                try {
                    check(pane)
                    val type = pane.buttonTypes.single { it.text == buttonText }
                    assertIs<Button>(pane.lookupButton(type)).fire()
                } catch (error: Throwable) {
                    interactionFailure = error
                    assertIs<Button>(pane.lookupButton(ButtonType.CANCEL)).fire()
                }
            }
        }
        act("選択") { pane ->
            @Suppress("UNCHECKED_CAST")
            val tree = pane.lookup("#item-selection-tree") as TreeView<SidebarTreeNode>
            pane.applyCss(); pane.layout()
            val treeColor = assertIs<Color>(tree.background.fills.last().fill)
            assertTrue(treeColor.red < 0.3 && treeColor.green < 0.3 && treeColor.blue < 0.3)
            val cell = tree.lookupAll(".tree-cell").filterIsInstance<TreeCell<*>>().first { it.isSelected && !it.isEmpty }
            val textColor = assertIs<Color>(cell.textFill)
            assertTrue(textColor.red > 0.8 && textColor.green > 0.8 && textColor.blue > 0.8)
            val selectionColor = assertIs<Color>(cell.background.fills.last().fill)
            assertEquals(Color.web("#2f3235"), selectionColor)
            val weapons = tree.root.children.single()
            assertEquals("weapons", weapons.value.name)
            val swords = weapons.children.single()
            assertEquals("swords", swords.value.name)
            assertEquals("test", swords.children.single().value.name)
            assertEquals(swords.children.single(), tree.selectionModel.selectedItem)
            assertIs<TextField>(pane.lookup("#item-selection-search")).text = "swords.test"
            assertEquals(1, tree.root.children.size)
        }
        assertEquals(ItemTreeSelection.Selected(item.internalId), ItemTreeSelectionDialog.show(null, listOf(item), item.internalId))
        act("選択を解除")
        assertEquals(ItemTreeSelection.Cleared, ItemTreeSelectionDialog.show(null, listOf(item), item.internalId))
        act(ButtonType.CANCEL.text)
        assertNull(ItemTreeSelectionDialog.show(null, listOf(item), ItemInternalId("missing")))
        interactionFailure?.let { throw it }
    }

    private fun onFxThread(action: () -> Unit) {
        val started = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); started.countDown() } }
        catch (_: IllegalStateException) { started.countDown() }
        assertTrue(started.await(20, TimeUnit.SECONDS), "JavaFXを起動できませんでした")
        val task = FutureTask(action)
        Platform.runLater(task)
        task.get(20, TimeUnit.SECONDS)
    }
}
