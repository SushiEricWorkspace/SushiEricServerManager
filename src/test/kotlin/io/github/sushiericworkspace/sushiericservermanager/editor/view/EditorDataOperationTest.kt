package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.ItemEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.ore.OreEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe.RecipeEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.shop.ShopEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.PendingStoreOperationKind
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.ContextMenuEvent
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class EditorDataOperationTest {
    @Test
    fun `4種別の作成と複製とディレクトリ操作は待機中もUIに応答し保存までデータを書かない`() {
        checkOperations(EditorDataDescriptors.item, ::ItemEditorLogic)
        checkOperations(EditorDataDescriptors.ore, ::OreEditorLogic)
        checkOperations(EditorDataDescriptors.recipe, ::RecipeEditorLogic)
        checkOperations(EditorDataDescriptors.shop, ::ShopEditorLogic)
    }

    private fun <T : ManagedData<T, *>> checkOperations(
        descriptor: EditorDataDescriptor<T>, factory: (MainController, EditorDataService) -> EditorView<T>
    ) = fixture(descriptor, factory) { f ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.store.beforeList = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        try {
            f.answers += "group.created"
            fx { f.logic.onCreateData() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            fx {
                assertTrue(f.main.operationRunning)
                assertTrue(f.main.actionButtonContainer.isDisabled)
                assertTrue(f.stage.isShowing)
                f.logic.onCreateDirectory()
                var closed: Boolean? = null
                f.logic.onClose { closed = it }
                assertEquals(false, closed)
            }
            release.countDown()
            awaitFx { !f.main.operationRunning && ids(f).contains("group.created") }
        } finally { release.countDown(); f.store.beforeList = null }
        f.service.submitBackupIo {}.get(10, TimeUnit.SECONDS)
        assertTrue(f.access.loadPendingStoreOperations().any { it.currentId == "group.created" && it.kind == PendingStoreOperationKind.CREATE })
        assertIs<StoreResult.Failure>(f.store.memory.load(descriptor, "group.created"))
        assertEquals(0, f.store.writes)

        f.answers += "copied"
        fx { dataMenu(f, "group.a", "複製") }
        awaitFx { !f.main.operationRunning && ids(f).contains("group.copied") }
        assertIs<StoreResult.Failure>(f.store.memory.load(descriptor, "group.copied"))
        f.service.submitBackupIo {}.get(10, TimeUnit.SECONDS)
        val source = when (val result = f.store.memory.load(descriptor, "group.a")) {
            is StoreResult.Success -> result.value
            is StoreResult.Failure -> error(result.toString())
        }
        val pair = f.access.loadBackupPair("group.copied")
        assertTrue(pair != null)
        val copy = pair.first
        assertEquals("group.a", source.id)
        assertEquals("group.copied", copy.id)
        if (source is MutableItemBaseData && copy is MutableItemBaseData) assertNotEquals(source.internalId, copy.internalId)

        val creating = CountDownLatch(1)
        val releaseCreate = CountDownLatch(1)
        f.store.beforeCreate = { creating.countDown(); assertTrue(releaseCreate.await(10, TimeUnit.SECONDS)) }
        try {
            f.answers += "group.empty"
            fx { f.logic.onCreateDirectory() }
            assertTrue(creating.await(10, TimeUnit.SECONDS))
            fx { assertTrue(f.main.operationRunning); assertTrue(f.stage.isShowing) }
            releaseCreate.countDown()
            awaitFx { !f.main.operationRunning && directories(f).contains("group.empty") }
        } finally { releaseCreate.countDown(); f.store.beforeCreate = null }
        fx { directoryMenu(f, "group.empty", "削除") }
        awaitFx { !f.main.operationRunning && !directories(f).contains("group.empty") }
        assertEquals(1, f.store.deletions)
        assertFalse(f.store.ioThreads.any { it })
        assertEquals(0, f.store.writes)
        f.answers += "renamed"
        fx { dataMenu(f, "group.a", "IDを変更") }
        awaitFx { !f.main.operationRunning && ids(f).contains("group.renamed") }
        assertIs<StoreResult.Success<*>>(f.store.memory.load(descriptor, "group.a"))
        assertIs<StoreResult.Failure>(f.store.memory.load(descriptor, "group.renamed"))
        f.service.submitBackupIo {}.get(10, TimeUnit.SECONDS)
        assertTrue(f.access.loadPendingStoreOperations().any { it.currentId == "group.renamed" && it.kind == PendingStoreOperationKind.RENAME })
        fx { assertTrue(move(f.logic, "group.renamed", "archive")) }
        awaitFx { !f.main.operationRunning && ids(f).contains("archive.renamed") }
        f.service.submitBackupIo {}.get(10, TimeUnit.SECONDS)
        assertTrue(f.access.loadPendingStoreOperations().any { it.currentId == "archive.renamed" && it.sourceId == "group.a" })
        assertIs<StoreResult.Success<*>>(f.store.memory.load(descriptor, "group.a"))
        assertIs<StoreResult.Failure>(f.store.memory.load(descriptor, "archive.renamed"))
        assertEquals(0, f.store.writes)
    }

    @Test
    fun `外部から同名追加された作成とID変更は古い一覧で許可しない`() = fixture(EditorDataDescriptors.ore, ::OreEditorLogic) { f ->
        f.store.memory.save(f.descriptor, "group.external", f.descriptor.createDefault("group.external"))
        f.answers += "group.external"
        fx { f.logic.onCreateData() }
        awaitFx { !f.main.operationRunning && f.errors.isNotEmpty() }
        assertEquals(0, f.store.writes)
        f.answers += "external"
        fx { dataMenu(f, "group.a", "IDを変更") }
        awaitFx { !f.main.operationRunning && f.errors.size == 2 }
        f.service.submitBackupIo {}.get(10, TimeUnit.SECONDS)
        assertTrue(f.access.loadPendingStoreOperations().isEmpty())
        fx { assertTrue(ids(f).contains("group.a")); assertFalse(ids(f).contains("group.external")) }
    }

    @Test
    fun `未取得の複製元は背景で読み対象消失なら保留も元データも変更しない`() = fixture(EditorDataDescriptors.ore, ::OreEditorLogic) { f ->
        f.store.memory.save(f.descriptor, "group.fresh", f.descriptor.createDefault("group.fresh"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.store.beforeLoad = { id -> if (id == "group.fresh") { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) } }
        try {
            f.answers += "copy"
            fx { duplicate(f.logic, "group.fresh") }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            fx { assertTrue(f.main.operationRunning); assertTrue(f.stage.isShowing) }
            f.store.memory.delete(f.descriptor, "group.fresh")
            release.countDown()
            awaitFx { !f.main.operationRunning && f.errors.isNotEmpty() }
            fx { assertFalse(ids(f).contains("group.copy")); assertTrue(ids(f).contains("group.a")) }
            assertEquals(0, f.store.writes)
        } finally { release.countDown(); f.store.beforeLoad = null }
    }

    @Test
    fun `入力と削除のキャンセルはI Oも未保存の保留操作も変更しない`() = fixture(EditorDataDescriptors.ore, ::OreEditorLogic) { f ->
        f.answers += "group.local"
        fx { f.logic.onCreateData() }
        awaitFx { !f.main.operationRunning && ids(f).contains("group.local") }
        f.cancelInput = true
        val lists = f.store.lists
        fx { f.logic.onCreateData() }
        assertEquals(lists, f.store.lists)
        f.confirmDeletion = false
        fx { directoryMenu(f, "group", "削除") }
        awaitFx { !f.main.operationRunning && f.confirmations > 0 }
        assertEquals(0, f.store.deletions)
        f.service.submitBackupIo {}.get(10, TimeUnit.SECONDS)
        assertTrue(f.access.loadPendingStoreOperations().any { it.currentId == "group.local" })
        fx { assertTrue(ids(f).contains("group.local")) }
    }

    @Test
    fun `権限不足と部分削除では残った編集と保留操作を保持する`() = fixture(EditorDataDescriptors.ore, ::OreEditorLogic) { f ->
        f.store.deleteFailure = StoreErrorCode.PERMISSION_DENIED
        fx { directoryMenu(f, "group", "削除") }
        awaitFx { !f.main.operationRunning && f.errors.isNotEmpty() }
        fx { assertTrue(ids(f).containsAll(listOf("group.a", "group.b"))) }
        f.store.partialDeletion = "group.a"
        f.store.deleteFailure = StoreErrorCode.IO_ERROR
        fx { directoryMenu(f, "group", "削除") }
        awaitFx { !f.main.operationRunning && f.errors.size == 2 }
        fx { assertFalse(ids(f).contains("group.a")); assertTrue(ids(f).contains("group.b")) }
        assertIs<StoreResult.Success<*>>(f.store.memory.load(f.descriptor, "group.b"))
        assertEquals(2, f.store.deletions)
        assertFalse(f.store.ioThreads.any { it })
    }

    @Test
    fun `local onlyディレクトリは最新一覧で確認して保留操作とバックアップだけ破棄する`() = fixture(EditorDataDescriptors.ore, ::OreEditorLogic) { f ->
        f.answers += "draft.local"
        fx { f.logic.onCreateData() }
        awaitFx { !f.main.operationRunning && ids(f).contains("draft.local") }
        f.service.submitBackupIo {}.get(10, TimeUnit.SECONDS)
        assertNotNull(f.access.loadBackupPair("draft.local"))
        fx { directoryMenu(f, "draft", "削除") }
        awaitFx { !f.main.operationRunning && !ids(f).contains("draft.local") }
        f.service.submitBackupIo {}.get(10, TimeUnit.SECONDS)
        assertNull(f.access.loadBackupPair("draft.local"))
        assertTrue(f.access.loadPendingStoreOperations().isEmpty())
        assertEquals(0, f.store.deletions)
    }

    @Test
    fun `配下が多い削除は重複要求を止め一覧を一度更新する`() = fixture(EditorDataDescriptors.ore, ::OreEditorLogic) { f ->
        repeat(200) { n -> val id = "bulk.data$n"; f.store.memory.save(f.descriptor, id, f.descriptor.createDefault(id)) }
        fx { f.logic.setupSidebar(f.main.sidebarContainer, "group.a") }
        awaitFx { !f.main.loadingRunning && directories(f).contains("bulk") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.store.beforeDelete = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        try {
            val lists = f.store.lists
            fx { directoryMenu(f, "bulk", "削除") }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            fx { assertTrue(f.main.operationRunning); f.logic.onCreateDirectory() }
            release.countDown()
            awaitFx { !f.main.operationRunning && !directories(f).contains("bulk") }
            assertEquals(1, f.store.deletions)
            assertEquals(lists + 2, f.store.lists)
        } finally { release.countDown(); f.store.beforeDelete = null }
    }

    @Test
    fun `削除成功後に一覧取得が失敗しても削除済みの保留と編集を再保存しない`() = fixture(EditorDataDescriptors.ore, ::OreEditorLogic) { f ->
        f.answers += "group.created"
        fx { f.logic.onCreateData() }
        awaitFx { !f.main.operationRunning && ids(f).contains("group.created") }
        f.store.listFailureAfterDelete = true
        fx { directoryMenu(f, "group", "削除") }
        awaitFx { !f.main.operationRunning && f.errors.isNotEmpty() }
        fx { assertTrue(ids(f).isEmpty()) }
        f.service.submitBackupIo {}.get(10, TimeUnit.SECONDS)
        assertTrue(f.access.loadPendingStoreOperations().isEmpty())
        assertNull(f.access.loadBackupPair("group.created"))
        assertEquals(1, f.store.deletions)
    }

    @Test
    fun `ディレクトリ作成の拒否でもオフライン編集と保留操作を維持する`() = fixture(EditorDataDescriptors.ore, ::OreEditorLogic) { f ->
        f.store.kind = EditorDataStoreKind.LOCAL
        f.answers += "group.created"
        fx { f.logic.onCreateData() }
        awaitFx { !f.main.operationRunning && ids(f).contains("group.created") }
        f.store.createFailure = true
        f.answers += "denied"
        fx { f.logic.onCreateDirectory() }
        awaitFx { !f.main.operationRunning && f.errors.isNotEmpty() }
        fx { assertTrue(ids(f).contains("group.created")); assertFalse(directories(f).contains("denied")) }
        f.service.submitBackupIo {}.get(10, TimeUnit.SECONDS)
        assertNotNull(f.access.loadBackupPair("group.created"))
        assertTrue(f.access.loadPendingStoreOperations().any { it.currentId == "group.created" })
        assertEquals(0, f.store.writes)
    }

    private fun move(logic: EditorView<*>, id: String, directory: String): Boolean {
        val owner = if (logic is ItemEditorLogic) ItemEditorLogic::class.java else ManagedDataEditorView::class.java
        return owner.getDeclaredMethod("moveData", String::class.java, String::class.java).apply { isAccessible = true }.invoke(logic, id, directory) as Boolean
    }

    private fun duplicate(logic: EditorView<*>, id: String) {
        EditorView::class.java.getDeclaredMethod("requestDuplicate", String::class.java).apply { isAccessible = true }.invoke(logic, id)
    }

    private fun <T : ManagedData<T, *>> dataMenu(f: Fixture<T>, id: String, text: String) = fireMenu(f, text) { it is SidebarRow.Data && it.fullId == id }
    private fun <T : ManagedData<T, *>> directoryMenu(f: Fixture<T>, id: String, text: String) = fireMenu(f, text) { it is SidebarRow.Directory && it.fullPath == id }
    private fun <T : ManagedData<T, *>> fireMenu(f: Fixture<T>, text: String, matches: (Any?) -> Boolean) {
        f.root.applyCss(); f.root.layout()
        val cell = f.root.lookupAll(".list-cell").filterIsInstance<ListCell<*>>().single { matches(it.item) }
        val button = cell.lookupAll(".button").filterIsInstance<Button>().single()
        button.onContextMenuRequested.handle(ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0.0, 0.0, 200.0, 200.0, false, null))
        val menu = button.contextMenu
        val item = menu.items.single { it.text == text }
        menu.hide()
        item.fire()
    }
    private fun <T : ManagedData<T, *>> ids(f: Fixture<T>) = (f.root.lookup(".sidebar-list") as EditorSidebar).items.filterIsInstance<SidebarRow.Data>().map { it.fullId }
    private fun <T : ManagedData<T, *>> directories(f: Fixture<T>) = (f.root.lookup(".sidebar-list") as EditorSidebar).items.filterIsInstance<SidebarRow.Directory>().map { it.fullPath }

    private class Fixture<T : ManagedData<T, *>>(
        val descriptor: EditorDataDescriptor<T>, val store: OperationStore, val service: EditorDataService,
        val access: EditorDataService.DataAccess<T>, val main: MainController, val root: Parent,
        val stage: Stage, val logic: EditorView<T>
    ) {
        val answers = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val errors = CopyOnWriteArrayList<String>()
        var cancelInput = false
        var confirmDeletion = true
        var confirmations = 0
    }

    private fun <T : ManagedData<T, *>> fixture(descriptor: EditorDataDescriptor<T>, factory: (MainController, EditorDataService) -> EditorView<T>, block: (Fixture<T>) -> Unit) {
        val ready = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
        catch (_: IllegalStateException) { Platform.runLater { ready.countDown() } }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        val directory = createTempDirectory("editor-data-operation").toFile()
        val store = OperationStore()
        listOf("group.a", "group.b").forEach { store.memory.save(descriptor, it, descriptor.createDefault(it)) }
        val service = EditorDataService(store, directory)
        val f = fx {
            val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
            val root = loader.load<Parent>()
            val main = loader.getController<MainController>()
            val stage = Stage().apply { scene = Scene(root, 1560.0, 720.0); show() }
            Fixture(descriptor, store, service, service.DataAccess(descriptor), main, root, stage, factory(main, service))
        }
        val dialogs = fx {
            Timeline(KeyFrame(Duration.millis(20.0), {
                Platform.runLater {
                    Window.getWindows().toList().filter { it !== f.stage }.forEach { window ->
                        val root = window.scene?.root ?: return@forEach
                        if (root.lookup(".validated-input-dialog") != null) {
                            val input = root.lookupAll(".text-field").filterIsInstance<TextField>().single()
                            val answer = f.answers.poll()
                            if (f.cancelInput) root.lookupAll(".button").filterIsInstance<Button>().single { it.text == "キャンセル" }.fire()
                            else if (answer != null) {
                                input.text = answer
                                root.lookupAll(".button").filterIsInstance<Button>().single { it.text == "決定" }.fire()
                            }
                        } else if (root is DialogPane) {
                            val confirming = root.buttonTypes.any { it.buttonData == ButtonBar.ButtonData.CANCEL_CLOSE }
                            if (confirming) f.confirmations++ else f.errors += root.headerText.orEmpty()
                            val type = root.buttonTypes.firstOrNull { it.buttonData == if (confirming && !f.confirmDeletion) ButtonBar.ButtonData.CANCEL_CLOSE else ButtonBar.ButtonData.OK_DONE } ?: root.buttonTypes.first()
                            (root.lookupButton(type) as Button).fire()
                        }
                    }
                }
            })).apply { cycleCount = Timeline.INDEFINITE; play() }
        }
        try {
            fx { f.main.switchView(f.logic) }
            awaitFx { !f.main.loadingRunning && ids(f).size == 2 }
            service.submitIo {}.get(10, TimeUnit.SECONDS)
            block(f)
        } finally {
            val closed = CompletableFuture<Boolean>()
            fx { dialogs.stop(); f.logic.onClose { success -> f.stage.close(); closed.complete(success) } }
            assertTrue(closed.get(10, TimeUnit.SECONDS))
            service.submitIo {}.get(10, TimeUnit.SECONDS)
            service.submitBackupIo {}.get(10, TimeUnit.SECONDS)
            service.itemReferences.close()
            service.closeIo()
            directory.deleteRecursively()
        }
    }

    private class OperationStore(val memory: InMemoryEditorDataStore = InMemoryEditorDataStore()) : EditorDataStore by memory {
        override var kind = EditorDataStoreKind.REMOTE
        val ioThreads = CopyOnWriteArrayList<Boolean>()
        var beforeList: (() -> Unit)? = null
        var beforeLoad: ((String) -> Unit)? = null
        var beforeDelete: (() -> Unit)? = null
        var beforeCreate: (() -> Unit)? = null
        var deleteFailure: StoreErrorCode? = null
        var partialDeletion: String? = null
        var listFailureAfterDelete = false
        var createFailure = false
        var lists = 0
        var writes = 0
        var deletions = 0
        override fun <T : ManagedData<T, *>> list(descriptor: EditorDataDescriptor<T>): StoreResult<List<StoreResource>> {
            ioThreads += Platform.isFxApplicationThread(); lists++; beforeList?.invoke()
            if (listFailureAfterDelete && deletions > 0) return StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR))
            return memory.list(descriptor)
        }
        override fun <T : ManagedData<T, *>> load(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<T> {
            ioThreads += Platform.isFxApplicationThread(); beforeLoad?.invoke(id); return memory.load(descriptor, id)
        }
        override fun <T : ManagedData<T, *>> save(descriptor: EditorDataDescriptor<T>, id: String, data: T): StoreResult<Unit> {
            writes++; return memory.save(descriptor, id, data)
        }
        override fun <T : ManagedData<T, *>> createDirectory(descriptor: EditorDataDescriptor<T>, directory: String): StoreResult<Unit> {
            ioThreads += Platform.isFxApplicationThread(); beforeCreate?.invoke()
            if (createFailure) return StoreResult.Failure(StoreError(StoreErrorCode.PERMISSION_DENIED, directory))
            return memory.createDirectory(descriptor, directory)
        }
        override fun <T : ManagedData<T, *>> deleteDirectory(descriptor: EditorDataDescriptor<T>, directory: String): StoreResult<Unit> {
            ioThreads += Platform.isFxApplicationThread(); deletions++; beforeDelete?.invoke()
            partialDeletion?.let { memory.delete(descriptor, it) }
            return deleteFailure?.let { StoreResult.Failure(StoreError(it, directory)) } ?: memory.deleteDirectory(descriptor, directory)
        }
        override fun <T : ManagedData<T, *>> listDirectories(descriptor: EditorDataDescriptor<T>): StoreResult<List<String>> {
            ioThreads += Platform.isFxApplicationThread(); return memory.listDirectories(descriptor)
        }
    }

    companion object {
        private fun <R> fx(block: () -> R): R {
            val task = FutureTask(block); Platform.runLater(task); return task.get(15, TimeUnit.SECONDS)
        }
        private fun awaitFx(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!fx(condition)) { assertTrue(System.nanoTime() < deadline, "背景操作が完了しません"); Thread.sleep(20) }
        }
    }
}
