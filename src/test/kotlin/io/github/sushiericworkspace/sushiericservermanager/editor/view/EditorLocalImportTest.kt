package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.VanillaBlockId
import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericdatamodel.data.ore.model.mutable.MutableOreBaseData
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Duration
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class EditorLocalImportTest {
    @Test
    fun `遅い走査中も編集でき終了後の画面へ結果を反映しない`() = fixture { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.store.beforeList = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        try {
            fx { f.startImport() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            fx {
                assertTrue(f.status().text.contains("走査"))
                assertEquals(-1.0, f.progress().progress)
                assertFalse(f.main.mainContentContainer.isDisabled)
                f.view.change(12.0)
            }
            val closed = CompletableFuture<Boolean>()
            fx { f.view.onClose { closed.complete(it) } }
            assertTrue(closed.get(10, TimeUnit.SECONDS))
            release.countDown()
            f.service.submitIo {}.get(10, TimeUnit.SECONDS)
            fx { assertNull(f.view.cached()); assertFalse(f.status().isVisible) }
        } finally { release.countDown() }
    }

    @Test
    fun `読み込み中の編集を承認して適用退避しリモートへ保存しない`() = checkEditing(true)

    @Test
    fun `読み込み中の編集の上書きを拒否すると編集を保持する`() = checkEditing(false)

    private fun checkEditing(approve: Boolean) = fixture { f ->
        f.approveChanges = approve
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.store.beforeLoad = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        try {
            fx { f.startImport() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            fx {
                assertTrue(f.status().isVisible)
                assertFalse(f.main.mainContentContainer.isDisabled)
                f.view.change(12.0)
            }
            release.countDown()
            awaitFx { !f.status().isVisible && f.confirmations > 0 }
            fx { assertEquals(if (approve) 7.0 else 12.0, f.view.cached()?.hardness) }
            assertEquals(5.0, assertIs<StoreResult.Success<MutableOreBaseData>>(
                f.store.memory.load(EditorDataDescriptors.ore, "one")).value.hardness)
            assertEquals(0, f.store.saves)
            if (approve) {
                assertEquals(2, f.store.lists)
                val restored = f.service.ores.loadBackupPair("one")
                assertEquals(7.0, restored?.first?.hardness)
            }
        } finally { release.countDown() }
    }

    @Test
    fun `適用後の退避待ちは表示を維持し編集を再開する`() = fixture { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queued = f.service.submitBackupIo { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        try {
            fx { f.startImport() }
            awaitFx { f.status().text.contains("退避") }
            fx {
                assertFalse(f.main.mainContentContainer.isDisabled)
                assertTrue(f.status().isVisible)
                f.view.change(12.0)
            }
            release.countDown()
            queued.get(10, TimeUnit.SECONDS)
            awaitFx { !f.status().isVisible }
            fx { assertFalse(f.main.operationRunning); assertFalse(f.main.mainContentContainer.isDisabled); assertEquals(12.0, f.view.cached()?.hardness) }
            assertEquals(0, f.store.saves)
        } finally { release.countDown() }
    }

    @Test
    fun `読み込み中に終了すると戻った結果を破棄する`() = fixture { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.store.beforeLoad = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        try {
            fx { f.startImport() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val closed = CompletableFuture<Boolean>()
            fx { f.view.onClose { closed.complete(it) } }
            assertTrue(closed.get(10, TimeUnit.SECONDS))
            release.countDown()
            f.service.submitIo {}.get(10, TimeUnit.SECONDS)
            fx { assertNull(f.view.cached()); assertFalse(f.status().isVisible) }
        } finally { release.countDown() }
    }

    @Test
    fun `取り込み中に競合するストア操作を要求してもFXスレッドで待機しない`() = fixture { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.store.beforeLoad = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        var completed = false
        try {
            fx { f.startImport() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            fx { f.view.readStore { completed = true } }
            fx {
                assertTrue(f.main.operationRunning)
                assertFalse(completed)
                assertTrue(f.status().isVisible)
            }
            release.countDown()
            awaitFx { completed && !f.status().isVisible }
            assertEquals(0, f.store.saves)
        } finally { release.countDown() }
    }

    @Test
    fun `取り込み後の退避待ちで終了すると最新編集を退避してから閉じる`() = fixture { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.service.submitBackupIo { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        try {
            fx { f.startImport() }
            awaitFx { f.status().text.contains("退避") }
            val closed = CompletableFuture<Boolean>()
            fx { f.view.change(12.0); f.view.onClose { closed.complete(it) } }
            assertFalse(closed.isDone)
            release.countDown()
            assertTrue(closed.get(10, TimeUnit.SECONDS))
            assertEquals(12.0, f.service.ores.loadBackupPair("one")?.first?.hardness)
            fx { assertNull(f.view.cached()); assertFalse(f.status().isVisible) }
        } finally { release.countDown() }
    }

    @Test
    fun `取り込み結果の退避に失敗しても編集を保持し終了時に再試行できる`() = fixture(backupFailure = true) { f ->
        fx { f.startImport() }
        awaitFx { !f.status().isVisible }
        fx { assertEquals(7.0, f.view.cached()?.hardness); assertFalse(f.main.operationRunning) }
        assertEquals(0, f.store.saves)
        fx { assertEquals(true, f.backupFailureBusy) }
    }

    private fun fixture(backupFailure: Boolean = false, block: (Fixture) -> Unit) {
        startFx()
        val directory = createTempDirectory("editor-local-import").toFile().canonicalFile
        val local = File(directory, "local")
        val store = DelayedStore(local)
        store.memory.save(EditorDataDescriptors.ore, "one", MutableOreBaseData(id = "one"))
        assertIs<StoreResult.Success<Unit>>(LocalEditorDataStore(local).save(
            EditorDataDescriptors.ore, "one", MutableOreBaseData(id = "one", blockId = VanillaBlockId("iron_ore"), hardness = 7.0)))
        val backup = File(directory, "backup")
        if (backupFailure) backup.writeText("書き込み不可の退避先")
        val service = EditorDataService(store, backup)
        val f = fx {
            val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
            val root = loader.load<Parent>()
            val main = loader.getController<MainController>()
            val stage = Stage().apply { scene = Scene(root, 1100.0, 700.0); show() }
            Fixture(store, service, main, root, stage, TestView(main, service)).also {
                it.view.seed()
                it.view.setupActions(main.actionButtonContainer)
            }
        }
        val dialogs = fx {
            Timeline(KeyFrame(Duration.millis(20.0), {
                Platform.runLater {
                    Window.getWindows().toList().mapNotNull { it.scene?.root as? DialogPane }.forEach { pane ->
                        val changing = pane.headerText.orEmpty().contains("読み込み中に編集内容が変わりました")
                        if (changing) f.confirmations++
                        if (pane.headerText.orEmpty().contains("退避に失敗しました")) {
                            f.backupFailureBusy = f.view.closeBusy
                        }
                        val button = if (changing && !f.approveChanges) {
                            pane.buttonTypes.first { it.buttonData.isCancelButton }
                        } else pane.buttonTypes.first()
                        (pane.lookupButton(button) as Button).fire()
                    }
                }
            })).apply { cycleCount = Timeline.INDEFINITE; play() }
        }
        fx { f.view.setupSidebar(f.main.sidebarContainer, "one") }
        awaitFx { !f.main.loadingRunning && f.view.selected != null }
        try { block(f) } finally {
            if (backup.isFile) backup.delete()
            val closed = CompletableFuture<Boolean>()
            fx { f.view.onClose { closed.complete(it) } }
            assertTrue(closed.get(10, TimeUnit.SECONDS))
            fx { dialogs.stop(); f.stage.close() }
            service.submitIo {}.get(10, TimeUnit.SECONDS)
            service.itemReferences.close()
            service.closeIo()
            directory.deleteRecursively()
        }
    }

    private class Fixture(
        val store: DelayedStore, val service: EditorDataService, val main: MainController,
        val root: Parent, val stage: Stage, val view: TestView
    ) {
        var approveChanges = true
        var confirmations = 0
        var backupFailureBusy: Boolean? = null
        fun status() = root.lookup("#importStatus") as Label
        fun progress() = root.lookup("#importProgress") as ProgressIndicator
        fun startImport() {
            main.actionButtonContainer.children.filterIsInstance<MenuButton>().flatMap { it.items }
                .single { it.text?.startsWith("ローカルデータ") == true }.fire()
        }
    }

    private class TestView(main: MainController, service: EditorDataService) :
        ManagedDataEditorView<MutableOreBaseData>(main, service, service.ores) {
        fun seed() {
            originalDataMap["one"] = MutableOreBaseData(id = "one")
            editingDataMap["one"] = originalDataMap.getValue("one").deepCopy()
        }
        fun change(hardness: Double) { editingDataMap.getValue("one").hardness = hardness }
        val selected get() = currentSelectedDataId
        fun cached() = editingDataMap["one"]
        fun readStore(onComplete: () -> Unit) {
            runStoreOperation("複製元を取得しています", { dataAccess.loadStore("one") }) { onComplete() }
        }
        override fun setupMainContent(selectData: MutableOreBaseData) {
            main.mainContentContainer.children.setAll(Label(selectData.id))
        }
        override fun resolveSaveConflict(dataId: String, originalData: MutableOreBaseData,
            currentData: MutableOreBaseData, serverData: MutableOreBaseData) = currentData
    }

    private class DelayedStore(override val offlineDirectory: File) : EditorDataStore by InMemoryEditorDataStore() {
        val memory = InMemoryEditorDataStore("import-test")
        override val identity = "import-test"
        override val kind = EditorDataStoreKind.REMOTE
        var beforeList: (() -> Unit)? = null
        var beforeLoad: (() -> Unit)? = null
        var lists = 0
        var saves = 0
        override fun <T : ManagedData<T, *>> list(descriptor: EditorDataDescriptor<T>): StoreResult<List<StoreResource>> {
            assertFalse(Platform.isFxApplicationThread())
            lists++
            beforeList?.invoke()
            return memory.list(descriptor)
        }
        override fun <T : ManagedData<T, *>> load(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<T> {
            assertFalse(Platform.isFxApplicationThread())
            beforeLoad?.invoke()
            return memory.load(descriptor, id)
        }
        override fun <T : ManagedData<T, *>> save(descriptor: EditorDataDescriptor<T>, id: String, data: T): StoreResult<Unit> {
            saves++
            error("取り込みからリモートへ保存してはいけません")
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
