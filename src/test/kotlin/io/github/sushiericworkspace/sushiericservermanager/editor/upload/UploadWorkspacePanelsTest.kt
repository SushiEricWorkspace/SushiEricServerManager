package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.app.*
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.*
import io.github.sushiericworkspace.sushiericservermanager.config.WorkspaceFeature
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.service.*
import io.github.sushiericworkspace.sushiericservermanager.editor.session.*
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import io.github.sushiericworkspace.sushiericservermanager.editor.view.*
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.ShortcutManager
import javafx.animation.*
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.event.Event
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.*
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Duration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.*
import java.io.File
import java.util.concurrent.*
import kotlin.test.*

class UploadWorkspacePanelsTest {
    @TempDir lateinit var directory: File

    @Test fun `左右同IDの文書を既存Viewで切り替えて入力とUndoを保存元別に保持する`() = fixture { f ->
        open(f, EditorDocumentSource.Local, "one")
        fx { assertEquals("local", f.view.field.text); edit(f.view.field, "local dirty") }
        open(f, f.service.documentSource, "one")
        fx { assertEquals("server", f.view.field.text); edit(f.view.field, "server dirty") }
        open(f, EditorDocumentSource.Local, "one")
        fx {
            assertEquals("local dirty", f.view.field.text)
            assertTrue(f.view.onUndo()); assertEquals("local", f.view.field.text)
            assertTrue(f.main.idTabContainer.lookupAll(".label").filterIsInstance<Label>().any { it.text.contains("ローカル / one") })
        }
        open(f, f.service.documentSource, "one")
        fx { assertEquals("server dirty", f.view.field.text); assertTrue(f.view.onUndo()); assertEquals("server", f.view.field.text) }
        assertEquals(0, f.remote.writes)
        assertEquals(1, f.created)
    }

    @Test fun `複数選択の正規保存と予約取消および複数宛先の復元は作用0`() = fixture { f ->
        f.diagnostic = true
        open(f, EditorDocumentSource.Local, "one")
        fx { edit(f.view.field, "saved by reservation") }
        val before = readPlan(f)
        f.cancel = true
        fx { reserveSelection(f, listOf("one", "folder"), "event") }
        idle(f)
        assertEquals(before, readPlan(f)); assertEquals(0, f.remote.writes)
        f.cancel = false
        fx { reserveSelection(f, listOf("one", "folder"), "event") }
        await { readPlan(f).groups.size == 1 }; idle(f)
        fx { reserveSelection(f, listOf("one", "folder"), "other") }
        await { readPlan(f).groups.size == 2 }; idle(f)
        val plan = readPlan(f)
        assertEquals(setOf("event.one", "event.two"), plan.groups.first().documents.map { it.targetId }.toSet())
        fx {
            assertEquals(listOf(ButtonBar.ButtonData.CANCEL_CLOSE, ButtonBar.ButtonData.OK_DONE,
                ButtonBar.ButtonData.OK_DONE, ButtonBar.ButtonData.OK_DONE), f.responses.map { it.second })
            assertEquals(f.responses.size, f.responses.map { it.first }.distinct().size)
        }
        assertNotEquals(plan.groups[0].itemIds, plan.groups[1].itemIds)
        assertEquals("saved by reservation", f.service.localService.items.decodeDocument("one",
            assertIs<StoreResult.Success<List<LocalDocumentRecord>>>(f.service.localService.items.localDocuments()).value.first { it.key.id == "one" }.saved!!).display.displayName)
        val restored = assertIs<StoreResult.Success<UploadPlan>>(UploadPlanService(f.service).read()).value
        assertEquals(plan, restored); assertTrue(restored.groups.all { it.operationId == null })
        assertEquals(0, f.remote.writes)
    }

