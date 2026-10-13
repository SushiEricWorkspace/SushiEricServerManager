package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.ItemEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.ore.OreEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.shop.ShopEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe.RecipeEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import javafx.animation.Timeline
import javafx.animation.KeyFrame
import javafx.util.Duration
import javafx.scene.control.DialogPane
import javafx.scene.control.Button
import javafx.stage.Window
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.stage.Stage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class EditorLoadingViewTest {
    @Test
    fun `4種別の一覧取得中にも画面が表示されUIを操作できる`() {
        checkStartup(EditorDataDescriptors.item)
        checkStartup(EditorDataDescriptors.ore)
        checkStartup(EditorDataDescriptors.shop)
        checkStartup(EditorDataDescriptors.recipe)
    }

    private fun <T : ManagedData<T, *>> checkStartup(descriptor: EditorDataDescriptor<T>) {
        fixture(descriptor) { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.store.beforeList = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
            try {
                fx { f.main.switchView(f.view) }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                fx {
                    assertTrue(f.stage.isShowing)
                    assertTrue(f.main.loadingRunning)
                    assertTrue(f.main.mainContentContainer.isDisabled)
                    assertTrue((f.root.lookup("#topInfoLabel") as Label).text.contains("一覧"))
                }
                release.countDown()
                awaitFx { f.view.shown.isNotEmpty() }
                fx { assertFalse(f.main.loadingRunning) }
                assertTrue(f.store.ioThreads.isNotEmpty())
                assertFalse(f.store.ioThreads.any { it })
            } finally { release.countDown() }
        }
    }

    @Test
    fun `遅延したAを選択後Bへ変更するとAを反映しない`() {
        fixture(EditorDataDescriptors.ore) { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.store.beforeLoad = { id -> if (id == "a") { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) } }
            try {
                fx { f.view.selectTab("a") }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                fx {
                    f.view.selectTab("b")
                    assertFalse(f.main.sidebarContainer.isDisabled)
                    assertTrue(f.main.mainContentContainer.isDisabled)
                    assertTrue(f.view.shown.isEmpty())
                    assertEquals(EditorView.SaveOutcome.BUSY, f.view.saveOutcome())
                }
                release.countDown()
                awaitFx { f.view.shown == listOf("b") }
                fx { assertNull(f.view.cached("a")); assertFalse(f.main.loadingRunning) }
            } finally { release.countDown() }
        }
    }

    @Test
    fun `選択取得中に閉じても後からキャッシュや画面を更新しない`() {
        fixture(EditorDataDescriptors.ore) { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.store.beforeLoad = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
            try {
                fx { f.view.selectTab("a") }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                fx { assertTrue(f.view.onClose()); f.stage.close() }
                release.countDown()
                f.service.submitIo {}.get(10, TimeUnit.SECONDS)
                fx { assertTrue(f.view.shown.isEmpty()); assertNull(f.view.cached("a")) }
            } finally { release.countDown() }
        }
    }

    @Test
    fun `復元待ちで閉じる場合に既存バックアップと保留操作を維持する`() {
        fixture(EditorDataDescriptors.ore) { f ->
            val access = f.service.ores
            val backup = access.createDefault("draft")
            access.saveToLocalBackup("draft", "editing", backup)
            access.saveToLocalBackup("draft", "original", backup.deepCopy())
            access.savePendingStoreOperations(listOf(PendingStoreOperation.Create("draft").toRecord()))
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            f.service.submitIo { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            try {
                fx { f.main.switchView(f.view); assertTrue(f.main.loadingRunning); assertTrue(f.view.onClose()); f.stage.close() }
                assertEquals(1, access.loadPendingStoreOperations().size)
                assertNotNull(access.loadBackupPair("draft"))
                release.countDown()
                f.service.submitIo {}.get(10, TimeUnit.SECONDS)
                fx { assertNull(f.view.cached("draft")); assertTrue(f.view.shown.isEmpty()) }
            } finally { release.countDown() }
        }
    }

    @Test
    fun `バックアップのeditingとoriginalを復元し未保存追加を選択できる`() {
        fixture(EditorDataDescriptors.ore) { f ->
            val access = f.service.ores
            val original = access.createDefault("draft")
            val editing = original.deepCopy().apply { completed = !original.completed }
            access.saveToLocalBackup("draft", "editing", editing)
            access.saveToLocalBackup("draft", "original", original)
            access.savePendingStoreOperations(listOf(PendingStoreOperation.Create("draft").toRecord()))
            fx { f.main.switchView(f.view) }
            awaitFx { f.view.shown.isNotEmpty() }
            fx {
                assertEquals(editing, f.view.cached("draft"))
                assertEquals(original, f.view.original("draft"))
                f.view.selectTab("draft")
                assertEquals("draft", f.view.shown.last())
                assertFalse(f.main.loadingRunning)
            }
        }
    }

    @Test
    fun `接続終了後の選択取得を適用しない`() {
        fixture(EditorDataDescriptors.ore) { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.store.beforeLoad = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
            try {
                fx { f.view.selectTab("a") }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                f.store.available = false
                release.countDown()
                f.service.submitIo {}.get(10, TimeUnit.SECONDS)
                fx { assertTrue(f.view.shown.isEmpty()); assertNull(f.view.cached("a")) }
            } finally { release.countDown() }
        }
    }

    @Test
    fun `空一覧と一覧失敗を読み込み中から区別する`() {
        fixture(EditorDataDescriptors.ore) { f ->
            f.store.emptyList = true
            fx { f.main.switchView(f.view) }
            awaitFx { !f.main.loadingRunning }
            fx {
                val sidebar = f.main.sidebarContainer.children.filterIsInstance<EditorSidebar>().single()
                assertTrue((sidebar.placeholder as Label).text.contains("ありません"))
                assertTrue(f.view.shown.isEmpty())
            }
            f.store.listFailure = true
            fx { f.view.setupSidebar(f.main.sidebarContainer) }
            awaitFx { (f.root.lookup("#topInfoLabel") as Label).text.contains("失敗") }
            fx {
                val sidebar = f.main.sidebarContainer.children.filterIsInstance<EditorSidebar>().single()
                assertTrue((sidebar.placeholder as Label).text.contains("失敗"))
                assertFalse(f.main.loadingRunning)
            }
        }
    }

    @Test
    fun `リモートの最新値を三者間マージし取得失敗でも未保存データを維持する`() {
        fixture(EditorDataDescriptors.ore) { f ->
            f.store.kind = EditorDataStoreKind.REMOTE
            val base = f.service.ores.createDefault("a")
            val local = base.deepCopy().apply { hardness = 17.0 }
            val remote = base.deepCopy().apply { magicPowerAmount = 5.0 }
            f.store.memory.save(EditorDataDescriptors.ore, "a", remote)
            fx { f.view.seed(base, local); f.view.selectTab("a") }
            awaitFx { f.view.shown.isNotEmpty() }
            fx {
                assertEquals(17.0, f.view.cached("a")!!.hardness)
                assertEquals(5.0, f.view.cached("a")!!.magicPowerAmount)
                assertEquals(remote, f.view.original("a"))
            }
            f.store.loadFailure = true
            fx { f.view.selectTab("a") }
            awaitFx { (f.root.lookup("#topInfoLabel") as Label).text.contains("失敗") }
            fx {
                assertEquals(17.0, f.view.cached("a")!!.hardness)
                assertEquals(5.0, f.view.cached("a")!!.magicPowerAmount)
                assertEquals(remote, f.view.original("a"))
            }
        }
    }

    @Test
    fun `実際の4種別エディターを一覧待ちより先に開いて初期データを表示する`() {
        for (kind in listOf(EditorDataStoreKind.IN_MEMORY, EditorDataStoreKind.REMOTE)) {
            checkRealEditor(EditorDataDescriptors.item, kind) { main, service -> ItemEditorLogic(main, service) }
            checkRealEditor(EditorDataDescriptors.ore, kind) { main, service -> OreEditorLogic(main, service) }
            checkRealEditor(EditorDataDescriptors.shop, kind) { main, service -> ShopEditorLogic(main, service) }
            checkRealEditor(EditorDataDescriptors.recipe, kind) { main, service -> RecipeEditorLogic(main, service) }
        }
    }

    private fun <T : ManagedData<T, *>> checkRealEditor(
        descriptor: EditorDataDescriptor<T>, kind: EditorDataStoreKind,
        factory: (MainController, EditorDataService) -> EditorView<*>
    ) {
        startFx()
        val directory = createTempDirectory("editor-window-loading").toFile()
        val store = DelayedStore().apply { this.kind = kind }
        val id = if (descriptor == EditorDataDescriptors.shop) "tab.saved" else "saved"
        assertIs<StoreResult.Success<Unit>>(store.memory.save(descriptor, id, descriptor.createDefault(id)))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        store.beforeList = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        val service = EditorDataService(store, directory)
        lateinit var main: MainController
        try {
            fx {
                EditorWindowManager.openEditor("loading-view-test", "読み込み検証", FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))) { controller ->
                    main = controller
                    factory(controller, service)
                }
                assertTrue(main.currentStage!!.isShowing)
                assertTrue(main.loadingRunning)
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            release.countDown()
            awaitFx { !main.loadingRunning && main.mainContentContainer.children.isNotEmpty() }
            assertFalse(store.ioThreads.any { it })
        } finally {
            release.countDown()
            fx { EditorWindowManager.closeAll() }
            service.submitIo {}.get(10, TimeUnit.SECONDS)
            service.itemReferences.close()
            service.closeIo()
            directory.deleteRecursively()
        }
    }

    private fun <T : ManagedData<T, *>> fixture(descriptor: EditorDataDescriptor<T>, block: (Fixture<T>) -> Unit) {
        startFx()
        val directory = createTempDirectory("editor-loading").toFile()
        val store = DelayedStore()
        val service = EditorDataService(store, directory)
        for (leaf in listOf("a", "b")) {
            val id = if (descriptor == EditorDataDescriptors.shop) "tab.$leaf" else leaf
            assertIs<StoreResult.Success<Unit>>(store.memory.save(descriptor, id, descriptor.createDefault(id)))
        }
        val f = fx {
            val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
            val root = loader.load<Parent>()
            val main = loader.getController<MainController>()
            val stage = Stage().apply { scene = Scene(root, 1000.0, 700.0); show() }
            Fixture(store, service, main, root, stage, TestView(main, service, service.DataAccess(descriptor)))
        }
        val dialogs = fx {
            Timeline(KeyFrame(Duration.millis(20.0), {
                Platform.runLater {
                    Window.getWindows().toList().mapNotNull { it.scene?.root as? DialogPane }.forEach { pane ->
                        pane.buttonTypes.firstOrNull()?.let { (pane.lookupButton(it) as Button).fire() }
                    }
                }
            })).apply { cycleCount = Timeline.INDEFINITE; play() }
        }
        try { block(f) } finally {
            fx { dialogs.stop(); f.view.onClose(); f.stage.close() }
            service.submitIo {}.get(10, TimeUnit.SECONDS)
            service.itemReferences.close()
            service.closeIo()
            directory.deleteRecursively()
        }
    }

    private class Fixture<T : ManagedData<T, *>>(
        val store: DelayedStore, val service: EditorDataService, val main: MainController,
        val root: Parent, val stage: Stage, val view: TestView<T>
    )

    private class TestView<T : ManagedData<T, *>>(main: MainController, service: EditorDataService, access: EditorDataService.DataAccess<T>) :
        ManagedDataEditorView<T>(main, service, access) {
        val shown = mutableListOf<String>()
        fun seed(base: T, local: T) { originalDataMap[base.id] = base; editingDataMap[base.id] = local }
        fun cached(id: String): T? = editingDataMap[id]
        fun original(id: String): T? = originalDataMap[id]
        fun saveOutcome(): SaveOutcome { var result = SaveOutcome.FAILED; onSave { result = it }; return result }
        override fun setupMainContent(selectData: T) { assertTrue(Platform.isFxApplicationThread()); shown += selectData.id; main.mainContentContainer.children.setAll(Label(selectData.id)) }
        override fun resolveSaveConflict(dataId: String, originalData: T, currentData: T, serverData: T): T = currentData
    }

    private class DelayedStore(val memory: InMemoryEditorDataStore = InMemoryEditorDataStore()) : EditorDataStore by memory {
        override var kind = EditorDataStoreKind.IN_MEMORY
        var emptyList = false
        var listFailure = false
        var loadFailure = false
        @Volatile var available = true
        override val isAvailable get() = available
        val ioThreads = CopyOnWriteArrayList<Boolean>()
        var beforeList: (() -> Unit)? = null
        var beforeLoad: ((String) -> Unit)? = null
        override fun <T : ManagedData<T, *>> list(descriptor: EditorDataDescriptor<T>): StoreResult<List<StoreResource>> {
            ioThreads += Platform.isFxApplicationThread()
            beforeList?.invoke()
            if (listFailure) return StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR))
            if (emptyList) return StoreResult.Success(emptyList())
            return memory.list(descriptor)
        }
        override fun <T : ManagedData<T, *>> listDirectories(descriptor: EditorDataDescriptor<T>): StoreResult<List<String>> {
            ioThreads += Platform.isFxApplicationThread()
            return memory.listDirectories(descriptor)
        }
        override fun <T : ManagedData<T, *>> load(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<T> {
            ioThreads += Platform.isFxApplicationThread()
            beforeLoad?.invoke(id)
            if (loadFailure) return StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, id))
            return memory.load(descriptor, id)
        }
    }

    companion object {
        private fun startFx() {
            val ready = CountDownLatch(1)
            try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
            catch (_: IllegalStateException) { Platform.runLater { Platform.setImplicitExit(false); ready.countDown() } }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
        }
        private fun <R> fx(block: () -> R): R {
            val task = FutureTask(block)
            Platform.runLater(task)
            return task.get(15, TimeUnit.SECONDS)
        }
        private fun awaitFx(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!fx(condition)) { assertTrue(System.nanoTime() < deadline); Thread.sleep(20) }
        }
    }
}
