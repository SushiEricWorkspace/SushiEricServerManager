package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.data.core.identity.VanillaBlockId
import io.github.sushiericworkspace.common.data.drop.model.mutable.MutableDropItemData
import io.github.sushiericworkspace.common.data.ore.model.mutable.MutableOreBaseData
import io.github.sushiericworkspace.common.data.shop.model.mutable.MutableShopProductData
import io.github.sushiericworkspace.common.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.common.data.recipe.model.mutable.MutableRecipeData
import io.github.sushiericworkspace.sushiericservermanager.communication.managed.*
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.PendingStoreOperationKind
import io.github.sushiericworkspace.sushiericservermanager.editor.service.PendingStoreOperationRecord
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask
import java.util.concurrent.CompletableFuture
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.stage.Stage
import javafx.scene.Scene
import javafx.scene.layout.VBox
import javafx.animation.Timeline
import javafx.animation.KeyFrame
import javafx.util.Duration
import javafx.stage.Window
import javafx.scene.control.DialogPane
import javafx.scene.control.ButtonBar
import javafx.scene.control.Button
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorView
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ManagedEditorDataStoreTest {
    @Test fun `管理接続の一括保存は共有工程を使い保存と一覧取得を背景実行する`() {
        Assumptions.assumeTrue(System.getProperty("os.name").let { it.startsWith("Windows") || it.startsWith("Mac") })
        val ready = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
        catch (_: IllegalStateException) { Platform.runLater { ready.countDown() } }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        fun <R> fx(action: () -> R): R {
            val task = FutureTask(action)
            Platform.runLater(task)
            return task.get(15, TimeUnit.SECONDS)
        }
        val directory = createTempDirectory("managed-batch-save-")
        val peer = StorePeer(directory)
        val service = EditorDataService(peer.store(), directory.toFile())
        val descriptor = EditorDataDescriptors.item
        val bases = listOf("first", "second").map { MutableItemBaseData(id = it).apply { display.displayName = "original" } }
        bases.forEach { assertIs<StoreResult.Success<Unit>>(service.items.saveStore(it.id, it).also { result -> assertTrue(result is StoreResult.Success, result.toString()) }) }
        val completed = CompletableFuture<EditorView.BatchSaveResult>()
        val fixture = fx {
            val loader = FXMLLoader(javaClass.getResource("/fxml/main/base.fxml"))
            val root = loader.load<Parent>()
            val main = loader.getController<MainController>()
            val stage = Stage().apply { scene = Scene(root); show() }
            val logic = object : EditorView<MutableItemBaseData>(main, service, service.items) {
                init {
                    bases.forEach { base ->
                        originalDataMap[base.id] = base.deepCopy()
                        editingDataMap[base.id] = base.deepCopy().apply { display.displayName = "changed" }
                    }
                    currentSelectedDataId = "first"
                }
                override fun setupSidebar(container: VBox, selectId: String?) {
                    assertTrue(Platform.isFxApplicationThread())
                    assertTrue(sidebarResources().second)
                    assertIs<StoreResult.Success<*>>(sidebarDirectoryList())
                }
                override fun setupMainContent(selectData: MutableItemBaseData) = Unit
                override fun resolveSaveConflict(dataId: String, originalData: MutableItemBaseData, currentData: MutableItemBaseData, serverData: MutableItemBaseData) = currentData
            }
            val timer = Timeline(KeyFrame(Duration.millis(20.0), {
                Platform.runLater {
                    Window.getWindows().toList().mapNotNull { it.scene?.root as? DialogPane }.forEach { pane ->
                        val type = pane.buttonTypes.firstOrNull { it.buttonData == ButtonBar.ButtonData.OK_DONE } ?: pane.buttonTypes.first()
                        (pane.lookupButton(type) as Button).fire()
                    }
                }
            })).apply { cycleCount = Timeline.INDEFINITE; play() }
            Triple(logic, stage, timer)
        }
        try {
            peer.ioThreads.clear()
            fx { fixture.first.onSaveAll { completed.complete(it) } }
            val result = completed.get(15, TimeUnit.SECONDS)
            assertEquals(EditorView.SaveOutcome.SAVED, result.outcome)
            assertEquals(listOf("first", "second"), result.saved)
            assertTrue(peer.ioThreads.isNotEmpty())
            assertTrue(peer.ioThreads.none { it })
            bases.forEach { base -> assertEquals("changed", assertIs<StoreResult.Success<MutableItemBaseData>>(service.items.loadStore(base.id)).value.display.displayName) }
        } finally {
            fx { fixture.third.stop(); fixture.first.onClose(); fixture.second.close() }
            service.closeIo()
            service.itemReferences.close()
            directory.toFile().deleteRecursively()
        }
    }

    @Test fun `同じpublicIDの異なる管理rootをCRUDとautoSave pending cache offlineで隔離する`() {
        val root = createTempDirectory("managed-store-profiles-")
        try {
            val first = StorePeer(root.resolve("first"))
            val second = StorePeer(root.resolve("second"))
            val a = first.store()
            val b = second.store()
            val descriptor = EditorDataDescriptors.item
            val sword = MutableItemBaseData(id = "shared").apply { display.displayName = "first" }
            val axe = MutableItemBaseData(id = "shared").apply { display.displayName = "second" }
            assertIs<StoreResult.Success<Unit>>(a.save(descriptor, "shared", sword))
            assertIs<StoreResult.Success<Unit>>(b.save(descriptor, "shared", axe))
            assertEquals("first", assertIs<StoreResult.Success<MutableItemBaseData>>(a.load(descriptor, "shared")).value.display.displayName)
            assertEquals("second", assertIs<StoreResult.Success<MutableItemBaseData>>(b.load(descriptor, "shared")).value.display.displayName)
            assertNotEquals(a.identity, b.identity)
            assertNotEquals(a.session.cacheDirectory, b.session.cacheDirectory)
            val aService = EditorDataService(a, a.session.autoSaveDirectory)
            val bService = EditorDataService(b, b.session.autoSaveDirectory)
            for ((service, data) in listOf(aService to sword, bService to axe)) {
                assertTrue(service.items.saveToLocalBackup("editing", data))
                assertTrue(service.items.saveToLocalBackup("original", data))
                assertTrue(service.items.savePendingStoreOperations(listOf(PendingStoreOperationRecord(PendingStoreOperationKind.CREATE, "shared"))))
            }
            aService.items.deleteLocalBackup("shared")
            assertTrue(aService.items.savePendingStoreOperations(emptyList()))
            assertNull(aService.items.loadBackupPair("shared"))
            assertEquals("second", bService.items.loadBackupPair("shared")!!.first.display.displayName)
            assertEquals(1, bService.items.loadPendingStoreOperations().size)
            a.session.offlineDirectory.mkdirs()
            a.session.offlineDirectory.resolve("only-first.yml").writeText("first")
            assertFalse(b.session.offlineDirectory.resolve("only-first.yml").exists())
            assertIs<StoreResult.Success<Unit>>(a.rename(descriptor, "shared", "renamed"))
            val renamed = assertIs<StoreResult.Success<MutableItemBaseData>>(a.load(descriptor, "renamed")).value
            assertEquals("renamed", renamed.id)
            assertEquals(sword.internalId, renamed.internalId)
            assertIs<StoreResult.Success<MutableItemBaseData>>(b.load(descriptor, "shared"))
            assertIs<StoreResult.Success<Unit>>(a.delete(descriptor, "renamed"))
            assertFalse(first.files.containsKey("item_data/stats/renamed.yml"))
            assertTrue(second.files.containsKey("item_data/stats/shared.yml"))
            assertFalse(first.fixture.profile.root.toFile().exists(), "Storeは管理rootを直接作成しない")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `rename衝突の作用前拒否は再保存可能で結果不明は成功やqueue消去にならない`() {
        val root = createTempDirectory("managed-store-failure-")
        try {
            val peer = StorePeer(root)
            val store = peer.store()
            val descriptor = EditorDataDescriptors.item
            val item = MutableItemBaseData(id = "first").apply { display.displayName = "Sword" }
            assertIs<StoreResult.Success<Unit>>(store.save(descriptor, "first", item))
            peer.files["item_data/stats/taken.yml"] = peer.files.getValue("item_data/stats/first.yml")
            assertEquals(StoreErrorCode.ALREADY_EXISTS, assertIs<StoreResult.Failure>(store.rename(descriptor, "first", "taken")).error.code)
            assertEquals(ManagedSession.State.OPEN, store.session.state)
            assertIs<StoreResult.Success<Unit>>(store.save(descriptor, "first", item))
            val service = EditorDataService(store, store.session.autoSaveDirectory)
            val pending = PendingStoreOperationRecord(PendingStoreOperationKind.CREATE, "first")
            assertTrue(service.items.savePendingStoreOperations(listOf(pending)))
            peer.failure = SupervisorFailure("RESULT_UNKNOWN")
            assertIs<StoreResult.Failure>(service.items.saveStore("first", item))
            assertEquals(listOf(pending), service.items.loadPendingStoreOperations())
            assertEquals(ManagedSession.State.UNKNOWN, store.session.state)
            assertFailsWith<IllegalStateException> { store.session.close {} }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `互換性readOnlyと不正IDではsupervisor変更要求を送らない`() {
        val root = createTempDirectory("managed-store-permission-")
        try {
            val peer = StorePeer(root)
            val session = peer.session()
            val store = ManagedEditorDataStore(session, ManagerCompatibility.UpdateRequired("99.0.0"))
            val before = peer.fixture.commands.size
            assertEquals(StoreErrorCode.PERMISSION_DENIED, assertIs<StoreResult.Failure>(store.writeText("config.yml", "secret")).error.code)
            assertEquals(StoreErrorCode.INVALID_ID, assertIs<StoreResult.Failure>(store.delete(EditorDataDescriptors.item, "../escape")).error.code)
            assertEquals(before, peer.fixture.commands.size)
            assertEquals(ManagedSession.State.OPEN, session.state)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `アイテム保存では列挙と読込を要求せず本体検証とYAMLを維持する`() {
        val root = createTempDirectory("managed-save-item-")
        try {
            val peer = StorePeer(root)
            val store = peer.store()
            val descriptor = EditorDataDescriptors.item
            val item = descriptor.createDefault("test")
            val before = peer.fixture.commands.size
            assertIs<StoreResult.Success<Unit>>(store.save(descriptor, item.id, item))
            assertTrue(item.completed)
            val io = peer.fixture.commands.drop(before).filter { it.first == "writer-io" }
            assertEquals(listOf("write"), io.map { it.second.getValue("request").jsonObject.getValue("action").jsonPrimitive.content })
            assertEquals(item.freeze(), assertIs<StoreResult.Success<MutableItemBaseData>>(store.load(descriptor, item.id)).value.freeze())
            val after = peer.fixture.commands.size
            item.display.displayName = " "
            assertEquals(StoreErrorCode.VALIDATION_FAILED, assertIs<StoreResult.Failure>(store.save(descriptor, item.id, item)).error.code)
            assertFalse(item.completed)
            assertEquals(after, peer.fixture.commands.size)
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `鉱石とショップの参照削除およびレシピ重複を最新定義で検出する`() {
        val root = createTempDirectory("managed-save-references-")
        try {
            val peer = StorePeer(root)
            val store = peer.store()
            val item = EditorDataDescriptors.item.createDefault("reference")
            assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.item, item.id, item))
            val ore = MutableOreBaseData(id = "ore", blockId = VanillaBlockId("iron_ore"),
                mutableDropItems = mutableListOf(MutableDropItemData(itemId = item.internalId)))
            val shop = MutableShopProductData("weapons.product", item.internalId, 100, 50, 12, true, 3)
            assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.ore, ore.id, ore))
            assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.shop, shop.id, shop))
            assertTrue(ore.completed)
            assertTrue(shop.completed)
            val recipe = MutableRecipeData(id = "recipe",
                ingredients = RecipeIngredients.Shaped(mapOf(0 to item.internalId)), resultItemInternalId = item.internalId)
            assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.recipe, recipe.id, recipe))
            assertTrue(recipe.completed)
            val duplicate = recipe.copy(id = "duplicate")
            assertEquals(StoreErrorCode.VALIDATION_FAILED,
                assertIs<StoreResult.Failure>(store.save(EditorDataDescriptors.recipe, duplicate.id, duplicate)).error.code)
            assertIs<StoreResult.Success<Unit>>(store.delete(EditorDataDescriptors.item, item.id))
            assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.ore, ore.id, ore))
            assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.shop, shop.id, shop))
            assertFalse(ore.completed)
            assertFalse(shop.completed)
        } finally { root.toFile().deleteRecursively() }
    }

    private class StorePeer(private val storage: Path) : SupervisorClient {
        val fixture = ManagedSessionTest.Fixture(storage)
        val files = mutableMapOf<String, String>()
        val ioThreads = mutableListOf<Boolean>()
        var failure: SupervisorFailure? = null
        fun session() = ManagedSession.open(fixture.profile, storage, this)
        fun store() = ManagedEditorDataStore(session(), ManagerCompatibility.Compatible("0.1.0"))
        override fun request(command: String, payload: JsonObject, operationId: String): JsonObject {
            if (command != "writer-io") return fixture.request(command, payload, operationId)
            ioThreads += Platform.isFxApplicationThread()
            fixture.commands += command to payload
            failure?.let { throw it }
            val request = payload.getValue("request").jsonObject
            val path = request.getValue("path").jsonPrimitive.content
            return when (request.getValue("action").jsonPrimitive.content) {
                "read" -> buildJsonObject { put("text", files[path] ?: throw SupervisorFailure("FILE_NOT_FOUND", rejectedBeforeEffect = true)) }
                "list" -> buildJsonObject {
                    putJsonArray("entries") {
                        files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.distinct().forEach { name ->
                            add(buildJsonObject { put("name", name); put("isDirectory", files.keys.any { it.startsWith("$path/$name/") }) })
                        }
                    }
                }
                "write" -> { files[path] = request.getValue("text").jsonPrimitive.content; buildJsonObject { put("completed", true) } }
                "move" -> {
                    val destination = request.getValue("destination").jsonPrimitive.content
                    if (destination in files) throw SupervisorFailure("ALREADY_EXISTS", rejectedBeforeEffect = true)
                    files[destination] = request["text"]?.jsonPrimitive?.content ?: files.getValue(path)
                    files.remove(path); buildJsonObject { put("completed", true) }
                }
                "delete" -> { files.remove(path); buildJsonObject { put("completed", true) } }
                else -> error("試験対象外")
            }
        }
    }
}