    @Test fun `一括確認後だけ固定要求へ結合し待機中も非対象文書を編集保存できる`() = fixture { f ->
        fx { reserveSelection(f, listOf("one"), "event") }
        await { readPlan(f).groups.size == 1 }; idle(f)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.remote.beforeWrite = { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
        fx { (f.stage.scene.root.lookup("#upload-plan-apply") as Button).fire() }
        try {
            if (!entered.await(15, TimeUnit.SECONDS)) fail("write未到達: " + readPlan(f).groups.map { it.operationId })
            fx {
                val keys = f.service.uploadingDocuments.toSet()
                assertEquals(setOf(f.service.localService.items.documentKey("one"), f.service.items.documentKey("event.one")), keys)
            }
            open(f, EditorDocumentSource.Local, "other")
            fx { assertFalse(f.main.mainContentContainer.isDisabled); edit(f.view.field, "other dirty"); assertTrue(f.view.onUndo()) }
            val saved = CompletableFuture<EditorView.SaveOutcome>()
            fx { edit(f.view.field, "other saved"); f.view.onSave("other") { saved.complete(it) } }
            assertEquals(EditorView.SaveOutcome.SAVED, saved.get(10, TimeUnit.SECONDS))
        } finally { release.countDown(); f.remote.beforeWrite = null }
        idle(f)
        await { assertIs<StoreResult.Success<LocalDocumentSnapshot>>(f.service.documents.read()).value.uploads.any { it.complete } }
        assertEquals(1, f.remote.writes)
        assertNotNull(readPlan(f).groups.single().operationId)
    }

    @Test fun `編集用一覧を再表示でき未保存IDはLocalだけへ合成し送信扱いにしない`() = fixture { f ->
        open(f, EditorDocumentSource.Local, "one")
        fx {
            assertFalse(f.main.sidebarContainer.isVisible)
            val toggle = f.main.actionButtonContainer.children.filterIsInstance<MenuButton>().last().items.filterIsInstance<CheckMenuItem>().single()
            toggle.isSelected = true; toggle.fire()
            assertTrue(f.main.sidebarContainer.isVisible); assertTrue(f.main.sidebarContainer.isManaged)
            toggle.isSelected = false; toggle.fire()
            assertFalse(f.main.sidebarContainer.isManaged)
            f.view.createDraft("new.unsaved")
        }
        await { fx { nodes(tree(f, true).root).any { it.value.key.id == "new.unsaved" && !it.value.directory } } }
        fx { assertTrue(nodes(tree(f, false).root).none { it.value.key.id == "new.unsaved" }) }
        val record = assertIs<StoreResult.Success<LocalDocumentSnapshot>>(f.service.localService.documents.read()).value.documents.firstOrNull { it.key.id == "new.unsaved" }
        assertNull(record?.saved)
        assertTrue(readPlan(f).groups.isEmpty()); assertEquals(0, f.remote.writes)
        open(f, f.service.documentSource, "one")
        fx { assertTrue(nodes(tree(f, true).root).any { it.value.key.id == "new.unsaved" && !it.value.directory }) }
    }

    @Test fun `予約再割当削除と並替および横scrollは書込と順序を混同しない`() = fixture { f ->
        for (index in 0 until 15) {
            val id = "longpath.document" + index
            val item = f.service.localService.items.createDefault(id)
            assertIs<StoreResult.Success<*>>(f.service.localService.items.saveLocalDocument(id, item, emptyMap()))
        }
        fx { f.stage.scene.root.lookupAll(".button").filterIsInstance<Button>().single { it.text == "一覧を更新" }.fire() }
        await { fx { nodes(tree(f, true).root).any { it.value.key.id == "longpath" } } }
        fx { reserveSelection(f, listOf("longpath"), "event") }
        await { readPlan(f).groups.size == 1 }; idle(f)
        fx { reserveSelection(f, listOf("one"), "other") }
        await { readPlan(f).groups.size == 2 }; idle(f)
        val first = readPlan(f).groups.first()
        fx {
            f.stage.width = 700.0; f.stage.scene.root.applyCss(); f.stage.scene.root.layout()
            val view = groupList(f)
            val bar = view.lookupAll(".scroll-bar").filterIsInstance<ScrollBar>().first { it.orientation == javafx.geometry.Orientation.HORIZONTAL }
            assertTrue(bar.max > bar.min)
            val offset = bar.value
            view.lookup(".virtual-flow").fireEvent(ScrollEvent(ScrollEvent.SCROLL, 20.0, 10.0, 20.0, 10.0, false, false, false, false, false, false,
                -120.0, 0.0, -120.0, 0.0, ScrollEvent.HorizontalTextScrollUnits.NONE, 0.0, ScrollEvent.VerticalTextScrollUnits.NONE, 0.0, 0, null))
            assertTrue(bar.value > offset)
            assertEquals(first.id, view.items.first().id)
            view.selectionModel.select(0)
            tree(f, false).selectionModel.select(nodes(tree(f, false).root).first { it.value.directory && it.value.key.category == "items" && it.value.key.id == "other" })
            button(f, "宛先へ再割当て").fire()
        }
        await { readPlan(f).groups.first().targetDirectory == "other" }; idle(f)
        assertEquals(15, readPlan(f).groups.first().documents.size)
        assertNotEquals(first.itemIds, readPlan(f).groups.first().itemIds)
        fx { groupList(f).selectionModel.select(0); button(f, "↓").fire() }
        await { readPlan(f).groups.last().id == first.id }; idle(f)
        fx { groupList(f).selectionModel.select(1); button(f, "予約を削除").fire() }
        await { readPlan(f).groups.size == 1 }; idle(f)
        assertEquals("other.one", readPlan(f).groups.single().documents.single().targetId)
        assertEquals(0, f.remote.writes)
    }

    @Test fun `UNKNOWNを予約から同要求だけ読戻し再送せず保全凍結を解除する`() = fixture { f ->
        fx { reserveSelection(f, listOf("one"), "event") }
        await { readPlan(f).groups.size == 1 }; idle(f)
        f.remote.unknown = true
        fx { (f.stage.scene.root.lookup("#upload-plan-apply") as Button).fire() }
        idle(f)
        val request = readPlan(f).groups.single().operationId
        assertNotNull(request)
        val before = assertIs<StoreResult.Success<LocalDocumentSnapshot>>(f.service.documents.read()).value.uploads.single()
        assertTrue(before.needsReadback); assertEquals(1, f.remote.writes)
        fx { assertTrue(f.main.mainContentContainer.isDisabled); assertTrue(f.service.unresolvedUploadDocuments.isNotEmpty()) }
        f.remote.readFailed = false; f.remote.unknown = false; f.requestToSelect = request
        fx { groupList(f).selectionModel.select(0); button(f, "同じ要求を読戻し").fire() }
        idle(f)
        await { assertIs<StoreResult.Success<LocalDocumentSnapshot>>(f.service.documents.read()).value.uploads.single().complete }
        val after = assertIs<StoreResult.Success<LocalDocumentSnapshot>>(f.service.documents.read()).value.uploads.single()
        assertEquals(request, after.id); assertEquals(1, f.remote.writes)
        fx { assertTrue(f.service.unresolvedUploadDocuments.isEmpty()); assertFalse(f.main.mainContentContainer.isDisabled) }
    }

    @Test fun `IME中の反対保存元要求は拒否を返し次の文書要求を妨げない`() = fixture { f ->
        open(f, f.service.documentSource, "one")
        val rejected = CompletableFuture<Boolean>()
        fx {
            f.view.field.requestFocus()
            Event.fireEvent(f.view.field, InputMethodEvent(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                FXCollections.observableArrayList(InputMethodTextRun("未確定", InputMethodHighlight.UNSELECTED_RAW)), "", 0))
            val composedText = f.view.field.text
            assertTrue(ShortcutManager.isComposing(f.stage.scene))
            EditorWindowManager.openDocument(f.service.localService.items.documentKey("other"), true) { rejected.complete(it) }
            assertSame(f.view.field, f.stage.scene.focusOwner)
            assertEquals(composedText, f.view.field.text)
            assertTrue(ShortcutManager.isComposing(f.stage.scene))
        }
        assertFalse(rejected.get(5, TimeUnit.SECONDS))
        fx { Event.fireEvent(f.view.field, InputMethodEvent(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
            FXCollections.observableArrayList<InputMethodTextRun>(), "", 0)) }
        open(f, EditorDocumentSource.Local, "other")
        fx { assertEquals("local", f.view.field.text); edit(f.view.field, "after rejection"); assertTrue(f.view.onUndo()) }
        assertEquals(0, f.remote.writes)
    }

    @Test fun `予約の同じ行へのdropは順序とrevisionを変えず左右dropだけ移動する`() = fixture { f ->
        assertIs<StoreResult.Success<*>>(f.remote.files.createDirectory(f.service.items.descriptor, "third"))
        val seeded = f.service.submitIo {
            val plans = UploadPlanService(f.service)
            var plan = assertIs<StoreResult.Success<UploadPlan>>(plans.read()).value
            for (destination in listOf("event", "other", "third")) {
                val group = assertIs<StoreResult.Success<UploadPlanGroup>>(plans.createGroup(
                    listOf(UploadPlanRoot(f.service.localService.items.documentKey("one"))), destination)).value
                plan = assertIs<StoreResult.Success<UploadPlan>>(plans.save(plan.add(group))).value
            }
            plan
        }.get(10, TimeUnit.SECONDS)
        fx { button(f, "一覧を更新").fire() }; idle(f)
        await { fx { groupList(f).items.size == 3 } }
        val ids = seeded.groups.map { it.id }
        fun drop(source: String, target: String) = fx {
            val list = groupList(f)
            f.stage.scene.root.applyCss(); f.stage.scene.root.layout()
            val cell = list.lookupAll(".list-cell").filterIsInstance<ListCell<*>>().single { (it.item as? UploadPlanGroup)?.id == target }
            val board = mock(Dragboard::class.java)
            `when`(board.getContent(any(DataFormat::class.java))).thenReturn(source)
            val event = DragEvent(DragEvent.DRAG_DROPPED, board, 0.0, 0.0, 0.0, 0.0, TransferMode.MOVE, list, cell, null)
            cell.onDragDropped.handle(event)
            event.isDropCompleted
        }
        assertFalse(drop(ids[1], ids[1])); idle(f)
        assertEquals(seeded, readPlan(f))
        assertTrue(drop(ids[0], ids[2]))
        await { readPlan(f).groups.map { it.id } == listOf(ids[1], ids[0], ids[2]) }; idle(f)
        assertTrue(drop(ids[2], ids[1]))
        await { readPlan(f).groups.map { it.id } == listOf(ids[2], ids[1], ids[0]) }; idle(f)
        assertEquals(0, f.remote.writes)
    }

    @Test fun `upload中の非対象保存元切替は既存queueの正規退避後に完了する`() = fixture { f ->
        fx { reserveSelection(f, listOf("one"), "event") }
        await { readPlan(f).groups.size == 1 }; idle(f)
        open(f, f.service.documentSource, "other")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        f.remote.beforeWrite = { entered.countDown(); check(release.await(15, TimeUnit.SECONDS)) }
        val opened = CompletableFuture<Boolean>()
        fx { (f.stage.scene.root.lookup("#upload-plan-apply") as Button).fire() }
        try {
            assertTrue(entered.await(15, TimeUnit.SECONDS))
            fx {
                EditorWindowManager.openDocument(f.service.localService.items.documentKey("other")) { opened.complete(it) }
                assertFalse(opened.isDone)
                assertEquals(setOf(f.service.localService.items.documentKey("one"), f.service.items.documentKey("event.one")),
                    f.service.uploadingDocuments.toSet())
            }
        } finally { release.countDown(); f.remote.beforeWrite = null }
        assertTrue(opened.get(15, TimeUnit.SECONDS)); idle(f)
        fx { assertEquals("local", f.view.field.text); edit(f.view.field, "local after queued switch"); assertTrue(f.view.onUndo()) }
        val saved = CompletableFuture<EditorView.SaveOutcome>()
        fx { edit(f.view.field, "local saved"); f.view.onSave("other") { saved.complete(it) } }
        assertEquals(EditorView.SaveOutcome.SAVED, saved.get(10, TimeUnit.SECONDS))
        assertEquals(1, f.remote.writes)
    }

    @Test fun `同じ保存元の別画面保存中の要求は拒否を返し保存完了後に再選択できる`() = fixture { f ->
        open(f, EditorDocumentSource.Local, "one")
        val opened = CompletableFuture<Boolean>()
        fx {
            f.service.localService.beginSave()
            try { EditorWindowManager.openDocument(f.service.localService.items.documentKey("other"), true) { opened.complete(it) } }
            finally { f.service.localService.endSave() }
        }
        assertFalse(opened.get(5, TimeUnit.SECONDS))
        open(f, EditorDocumentSource.Local, "other")
        fx { assertEquals("local", f.view.field.text); edit(f.view.field, "after peer save"); assertTrue(f.view.onUndo()) }
        assertEquals(0, f.remote.writes)
    }

    @Test fun `宛先TreeCellへの同tokenだけが複数fileとfolderを相対予約して書込0`() = fixture { f ->
        val workspace = fx { WorkspaceManager.requireWorkspace() }
        val panels = workspace.javaClass.getDeclaredField("uploadPanels").apply { isAccessible = true }.get(workspace)!!
        val token = panels.javaClass.getDeclaredField("token").apply { isAccessible = true }.get(panels) as String
        panels.javaClass.getDeclaredField("dragged").apply { isAccessible = true }.set(panels, listOf(
            UploadPlanRoot(f.service.localService.items.documentKey("one")),
            UploadPlanRoot(f.service.localService.items.documentKey("folder"), true),
            UploadPlanRoot(f.service.localService.items.documentKey("folder.two"))))
        fun drop(value: String) = fx {
            val server = tree(f, false)
            val destination = nodes(server.root).first { it.value.key.category == "items" && it.value.directory && it.value.key.id == "event" }
            server.scrollTo(server.getRow(destination)); f.stage.scene.root.applyCss(); f.stage.scene.root.layout()
            val cell = server.lookupAll(".tree-cell").filterIsInstance<TreeCell<*>>().single { it.treeItem === destination }
            val board = mock(Dragboard::class.java)
            `when`(board.getContent(any(DataFormat::class.java))).thenReturn(value)
            val event = DragEvent(DragEvent.DRAG_DROPPED, board, 0.0, 0.0, 0.0, 0.0, TransferMode.COPY, tree(f, true), cell, null)
            cell.onDragDropped.handle(event)
            event.isDropCompleted
        }
        assertFalse(drop("foreign token")); idle(f)
        assertTrue(readPlan(f).groups.isEmpty()); assertEquals(0, f.remote.writes)
        assertTrue(drop(token))
        await { readPlan(f).groups.size == 1 }; idle(f)
        assertEquals(setOf("event.one", "event.two"), readPlan(f).groups.single().documents.map { it.targetId }.toSet())
        assertEquals(0, f.remote.writes)
    }

    @Test fun `cleanupのidle失敗でも一次例外と編集退避を保ち所有資源を解放する`() {
        startFx()
        val primary = IllegalStateException("本体の一次失敗")
        val idleFailure = IllegalStateException("強制したcleanup idle失敗")
        lateinit var owned: Fixture
        val unrelated = fx { Stage().apply { scene = Scene(VBox(Label("fixture外のStage"))); show() } }
        try {
            val actual = assertFailsWith<IllegalStateException> {
                fixture(cleanupIdle = { throw idleFailure }) { f ->
                    owned = f
                    open(f, EditorDocumentSource.Local, "one")
                    fx { edit(f.view.field, "cleanup draft") }
                    throw primary
                }
            }
            assertSame(primary, actual)
            assertEquals(listOf(idleFailure), actual.suppressed.toList())
            assertReleased(owned)
            val snapshot = assertIs<StoreResult.Success<LocalDocumentSnapshot>>(owned.service.localService.documents.read()).value
            val record = snapshot.documents.single { it.key.id == "one" && it.key.category == "items" }
            assertEquals("cleanup draft", owned.service.localService.items.decodeDocument("one", record.editing!!).display.displayName)
            fx { assertTrue(unrelated.isShowing) }
        } finally { fx { unrelated.close() } }
    }

    @Test fun `本体成功後のcleanup idle失敗も隠さず所有資源を解放する`() {
        startFx()
        val idleFailure = IllegalStateException("強制したcleanup idle失敗")
        lateinit var owned: Fixture
        val actual = assertFailsWith<IllegalStateException> {
            fixture(cleanupIdle = { throw idleFailure }) { owned = it }
        }
        assertSame(idleFailure, actual)
        assertTrue(actual.suppressed.isEmpty())
        assertReleased(owned)
    }

    @Test fun `cleanupは別fixtureのSessionをclearしない`() {
        startFx()
        val replacement = EditorDataService(Remote(LocalEditorDataStore(directory.resolve("replacement-remote"))),
            directory.resolve("replacement-app"))
        var replacementClosed = false
        val connection = object : EditorConnection {
            override val contextName = "replacement in-process fixture"
            override val compatibility = ManagerCompatibility.Compatible("0.0.0")
            override fun open() = replacement
            override fun close() { replacementClosed = true }
            override fun abort() = Unit
        }
        lateinit var owned: Fixture
        try {
            assertFailsWith<IllegalStateException> {
                fixture(cleanupIdle = {
                    EditorSession.resetMode()
                    EditorSession.startConnection(connection)
                }) { owned = it }
            }
            assertSame(replacement, EditorSession.dataService)
            assertFalse(replacementClosed)
            fx {
                assertEquals(Animation.Status.STOPPED, owned.timer.status)
                assertFalse(owned.stage.isShowing)
            }
        } finally {
            if (EditorSession.dataService === replacement) EditorSession.resetMode()
        }
        assertTrue(replacementClosed)
    }

    private fun assertReleased(f: Fixture) {
        fx {
            assertEquals(Animation.Status.STOPPED, f.timer.status)
            assertFalse(f.stage.isShowing)
            assertTrue(f.queuedResponses.isEmpty())
        }
        assertNull(EditorSession.dataService)
        assertNull(EditorSession.mode)
    }

    private class Remote(val files: LocalEditorDataStore) : EditorDataStore by files {
        override val kind = EditorDataStoreKind.REMOTE
        override val documentSource = EditorDocumentSource.Server("ui-plan", "declared/root")
        var writes = 0
        var unknown = false
        var readFailed = false
        var beforeWrite: (() -> Unit)? = null
        override fun <T : ManagedData<T, *>> save(descriptor: EditorDataDescriptor<T>, id: String, data: T): StoreResult<Unit> {
            writes++; beforeWrite?.invoke(); val result = files.save(descriptor, id, data)
            if (unknown) { readFailed = true; return StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR)) }
            return result
        }
        override fun <T : ManagedData<T, *>> load(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<T> =
            if (readFailed) StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR)) else files.load(descriptor, id)
    }
    private class View(main: MainController, service: EditorDataService) : EditorView<MutableItemBaseData>(main, service, service.items) {
        lateinit var field: TextField
        fun inputFocused(): Boolean? = if (::field.isInitialized) field.isFocused else null
        fun createDraft(id: String) {
            val data = dataAccess.createDefault(id)
            editingDataMap[id] = data; originalDataMap[id] = data.deepCopy()
            stageDataCreation(id); refreshLocalSidebar(id)
        }
        override fun setupSidebar(container: VBox, selectId: String?) {
            loadSidebar({}, { ids, _ ->
                registeredSidebarIds.clear(); registeredSidebarIds.addAll(ids)
                restoreDocumentTabs(ids, selectId)?.let(::selectTab)
            })
        }
        override fun setupMainContent(selectData: MutableItemBaseData) {
            field = TextField(selectData.display.displayName).apply {
                id = "plan-editor-name"
                textProperty().addListener { _, _, value -> selectData.display.displayName = value }
            }
            main.mainContentContainer.children.setAll(field)
        }
        override fun resolveSaveConflict(dataId: String, originalData: MutableItemBaseData, currentData: MutableItemBaseData,
            serverData: MutableItemBaseData) = currentData
    }
    private class Fixture(val service: EditorDataService, val remote: Remote) {
        lateinit var stage: Stage
        lateinit var main: MainController
        lateinit var view: View
        lateinit var timer: Timeline
        fun hasView(): Boolean = ::view.isInitialized
        val responses = mutableListOf<Pair<DialogPane, ButtonBar.ButtonData>>()
        val queuedResponses = mutableSetOf<DialogPane>()
        var diagnostic = false
        var lastState: String? = null
        var created = 0
        var cancel = false
        var requestToSelect: String? = null
    }
    private fun fixture(cleanupIdle: (Fixture) -> Unit = ::idle, action: (Fixture) -> Unit) {
        startFx()
        val remote = Remote(LocalEditorDataStore(directory.resolve("remote")))
        val service = EditorDataService(remote, directory.resolve("app")); val f = Fixture(service, remote)
        listOf("one", "other", "folder.two").forEach { id ->
            val local = service.localService.items.createDefault(id).apply { display.displayName = "local" }
            assertIs<StoreResult.Success<*>>(service.localService.items.saveLocalDocument(id, local, emptyMap()))
            assertIs<StoreResult.Success<*>>(remote.files.save(service.items.descriptor, id,
                service.items.createDefault(id).apply { display.displayName = "server" }))
        }
        for (path in listOf("event", "other")) assertIs<StoreResult.Success<*>>(remote.files.createDirectory(service.items.descriptor, path))
        val connection = object : EditorConnection {
            override val contextName = "in-process plan fixture"
            override val compatibility = ManagerCompatibility.Compatible("0.0.0")
            override fun open() = service
            override fun close() = Unit
            override fun abort() = Unit
        }
        fx {
            EditorSession.startConnection(connection)
            val home = VBox(Label("home")); val frame = AppFrame(AppScreen.HOME, home)
            val workspace = WorkspaceView(frame, home, null, { feature ->
                if (feature == WorkspaceFeature.ITEM_EDITOR) EditorWindowManager.openEditor(feature, "Item",
                    FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))) { main ->
                    f.created++; f.main = main; View(main, service).also { f.view = it }
                }
            }, { true }, {})
            frame.center = workspace
            f.stage = Stage().apply { scene = Scene(frame, 1400.0, 900.0); show() }
            f.timer = Timeline(KeyFrame(Duration.millis(20.0), {
                if (f.diagnostic) observe(f, "pulse")
                Window.getWindows().toList().filter { (it as? Stage)?.owner === f.stage }
                    .mapNotNull { it.scene?.root as? DialogPane }.forEach { pane ->
                    if (f.responses.none { it.first === pane } && f.queuedResponses.add(pane)) Platform.runLater {
                        try {
                            val window = pane.scene?.window as? Stage
                            if (window?.isShowing != true || window.owner !== f.stage) return@runLater
                            if (!f.cancel) {
                                if (pane.buttonTypes.any { it.text == "この要求を確認" }) {
                                    val request = f.requestToSelect
                                    if (request != null) pane.lookupAll(".combo-box").filterIsInstance<ComboBox<*>>().forEach { box ->
                                        val index = box.items.indexOfFirst { it.toString().startsWith(request + " /") }
                                        if (index >= 0) box.selectionModel.select(index)
                                    }
                                }
                                pane.lookupAll(".radio-button").filterIsInstance<RadioButton>().filter { it.userData == true }.forEach { it.isSelected = true }
                                pane.lookupAll(".check-box").filterIsInstance<CheckBox>().forEach { it.isSelected = true }
                            }
                            val affirmative = pane.buttonTypes.firstOrNull { it.buttonData == ButtonBar.ButtonData.OK_DONE }
                            val button = if (f.cancel && affirmative != null)
                                pane.buttonTypes.firstOrNull { it.buttonData == ButtonBar.ButtonData.CANCEL_CLOSE }
                            else affirmative
                            if (f.diagnostic) println("RESERVATION_DIALOG title=" + (pane.scene?.window as? Stage)?.title +
                                " buttons=" + pane.buttonTypes.map { it.buttonData } + " response=" + button?.buttonData + " cancel=" + f.cancel)
                            button?.let {
                                val control = pane.lookupButton(it) as Button
                                if (!control.isDisabled) {
                                    check(pane.scene.window.isShowing)
                                    f.responses.add(pane to it.buttonData)
                                    control.fire()
                                }
                            }
                        } finally { f.queuedResponses.remove(pane) }
                    }
                }
            })).apply { cycleCount = Timeline.INDEFINITE; play() }
        }
        var primary: Throwable? = null
        try {
            await { fx { tree(f, true).root != null && tree(f, false).root != null } }
            action(f)
        } catch (failure: Throwable) {
            primary = failure
            if (f.diagnostic) try { fx { observe(f, "failure") } }
            catch (observation: Throwable) { failure.addSuppressed(observation) }
            throw failure
        }
        finally {
            var cleanupFailure: Throwable? = null
            fun keep(failure: Throwable) {
                val previous = cleanupFailure
                if (previous == null) cleanupFailure = failure else previous.addSuppressed(failure)
            }
            f.remote.beforeWrite = null
            try { cleanupIdle(f) } catch (failure: Throwable) { keep(failure) }
            try {
                check(EditorSession.dataService === f.service)
                val done = CompletableFuture<Boolean>(); fx { EditorWindowManager.closeAll { done.complete(it) } }
                assertTrue(done.get(15, TimeUnit.SECONDS))
                check(EditorSession.dataService === f.service)
                EditorSession.resetMode()
            } catch (failure: Throwable) { keep(failure) }
            finally {
                try { fx { f.timer.stop(); f.stage.hide() } } catch (failure: Throwable) { keep(failure) }
            }
            cleanupFailure?.let { failure -> if (primary == null) throw failure else primary.addSuppressed(failure) }
        }
    }
    private fun open(f: Fixture, source: EditorDocumentSource, id: String) {
        val done = CompletableFuture<Boolean>()
        fx { EditorWindowManager.openDocument(EditorDocumentKey(source, f.service.items.dataType.categoryDirName, id)) { done.complete(it) } }
        assertTrue(done.get(15, TimeUnit.SECONDS))
    }
    private fun edit(field: TextField, text: String) {
        field.requestFocus(); field.text = text
        Event.fireEvent(field, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
    }
    @Suppress("UNCHECKED_CAST")
    private fun tree(f: Fixture, local: Boolean) = f.stage.scene.root.lookup(if (local) "#upload-local-tree" else "#upload-server-tree") as TreeView<UploadWorkspacePanels.Entry>
    private fun nodes(root: TreeItem<UploadWorkspacePanels.Entry>): List<TreeItem<UploadWorkspacePanels.Entry>> = root.children.flatMap { listOf(it) + nodes(it) }
    private fun reserveSelection(f: Fixture, ids: List<String>, destination: String) {
        val local = tree(f, true); val server = tree(f, false)
        local.selectionModel.clearSelection()
        ids.forEach { id -> local.selectionModel.select(nodes(local.root).first { it.value.key.category == "items" && it.value.key.id == id }) }
        server.selectionModel.select(nodes(server.root).first { it.value.key.category == "items" && it.value.directory && it.value.key.id == destination })
        if (f.diagnostic) observe(f, "reserve-before")
        (f.stage.scene.root.lookup("#upload-reserve-selection") as Button).fire()
        if (f.diagnostic) observe(f, "reserve-after")
    }
    private fun observe(f: Fixture, phase: String) {
        if (!f.hasView()) return
        val workspace = WorkspaceManager.requireWorkspace()
        val pending = EditorView::class.java.getDeclaredField("externalDocument").apply { isAccessible = true }.get(f.view) != null
        val state = "busy=" + workspace.uploadRunning + " saves=" + f.service.localService.activeSaves +
            " loading=" + f.main.loadingRunning + " operation=" + f.main.operationRunning + " pending=" + pending +
            " stageFocused=" + f.stage.isFocused + " fieldFocused=" + f.view.inputFocused() +
            " local=" + tree(f, true).selectionModel.selectedItems.map { it.value.key.id } +
            " destination=" + tree(f, false).selectionModel.selectedItem?.value?.key?.id +
            " reserveDisabled=" + (f.stage.scene.root.lookup("#upload-reserve-selection") as Button).isDisabled +
            " dialogs=" + Window.getWindows().filter { it.scene?.root is DialogPane }.map { (it as? Stage)?.title }
        if (phase != "pulse" || state != f.lastState) println("RESERVATION_STATE " + phase + " " + state)
        f.lastState = state
    }
    @Suppress("UNCHECKED_CAST")
    private fun groupList(f: Fixture) = f.stage.scene.root.lookup("#upload-plan-groups") as ListView<UploadPlanGroup>
    private fun button(f: Fixture, text: String) = f.stage.scene.root.lookupAll(".button").filterIsInstance<Button>().single { it.text == text }
    private fun readPlan(f: Fixture) = assertIs<StoreResult.Success<UploadPlan>>(f.service.submitIo { UploadPlanService(f.service).read() }.get(5, TimeUnit.SECONDS)).value
    private fun idle(f: Fixture) = await { fx { !WorkspaceManager.requireWorkspace().uploadRunning &&
        f.queuedResponses.isEmpty() && Window.getWindows().none { it.scene?.root is DialogPane } } }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!condition()) { check(System.nanoTime() < deadline) { "fixture待機が期限を超えました" }; Thread.sleep(20) }
    }
    companion object {
        private fun startFx() {
            val ready = CountDownLatch(1)
            try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
            catch (_: IllegalStateException) { Platform.runLater { Platform.setImplicitExit(false); ready.countDown() } }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
        }
        private fun <T> fx(action: () -> T): T {
            val task = FutureTask(action); Platform.runLater(task); return task.get(15, TimeUnit.SECONDS)
        }
    }
}
