package io.github.sushiericworkspace.sushiericservermanager.feature.modconfig

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.service.*
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.ShortcutManager
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.EditorShortcut
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.SavedUploadOperation
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.SavedUploadDocument
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.SavedUploadTargetVersion
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.SavedUploadStep
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.SavedUploadState
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.savedUploadFingerprint
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ModConfigDocumentTest {
    @TempDir lateinit var root: File

    @Test
    fun confirmedServerFetchIsSeparateFromLocalReloadAndKeepsInvalidSavedText() = fixture { f ->
        fx { assertFalse(f.fetch.isVisible); assertEquals("local: first", f.text.text) }
        f.server()
        fx {
            assertSame(f.text, f.text.scene.focusOwner)
            assertFalse(f.save.isDisabled)
            f.text.text = "invalid: ["
            f.save.fire()
            assertEquals("保存しています…", f.status.text, "Button.fireの保存が受け付けられません")
        }
        waitFx { f.status.text.contains("ローカル保存済み") }
        f.service.awaitIo()
        val before = (f.service.documents.read() as StoreResult.Success).value
        f.memory.writeText(f.service.modConfig.relativePath, "remote: latest")
        respond(f, "fetchButton", approve = false)
        waitFx { !f.status.text.contains("取得しています") && !f.text.isDisabled && !f.hasDialog() }
        assertEquals(before, (f.service.documents.read() as StoreResult.Success).value)
        respond(f, "fetchButton")
        waitFx { f.status.text.contains("未アップロード") && !f.text.isDisabled && !f.hasDialog() }
        val state = (f.service.modConfig.localState() as StoreResult.Success).value
        assertEquals("remote: latest", state.baseline)
        assertEquals("invalid: [", state.saved)
        fx { assertEquals("invalid: [", f.text.text); f.text.text = "uncommitted" }
        respond(f, "reloadButton")
        waitFx { f.text.text == "invalid: [" && !f.hasDialog() }
        assertEquals(0, f.remoteWrites)
    }

    @Test
    fun acknowledgedTextUsesRecordStateAcrossLoadSaveAndExplicitFetch() = fixture { f ->
        f.server()
        f.service.awaitIo()
        val old = (f.service.modConfig.localState() as StoreResult.Success).value
        val acknowledged = old.copy(saved = "local: acknowledged", editing = "local: acknowledged",
            original = "local: acknowledged", baseline = "remote: accepted", baselineKnown = true,
            reflectedSavedHash = savedUploadFingerprint(
                old.key, "local: acknowledged", false, emptyMap()))
        assertTrue(f.service.documents.publish(mapOf(old.key to old.revision), listOf(acknowledged)) is StoreResult.Success)
        f.memory.writeText(f.service.modConfig.relativePath, "remote: accepted")
        respond(f, "reloadButton")
        waitFx { f.text.text == "local: acknowledged" && f.status.text == "ローカル保存済み / 反映確認済み" }
        f.service.awaitIo()
        fx { f.save.fire() }
        waitFx { f.status.text == "ローカル保存済み / 反映確認済み" }
        f.service.awaitIo()
        respond(f, "fetchButton")
        waitFx { f.text.text == "remote: accepted" && f.status.text == "ローカル保存済み / 反映確認済み" }
        val state = (f.service.modConfig.localState() as StoreResult.Success).value
        assertEquals(state.saved, state.baseline)
        assertNull(state.reflectedSavedHash)
        assertFalse(state.unsaved)
        assertEquals(0, f.remoteWrites)
    }

    @Test
    fun staleControllerSaveKeepsItsDraftAndTheOtherWritersCheckpoint() = fixture { f ->
        f.server()
        val record = (f.service.modConfig.localState() as StoreResult.Success).value
        val other = f.newService()
        assertTrue(other.modConfig.saveObserved("another-editor", LocalDocumentVersion(record.key, record.revision)) is StoreResult.Success)
        val before = (other.documents.read() as StoreResult.Success).value
        fx { f.text.text = "stale-invalid: [" }
        respond(f, "saveButton")
        waitFx { f.status.text.contains("保存できませんでした") && !f.hasDialog() }
        assertEquals(before, (other.documents.read() as StoreResult.Success).value)
        fx { assertEquals("stale-invalid: [", f.text.text) }
        respond(f, "reloadButton")
        waitFx { f.text.text == "another-editor" && !f.hasDialog() }
        assertEquals(0, f.remoteWrites)
        other.closeIo()
    }

    @Test
    fun uploadAndSelectedRequestReadbackKeepUnparsedSavedTextAndNeverResend() = fixture { f ->
        f.server()
        fx { f.text.requestFocus(); ShortcutManager.activate(f.sceneRoot) }
        waitFx { !f.save.isDisabled && f.text.scene.focusOwner === f.text }
        fx {
            f.text.text = "invalid: ["
            assertTrue(ShortcutManager.execute(f.sceneRoot, EditorShortcut.SAVE),
                "準備完了したMod画面がローカル保存を受け付けません")
        }
        waitFx { f.status.text.contains("ローカル保存済み") && !f.text.isDisabled }
        f.service.awaitIo()
        assertEquals("invalid: [", (f.service.modConfig.localState() as StoreResult.Success).value.saved)
        f.allowWrites = true
        f.unknownWrite = true
        fx { f.text.text = "未保存: ["; (f.sceneRoot.lookup("#uploadButton") as Button).fire() }
        waitFx { f.dialogs().any { pane -> pane.buttonTypes.any { it.text == "確認した保存済み内容をアップロード" } } }
        fx {
            val pane = f.dialogs().single { it.buttonTypes.any { button -> button.text == "確認した保存済み内容をアップロード" } }
            val button = pane.buttonTypes.single { it.text == "確認した保存済み内容をアップロード" }
            Platform.runLater { (pane.lookupButton(button) as Button).fire() }
        }
        dismissResult(f)
        val unknown = (f.service.documents.read() as StoreResult.Success).value.uploads.single()
        assertTrue(unknown.needsReadback)
        assertEquals(1, f.remoteWrites)
        assertEquals("invalid: [", (f.memory.readText(f.service.modConfig.relativePath) as StoreResult.Success).value)
        f.unknownWrite = false
        fx {
            assertEquals("未保存: [", f.text.text)
            assertTrue(f.text.isDisabled)
            assertTrue((f.sceneRoot.lookup("#uploadButton") as Button).isDisabled)
            (f.sceneRoot.lookup("#readbackButton") as Button).fire()
        }
        confirmReadback(f)
        dismissResult(f)
        val confirmed = (f.service.documents.read() as StoreResult.Success).value.uploads.single()
        assertEquals(unknown.id, confirmed.id)
        assertTrue(confirmed.complete)
        assertEquals(1, f.remoteWrites)
        fx {
            assertFalse(f.text.isDisabled)
            assertEquals("未保存: [", f.text.text)
            assertTrue(f.status.text.contains("反映確認済み"))
        }
        val record = (f.service.modConfig.localState() as StoreResult.Success).value
        assertEquals("invalid: [", record.saved)
        assertEquals("invalid: [", record.baseline)
        assertEquals("未保存: [", record.editing)
        assertFalse(record.unreflected!!)
        assertTrue(record.unsaved)
    }

    @Test
    fun anUnknownJournalWithUnreadablePostResultStaysFrozenAndCanBeReadBackAfterReopening() =
        unreadablePostResult(queued = true)

    @Test
    fun aWritingJournalAfterUpdateFailureStaysFrozenAndCanBeReadBackAfterReopening() =
        unreadablePostResult(queued = false)

    private fun unreadablePostResult(queued: Boolean) = fixture { f ->
        f.server()
        fx { f.text.requestFocus(); ShortcutManager.activate(f.sceneRoot) }
        waitFx { !f.save.isDisabled && f.text.scene.focusOwner === f.text }
        fx { f.text.text = "saved: ["; assertTrue(ShortcutManager.execute(f.sceneRoot, EditorShortcut.SAVE)) }
        waitFx { f.status.text.contains("ローカル保存済み") && !f.text.isDisabled }
        f.service.awaitIo()
        f.allowWrites = true
        f.unknownWrite = true
        val key = (f.service.modConfig.localState() as StoreResult.Success).value.key
        val pointer = root.resolve("autosave/documents").resolve(f.service.documentSource.directory).resolve("current")
        var original: ByteArray? = null
        val corrupt = {
            original = pointer.readBytes()
            pointer.writeText("unavailable")
        }
        f.afterWrite = {
            if (queued) f.service.submitIo { corrupt() } else corrupt()
            Unit
        }
        fx { (f.sceneRoot.lookup("#uploadButton") as Button).fire() }
        waitFx { f.dialogs().any { pane -> pane.buttonTypes.any { it.text == "確認した保存済み内容をアップロード" } } }
        fx {
            val pane = f.dialogs().single { it.buttonTypes.any { button -> button.text == "確認した保存済み内容をアップロード" } }
            val button = pane.buttonTypes.single { it.text == "確認した保存済み内容をアップロード" }
            Platform.runLater { (pane.lookupButton(button) as Button).fire() }
        }
        try {
            dismissResult(f)
            fx {
                assertTrue(f.text.isDisabled)
                assertTrue((f.sceneRoot.lookup("#saveButton") as Button).isDisabled)
                assertEquals(setOf(key), f.service.unresolvedUploadDocuments.toSet())
                assertTrue(f.service.uploadingDocuments.isEmpty())
            }
            assertEquals(1, f.remoteWrites)
        } finally {
            original?.let { pointer.writeBytes(it) }
            f.afterWrite = null
        }
        val reopened = f.newService()
        try {
            val snapshot = (reopened.documents.read() as StoreResult.Success).value
            val operation = snapshot.uploads.single()
            assertEquals(if (queued) SavedUploadState.UNKNOWN else SavedUploadState.WRITING, operation.steps.single().state)
            fx { reopened.refreshUploadState() }
            reopened.awaitIo()
            waitFx { reopened.unresolvedUploadDocuments == f.service.unresolvedUploadDocuments }
        } finally { reopened.closeIo() }
        f.unknownWrite = false
        fx { (f.sceneRoot.lookup("#readbackButton") as Button).fire() }
        confirmReadback(f)
        dismissResult(f)
        assertEquals(1, f.remoteWrites)
        assertTrue((f.service.documents.read() as StoreResult.Success).value.uploads.single().complete)
        fx { assertFalse(f.text.isDisabled); assertTrue(f.service.unresolvedUploadDocuments.isEmpty()) }
    }

    @Test
    fun multipleFailedRequestsRequireSelectionAndCancellationPreservesEveryRecord() = fixture { f ->
        f.server()
        f.service.awaitIo()
        val record = (f.service.modConfig.localState() as StoreResult.Success).value
        val operation = SavedUploadOperation("failed-first", record.key.source as EditorDocumentSource.Server,
            listOf(SavedUploadDocument(record.key, record.revision, record.saved, false, emptyMap())),
            listOf(SavedUploadTargetVersion(record.key, record.revision)),
            listOf(SavedUploadStep(record.key, record.baseline, record.saved,
                state = SavedUploadState.FAILED, detail = "最初の要求の保留理由")))
        assertTrue(f.service.documents.publish(emptyMap(), emptyList(), upload = operation) is StoreResult.Success)
        val second = operation.copy(id = "failed-second",
            steps = operation.steps.map { it.copy(detail = "選択した要求の保留理由") })
        assertTrue(f.service.documents.publish(emptyMap(), emptyList(), upload = second) is StoreResult.Success)
        val before = (f.service.documents.read() as StoreResult.Success).value
        fx { (f.sceneRoot.lookup("#readbackButton") as Button).fire() }
        waitFx { f.dialogs().any { pane -> pane.buttonTypes.any { it.text == "この要求を確認" } } }
        fx {
            val pane = f.dialogs().single()
            val type = pane.buttonTypes.single { it.text == "この要求を確認" }
            assertTrue(pane.lookupButton(type).isDisabled)
            val choices = pane.content.lookup(".combo-box") as ComboBox<*>
            assertEquals(2, choices.items.size)
            assertEquals(-1, choices.selectionModel.selectedIndex)
            assertTrue(f.service.uploadingDocuments.isEmpty())
            Platform.runLater { (pane.lookupButton(ButtonType.CANCEL) as Button).fire() }
        }
        waitFx { !f.hasDialog() && f.service.uploadingDocuments.isEmpty() }
        assertEquals(before, (f.service.documents.read() as StoreResult.Success).value)
        fx { (f.sceneRoot.lookup("#readbackButton") as Button).fire() }
        selectRequest(f, 1)
        waitFx { f.dialogs().any { pane -> pane.buttonTypes.contains(ButtonType.OK) } }
        fx {
            val text = f.dialogs().single().lookupAll(".label").filterIsInstance<Label>().joinToString { it.text }
            assertTrue(text.contains("選択した要求の保留理由"))
            assertFalse(text.contains("最初の要求の保留理由"))
        }
        dismissResult(f)
        assertEquals(before, (f.service.documents.read() as StoreResult.Success).value)
        assertEquals(0, f.remoteWrites)
    }

    @Test
    fun `空文字へ編集した本文も再読込の取消で保持し明示破棄で保存済みへ戻す`() = fixture { f ->
        f.server()
        fx { f.text.text = "invalid: ["; f.save.fire() }
        waitFx { f.status.text.contains("ローカル保存済み") && !f.text.isDisabled }
        fx { f.text.text = "" }
        respond(f, "reloadButton", approve = false)
        fx { assertEquals("", f.text.text); assertTrue(f.status.text.contains("未保存")) }
        val backedUp = CompletableFuture<Boolean>()
        fx { f.controller.prepareClose { backedUp.complete(it) } }
        assertTrue(backedUp.get(10, TimeUnit.SECONDS))
        val before = (f.service.modConfig.localState() as StoreResult.Success).value
        assertEquals("invalid: [", before.saved)
        assertEquals("", before.editing)
        respond(f, "reloadButton")
        waitFx { f.text.text == "invalid: [" && !f.text.isDisabled }
        val after = (f.service.modConfig.localState() as StoreResult.Success).value
        assertEquals(before.saved, after.saved)
        assertEquals(before.baseline, after.baseline)
        assertEquals(true, after.unreflected)
        assertEquals(0, f.remoteWrites)
    }

    @Test
    fun `再読込の確認中に切り替えた保存元へ古い応答を適用しない`() = fixture { f ->
        f.server()
        fx { f.text.text = "server-draft: [" }
        respond(f, "reloadButton", beforeReply = {
            fx { (f.sceneRoot.lookup("#sourceMenu") as MenuButton).items.single { it.text == "ローカル" }.fire() }
            waitFx { f.text.text == "local: first" && f.text.isEditable && !f.text.isDisabled }
            fx { f.text.text = "local-draft: [" }
        })
        waitFx { !f.text.isDisabled }
        fx { assertEquals("local-draft: [", f.text.text); assertTrue(f.status.text.contains("未保存")) }
        assertEquals("server-draft: [", (f.service.modConfig.localState() as StoreResult.Success).value.editing)
        assertEquals(0, f.remoteWrites)
    }

    @Test
    fun `領域終了後は予約した読込と遅い読込完了を表示へ反映しない`() = fixture { f ->
        fun newController(): Pair<Parent, ModConfigController> {
            val loader = FXMLLoader(javaClass.getResource(AppScreen.MOD_CONFIG.fxml!!))
            return loader.load<Parent>() to loader.getController()
        }
        val early = fx { newController().also { (_, control) -> control.initData(f.service); control.dispose() } }
        fx { assertFalse((early.first.lookup("#contentArea") as TextArea).isEditable) }

        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.service.localService.submitIo { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        var late: Pair<Parent, ModConfigController>? = null
        try {
            val active = fx { newController().also { (_, control) -> control.initData(f.service) } }
            late = active
            val closed = CompletableFuture<Boolean>()
            fx {
                assertFalse((active.first.lookup("#contentArea") as TextArea).isEditable)
                active.second.prepareClose { closed.complete(it) }
                active.second.dispose()
            }
            assertFalse(closed.get(10, TimeUnit.SECONDS))
        } finally { release.countDown(); late?.let { fx { it.second.dispose() } } }
        f.service.localService.awaitIo()
        waitFx { f.service.localService.activeSaves == 0 }
        fx {
            for (pane in listOf(early.first, assertNotNull(late).first)) {
                val text = pane.lookup("#contentArea") as TextArea
                assertFalse(text.isEditable)
                assertEquals("", text.text)
            }
        }
    }

    private fun selectRequest(f: Fixture, index: Int = 0) {
        waitFx { f.dialogs().any { pane -> pane.buttonTypes.any { it.text == "この要求を確認" } } }
        fx {
            val pane = f.dialogs().single { it.buttonTypes.any { button -> button.text == "この要求を確認" } }
            val button = pane.buttonTypes.single { it.text == "この要求を確認" }
            assertTrue(pane.lookupButton(button).isDisabled)
            (pane.content.lookup(".combo-box") as ComboBox<*>).selectionModel.select(index)
            assertFalse(pane.lookupButton(button).isDisabled)
            Platform.runLater { (pane.lookupButton(button) as Button).fire() }
        }
    }

    private fun confirmReadback(f: Fixture) {
        selectRequest(f)
        waitFx { f.dialogs().any { pane -> pane.buttonTypes.any { it.text == "読戻しで確認" } } }
        fx {
            val pane = f.dialogs().single { it.buttonTypes.any { button -> button.text == "読戻しで確認" } }
            val button = pane.buttonTypes.single { it.text == "読戻しで確認" }
            Platform.runLater { (pane.lookupButton(button) as Button).fire() }
        }
    }

    private fun dismissResult(f: Fixture) {
        try {
            waitFx { f.dialogs().any { pane -> pane.buttonTypes.contains(ButtonType.OK) } }
        } catch (error: AssertionError) {
            val state = fx { "writes=" + f.remoteWrites + " status=" + f.status.text +
                " disabled=" + f.text.isDisabled + " dialogs=" + f.dialogs().map { pane ->
                    pane.buttonTypes.map { it.text } to pane.lookupAll(".label").filterIsInstance<Label>().map { it.text }
                } }
            throw AssertionError(state + " journal=" + f.service.documents.read(), error)
        }
        fx {
            val pane = f.dialogs().single { it.buttonTypes.contains(ButtonType.OK) }
            (pane.lookupButton(ButtonType.OK) as Button).fire()
        }
        waitFx { !f.hasDialog() }
        f.service.awaitIo()
    }

    private inner class Fixture(val service: EditorDataService, val memory: InMemoryEditorDataStore,
                                val sceneRoot: Parent, val controller: ModConfigController, val stage: Stage,
                                val newService: () -> EditorDataService) {
        var remoteWrites = 0
        var allowWrites = false
        var unknownWrite = false
        var afterWrite: (() -> Unit)? = null
        val text get() = sceneRoot.lookup("#contentArea") as TextArea
        val save get() = sceneRoot.lookup("#saveButton") as Button
        val reload get() = sceneRoot.lookup("#reloadButton") as Button
        val fetch get() = sceneRoot.lookup("#fetchButton") as Button
        val status get() = sceneRoot.lookup("#statusLabel") as Label
        fun dialogs() = Window.getWindows().filter { (it as? Stage)?.owner === stage }
            .mapNotNull { it.scene?.root?.lookup(".dialog-pane") as? DialogPane }
        fun hasDialog() = dialogs().isNotEmpty()
        fun server() {
            fx { (sceneRoot.lookup("#sourceMenu") as MenuButton).items.single { it.text == "サーバーの作業コピー" }.fire() }
            waitFx { text.text == "remote: first" && text.isEditable && !text.isDisabled }
            fx { text.requestFocus(); ShortcutManager.activate(sceneRoot) }
            waitFx { text.scene.focusOwner === text && !save.isDisabled }
        }
    }

    private fun fixture(action: (Fixture) -> Unit) {
        val ready = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
        catch (_: IllegalStateException) { Platform.runLater { Platform.setImplicitExit(false); ready.countDown() } }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        val memory = InMemoryEditorDataStore()
        lateinit var f: Fixture
        var initialized = false
        val source = object : EditorDataStore by memory {
            override val kind = EditorDataStoreKind.REMOTE
            override val documentSource = EditorDocumentSource.Server("mod-ui", "declared/root")
            override fun readText(relativePath: String): StoreResult<String> {
                if (initialized && f.unknownWrite && f.remoteWrites > 0)
                    return StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, detail = "書込後の読取応答が失われました"))
                return memory.readText(relativePath)
            }
            override fun writeText(relativePath: String, text: String): StoreResult<Unit> {
                if (initialized) f.remoteWrites++
                check(initialized && f.allowWrites) { "接続元へ保存してはいけません" }
                memory.writeText(relativePath, text)
                f.afterWrite?.invoke()
                return if (f.unknownWrite) StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR,
                    detail = "書込応答が失われました")) else StoreResult.Success(Unit)
            }
        }
        fun createService() = EditorDataService(source, root.resolve("autosave"))
        val service = createService()
        memory.writeText(service.modConfig.relativePath, "remote: first")
        service.localService.modConfig.save("local: first")
        f = fx {
            val loader = FXMLLoader(javaClass.getResource(AppScreen.MOD_CONFIG.fxml!!))
            val parent = loader.load<Parent>()
            val controller = loader.getController<ModConfigController>()
            val stage = Stage().apply { scene = Scene(parent, 1000.0, 700.0); show() }
            controller.initData(service)
            Fixture(service, memory, parent, controller, stage, ::createService)
        }
        initialized = true
        var primary: Throwable? = null
        try {
            waitFx { f.text.isEditable && f.text.text == "local: first" }
            action(f)
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            try {
                val closed = CompletableFuture<Boolean>()
                fx { f.controller.prepareClose { closed.complete(it) } }
                assertTrue(closed.get(10, TimeUnit.SECONDS))
            } catch (error: Throwable) {
                if (primary == null) throw error
                primary.addSuppressed(error)
            } finally {
                fx {
                    Window.getWindows().toList().filter { (it as? Stage)?.owner === f.stage }.forEach { it.hide() }
                    f.controller.dispose()
                    f.stage.close()
                }
                service.awaitIo()
                service.closeIo()
            }
        }
    }

    private fun respond(fixture: Fixture, buttonId: String, approve: Boolean = true, beforeReply: () -> Unit = {}) {
        val button = fx { fixture.sceneRoot.lookup("#$buttonId") as Button }
        waitFx { !button.isDisabled }
        fx { button.requestFocus() }
        waitFx { button.scene.focusOwner === button }
        val action = FutureTask { button.fire() }
        fx { Platform.runLater(action) }
        waitFx { fixture.hasDialog() }
        beforeReply()
        fx {
            val pane = fixture.dialogs().single()
            assertTrue(pane.contentText.contains("ローカル保存済み") || buttonId != "reloadButton")
            val role = if (approve) ButtonBar.ButtonData.OK_DONE else ButtonBar.ButtonData.CANCEL_CLOSE
            val type = pane.buttonTypes.first { it.buttonData == role }
            Platform.runLater { (pane.lookupButton(type) as Button).fire() }
        }
        action.get(15, TimeUnit.SECONDS)
        waitFx { !fixture.hasDialog() }
    }

    private fun <T> fx(action: () -> T): T {
        val task = FutureTask(action)
        Platform.runLater(task)
        return task.get(15, TimeUnit.SECONDS)
    }

    private fun waitFx(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!fx(condition)) {
            assertTrue(System.nanoTime() < deadline, "Mod文書操作が終了しません")
            Thread.sleep(20)
        }
    }
}
