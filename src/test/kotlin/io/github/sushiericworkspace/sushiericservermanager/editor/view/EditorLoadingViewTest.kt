package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.app.workspaceStage
import io.github.sushiericworkspace.sushiericservermanager.config.WorkspaceFeature
import io.github.sushiericworkspace.sushiericservermanager.config.OS
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.ShortcutManager
import javafx.event.Event
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.InputMethodEvent
import javafx.scene.input.InputMethodTextRun
import javafx.scene.input.InputMethodHighlight
import javafx.scene.control.TextField
import javafx.collections.FXCollections
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
import java.util.concurrent.CompletableFuture
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

    @Test
    fun failedInitialImportDoesNotPublishHalfACategoryAndCanBeRetried() {
        fixture(EditorDataDescriptors.ore) { f ->
            f.store.failedId = "b"
            fx { f.main.switchView(f.view) }
            awaitFx { (f.root.lookup("#topInfoLabel") as Label).text.contains("失敗") }
            assertTrue((f.service.documents.read() as StoreResult.Success).value.documents.isEmpty())
            fx { assertNull(f.view.cached("a")); assertNull(f.view.cached("b")) }
            f.store.failedId = null
            fx { f.view.start() }
            awaitFx { !f.main.loadingRunning && f.view.cached("b") != null }
            val records = (f.service.documents.read() as StoreResult.Success).value.documents
            assertEquals(setOf("a", "b"), records.map { it.key.id }.toSet())
        }
    }

    @Test
    fun initialWorkingCopyIsPublishedBeforeItBecomesEditable() {
        fixture(EditorDataDescriptors.ore) { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.store.beforeLoad = { id -> if (id == "b") { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) } }
            try {
                fx { f.main.switchView(f.view) }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                fx {
                    assertTrue(f.main.loadingRunning)
                    assertTrue(f.main.mainContentContainer.isDisabled)
                    assertNull(f.view.cached("a"))
                    assertNull(f.view.cached("b"))
                }
                assertTrue((f.service.documents.read() as StoreResult.Success).value.documents.isEmpty())
            } finally { release.countDown() }
            awaitFx { !f.main.loadingRunning && f.view.cached("b") != null }
            fx { assertNotNull(f.view.cached("a")); assertFalse(f.main.mainContentContainer.isDisabled) }
        }
    }

    @Test
    fun `背景取得中に画面を閉じても行と進捗を反映しない`() {
        fixture(EditorDataDescriptors.ore) { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.store.beforeLoad = { id -> if (id == "b") { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) } }
            try {
                fx { f.main.switchView(f.view) }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val closed = CompletableFuture<Boolean>()
                fx { f.view.onClose { success -> f.stage.close(); closed.complete(success) } }
                release.countDown()
                assertTrue(closed.get(10, TimeUnit.SECONDS))
                f.service.submitIo {}.get(10, TimeUnit.SECONDS)
                fx { assertNull(f.view.cached("b")); assertEquals("", (f.root.lookup("#sidebarStatus") as Label).text) }
            } finally { release.countDown() }
        }
    }

    @Test
    fun `接続終了を取り消すと抑止した背景取得を再開する`() {
        fixture(EditorDataDescriptors.ore) { f ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            f.store.beforeLoad = { id -> if (id == "b") { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) } }
            try {
                fx { f.main.switchView(f.view) }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val prepared = CompletableFuture<Boolean>()
                fx { f.view.prepareClose { prepared.complete(it) } }
                release.countDown()
                assertTrue(prepared.get(10, TimeUnit.SECONDS))
                fx {
                    assertNull(f.view.cached("b"))
                    assertTrue(f.main.operationRunning)
                    f.view.cancelClose()
                    assertFalse(f.main.operationRunning)
                }
                awaitFx { !f.main.loadingRunning && f.view.cached("b") != null }
                fx { assertEquals(SidebarValidationStatus.CHECKED, f.view.sidebarState("b").validationStatus) }
            } finally { release.countDown() }
        }
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
                    assertTrue((f.root.lookup("#topInfoLabel") as Label).text.contains("復元"))
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
                val closed = CompletableFuture<Boolean>()
                fx { f.view.onClose { success -> f.stage.close(); closed.complete(success) } }
                assertFalse(closed.isDone)
                release.countDown()
                assertTrue(closed.get(10, TimeUnit.SECONDS))
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
                val closed = CompletableFuture<Boolean>()
                fx { f.main.switchView(f.view); assertTrue(f.main.loadingRunning); f.view.onClose { success -> f.stage.close(); closed.complete(success) } }
                assertTrue(closed.get(10, TimeUnit.SECONDS))
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
    fun localWorkingCopyRemainsReadableAfterTheConnectionBecomesUnavailable() {
        fixture(EditorDataDescriptors.ore) { f ->
            assertTrue(f.service.ores.listStoreResources() is StoreResult.Success)
            f.store.available = false
            f.store.loadFailure = true
            fx { f.view.selectTab("a") }
            awaitFx { f.view.shown.isNotEmpty() }
            fx { assertNotNull(f.view.cached("a")); assertFalse(f.main.loadingRunning) }
        }
    }

    @Test
    fun emptyInitialListAndFailedInitialListAreDifferentStates() {
        fixture(EditorDataDescriptors.ore) { f ->
            f.store.emptyList = true
            fx { f.main.switchView(f.view) }
            awaitFx { !f.main.loadingRunning }
            fx {
                val sidebar = f.main.sidebarContainer.children.filterIsInstance<EditorSidebar>().single()
                assertTrue((sidebar.placeholder as Label).text.contains("ありません"))
                assertTrue(f.view.shown.isEmpty())
            }
        }
        fixture(EditorDataDescriptors.ore) { f ->
            f.store.listFailure = true
            fx { f.main.switchView(f.view) }
            awaitFx { (f.root.lookup("#topInfoLabel") as Label).text.contains("失敗") }
            fx { assertTrue(f.view.shown.isEmpty()); assertFalse(f.main.loadingRunning) }
            assertTrue((f.service.documents.read() as StoreResult.Success).value.documents.isEmpty())
        }
    }

    @Test
    fun selectingACachedDocumentDoesNotFetchOrMergeItsSource() {
        fixture(EditorDataDescriptors.ore) { f ->
            val base = f.service.ores.createDefault("a")
            val local = base.deepCopy().apply { hardness = 17.0 }
            val source = base.deepCopy().apply { magicPowerAmount = 5.0 }
            f.store.memory.save(EditorDataDescriptors.ore, "a", source)
            f.store.beforeLoad = { error("選択時に接続元を取得してはいけません") }
            fx { f.view.seed(base, local); f.view.selectTab("a") }
            fx {
                assertEquals(17.0, f.view.cached("a")!!.hardness)
                assertEquals(base.magicPowerAmount, f.view.cached("a")!!.magicPowerAmount)
                assertEquals(base, f.view.original("a"))
            }
        }
    }

    @Test
    fun `実際の4種別エディターを一覧待ちより先に開いて初期データを表示する`() {
        for (kind in listOf(EditorDataStoreKind.IN_MEMORY)) {
            checkRealEditor(EditorDataDescriptors.item, kind) { main, service -> ItemEditorLogic(main, service) }
            checkRealEditor(EditorDataDescriptors.ore, kind) { main, service -> OreEditorLogic(main, service) }
            checkRealEditor(EditorDataDescriptors.shop, kind) { main, service -> ShopEditorLogic(main, service) }
            checkRealEditor(EditorDataDescriptors.recipe, kind) { main, service -> RecipeEditorLogic(main, service) }
        }
    }

    @Test
    fun `作業中の履歴ショートカットを拒否し代替Redoの登録と解除を守る`() {
        fixture(EditorDataDescriptors.ore) { f ->
            val previous = fx { ShortcutManager.overrides() }
            val base = f.service.ores.createDefault("a")
            val local = base.deepCopy().apply { hardness = 25.45 }
            fun press(code: KeyCode, shortcut: Boolean = true) = fx {
                Event.fireEvent(f.main.mainContentContainer, KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false,
                    shortcut && !OS.isMac, false, shortcut && OS.isMac))
            }
            try {
                fx {
                    ShortcutManager.save(emptyMap())
                    f.view.setupActions(f.main.actionButtonContainer)
                    f.view.seedHistory(base, local)
                    f.main.mainContentContainer.isFocusTraversable = true
                    f.main.mainContentContainer.requestFocus()
                    assertTrue(f.view.onUndo()); assertTrue(f.view.onRedo())
                    f.main.showOperation("取得と準備中")
                    assertEquals(0, f.service.activeSaves)
                }
                press(KeyCode.Z)
                fx { assertEquals(25.45, f.view.cached("a")!!.hardness); f.main.finishOperation(); f.main.mainContentContainer.requestFocus() }
                press(KeyCode.Z)
                fx { assertEquals(base.hardness, f.view.cached("a")!!.hardness); f.main.showOperation("取得と準備中") }
                press(KeyCode.Y)
                fx { assertEquals(base.hardness, f.view.cached("a")!!.hardness); f.main.finishOperation(); f.main.mainContentContainer.requestFocus() }
                press(KeyCode.Y)
                fx { assertEquals(25.45, f.view.cached("a")!!.hardness); ShortcutManager.save(mapOf("REDO_ALTERNATIVE" to "F7")) }
                press(KeyCode.Z); press(KeyCode.Y)
                fx { assertEquals(base.hardness, f.view.cached("a")!!.hardness) }
                press(KeyCode.F7, false)
                fx { assertEquals(25.45, f.view.cached("a")!!.hardness); f.main.clearShortcuts() }
                press(KeyCode.Z)
                fx { assertEquals(25.45, f.view.cached("a")!!.hardness) }
            } finally { fx { f.main.finishOperation(); ShortcutManager.save(previous) } }
        }
    }

    @Test
    fun `IME未確定値を文書履歴へ確定せず取消後のUndoで復活させない`() {
        fixture(EditorDataDescriptors.item) { f ->
            fx { f.main.switchView(f.view); f.view.selectTab("a") }
            awaitFx { f.view.cached("a") != null && !f.main.loadingRunning }
            fx {
                val model = f.view.cached("a")!!
                val before = model.display.displayName
                val field = TextField(before).apply {
                    textProperty().addListener { _, _, text -> model.display.displayName = text }
                }
                f.main.mainContentContainer.children.setAll(field)
                f.root.applyCss()
                f.root.layout()
                field.requestFocus()
                assertSame(field, f.stage.scene.focusOwner)
                field.positionCaret(field.length)
                fun input(composed: String, committed: String = "") = Event.fireEvent(field, InputMethodEvent(
                    InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                    FXCollections.observableArrayList<InputMethodTextRun>().apply {
                        if (composed.isNotEmpty()) add(InputMethodTextRun(composed, InputMethodHighlight.UNSELECTED_RAW))
                    }, committed, 0))
                input("未確定")
                assertEquals(before + "未確定", field.text)
                assertEquals(field.text, model.display.displayName)
                assertTrue(ShortcutManager.isComposing(f.stage.scene))
                Event.fireEvent(field, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.S, false, !OS.isMac, false, OS.isMac))
                input("")
                assertFalse(ShortcutManager.isComposing(f.stage.scene))
                assertEquals(before, model.display.displayName)
                assertFalse(f.view.onUndo())
                assertEquals(before, f.view.cached("a")!!.display.displayName)
                input("未確定")
                input("", "確定")
                assertEquals(before + "確定", model.display.displayName)
                Event.fireEvent(field, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
                assertTrue(f.view.onUndo())
                assertEquals(before, f.view.cached("a")!!.display.displayName)
                assertTrue(f.view.onRedo())
                assertEquals(before + "確定", f.view.cached("a")!!.display.displayName)
            }
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
        lateinit var stage: Stage
        try {
            fx {
                stage = workspaceStage()
                val feature = when (descriptor) {
                    EditorDataDescriptors.item -> WorkspaceFeature.ITEM_EDITOR
                    EditorDataDescriptors.ore -> WorkspaceFeature.ORE_EDITOR
                    EditorDataDescriptors.shop -> WorkspaceFeature.SHOP_EDITOR
                    else -> WorkspaceFeature.RECIPE_EDITOR
                }
                EditorWindowManager.openEditor(feature, "読み込み検証", FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))) { controller ->
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
            val closed = CompletableFuture<Boolean>()
            fx { EditorWindowManager.closeAll { closed.complete(it) } }
            assertTrue(closed.get(10, TimeUnit.SECONDS))
            fx { stage.close() }
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
            val closed = CompletableFuture<Boolean>()
            fx { dialogs.stop(); f.view.onClose { success -> f.stage.close(); closed.complete(success) } }
            assertTrue(closed.get(10, TimeUnit.SECONDS))
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
        fun seedHistory(base: T, local: T) {
            seed(base, base.deepCopy()); currentSelectedDataId = base.id; registeredSidebarIds += base.id; recordHistorySnapshot(base.id)
            editingDataMap[base.id] = local.deepCopy(); recordHistorySnapshot(base.id)
            setupMainContent(local); refreshActionMenuState()
        }
        fun cached(id: String): T? = editingDataMap[id]
        fun preload() = preloadSidebarDataForVisualStates(registeredSidebarIds)
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
        var failedId: String? = null
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
            if (loadFailure || id == failedId) return StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, id))
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
