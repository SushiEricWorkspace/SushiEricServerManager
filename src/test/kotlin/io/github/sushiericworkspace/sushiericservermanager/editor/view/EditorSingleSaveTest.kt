package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.data.core.ManagedData
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
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.DialogPane
import javafx.scene.control.Label
import javafx.scene.control.ProgressIndicator
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Duration
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** 実サーバーを使わず、遅延・失敗する保存先と実FXMLで工程と結果を確認します。 */
@EnabledOnOs(OS.MAC, OS.WINDOWS)
class EditorSingleSaveTest {
    @Test
    fun `全種別の保存で取得と書込みを背景実行し待機中もFXが応答する`() {
        verifySave(EditorDataDescriptors.item)
        verifySave(EditorDataDescriptors.ore)
        verifySave(EditorDataDescriptors.shop)
        verifySave(EditorDataDescriptors.recipe)
    }

    private fun <T : ManagedData<T, *>> verifySave(descriptor: EditorDataDescriptor<T>) = withFixture(descriptor) { fixture ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.store.beforeSave = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        val completed = CompletableFuture<EditorView.SaveOutcome>()
        val id = fx { fixture.logic.current().id }
        fx { fixture.logic.onSave(id) { completed.complete(it) } }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS), descriptor.displayName + ": " + fx { (fixture.root.lookup("#topInfoLabel") as Label).text + " result=" + completed.getNow(null) })
            fx {
                assertTrue(fixture.main.operationRunning)
                assertTrue(fixture.main.mainContentContainer.isDisabled)
                assertTrue(fixture.main.sidebarContainer.isDisabled)
                assertTrue((fixture.root.lookup("#operationProgress") as ProgressIndicator).isVisible)
                assertFalse(fixture.logic.onClose())
                fixture.logic.onSave(id) { assertEquals(EditorView.SaveOutcome.BUSY, it) }
                fixture.logic.onUndo()
                assertFalse(completed.isDone)
                fixture.root.applyCss(); fixture.root.layout()
            }
        } finally { release.countDown() }
        assertEquals(EditorView.SaveOutcome.SAVED, completed.get(10, TimeUnit.SECONDS))
        fx {
            assertFalse(fixture.main.operationRunning)
            assertFalse(fixture.main.mainContentContainer.isDisabled)
            assertEquals(fixture.store.savedCompleted, fixture.logic.current().completed)
            assertTrue(fixture.logic.unchanged())
        }
        assertEquals(1, fixture.store.writes)
        assertTrue(fixture.store.ioThreads.all { !it })
    }

    @Test
    fun `確認キャンセルでは書き込まず状態を解除する`() = withFixture(EditorDataDescriptors.item) { fixture ->
        fixture.cancel = true
        val completed = CompletableFuture<EditorView.SaveOutcome>()
        fx { fixture.logic.onSave("saved") { completed.complete(it) } }
        assertEquals(EditorView.SaveOutcome.CANCELLED, completed.get(10, TimeUnit.SECONDS))
        assertEquals(0, fixture.store.writes)
        fx { assertFalse(fixture.main.operationRunning); assertFalse(fixture.logic.unchanged()) }
    }

    @Test
    fun `検証失敗と通信失敗で編集を保持し完了を一度通知する`() {
        for (code in listOf(StoreErrorCode.VALIDATION_FAILED, StoreErrorCode.IO_ERROR, StoreErrorCode.PERMISSION_DENIED)) {
            withFixture(EditorDataDescriptors.recipe) { fixture ->
                fixture.store.writeFailure = code
                val completed = CompletableFuture<EditorView.SaveOutcome>()
                fx { fixture.logic.onSave("saved") { completed.complete(it) } }
                assertEquals(EditorView.SaveOutcome.FAILED, completed.get(10, TimeUnit.SECONDS))
                fx { assertFalse(fixture.main.operationRunning); assertFalse(fixture.logic.unchanged()) }
            }
        }
    }

    @Test
    fun `書込み後の一覧取得失敗は保存済みと後処理エラーを返す`() = withFixture(EditorDataDescriptors.item) { fixture ->
        fixture.store.failListAfterSave = true
        val completed = CompletableFuture<EditorView.SaveOutcome>()
        fx { fixture.logic.onSave("saved") { completed.complete(it) } }
        assertEquals(EditorView.SaveOutcome.SAVED_WITH_ERRORS, completed.get(10, TimeUnit.SECONDS))
        fx { assertTrue(fixture.logic.unchanged()); assertFalse(fixture.main.operationRunning) }
        assertEquals(1, fixture.store.writes)
    }

    @Test
    fun `ID変更後の書込み失敗は同じ工程内で元のIDへ戻す`() = withFixture(EditorDataDescriptors.item) { fixture ->
        fixture.store.writeFailure = StoreErrorCode.IO_ERROR
        fx { fixture.logic.current().itemDetail.maxStackSize = 32; fixture.logic.rename() }
        val completed = CompletableFuture<EditorView.SaveOutcome>()
        fx { fixture.logic.onSave("renamed") { completed.complete(it) } }
        assertEquals(EditorView.SaveOutcome.FAILED, completed.get(10, TimeUnit.SECONDS))
        assertIs<StoreResult.Success<*>>(fixture.store.memory.load(EditorDataDescriptors.item, "saved"))
        assertIs<StoreResult.Failure>(fixture.store.memory.load(EditorDataDescriptors.item, "renamed"))
        assertEquals(listOf("saved:renamed", "renamed:saved"), fixture.store.renames)
        val pair = assertNotNull(fixture.service.items.loadBackupPair("renamed"))
        assertEquals("renamed", pair.first.id)
        assertEquals(32, pair.first.itemDetail.maxStackSize)
        assertEquals(1, pair.second.itemDetail.maxStackSize)
        assertEquals("renamed", fixture.service.items.loadPendingStoreOperations().single().currentId)
    }

    @Test
    fun `移動後の葉名変更失敗は元ディレクトリへ戻して保留を維持する`() = withFixture(EditorDataDescriptors.item) { fixture ->
        fixture.service.itemReferences.load().get(5, TimeUnit.SECONDS)
        fixture.store.failedRename = "renamed"
        fx { fixture.logic.rename("other.renamed") }
        val completed = CompletableFuture<EditorView.SaveOutcome>()
        fx { fixture.logic.onSave("other.renamed") { completed.complete(it) } }
        assertEquals(EditorView.SaveOutcome.FAILED, completed.get(10, TimeUnit.SECONDS))
        assertIs<StoreResult.Success<*>>(fixture.store.memory.load(EditorDataDescriptors.item, "saved"))
        assertIs<StoreResult.Failure>(fixture.store.memory.load(EditorDataDescriptors.item, "other.saved"))
        assertEquals("saved", fixture.service.itemReferences.snapshot.items.single().id)
        assertNotNull(fixture.service.items.loadBackupPair("other.renamed"))
        assertEquals("other.renamed", fixture.service.items.loadPendingStoreOperations().single().currentId)
    }

    @Test
    fun `最新取得と一覧更新の遅延中も工程を表示して描画できる`() = withFixture(EditorDataDescriptors.item) { fixture ->
        val reading = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val listing = CountDownLatch(1)
        val releaseList = CountDownLatch(1)
        fixture.store.beforeLoad = { reading.countDown(); assertTrue(releaseRead.await(10, TimeUnit.SECONDS)) }
        fixture.store.beforeList = { listing.countDown(); assertTrue(releaseList.await(10, TimeUnit.SECONDS)) }
        val completed = CompletableFuture<EditorView.SaveOutcome>()
        fx { fixture.logic.onSave("saved") { completed.complete(it) } }
        try {
            assertTrue(reading.await(10, TimeUnit.SECONDS))
            fx { assertTrue(fixture.main.operationRunning); fixture.root.applyCss(); fixture.root.layout() }
            releaseRead.countDown()
            assertTrue(listing.await(10, TimeUnit.SECONDS))
            fx { assertTrue(fixture.main.operationRunning); assertTrue(fixture.logic.unchanged()) }
        } finally { releaseRead.countDown(); releaseList.countDown() }
        assertEquals(EditorView.SaveOutcome.SAVED, completed.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun `競合確認待ちにIOキューを占有せずキャンセル時に編集を維持する`() = withFixture(EditorDataDescriptors.item) { fixture ->
        val seen = CountDownLatch(1)
        fx {
            fixture.logic.current().itemDetail.maxStackSize = 32
            val remote = fixture.store.memory.load(EditorDataDescriptors.item, "saved") as StoreResult.Success
            remote.value.itemDetail.maxStackSize = 16
            fixture.store.memory.save(EditorDataDescriptors.item, "saved", remote.value)
            fixture.pauseDialogs = true
            fixture.onDialog = { pane ->
                if (pane.headerText == null) seen.countDown()
            }
        }
        val completed = CompletableFuture<EditorView.SaveOutcome>()
        fx { fixture.logic.onSave("saved") { completed.complete(it) } }
        assertTrue(seen.await(10, TimeUnit.SECONDS))
        assertEquals("free", fixture.service.submitIo { "free" }.get(5, TimeUnit.SECONDS))
        fx { fixture.cancel = true; fixture.pauseDialogs = false }
        assertEquals(EditorView.SaveOutcome.CANCELLED, completed.get(10, TimeUnit.SECONDS))
        assertEquals(0, fixture.store.writes)
        fx { assertEquals(32, fixture.logic.current().itemDetail.maxStackSize) }
    }

    @Test
    fun `破損YAMLは承認した場合だけ保存する`() {
        for (cancel in listOf(false, true)) withFixture(EditorDataDescriptors.item) { fixture ->
            fixture.store.readFailure = StoreErrorCode.INVALID_YAML
            fixture.cancel = cancel
            val completed = CompletableFuture<EditorView.SaveOutcome>()
            fx { fixture.logic.onSave("saved") { completed.complete(it) } }
            assertEquals(if (cancel) EditorView.SaveOutcome.CANCELLED else EditorView.SaveOutcome.SAVED,
                completed.get(10, TimeUnit.SECONDS))
            assertEquals(if (cancel) 0 else 1, fixture.store.writes)
        }
    }

    @Test
    fun `アイテム削除の完了を共有参照へ反映する`() = withFixture(EditorDataDescriptors.item) { fixture ->
        fixture.service.itemReferences.load().get(5, TimeUnit.SECONDS)
        fx { fixture.logic.delete() }
        val completed = CompletableFuture<EditorView.SaveOutcome>()
        fx { fixture.logic.onSave("saved") { completed.complete(it) } }
        assertEquals(EditorView.SaveOutcome.SAVED, completed.get(10, TimeUnit.SECONDS))
        assertTrue(fixture.service.itemReferences.snapshot.items.isEmpty())
        assertIs<StoreResult.Failure>(fixture.store.memory.load(EditorDataDescriptors.item, "saved"))
    }

    @Test
    fun `バックアップ削除失敗は保存済みの後処理エラーとして返す`() = withFixture(EditorDataDescriptors.item) { fixture ->
        val backup = fixture.service.autoSaveDirectory.resolve(fixture.service.cacheIdentity)
            .resolve(EditorDataDescriptors.item.dataType.categoryDirName).resolve("editing/saved.yml")
        assertTrue(backup.mkdirs())
        backup.resolve("undeletable").writeText("test")
        val completed = CompletableFuture<EditorView.SaveOutcome>()
        fx { fixture.logic.onSave("saved") { completed.complete(it) } }
        assertEquals(EditorView.SaveOutcome.SAVED_WITH_ERRORS, completed.get(10, TimeUnit.SECONDS))
        fx { assertTrue(fixture.logic.unchanged()) }
    }

    @Test
    fun `オフライン保存では最新取得を行わずコピーを保存する`() = withFixture(EditorDataDescriptors.item) { fixture ->
        fixture.store.kind = EditorDataStoreKind.LOCAL
        val completed = CompletableFuture<EditorView.SaveOutcome>()
        fx { fixture.logic.onSave("saved") { completed.complete(it) } }
        assertEquals(EditorView.SaveOutcome.SAVED, completed.get(10, TimeUnit.SECONDS))
        assertEquals(0, fixture.store.loads)
        assertEquals(1, fixture.store.writes)
    }

    @Test
    fun `同じ接続の別画面は本文編集を維持し同期IO入口を抑止する`() = withFixture(EditorDataDescriptors.item) { fixture ->
        val other = fx {
            val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
            val root = loader.load<Parent>()
            Scene(root)
            val controller = loader.getController<MainController>()
            val view = TestView(controller, fixture.service, fixture.service.items)
            view.seed(MutableItemBaseData(id = "other"), MutableItemBaseData(id = "other"))
            controller to view
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.store.beforeSave = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        val completed = CompletableFuture<EditorView.SaveOutcome>()
        fx { fixture.logic.onSave("saved") { completed.complete(it) } }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            fx {
                assertFalse(other.first.mainContentContainer.isDisabled)
                assertTrue(other.first.sidebarContainer.isDisabled)
                assertTrue(other.first.actionButtonContainer.isDisabled)
                other.second.selectTab("uncached")
                assertEquals("other", other.second.current().id)
            }
        } finally { release.countDown() }
        assertEquals(EditorView.SaveOutcome.SAVED, completed.get(10, TimeUnit.SECONDS))
        fx { assertFalse(other.first.sidebarContainer.isDisabled); other.second.onClose() }
    }

    private fun <T : ManagedData<T, *>> withFixture(descriptor: EditorDataDescriptor<T>, action: (Fixture<T>) -> Unit) {
        startFx()
        val directory = createTempDirectory("editor-single-save").toFile()
        val store = DelayedStore()
        val service = EditorDataService(store, directory)
        val base = descriptor.createDefault(if (descriptor == EditorDataDescriptors.shop) "tab.saved" else "saved")
        val edit = base.deepCopy().apply { completed = !base.completed }
        store.memory.save(descriptor, base.id, base)
        val fixture = fx {
            val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
            val root = loader.load<Parent>()
            val main = loader.getController<MainController>()
            val stage = Stage().apply { scene = Scene(root); show() }
            val dataAccess = service.DataAccess(descriptor)
            val logic = TestView(main, service, dataAccess)
            logic.seed(base, edit)
            val fixture = Fixture(store, service, main, root, stage, logic)
            fixture.timer = Timeline(KeyFrame(Duration.millis(20.0), {
                Platform.runLater {
                    Window.getWindows().toList().mapNotNull { it.scene?.root as? DialogPane }.forEach { pane ->
                        fixture.onDialog?.invoke(pane)
                        if (fixture.pauseDialogs) return@forEach
                        val type = pane.buttonTypes.firstOrNull {
                            it.buttonData == if (fixture.cancel) ButtonBar.ButtonData.CANCEL_CLOSE else ButtonBar.ButtonData.OK_DONE
                        } ?: pane.buttonTypes.firstOrNull()
                        type?.let { (pane.lookupButton(it) as Button).fire() }
                    }
                }
            })).apply { cycleCount = Timeline.INDEFINITE; play() }
            fixture
        }
        try { action(fixture) }
        finally {
            fx { fixture.timer.stop(); fixture.logic.onClose(); fixture.stage.close() }
            service.itemReferences.close()
            service.closeIo()
            directory.deleteRecursively()
        }
    }

    private class Fixture<T : ManagedData<T, *>>(
        val store: DelayedStore, val service: EditorDataService, val main: MainController,
        val root: Parent, val stage: Stage, val logic: TestView<T>
    ) {
        var cancel = false
        var pauseDialogs = false
        var onDialog: ((DialogPane) -> Unit)? = null
        lateinit var timer: Timeline
    }

    private class TestView<T : ManagedData<T, *>>(main: MainController, service: EditorDataService, access: EditorDataService.DataAccess<T>) :
        EditorView<T>(main, service, access) {
        fun seed(base: T, edit: T) {
            currentSelectedDataId = base.id
            originalDataMap[base.id] = base
            editingDataMap[base.id] = edit
            setupMainContent(edit)
        }
        fun current(): T = editingDataMap.getValue(currentSelectedDataId!!)
        fun unchanged(): Boolean = editingDataMap == originalDataMap
        fun rename(newId: String = "renamed") { stageDataRename("saved", newId) }
        fun delete() { stageDataDeletion("saved") }
        override fun setupSidebar(container: VBox, selectId: String?) {
            assertTrue(Platform.isFxApplicationThread())
            loadSidebar(onFailed = { error("一覧の取得に失敗しました") }) { _, _ ->
                selectId?.let(::selectTab)
            }
        }
        override fun setupMainContent(selectData: T) { main.mainContentContainer.children.setAll(Label(selectData.id)) }
        override fun resolveSaveConflict(dataId: String, originalData: T, currentData: T, serverData: T): T = currentData
    }

    private class DelayedStore(val memory: InMemoryEditorDataStore = InMemoryEditorDataStore()) : EditorDataStore by memory {
        override var kind = EditorDataStoreKind.REMOTE
        val ioThreads = mutableListOf<Boolean>()
        val renames = mutableListOf<String>()
        var beforeSave: (() -> Unit)? = null
        var beforeLoad: (() -> Unit)? = null
        var beforeList: (() -> Unit)? = null
        var readFailure: StoreErrorCode? = null
        var loads = 0
        var writeFailure: StoreErrorCode? = null
        var failListAfterSave = false
        var failedRename: String? = null
        var savedCompleted = false
        var writes = 0
        override fun <T : ManagedData<T, *>> load(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<T> {
            ioThreads += Platform.isFxApplicationThread()
            loads++
            beforeLoad?.invoke()
            readFailure?.let { return StoreResult.Failure(StoreError(it, id)) }
            return memory.load(descriptor, id)
        }
        override fun <T : ManagedData<T, *>> save(descriptor: EditorDataDescriptor<T>, id: String, data: T): StoreResult<Unit> {
            ioThreads += Platform.isFxApplicationThread()
            beforeSave?.invoke()
            writes++
            writeFailure?.let { return StoreResult.Failure(StoreError(it, id)) }
            data.completed = !data.completed
            savedCompleted = data.completed
            return memory.save(descriptor, id, data)
        }
        override fun <T : ManagedData<T, *>> list(descriptor: EditorDataDescriptor<T>): StoreResult<List<StoreResource>> {
            ioThreads += Platform.isFxApplicationThread()
            beforeList?.invoke()
            return if (failListAfterSave && writes > 0) StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR)) else memory.list(descriptor)
        }
        override fun <T : ManagedData<T, *>> rename(descriptor: EditorDataDescriptor<T>, oldId: String, newName: String): StoreResult<Unit> {
            renames += "$oldId:$newName"
            if (newName == failedRename) return StoreResult.Failure(StoreError(StoreErrorCode.ALREADY_EXISTS, oldId))
            return memory.rename(descriptor, oldId, newName)
        }
    }

    companion object {
        private var started = false
        @Synchronized private fun startFx() {
            if (started) return
            val ready = CountDownLatch(1)
            try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
            catch (_: IllegalStateException) { Platform.runLater { Platform.setImplicitExit(false); ready.countDown() } }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            started = true
        }
        private fun <R> fx(action: () -> R): R {
            val task = FutureTask(action)
            Platform.runLater(task)
            return task.get(15, TimeUnit.SECONDS)
        }
    }
}
