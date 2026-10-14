package io.github.sushiericworkspace.sushiericservermanager.editor.component

import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.common.data.core.identity.VanillaBlockId
import io.github.sushiericworkspace.common.data.drop.model.mutable.MutableDropItemData
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.data.ore.model.mutable.MutableOreBaseData
import io.github.sushiericworkspace.common.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.common.data.recipe.model.mutable.MutableRecipeData
import io.github.sushiericworkspace.common.data.shop.model.mutable.MutableShopProductData
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.main.ore.OreEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe.RecipeEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.shop.ShopEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorView
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.ComboBox
import javafx.scene.control.DialogPane
import javafx.scene.control.Label
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** Windows上で実際のFXML、ウィンドウ、選択モーダルとキャッシュ共有を確認します。 */
@EnabledOnOs(OS.WINDOWS)
class ItemReferenceEditorsTest {
    @Test
    fun `三つのエディターと二回目の表示で再読込せず選択でき保存削除の通知が届く`() {
        val directory = createTempDirectory("item-reference-editors").toFile()
        val memory = InMemoryEditorDataStore()
        val reads = AtomicInteger()
        val store = object : EditorDataStore by memory {
            override fun <T : ManagedData<T, *>> list(descriptor: EditorDataDescriptor<T>): StoreResult<List<StoreResource>> {
                if (descriptor == EditorDataDescriptors.item) reads.incrementAndGet()
                return memory.list(descriptor)
            }
            override fun <T : ManagedData<T, *>> load(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<T> {
                if (descriptor == EditorDataDescriptors.item) reads.incrementAndGet()
                return memory.load(descriptor, id)
            }
        }
        val item = MutableItemBaseData(id = "items.first", internalId = ItemInternalId("shared-item"))
        memory.save(EditorDataDescriptors.item, item.id, item)
        memory.save(EditorDataDescriptors.shop, "shops.product", MutableShopProductData(id = "shops.product", itemInternalId = item.internalId, purchasePrice = 5))
        memory.save(EditorDataDescriptors.recipe, "recipe", MutableRecipeData(id = "recipe",
            ingredients = RecipeIngredients.Shaped(mapOf(0 to item.internalId)), resultItemInternalId = item.internalId))
        memory.save(EditorDataDescriptors.ore, "ore", MutableOreBaseData(id = "ore", blockId = VanillaBlockId("stone"),
            mutableDropItems = mutableListOf(MutableDropItemData(itemId = item.internalId))))
        val added = MutableItemBaseData(id = "items.added", internalId = ItemInternalId("added-item"))
        val service = EditorDataService(store, directory)
        val opened = mutableListOf<Pair<Stage, EditorView<*>>>()
        try {
            assertIs<StoreResult.Success<*>>(service.itemReferences.load().get(5, TimeUnit.SECONDS))
            onFxThread {
                fun open(factory: (MainController) -> EditorView<*>): Stage {
                    val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
                    val root = loader.load<Parent>()
                    val main = loader.getController<MainController>()
                    val logic = factory(main)
                    val stage = Stage().apply {
                        scene = Scene(root, 1100.0, 650.0).apply {
                            stylesheets.add(assertNotNull(javaClass.getResource(AppScreen.BASE.css)).toExternalForm())
                        }
                    }
                    opened += stage to logic
                    main.switchView(logic)
                    stage.show()
                    root.applyCss(); root.layout()
                    return stage
                }
                open { ShopEditorLogic(it, service) }
                open { RecipeEditorLogic(it, service) }
                open { OreEditorLogic(it, service) }
                open { ShopEditorLogic(it, service) }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var ready = false
            while (!ready) {
                onFxThread {
                    ready = opened[0].first.scene.root.lookupAll(".button").filterIsInstance<Button>().any { it.text == "アイテムを選択..." } &&
                        opened[2].first.scene.root.lookupAll(".button").filterIsInstance<Button>().any { it.text.startsWith("ドロップ") } &&
                        opened[3].first.scene.root.lookupAll(".label").filterIsInstance<Label>().any { it.text == "items.first" } &&
                        opened[1].first.scene.lookup("#recipe-result") != null &&
                        !(opened[1].first.scene.lookup("#recipe-result") as Button).isDisable
                }
                assertTrue(System.nanoTime() < deadline, "エディターの初期読み込みが完了しません")
                if (!ready) Thread.sleep(20)
            }
            onFxThread {
                val shop = opened[0].first
                val recipe = opened[1].first
                val ore = opened[2].first
                val secondShop = opened[3].first
                assertFalse(assertIs<Button>(recipe.scene.lookup("#recipe-result")).isDisable)
                assertTrue(secondShop.scene.root.lookupAll(".label").filterIsInstance<Label>().any { it.text == "items.first" }, secondShop.scene.root.lookupAll(".label").filterIsInstance<Label>().map { it.text }.toString())
                assertEquals(2, reads.get())

                var failure: Throwable? = null
                fun cancelDialog(check: (DialogPane) -> Unit) {
                    Platform.runLater {
                        val pane = Window.getWindows().mapNotNull { it.scene?.root as? DialogPane }.single()
                        try { check(pane) } catch (e: Throwable) { failure = e }
                        finally { assertIs<Button>(pane.lookupButton(ButtonType.CANCEL)).fire() }
                    }
                }
                cancelDialog { assertNotNull(it.lookup("#item-selection-tree")) }
                assertIs<Button>(recipe.scene.lookup("#recipe-result")).fire()
                cancelDialog { pane ->
                    assertEquals(listOf(item.internalId), assertIs<ComboBox<*>>(pane.lookup(".combo-box")).items.toList())
                }
                shop.scene.root.lookupAll(".button").filterIsInstance<Button>().single { it.text == "アイテムを選択..." }.fire()
                Platform.runLater {
                    val dialog = Window.getWindows().filterIsInstance<Stage>().single { it !in opened.map { entry -> entry.first } }
                    try {
                        val choices = assertIs<ComboBox<*>>(dialog.scene.lookup(".combo-box")).items
                        assertEquals(listOf(item.internalId), choices.filterIsInstance<DropItemChoice>().map { it.internalId })
                    } catch (e: Throwable) { failure = e }
                    finally { dialog.close() }
                }
                ore.scene.root.lookupAll(".button").filterIsInstance<Button>().single { it.text.startsWith("ドロップ") }.fire()
                failure?.let { throw it }
                assertEquals(2, reads.get())
                assertIs<StoreResult.Success<Unit>>(service.items.saveStore(added.id, added))
                service.items.renameTo("items.first", "items.renamed")
            }
            onFxThread {
                val recipe = opened[1].first
                assertEquals("items.renamed", assertIs<Button>(recipe.scene.lookup("#recipe-result")).text)
                val shop = opened.first().first
                assertTrue(shop.scene.root.lookupAll(".label").filterIsInstance<Label>().any { it.text == "items.renamed" })
                var failure: Throwable? = null
                Platform.runLater {
                    val pane = Window.getWindows().mapNotNull { it.scene?.root as? DialogPane }.single()
                    try {
                        assertEquals(listOf(added.internalId, item.internalId), assertIs<ComboBox<*>>(pane.lookup(".combo-box")).items.toList())
                    } catch (e: Throwable) { failure = e }
                    finally { assertIs<Button>(pane.lookupButton(ButtonType.CANCEL)).fire() }
                }
                shop.scene.root.lookupAll(".button").filterIsInstance<Button>().single { it.text == "アイテムを選択..." }.fire()
                failure?.let { throw it }
                service.items.delete("items.renamed")
            }
            onFxThread {
                assertTrue(assertIs<Button>(opened[1].first.scene.lookup("#recipe-result")).text.startsWith("参照先不明"))
                assertTrue(opened.first().first.scene.root.lookupAll(".label").filterIsInstance<Label>().any { it.text == "参照先不明" })
                assertEquals(2, reads.get())
            }
        } finally {
            val closed = opened.map { (stage, logic) ->
                CompletableFuture<Boolean>().also { done ->
                    onFxThread { logic.onClose { success -> stage.close(); done.complete(success) } }
                }
            }
            closed.forEach { assertTrue(it.get(10, TimeUnit.SECONDS)) }
            service.closeIo()
            service.itemReferences.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `購読解除した画面へ遅延通知を送らない`() {
        val service = EditorDataService(InMemoryEditorDataStore())
        var notifications = 0
        onFxThread {
            val binding = ItemReferenceBinding(service.itemReferences, { notifications++ }, { error("読込失敗") })
            service.items.saveStore("first", MutableItemBaseData(id = "first"))
            binding.close()
        }
        onFxThread { assertEquals(0, notifications) }
        service.itemReferences.close()
    }

    @Test
    fun `同一FX処理中の共有通知をまとめて適用する`() {
        val service = EditorDataService(InMemoryEditorDataStore())
        var notifications = 0
        lateinit var binding: ItemReferenceBinding
        try {
            onFxThread {
                binding = ItemReferenceBinding(service.itemReferences, { notifications++ }, { error("読込失敗") })
                repeat(20) { service.items.saveStore("item$it", MutableItemBaseData(id = "item$it")) }
                assertEquals(0, notifications)
            }
            onFxThread { assertEquals(1, notifications); binding.close() }
        } finally { service.itemReferences.close(); service.closeIo() }
    }

    @Test
    fun `背景失敗をモーダル通知せず再表示で自動再試行しない`() {
        val reads = AtomicInteger()
        val memory = InMemoryEditorDataStore()
        val service = EditorDataService(object : EditorDataStore by memory {
            override fun <T : ManagedData<T, *>> list(descriptor: EditorDataDescriptor<T>): StoreResult<List<StoreResource>> {
                reads.incrementAndGet()
                return StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR))
            }
        })
        var failures = 0
        lateinit var binding: ItemReferenceBinding
        try {
            onFxThread { binding = ItemReferenceBinding(service.itemReferences, {}, { failures++ }); binding.load() }
            service.submitIo {}.get(10, TimeUnit.SECONDS)
            onFxThread { assertEquals(0, failures); binding.load(); assertEquals(1, reads.get()); binding.load(force = true) }
            service.submitIo {}.get(10, TimeUnit.SECONDS)
            onFxThread { assertEquals(1, failures); assertEquals(2, reads.get()); binding.close() }
        } finally { service.itemReferences.close(); service.closeIo() }
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
