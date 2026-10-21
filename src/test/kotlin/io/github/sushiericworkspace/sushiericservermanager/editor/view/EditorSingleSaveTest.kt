package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.ore.model.mutable.MutableOreBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.shop.model.mutable.MutableShopProductData
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.mutable.MutableRecipeData
import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericdatamodel.data.core.validation.SushiEricValidationError
import io.github.sushiericworkspace.sushiericdatamodel.data.core.validation.SushiEricValidationSeverity
import io.github.sushiericworkspace.sushiericservermanager.editor.validation.ValidationRepairRegistry
import io.github.sushiericworkspace.sushiericservermanager.editor.validation.ValidationRepairResult
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.component.EditorSpinnerFactory
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentSource
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.savedUploadFingerprint
import javafx.application.Platform
import javafx.event.Event
import javafx.collections.FXCollections
import javafx.scene.input.InputMethodEvent
import javafx.scene.input.InputMethodTextRun
import javafx.scene.input.InputMethodHighlight
import javafx.scene.input.KeyEvent
import javafx.scene.input.KeyCode
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.EditorShortcut
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.ShortcutManager
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

/** 実FXMLとローカルcodec・世代保存で工程を検証し、接続元への書込がないことを確認します。 */
@EnabledOnOs(OS.MAC, OS.WINDOWS)
class EditorSingleSaveTest {
    @Test
    fun localSaveForEveryTypeKeepsFxResponsiveAndNeverWritesItsSource() {
        verifySave(EditorDataDescriptors.item)
        verifySave(EditorDataDescriptors.ore)
        verifySave(EditorDataDescriptors.shop)
        verifySave(EditorDataDescriptors.recipe)
    }

    private fun <T : ManagedData<T, *>> verifySave(descriptor: EditorDataDescriptor<T>) = withFixture(descriptor) { fixture ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.service.submitIo { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val completed = CompletableFuture<EditorView.SaveOutcome>()
        val copy = fx { fixture.logic.current().deepCopy() }
        fx { fixture.logic.onSave(fixture.id) { completed.complete(it) } }
        try {
            fx {
                assertTrue(fixture.main.operationRunning)
                assertTrue(fixture.main.mainContentContainer.isDisabled)
                assertTrue(fixture.main.sidebarContainer.isDisabled)
                fixture.logic.onClose { assertFalse(it) }
                fixture.logic.onSave(fixture.id) { assertEquals(EditorView.SaveOutcome.BUSY, it) }
                assertFalse(fixture.logic.onUndo())
                fixture.root.applyCss(); fixture.root.layout()
                assertFalse(completed.isDone)
            }
        } finally { release.countDown() }
        assertEquals(EditorView.SaveOutcome.SAVED, completed.get(15, TimeUnit.SECONDS))
        val record = (fixture.service.DataAccess(descriptor).localDocuments() as StoreResult.Success).value.single()
        assertFalse(record.unsaved)
        assertEquals(copy, fixture.service.DataAccess(descriptor).decodeDocument(fixture.id, requireNotNull(record.saved)))
        assertEquals(0, fixture.store.writes)
        assertTrue(fixture.store.renames.isEmpty())
        fx { assertTrue(fixture.logic.unchanged()); assertFalse(fixture.main.operationRunning) }
    }

    @Test
    fun cancellingThePreviewDoesNotAdvanceTheCheckpoint() = withFixture(EditorDataDescriptors.item) { fixture ->
        val before = (fixture.service.documents.read() as StoreResult.Success).value
        fixture.cancel = true
        val result = save(fixture)
        assertEquals(EditorView.SaveOutcome.CANCELLED, result)
        assertEquals(before, (fixture.service.documents.read() as StoreResult.Success).value)
        fx { assertFalse(fixture.logic.unchanged()); assertFalse(fixture.main.operationRunning) }
        assertEquals(0, fixture.store.writes)
    }

    @Test
    fun localCodecFailureKeepsSavedAndEditingAndNotifiesOnce() {
        var fail = false
        withFixture(EditorDataDescriptors.recipe, backupWriter = { file, data ->
            if (fail) error("ローカルcodecの書込失敗")
            EditorDataDescriptors.recipe.saveBackup(file, data)
        }) { fixture ->
            val before = (fixture.service.documents.read() as StoreResult.Success).value
            fail = true
            val calls = java.util.concurrent.atomic.AtomicInteger()
            val completed = CompletableFuture<EditorView.SaveOutcome>()
            fx { fixture.logic.onSave(fixture.id) { calls.incrementAndGet(); completed.complete(it) } }
            assertEquals(EditorView.SaveOutcome.FAILED, completed.get(10, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            assertEquals(before, (fixture.service.documents.read() as StoreResult.Success).value)
            fx { assertFalse(fixture.logic.unchanged()); assertFalse(fixture.main.operationRunning) }
            fail = false
        }
    }

    @Test
    fun communicationReadFailureDoesNotPreventLocalSave() = withFixture(EditorDataDescriptors.item) { fixture ->
        fixture.store.readFailure = StoreErrorCode.PERMISSION_DENIED
        val reads = fixture.store.loads
        assertEquals(EditorView.SaveOutcome.SAVED, save(fixture))
        assertEquals(reads, fixture.store.loads)
        assertEquals(0, fixture.store.writes)
    }

    @Test
    fun localSaveDoesNotAdvanceTheConfirmedServerBaseline() = withFixture(EditorDataDescriptors.item) { fixture ->
        fixture.service.items.updateLocalDocument(fixture.id) { it.copy(baseline = "confirmed-server", baselineKnown = true) }
        fx { fixture.logic.start() }
        awaitIdle(fixture)
        fx { fixture.logic.current().editorMeta.comment = mutableListOf("ローカル編集") }
        assertEquals(EditorView.SaveOutcome.SAVED, save(fixture))
        val record = (fixture.service.items.localDocuments() as StoreResult.Success).value.single()
        assertEquals("confirmed-server", record.baseline)
        assertTrue(record.unreflected == true)
        assertFalse(record.unsaved)
    }

    @Test
    fun renameFailureKeepsTheOldCheckpointAndPendingNewId() {
        var fail = false
        withFixture(EditorDataDescriptors.item, backupWriter = { file, data ->
            if (fail) error("ID変更のcodec失敗")
            EditorDataDescriptors.item.saveBackup(file, data)
        }) { fixture ->
            val before = (fixture.service.documents.read() as StoreResult.Success).value
            fail = true
            try {
                fx { fixture.logic.current().itemDetail.maxStackSize = 32; fixture.logic.rename("other.renamed") }
                assertEquals(EditorView.SaveOutcome.FAILED, save(fixture, "other.renamed"))
                assertEquals(before, (fixture.service.documents.read() as StoreResult.Success).value)
                fx { assertEquals("other.renamed", fixture.logic.current().id); assertTrue(fixture.logic.pending()) }
                assertTrue(fixture.store.renames.isEmpty())
            } finally { fail = false }
        }
    }

    @Test
    fun savingARenameAndMovePublishesOneLocalIdentityWithoutChangingTheSource() = withFixture(EditorDataDescriptors.item) { fixture ->
        fx { fixture.logic.rename("other.renamed") }
        assertEquals(EditorView.SaveOutcome.SAVED, save(fixture, "other.renamed"))
        val records = (fixture.service.items.localDocuments() as StoreResult.Success).value
        assertTrue(records.single { it.key.id == "saved" }.savedDeleted)
        assertEquals("other.renamed", records.single { it.key.id == "saved" }.movedTo)
        assertNotNull(records.single { it.key.id == "other.renamed" }.saved)
        assertIs<StoreResult.Success<*>>(fixture.store.memory.load(EditorDataDescriptors.item, "saved"))
        assertIs<StoreResult.Failure>(fixture.store.memory.load(EditorDataDescriptors.item, "other.renamed"))
        assertTrue(fixture.store.renames.isEmpty())
    }

    @Test
    fun deletingAnItemUpdatesOnlyLocalReferencesAndTheLocalCheckpoint() = withFixture(EditorDataDescriptors.item) { fixture ->
        fixture.service.itemReferences.load().get(5, TimeUnit.SECONDS)
        fx { fixture.logic.delete() }
        assertEquals(EditorView.SaveOutcome.SAVED, save(fixture))
        assertTrue(fixture.service.itemReferences.snapshot.items.isEmpty())
        assertEquals(0, fixture.store.deletes)
        assertTrue((fixture.service.items.localDocuments() as StoreResult.Success).value.single().savedDeleted)
        assertIs<StoreResult.Success<*>>(fixture.store.memory.load(EditorDataDescriptors.item, "saved"))
    }

    @Test
    fun displayFailureAfterPublicationReportsSavedWithErrors() = withFixture(EditorDataDescriptors.item) { fixture ->
        fx { fixture.logic.failSidebar = true }
        assertEquals(EditorView.SaveOutcome.SAVED_WITH_ERRORS, save(fixture))
        assertFalse((fixture.service.items.localDocuments() as StoreResult.Success).value.single().unsaved)
        fx { fixture.logic.failSidebar = false; assertTrue(fixture.logic.unchanged()) }
    }

    @Test
    fun twoServicesWithDifferentEditedIdsCanCloseTheirViewsWithoutOverwritingEachOther() =
        withFixture(EditorDataDescriptors.item) { first ->
            val base = first.service.items.createDefault("second")
            assertIs<StoreResult.Success<*>>(first.service.items.saveLocalDocument("second", base, emptyMap()))
            val other = EditorDataService(InMemoryEditorDataStore(), first.service.autoSaveDirectory)
            lateinit var stage: Stage
            val second = fx {
                val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
                val root = loader.load<Parent>()
                val main = loader.getController<MainController>()
                stage = Stage().apply { scene = Scene(root); show() }
                TestView(main, other, other.items).apply { setupActions(main.actionButtonContainer); start() }
            }
            try {
                second.ready.get(10, TimeUnit.SECONDS)
                fx {
                    second.selectTab("second")
                    second.current().editorMeta.comment = mutableListOf("別文書の編集")
                }
                val left = CompletableFuture<Boolean>()
                val right = CompletableFuture<Boolean>()
                fx {
                    first.logic.onClose { left.complete(it) }
                    second.onClose { right.complete(it) }
                }
                assertTrue(left.get(10, TimeUnit.SECONDS))
                assertTrue(right.get(10, TimeUnit.SECONDS))
                assertEquals(listOf("編集中の変更"),
                    requireNotNull(first.service.items.loadBackupPair(first.id)).first.editorMeta.comment)
                assertEquals(listOf("別文書の編集"),
                    requireNotNull(other.items.loadBackupPair("second")).first.editorMeta.comment)
            } finally {
                fx { stage.close() }
                other.itemReferences.close()
                other.closeIo()
            }
        }

    @Test
    fun backgroundBackupAndCloseUseTheSameSerializedPublisher() {
        val descriptor = EditorDataDescriptors.item
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = java.util.concurrent.atomic.AtomicBoolean(true)
        withFixture(descriptor, backupWriter = { file, data ->
            assertFalse(Platform.isFxApplicationThread())
            if (first.compareAndSet(true, false)) {
                entered.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
            }
            descriptor.saveBackup(file, data)
        }) { fixture ->
            val backedUp = fx { fixture.logic.backup() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val closed = CompletableFuture<Boolean>()
            try {
                fx {
                    assertFalse(fixture.main.mainContentContainer.isDisabled)
                    fixture.logic.onClose { closed.complete(it) }
                    assertTrue(fixture.main.operationRunning)
                    fixture.logic.onSaveAll { assertEquals(EditorView.SaveOutcome.BUSY, it.outcome) }
                    assertFalse(closed.isDone)
                }
            } finally { release.countDown() }
            assertTrue(backedUp.get(10, TimeUnit.SECONDS))
            assertTrue(closed.get(10, TimeUnit.SECONDS))
            val pair = assertNotNull(fixture.service.items.loadBackupPair(fixture.id))
            assertEquals(fixture.id, pair.first.id)
            assertEquals(fixture.id, pair.second.id)
        }
    }

    @Test
    fun originalEncodingFailureDoesNotPublishHalfABackupAndCloseCanBeRetried() {
        val descriptor = EditorDataDescriptors.item
        var fail = true
        val writes = java.util.concurrent.atomic.AtomicInteger()
        withFixture(descriptor, backupWriter = { file, data ->
            if (writes.incrementAndGet() == 2 && fail) error("originalのcodec失敗")
            descriptor.saveBackup(file, data)
        }) { fixture ->
            val before = (fixture.service.documents.read() as StoreResult.Success).value
            val closed = CompletableFuture<Boolean>()
            fx { fixture.logic.onClose { closed.complete(it) } }
            assertFalse(closed.get(10, TimeUnit.SECONDS))
            assertEquals(before, (fixture.service.documents.read() as StoreResult.Success).value)
            fx { assertFalse(fixture.logic.unchanged()); assertFalse(fixture.main.operationRunning) }
            fail = false
            val retry = CompletableFuture<Boolean>()
            fx { fixture.logic.onClose { retry.complete(it) } }
            assertTrue(retry.get(10, TimeUnit.SECONDS))
            assertNotNull(fixture.service.items.loadBackupPair(fixture.id))
        }
    }

    @Test
    fun saveAllPublishesEveryTypeLocallyWithoutSourceWrites() {
        verifyBatch(EditorDataDescriptors.item)
        verifyBatch(EditorDataDescriptors.ore)
        verifyBatch(EditorDataDescriptors.shop)
        verifyBatch(EditorDataDescriptors.recipe)
    }

    private fun <T : ManagedData<T, *>> verifyBatch(descriptor: EditorDataDescriptor<T>) = withFixture(descriptor) { fixture ->
        val second = descriptor.createDefault(if (descriptor == EditorDataDescriptors.shop) "tab.second" else "second")
        fixture.service.DataAccess(descriptor).saveLocalDocument(second.id, second, emptyMap())
        selectStored(fixture, second.id)
        fx { fixture.logic.add(second, second.deepCopy().apply { editFixture(this, "2件目の編集") }) }
        val result = saveAll(fixture)
        assertEquals(EditorView.SaveOutcome.SAVED, result.outcome)
        assertEquals(2, result.saved.size)
        assertTrue((fixture.service.DataAccess(descriptor).localDocuments() as StoreResult.Success).value.none { it.unsaved })
        assertEquals(0, fixture.store.writes)
    }

    @Test
    fun saveAllKeepsAFailedDocumentAndSavesTheOtherDocuments() {
        var fail = false
        withFixture(EditorDataDescriptors.item, backupWriter = { file, data ->
            if (fail && data.id == "failed") error("対象別codec失敗")
            EditorDataDescriptors.item.saveBackup(file, data)
        }) { fixture ->
            addItem(fixture, "failed")
            fail = true
            val result = saveAll(fixture)
            assertTrue("saved" in result.saved)
            assertTrue("failed" in result.failed)
            fx { assertTrue(fixture.logic.isSaved("saved")); assertFalse(fixture.logic.isSaved("failed")) }
            fail = false
        }
    }

    @Test
    fun `単件失敗は全保存成功した同IDだけ解決し全保存失敗IDと別保存元を保持する`() {
        var failingId = ""
        withFixture(EditorDataDescriptors.item, backupWriter = { file, data ->
            if (data.id == failingId) error("対象別codec失敗")
            EditorDataDescriptors.item.saveBackup(file, data)
        }) { fixture ->
            val notifications = EditorSession.notifications
            val scope = fx { fixture.main.notificationScope().copy(target = fixture.id) }
            val other = scope.copy(source = "fixture255-other-source")
            try {
                notifications.condition(other, "save", "別保存元の失敗")
                failingId = fixture.id
                assertEquals(EditorView.SaveOutcome.FAILED, save(fixture))
                assertTrue(notifications.snapshot().any { it.scope == scope && it.key == "save" })
                failingId = ""
                val saved = saveAll(fixture)
                assertEquals(listOf(fixture.id), saved.saved)
                assertFalse(notifications.snapshot().any { it.scope == scope && it.key == "save" })
                assertTrue(notifications.snapshot().any { it.scope == other && it.key == "save" })
                addItem(fixture, "failed")
                failingId = "failed"
                val failed = saveAll(fixture)
                assertEquals(setOf("failed"), failed.failed.keys)
                assertTrue(notifications.snapshot().any {
                    it.scope == scope.copy(target = "failed") && it.key == "save" && it.text == failed.failed["failed"]
                })
                assertTrue(notifications.snapshot().any { it.scope == other && it.key == "save" })
            } finally {
                failingId = ""
                notifications.resolve(scope, "save")
                notifications.resolve(scope.copy(target = "failed"), "save")
                notifications.resolve(other, "save")
            }
        }
    }

    @Test
    fun `全保存を確認取消しても既存の対象失敗Bannerを消さない`() {
        var fail = false
        withFixture(EditorDataDescriptors.item, backupWriter = { file, data ->
            if (fail) error("保存codec失敗")
            EditorDataDescriptors.item.saveBackup(file, data)
        }) { fixture ->
            val notifications = EditorSession.notifications
            val scope = fx { fixture.main.notificationScope().copy(target = fixture.id) }
            try {
                fail = true
                assertEquals(EditorView.SaveOutcome.FAILED, save(fixture))
                fail = false
                fixture.cancel = true
                val cancelled = saveAll(fixture)
                assertEquals(EditorView.SaveOutcome.CANCELLED, cancelled.outcome)
                assertEquals(listOf(fixture.id), cancelled.notExecuted)
                assertTrue(notifications.snapshot().any { it.scope == scope && it.key == "save" })
            } finally { fail = false; notifications.resolve(scope, "save") }
        }
    }

    @Test
    fun cancellingSaveAllKeepsEveryCheckpointUnchanged() = withFixture(EditorDataDescriptors.item) { fixture ->
        addItem(fixture, "second")
        val before = (fixture.service.documents.read() as StoreResult.Success).value
        fixture.cancel = true
        val result = saveAll(fixture)
        assertEquals(EditorView.SaveOutcome.CANCELLED, result.outcome)
        assertEquals(before, (fixture.service.documents.read() as StoreResult.Success).value)
        assertEquals(0, fixture.store.writes)
    }

    @Test
    fun aQueuedBackupCannotResurrectThePreSaveCheckpoint() {
        for (all in listOf(false, true)) withFixture(EditorDataDescriptors.item) { fixture ->
            assertTrue(fx { fixture.logic.backup() }.get(10, TimeUnit.SECONDS))
            fx { fixture.logic.current().itemDetail.maxStackSize = 32 }
            if (all) assertEquals(EditorView.SaveOutcome.SAVED, saveAll(fixture).outcome)
            else assertEquals(EditorView.SaveOutcome.SAVED, save(fixture))
            assertNull(fixture.service.items.loadBackupPair(fixture.id))
            val record = (fixture.service.items.localDocuments() as StoreResult.Success).value.single()
            assertFalse(record.unsaved)
            assertEquals(32, fixture.service.items.decodeDocument(fixture.id, requireNotNull(record.saved)).itemDetail.maxStackSize)
        }
    }

    @Test
    fun sameIdSourcesKeepTheirEditsAndUndoRedoSeparate() = withFixture(EditorDataDescriptors.item, remote = true) { fixture ->
        fx {
            fixture.logic.current().itemDetail.maxStackSize = 32
            fixture.logic.selectTab(fixture.id)
            fixture.logic.current().itemDetail.maxStackSize = 48
            assertTrue(fixture.logic.onUndo())
        }
        selectSource(fixture, true)
        fx {
            assertEquals(1, fixture.logic.current().itemDetail.maxStackSize)
            fixture.logic.current().itemDetail.maxStackSize = 16
            fixture.logic.selectTab(fixture.id)
            fixture.logic.current().itemDetail.maxStackSize = 17
            assertTrue(fixture.logic.onUndo())
        }
        selectSource(fixture, false)
        fx {
            assertEquals(32, fixture.logic.current().itemDetail.maxStackSize)
            assertTrue(fixture.logic.onRedo())
            assertEquals(48, fixture.logic.current().itemDetail.maxStackSize)
        }
        selectSource(fixture, true)
        fx {
            assertEquals(16, fixture.logic.current().itemDetail.maxStackSize)
            assertTrue(fixture.logic.onRedo())
            assertEquals(17, fixture.logic.current().itemDetail.maxStackSize)
        }
        assertEquals(0, fixture.store.writes)
    }

    @Test
    fun explicitFetchKeepsUnsentSavedDataAndMergesEditingAgainstTheBaseline() =
        withFixture(EditorDataDescriptors.item, remote = true) { fixture ->
            selectSource(fixture, true)
            fx { fixture.logic.current().editorMeta.comment = mutableListOf("未送信") }
            assertEquals(EditorView.SaveOutcome.SAVED, save(fixture))
            val latest = (fixture.store.memory.load(EditorDataDescriptors.item, fixture.id) as StoreResult.Success).value
            latest.itemDetail.maxStackSize = 32
            fixture.store.memory.save(EditorDataDescriptors.item, fixture.id, latest)
            fx { fixture.logic.onSynchronizeSelected() }
            awaitIdle(fixture)
            val record = (fixture.service.items.localDocuments() as StoreResult.Success).value.single()
            val saved = fixture.service.items.decodeDocument(fixture.id, requireNotNull(record.saved))
            val baseline = fixture.service.items.decodeDocument(fixture.id, requireNotNull(record.baseline))
            assertEquals(1, saved.itemDetail.maxStackSize)
            assertEquals(listOf("未送信"), saved.editorMeta.comment)
            assertEquals(32, baseline.itemDetail.maxStackSize)
            assertTrue(baseline.editorMeta.comment.isEmpty())
            fx {
                assertEquals(32, fixture.logic.current().itemDetail.maxStackSize)
                assertEquals(listOf("未送信"), fixture.logic.current().editorMeta.comment)
            }
            assertTrue(record.unsaved)
            assertEquals(true, record.unreflected)
            assertEquals(0, fixture.store.writes)
        }

    @Test
    fun fetchingAcknowledgedRemoteOnlyFieldsKeepsCleanAndDirtyEditingRelativeToSaved() {
        for (dirty in listOf(false, true)) withFixture(EditorDataDescriptors.item, remote = true) { fixture ->
            selectSource(fixture, true)
            val old = (fixture.service.items.localDocuments() as StoreResult.Success).value.single()
            val saved = requireNotNull(old.saved)
            val latest = fixture.service.items.decodeDocument(fixture.id, saved).apply { itemDetail.maxStackSize = 32 }
            fixture.store.memory.save(EditorDataDescriptors.item, fixture.id, latest)
            assertTrue(fixture.service.items.updateLocalDocument(fixture.id) {
                it.copy(editing = saved, baseline = fixture.service.items.encodeDocument(latest), baselineKnown = true,
                    reflectedSavedHash = savedUploadFingerprint(
                        it.key, it.saved, it.savedDeleted, it.savedRaw))
            } is StoreResult.Success)
            selectSource(fixture, false)
            selectSource(fixture, true)
            if (dirty) fx { fixture.logic.current().editorMeta.comment = mutableListOf("未保存だけの変更") }
            fx { fixture.logic.onSynchronizeSelected() }
            awaitIdle(fixture)
            val record = (fixture.service.items.localDocuments() as StoreResult.Success).value.single()
            val checkpoint = fixture.service.items.decodeDocument(fixture.id, requireNotNull(record.saved))
            assertEquals(32, checkpoint.itemDetail.maxStackSize)
            fx {
                assertEquals(32, fixture.logic.current().itemDetail.maxStackSize)
                assertEquals(if (dirty) listOf("未保存だけの変更") else emptyList(), fixture.logic.current().editorMeta.comment)
            }
            assertEquals(dirty, record.unsaved)
            assertEquals(false, record.unreflected)
            assertEquals(0, fixture.store.writes)
        }
    }

    @Test
    fun uploadUsesOnlyTheSavedGenerationAndFreezesOnlyItsDocuments() =
        withFixture(EditorDataDescriptors.item, remote = true) { fixture ->
            assertEquals(EditorView.SaveOutcome.SAVED, save(fixture))
            val expected = fx { fixture.logic.current().deepCopy() }
            val other = EditorDataDescriptors.item.createDefault("other")
            fx {
                fixture.logic.add(other, other.deepCopy())
                fixture.logic.showDraft = true
                fixture.logic.selectTab(fixture.id)
                fixture.logic.current().editorMeta.comment = mutableListOf("送信しない編集中")
                fixture.logic.draftField.text = "-"
                fixture.main.mainContentContainer.requestFocus()
            }
            fixture.pauseDialogs = true
            fixture.onDialog = { pane ->
                val apply = pane.buttonTypes.firstOrNull { it.text == "確認した保存済み内容をアップロード" }
                if (apply != null) {
                    pane.lookupAll(".radio-button").filterIsInstance<javafx.scene.control.RadioButton>()
                        .filter { it.text.startsWith("保存済み:") }.forEach { it.isSelected = true }
                    pane.lookupAll(".check-box").filterIsInstance<javafx.scene.control.CheckBox>()
                        .forEach { it.isSelected = true }
                    Platform.runLater { (pane.lookupButton(apply) as Button).fire() }
                }
            }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.store.beforeWrite = {
                entered.countDown()
                assertTrue(release.await(15, TimeUnit.SECONDS))
            }
            fx { assertTrue(ShortcutManager.execute(fixture.main.shortcutRoot,
                EditorShortcut.UPLOAD)) }
            try {
                assertTrue(entered.await(15, TimeUnit.SECONDS), "アップロードの実writeへ到達しません")
                fx {
                    assertTrue(fixture.main.mainContentContainer.isDisabled)
                    assertFalse(fixture.main.sidebarContainer.isDisabled)
                    fixture.main.showOperation("対象外の処理")
                    fixture.main.finishOperation()
                    assertTrue(fixture.main.mainContentContainer.isDisabled)
                    fixture.main.showLoading("対象外の読込")
                    fixture.main.finishLoading()
                    assertTrue(fixture.main.mainContentContainer.isDisabled)
                    assertFalse(fixture.logic.onUndo())
                    fixture.logic.onSave { assertEquals(EditorView.SaveOutcome.BUSY, it) }
                    fixture.logic.onClose { assertFalse(it) }
                    fixture.logic.selectTab("other")
                    assertFalse(fixture.main.mainContentContainer.isDisabled)
                    fixture.logic.current().editorMeta.comment = mutableListOf("対象外は編集可能")
                    fixture.logic.selectTab(fixture.id)
                    assertTrue(fixture.main.mainContentContainer.isDisabled)
                    assertEquals("-", fixture.logic.draftField.text)
                }
            } finally {
                release.countDown()
                fixture.store.beforeWrite = null
                fixture.pauseDialogs = false
            }
            awaitIdle(fixture)
            fixture.service.awaitIo()
            val uploaded = (fixture.store.memory.load(EditorDataDescriptors.item, fixture.id) as StoreResult.Success).value
            assertEquals(expected.editorMeta.comment, uploaded.editorMeta.comment)
            assertEquals(1, fixture.store.writes)
            val local = (fixture.service.localService.items.localDocuments() as StoreResult.Success).value.single { it.key.id == fixture.id }
            assertEquals("-", local.raw["test-number"])
            assertTrue(local.savedRaw.isEmpty())
            assertFalse(local.baselineKnown)
            fx {
                assertEquals(listOf("送信しない編集中"), fixture.logic.current().editorMeta.comment)
                assertEquals("-", fixture.logic.draftField.text)
                assertFalse(fixture.main.mainContentContainer.isDisabled)
            }
            assertTrue((fixture.service.documents.read() as StoreResult.Success).value.uploads.single().complete)
        }

    @Test
    fun categoryReadbackFreezesOnlyTheExplicitlySelectedRequestWhileOtherDocumentsCanUndoAndSave() =
        withFixture(EditorDataDescriptors.item, remote = true) { fixture ->
            assertEquals(EditorView.SaveOutcome.SAVED, save(fixture))
            val other = EditorDataDescriptors.item.createDefault("other")
            fx {
                fixture.logic.add(other, other.deepCopy().apply { editorMeta.comment = mutableListOf("saved other") })
            }
            assertEquals(EditorView.SaveOutcome.SAVED, save(fixture, other.id))
            fx {
                fixture.logic.showDraft = true
                fixture.logic.selectTab(fixture.id)
            }
            fixture.pauseDialogs = true
            fixture.onDialog = { pane ->
                pane.buttonTypes.firstOrNull { it.text == "確認した保存済み内容をアップロード" }?.let { button ->
                    pane.lookupAll(".check-box").filterIsInstance<javafx.scene.control.CheckBox>()
                        .forEach { it.isSelected = true }
                    pane.lookupAll(".radio-button").filterIsInstance<javafx.scene.control.RadioButton>()
                        .filter { it.text.startsWith("保存済み:") }.forEach { it.isSelected = true }
                    Platform.runLater { (pane.lookupButton(button) as Button).fire() }
                }
            }
            fixture.store.beforeWrite = { fixture.store.readFailure = StoreErrorCode.IO_ERROR }
            fx { fixture.logic.onUploadSaved() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (fx { fixture.service.uploadingDocuments.isNotEmpty() }) {
                assertTrue(System.nanoTime() < deadline)
                Thread.sleep(20)
            }
            fixture.store.beforeWrite = null
            fixture.store.readFailure = null
            fixture.pauseDialogs = false
            awaitIdle(fixture)
            val operation = (fixture.service.documents.read() as StoreResult.Success).value.uploads.single()
            assertTrue(operation.needsReadback)
            fixture.pauseDialogs = true
            fixture.onDialog = null
            fx { fixture.logic.onUploadSaved(all = true, readback = true) }
            fun pane(text: String): DialogPane? = Window.getWindows().mapNotNull { it.scene?.root as? DialogPane }
                .firstOrNull { it.buttonTypes.any { type -> type.text == text } }
            fun waitPane(text: String) {
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (!fx { pane(text) != null }) {
                    assertTrue(System.nanoTime() < until)
                    Thread.sleep(20)
                }
            }
            waitPane("この要求を確認")
            fx {
                assertTrue(fixture.service.uploadingDocuments.isEmpty())
                val dialog = requireNotNull(pane("この要求を確認"))
                val button = dialog.buttonTypes.single { it.text == "この要求を確認" }
                assertTrue(dialog.lookupButton(button).isDisabled)
                (dialog.content.lookup(".combo-box") as javafx.scene.control.ComboBox<*>).selectionModel.select(0)
                Platform.runLater { (dialog.lookupButton(button) as Button).fire() }
            }
            waitPane("読戻しで確認")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.store.beforeLoad = { entered.countDown(); assertTrue(release.await(15, TimeUnit.SECONDS)) }
            fx {
                val dialog = requireNotNull(pane("読戻しで確認"))
                val button = dialog.buttonTypes.single { it.text == "読戻しで確認" }
                Platform.runLater { (dialog.lookupButton(button) as Button).fire() }
            }
            try {
                assertTrue(entered.await(15, TimeUnit.SECONDS))
                fx {
                    assertEquals(setOf(operation.inputs.single().key, operation.targetRevisions.single().key),
                        fixture.service.uploadingDocuments.toSet())
                    fixture.logic.selectTab(other.id)
                    assertFalse(fixture.main.mainContentContainer.isDisabled)
                    fixture.logic.draftField.text = "-"
                    fixture.main.mainContentContainer.requestFocus()
                    assertTrue(fixture.logic.onUndo())
                    assertEquals("1", fixture.logic.draftField.text)
                    fixture.logic.current().editorMeta.comment = mutableListOf("other saved during readback")
                }
                val dialogState = java.util.concurrent.atomic.AtomicReference<List<String>>(emptyList())
                fixture.onDialog = { dialog ->
                    dialogState.set(dialog.buttonTypes.map { type -> type.text })
                    dialog.buttonTypes.singleOrNull { it.text == "保存" }?.let { button ->
                        Platform.runLater { (dialog.lookupButton(button) as Button).fire() }
                    }
                }
                val completed = CompletableFuture<EditorView.SaveOutcome>()
                Platform.runLater {
                    try { fixture.logic.onSave(other.id) { completed.complete(it) } }
                    catch (error: Throwable) { completed.completeExceptionally(error) }
                }
                val outcome = try { completed.get(5, TimeUnit.SECONDS) } catch (error: Exception) {
                    val stacks = Thread.getAllStackTraces().filter {
                        it.key.name == "JavaFX Application Thread" || it.key.name == "editor-store-io"
                    }.map { it.key.name + "\n" + it.value.joinToString("\n") }
                    throw AssertionError("読戻し中の別文書保存: Dialog=" + dialogState.get() + "\n" + stacks.joinToString("\n"), error)
                }
                assertEquals(EditorView.SaveOutcome.SAVED, outcome)
                fx {
                    fixture.logic.selectTab(fixture.id)
                    assertTrue(fixture.main.mainContentContainer.isDisabled)
                }
            } finally {
                release.countDown()
                fixture.store.beforeLoad = null
                fixture.pauseDialogs = false
            }
            awaitIdle(fixture)
            assertEquals(1, fixture.store.writes)
            assertTrue((fixture.service.documents.read() as StoreResult.Success).value.uploads.single().complete)
        }

    @Test
    fun aLaterJournalFailureStillAcceptsItsOwnConfirmedRevisionForDirtyBackupAndClose() =
        withFixture(EditorDataDescriptors.item, remote = true) { fixture ->
            val second = EditorDataDescriptors.item.createDefault("second")
            fixture.store.memory.save(EditorDataDescriptors.item, second.id, second)
            assertTrue(fixture.service.items.observeStore(second.id) is StoreResult.Success)
            selectSource(fixture, true)
            fx { fixture.logic.current().editorMeta.comment = mutableListOf("saved first") }
            assertEquals(EditorView.SaveOutcome.SAVED, save(fixture))
            fx { fixture.logic.selectTab(second.id) }
            awaitIdle(fixture)
            fixture.service.awaitIo()
            fx {
                assertEquals(second.id, fixture.logic.current().id)
                fixture.logic.current().editorMeta.comment = mutableListOf("saved second")
            }
            assertEquals(EditorView.SaveOutcome.SAVED, save(fixture, second.id))
            fx {
                fixture.logic.showDraft = true
                fixture.logic.selectTab(fixture.id)
                fixture.logic.current().editorMeta.comment = mutableListOf("dirty after saved")
                fixture.logic.draftField.text = "-"
                fixture.main.mainContentContainer.requestFocus()
            }
            val lock = fixture.service.autoSaveDirectory.resolve("documents")
                .resolve(fixture.service.documentSource.directory).resolve("publisher.lock")
            val original = lock.resolveSibling("publisher.lock-original")
            val fault = CountDownLatch(1)
            fixture.store.beforeLoad = {
                val snapshot = (fixture.service.documents.read() as StoreResult.Success).value
                if (fault.count > 0 && snapshot.uploads.any { op ->
                        op.steps.any { it.state == io.github.sushiericworkspace.sushiericservermanager.editor.upload.SavedUploadState.CONFIRMED } }) {
                    java.nio.file.Files.move(lock.toPath(), original.toPath())
                    assertTrue(lock.mkdir())
                    fault.countDown()
                }
            }
            fixture.pauseDialogs = true
            fixture.onDialog = { pane ->
                pane.buttonTypes.firstOrNull { it.text == "確認した保存済み内容をアップロード" }?.let { button ->
                    Platform.runLater { (pane.lookupButton(button) as Button).fire() }
                }
            }
            fx { fixture.logic.onUploadSaved(all = true) }
            try {
                assertTrue(fault.await(15, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                while (fx { fixture.service.uploadingDocuments.isNotEmpty() }) {
                    assertTrue(System.nanoTime() < deadline)
                    Thread.sleep(20)
                }
            } finally {
                fixture.store.beforeLoad = null
                if (original.exists()) {
                    assertTrue(lock.delete())
                    java.nio.file.Files.move(original.toPath(), lock.toPath())
                }
                fixture.pauseDialogs = false
            }
            awaitIdle(fixture)
            val operation = (fixture.service.documents.read() as StoreResult.Success).value.uploads.single()
            assertEquals(1, operation.steps.count {
                it.state == io.github.sushiericworkspace.sushiericservermanager.editor.upload.SavedUploadState.CONFIRMED })
            assertEquals(1, fixture.store.writes)
            val backup = fx { fixture.logic.backup() }
            assertTrue(backup.get(10, TimeUnit.SECONDS), "自己成功revisionへ追随できず未保存退避が拒否されました")
            fixture.service.awaitIo()
            val record = (fixture.service.documents.read() as StoreResult.Success).value.documents.single { it.key.id == fixture.id }
            assertEquals(listOf("dirty after saved"), fx { fixture.logic.current().editorMeta.comment })
            assertEquals("-", record.raw["test-number"])
            assertTrue(record.unsaved)
        }

    @Test
    fun cancellingOrFailingExplicitFetchKeepsTheGeneration() = withFixture(EditorDataDescriptors.item, remote = true) { fixture ->
        selectSource(fixture, true)
        val before = (fixture.service.documents.read() as StoreResult.Success).value
        fixture.cancel = true
        fx { fixture.logic.onSynchronizeSelected() }
        awaitIdle(fixture)
        assertEquals(before, (fixture.service.documents.read() as StoreResult.Success).value)
        fixture.cancel = false
        fixture.store.readFailure = StoreErrorCode.IO_ERROR
        fx { fixture.logic.onSynchronizeSelected() }
        awaitIdle(fixture)
        assertEquals(before, (fixture.service.documents.read() as StoreResult.Success).value)
        assertEquals(0, fixture.store.writes)
    }

    @Test
    fun delayedFetchCannotSwitchItsSourceOrCloseBeforeItFinishes() = withFixture(EditorDataDescriptors.item, remote = true) { fixture ->
        selectSource(fixture, true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.store.beforeLoad = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        fx { fixture.logic.onSynchronizeSelected() }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            fx {
                val menu = fixture.main.actionButtonContainer.children.filterIsInstance<javafx.scene.control.MenuButton>().last()
                menu.items.first().fire()
                assertEquals(fixture.service.documentSource, fixture.logic.source())
                fixture.logic.onClose { assertFalse(it) }
                assertTrue(fixture.main.operationRunning)
            }
        } finally { release.countDown(); fixture.store.beforeLoad = null }
        awaitIdle(fixture)
        assertEquals(fixture.service.documentSource, fx { fixture.logic.source() })
        assertEquals(0, fixture.store.writes)
    }

    private fun selectSource(fixture: Fixture<*>, remote: Boolean) {
        fx {
            val menu = fixture.main.actionButtonContainer.children.filterIsInstance<javafx.scene.control.MenuButton>().last()
            menu.items.single { it.text == if (remote) "サーバーの作業コピー" else "ローカル" }.fire()
        }
        awaitIdle(fixture)
        assertEquals(if (remote) fixture.service.documentSource else fixture.service.localService.documentSource,
            fx { fixture.logic.source() })
    }

    private fun awaitIdle(fixture: Fixture<*>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (fx { fixture.main.operationRunning || fixture.main.loadingRunning ||
                Window.getWindows().any { it.scene?.root is DialogPane } }) {
            assertTrue(System.nanoTime() < deadline, "保存元操作が終了しません")
            Thread.sleep(20)
        }
    }

    @Test
    fun invalidRawOnlyChangesAreUndoneAndRedoneWithTheTypedDocument() = withFixture(EditorDataDescriptors.ore) { fixture ->
        fx {
            fixture.logic.restoreOriginal()
            fixture.logic.showDraft = true
            fixture.logic.selectTab(fixture.id)
            fixture.logic.draftField.text = "-"
            fixture.main.mainContentContainer.requestFocus()
            assertTrue(fixture.logic.onUndo())
            assertEquals("1", fixture.logic.draftField.text)
            assertTrue(fixture.logic.onRedo())
            assertEquals("-", fixture.logic.draftField.text)
        }
        assertEquals(EditorView.SaveOutcome.SAVED, save(fixture))
        fx {
            fixture.logic.draftField.text = "--"
            fixture.main.mainContentContainer.requestFocus()
            assertTrue(fixture.logic.onUndo())
            assertEquals("-", fixture.logic.draftField.text)
        }
        val record = (fixture.service.ores.localDocuments() as StoreResult.Success).value.single()
        assertEquals("-", record.savedRaw["test-number"])
    }

    @Test
    fun rawCompositionIsNotRecordedUntilCommitAndCancellationDoesNotReappearInUndo() = withFixture(EditorDataDescriptors.ore) { fixture ->
        fx {
            fixture.logic.restoreOriginal()
            fixture.logic.showDraft = true
            fixture.logic.selectTab(fixture.id)
            val field = fixture.logic.draftField
            fixture.root.applyCss(); fixture.root.layout()
            field.requestFocus()
            assertSame(field, fixture.stage.scene.focusOwner)
            field.positionCaret(field.length)
            fun input(composed: String, committed: String = "") = Event.fireEvent(field, InputMethodEvent(
                InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                FXCollections.observableArrayList<InputMethodTextRun>().apply {
                    if (composed.isNotEmpty()) add(InputMethodTextRun(composed, InputMethodHighlight.UNSELECTED_RAW))
                }, committed, 0))
            input("-")
            assertEquals("1-", field.text)
            assertTrue(ShortcutManager.isComposing(fixture.stage.scene))
            assertFalse(fixture.logic.onUndo())
            input("")
            assertEquals("1", field.text)
            assertFalse(fixture.logic.onUndo())
            input("-")
            input("", "-")
            assertEquals("1-", field.text)
            Event.fireEvent(field, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
            assertTrue(fixture.logic.onUndo())
            assertEquals("1", fixture.logic.draftField.text)
            assertTrue(fixture.logic.onRedo())
            assertEquals("1-", fixture.logic.draftField.text)
        }
    }

    private fun <T : ManagedData<T, *>> save(fixture: Fixture<T>, id: String = fixture.id): EditorView.SaveOutcome {
        val completed = CompletableFuture<EditorView.SaveOutcome>()
        fx { fixture.logic.onSave(id) { completed.complete(it) } }
        return completed.get(15, TimeUnit.SECONDS)
    }

    private fun <T : ManagedData<T, *>> saveAll(fixture: Fixture<T>): EditorView.BatchSaveResult {
        val completed = CompletableFuture<EditorView.BatchSaveResult>()
        fx { fixture.logic.onSaveAll { completed.complete(it) } }
        return completed.get(15, TimeUnit.SECONDS)
    }

    private fun selectStored(fixture: Fixture<*>, id: String) {
        fx { fixture.logic.selectTab(id) }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!fx { !fixture.main.loadingRunning && fixture.logic.current().id == id }) {
            assertTrue(System.nanoTime() < deadline, "保存済み文書を開けません")
            Thread.sleep(20)
        }
    }

    private fun addItem(fixture: Fixture<MutableItemBaseData>, id: String) {
        val base = MutableItemBaseData(id = id)
        fixture.service.items.saveLocalDocument(id, base, emptyMap())
        selectStored(fixture, id)
        fx { fixture.logic.add(base, base.deepCopy().apply { itemDetail.maxStackSize = 32 }) }
    }

    @Test
    fun `全種別の警告修正で進捗と操作抑止を退避完了まで維持する`() {
        verifyRepair(EditorDataDescriptors.item, { it.itemDetail.maxStackSize = 32 }, { it.itemDetail.maxStackSize })
        verifyRepair(EditorDataDescriptors.ore, { it.magicPowerAmount = 32.0 }, { it.magicPowerAmount.toInt() })
        verifyRepair(EditorDataDescriptors.shop, { it.displayOrder = 32 }, { it.displayOrder })
        verifyRepair(EditorDataDescriptors.recipe, { it.resultCount = 32 }, { it.resultCount })
    }

    private fun <T : ManagedData<T, *>> verifyRepair(
        descriptor: EditorDataDescriptor<T>, change: (T) -> Unit, value: (T) -> Int
    ) {
        val repairing = CountDownLatch(1)
        val releaseRepair = CountDownLatch(1)
        val backingUp = CountDownLatch(1)
        val releaseBackup = CountDownLatch(1)
        withFixture(descriptor, backupWriter = { file, data ->
            backingUp.countDown()
            assertTrue(releaseBackup.await(10, TimeUnit.SECONDS))
            descriptor.saveBackup(file, data)
        }) { fixture ->
            var calls = 0
            fx {
                fixture.logic.warnings = listOf(repairWarning())
                fixture.logic.validationRepairRegistry.register({ true }, { "遅いスキン取得" }) { data, _ ->
                    assertFalse(Platform.isFxApplicationThread())
                    calls++
                    repairing.countDown()
                    assertTrue(releaseRepair.await(10, TimeUnit.SECONDS))
                    change(data)
                    ValidationRepairResult.Applied("スキンを取得")
                }
                fixture.logic.repairSelectedWarnings()
            }
            try {
                assertTrue(repairing.await(10, TimeUnit.SECONDS))
                fx {
                    assertTrue(fixture.main.operationRunning)
                    assertTrue(fixture.main.mainContentContainer.isDisabled)
                    assertTrue(fixture.main.sidebarContainer.isDisabled)
                    val label = (fixture.root.lookup("#topInfoLabel") as Label).text
                    assertTrue(label.contains(fixture.id))
                    assertTrue(label.contains("遅いスキン取得"))
                    val progress = fixture.root.lookup("#operationProgress") as ProgressIndicator
                    assertTrue(progress.isVisible)
                    assertTrue(progress.isIndeterminate)
                    fixture.logic.repairSelectedWarnings()
                    fixture.logic.repairAllWarnings()
                    fixture.logic.selectTab("other")
                    assertEquals(fixture.id, fixture.logic.current().id)
                    assertFalse(fixture.logic.onUndo())
                    fixture.logic.onSave(fixture.id) { assertEquals(EditorView.SaveOutcome.BUSY, it) }
                    fixture.logic.onClose { assertFalse(it) }
                    fixture.service.beginSave(); fixture.service.endSave()
                    assertTrue(fixture.main.sidebarContainer.isDisabled)
                    fixture.root.applyCss(); fixture.root.layout()
                }
                releaseRepair.countDown()
                assertTrue(backingUp.await(10, TimeUnit.SECONDS))
                fx {
                    assertFalse(fixture.logic.unchanged())
                    assertTrue(fixture.main.operationRunning)
                    assertTrue((fixture.root.lookup("#topInfoLabel") as Label).text.contains("バックアップ"))
                    fixture.logic.onClose { assertFalse(it) }
                    fixture.logic.repairSelectedWarnings()
                }
            } finally { releaseRepair.countDown(); releaseBackup.countDown() }
            waitRepair(fixture)
            assertEquals(1, calls)
            assertEquals(0, fixture.store.writes)
            val pair = assertNotNull(fixture.service.DataAccess(descriptor).loadBackupPair(fixture.id))
            assertEquals(32, value(pair.first))
            assertNotEquals(32, value(pair.second))
            fx { assertFalse(fixture.logic.unchanged()); assertFalse(fixture.main.mainContentContainer.isDisabled) }
        }
    }

    @Test
    fun `全件修正は未対応と例外の変更を捨て成功分を保持する`() = withFixture(EditorDataDescriptors.item) { fixture ->
        addItem(fixture, "second")
        fx {
            fixture.logic.warnings = listOf(repairWarning(3), repairWarning(2), repairWarning(1), repairWarning(0))
            fixture.logic.validationRepairRegistry.register({ it.key != 0 }, { "修正${it.key}" }) { data, warning ->
                when (warning.key) {
                    3 -> { data.editorMeta.comment = mutableListOf("成功"); ValidationRepairResult.Applied("成功") }
                    2 -> { data.editorMeta.comment = mutableListOf("失敗の変更"); ValidationRepairResult.Failed("失敗") }
                    else -> { data.editorMeta.comment = mutableListOf("例外の変更"); error("取得失敗") }
                }
            }
            fixture.logic.repairAllWarnings()
        }
        waitRepair(fixture)
        fx {
            for (id in listOf("saved", "second")) {
                assertEquals(listOf("成功"), fixture.logic.data(id).editorMeta.comment)
                assertFalse(fixture.logic.isSaved(id))
            }
        }
        assertEquals(0, fixture.store.writes)
    }

    @Test
    fun `修正後の退避失敗は成功表示せず編集を維持して終了で再試行できる`() {
        var failBackup = true
        withFixture(EditorDataDescriptors.item, backupWriter = { file, data ->
            if (failBackup) error("退避失敗")
            EditorDataDescriptors.item.saveBackup(file, data)
        }) { fixture ->
            fx {
                fixture.logic.warnings = listOf(repairWarning())
                fixture.logic.validationRepairRegistry.register({ true }, { "修正" }) { data, _ ->
                    data.editorMeta.comment = mutableListOf("保持する変更")
                    ValidationRepairResult.Applied("修正")
                }
                fixture.logic.repairSelectedWarnings()
            }
            waitRepair(fixture)
            fx { assertEquals(listOf("保持する変更"), fixture.logic.current().editorMeta.comment); assertFalse(fixture.logic.unchanged()) }
            failBackup = false
            val completed = CompletableFuture<Boolean>()
            fx { fixture.logic.onClose { completed.complete(it) } }
            assertTrue(completed.get(10, TimeUnit.SECONDS))
            val pair = assertNotNull(fixture.service.items.loadBackupPair(fixture.id))
            assertEquals(listOf("保持する変更"), pair.first.editorMeta.comment)
            assertTrue(pair.second.editorMeta.comment.isEmpty())
        }
    }

    @Test
    fun `全件修正の確認取消はコピーを適用せず操作を解除する`() = withFixture(EditorDataDescriptors.item) { fixture ->
        fixture.cancel = true
        fx {
            fixture.logic.warnings = listOf(repairWarning())
            fixture.logic.validationRepairRegistry.register({ true }, { "修正" }) { _, _ -> error("実行されない") }
            fixture.logic.repairAllWarnings()
            assertFalse(fixture.main.operationRunning)
            assertEquals(listOf("編集中の変更"), fixture.logic.current().editorMeta.comment)
        }
        assertEquals(0, fixture.store.writes)
    }

    @Test
    fun `ローカル接続の警告修正はストアへ書き込まない`() = withFixture(EditorDataDescriptors.item) { fixture ->
        fixture.store.kind = EditorDataStoreKind.LOCAL
        fx {
            fixture.logic.warnings = listOf(repairWarning())
            fixture.logic.validationRepairRegistry.register({ true }, { "修正" }) { data, _ ->
                data.editorMeta.comment = mutableListOf("ローカル修正")
                ValidationRepairResult.Applied("修正")
            }
            fixture.logic.repairSelectedWarnings()
        }
        waitRepair(fixture)
        assertEquals(0, fixture.store.writes)
        fx { assertEquals(listOf("ローカル修正"), fixture.logic.current().editorMeta.comment) }
    }

    @Test
    fun `入力中のSpinner値を確定してから警告修正のコピーを取得する`() = withFixture(EditorDataDescriptors.item) { fixture ->
        val spinner = fx {
            fixture.logic.warnings = listOf(repairWarning())
            fixture.logic.validationRepairRegistry.register({ true }, { "入力値の修正" }) { data, _ ->
                assertEquals(32, data.itemDetail.maxStackSize)
                data.itemDetail.maxStackSize = 64
                ValidationRepairResult.Applied("入力値を修正")
            }
            EditorSpinnerFactory.intSpinner(1, max = 64) { fixture.logic.current().itemDetail.maxStackSize = it }
                .also {
                    fixture.main.mainContentContainer.children.setAll(it)
                    fixture.root.applyCss(); fixture.root.layout()
                    fixture.stage.toFront(); fixture.stage.requestFocus()
                    it.editor.requestFocus()
                }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        var lastFocusState: String? = null
        while (true) {
            val focused = fx {
                spinner.editor.requestFocus()
                val state = "windowFocused=" + fixture.stage.isFocused + " showing=" + fixture.stage.isShowing +
                    " attached=" + (spinner.scene === fixture.stage.scene) + " editorFocused=" + spinner.editor.isFocused +
                    " sceneOwner=" + fixture.stage.scene.focusOwner?.javaClass?.simpleName +
                    " dialogs=" + Window.getWindows().filter { (it as? Stage)?.owner === fixture.stage }.map { (it as? Stage)?.title }
                if (state != lastFocusState) println("SPINNER_FOCUS " + state)
                lastFocusState = state
                spinner.editor.isFocused
            }
            if (focused) break
            assertTrue(System.nanoTime() < deadline, "入力欄へフォーカスできません: " + lastFocusState)
            Thread.sleep(20)
        }
        fx {
            spinner.editor.text = "32"
            assertEquals(1, fixture.logic.current().itemDetail.maxStackSize)
            fixture.logic.repairSelectedWarnings()
        }
        waitRepair(fixture)
        fx { assertEquals(64, fixture.logic.current().itemDetail.maxStackSize) }
    }

    private fun repairWarning(key: Int = 0) = SushiEricValidationError(
        MutableItemBaseData::id, "テスト警告$key", key, SushiEricValidationSeverity.WARNING
    )

    private fun waitRepair(fixture: Fixture<*>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (fx { fixture.main.operationRunning || Window.getWindows().any { it.scene?.root is DialogPane } }) {
            assertTrue(System.nanoTime() < deadline, "警告修正が完了しません")
            Thread.sleep(20)
        }
    }

    private fun editFixture(data: ManagedData<*, *>, comment: String = "編集中の変更") {
        when (data) {
            is MutableItemBaseData -> data.editorMeta.comment = mutableListOf(comment)
            is MutableOreBaseData -> data.editorMeta.comment = mutableListOf(comment)
            is MutableShopProductData -> data.displayOrder = 1
            is MutableRecipeData -> data.resultCount = 2
            else -> error("この保存試験の対象外のデータ型です")
        }
    }

    private fun <T : ManagedData<T, *>> withFixture(
        descriptor: EditorDataDescriptor<T>,
        backupWriter: ((java.io.File, T) -> Unit)? = null,
        remote: Boolean = false,
        action: (Fixture<T>) -> Unit
    ) {
        startFx()
        val directory = createTempDirectory("editor-single-save").toFile()
        val store = DelayedStore(remote = remote)
        val service = EditorDataService(store, directory)
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val base = descriptor.createDefault(if (descriptor == EditorDataDescriptors.shop) "tab.saved" else "saved")
        store.memory.save(descriptor, base.id, base)
        if (remote) service.localService.DataAccess(descriptor).saveLocalDocument(base.id, base, emptyMap())
        val fixture = fx {
            val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
            val root = loader.load<Parent>()
            val main = loader.getController<MainController>()
            val stage = Stage().apply { scene = Scene(root); show() }
            val backupDescriptor = if (backupWriter == null) descriptor else EditorDataDescriptor(
                descriptor.dataType, descriptor.load, descriptor.save, descriptor.validate, descriptor.merger,
                loadBackup = descriptor.loadBackup, saveBackup = { file, data ->
                    if (armed.get()) backupWriter(file, data) else descriptor.saveBackup(file, data)
                },
                validateInStore = descriptor.validateInStore, storesIdInFile = descriptor.storesIdInFile
            )
            val dataAccess = service.DataAccess(backupDescriptor)
            val logic = TestView(main, service, dataAccess)
            logic.setupActions(main.actionButtonContainer)
            val fixture = Fixture(store, service, main, root, stage, logic, base.id)
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
            logic.start()
            fixture
        }
        var primary: Throwable? = null
        try {
            fixture.logic.ready.get(10, TimeUnit.SECONDS)
            fx { editFixture(fixture.logic.current()) }
            armed.set(true)
            action(fixture)
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            val closed = CompletableFuture<Boolean>()
            try {
                fx { fixture.logic.onClose { closed.complete(it) } }
                assertTrue(closed.get(10, TimeUnit.SECONDS))
            } catch (error: Throwable) {
                if (primary == null) throw error
                primary.addSuppressed(error)
            } finally {
                fx {
                    fixture.timer.stop()
                    Window.getWindows().toList().filter { (it as? Stage)?.owner === fixture.stage }.forEach { it.hide() }
                    fixture.stage.close()
                }
                service.itemReferences.close()
                service.closeIo()
                directory.deleteRecursively()
            }
        }
    }

    private class Fixture<T : ManagedData<T, *>>(
        val store: DelayedStore, val service: EditorDataService, val main: MainController,
        val root: Parent, val stage: Stage, val logic: TestView<T>, val id: String
    ) {
        var cancel = false
        var pauseDialogs = false
        var onDialog: ((DialogPane) -> Unit)? = null
        lateinit var timer: Timeline
    }

    private class TestView<T : ManagedData<T, *>>(main: MainController, service: EditorDataService, access: EditorDataService.DataAccess<T>) :
        EditorView<T>(main, service, access) {
        fun source() = dataService.documentSource
        val ready = CompletableFuture<Unit>()
        var failSidebar = false
        var showDraft = false
        lateinit var draftField: javafx.scene.control.TextField
        fun restoreOriginal() {
            val id = currentSelectedDataId!!
            editingDataMap[id] = originalDataMap.getValue(id).deepCopy()
        }
        public override val validationRepairRegistry = ValidationRepairRegistry<T>()
        var warnings: List<SushiEricValidationError>? = null
        override fun validationErrors(data: T): List<SushiEricValidationError> = warnings ?: super.validationErrors(data)
        fun backup(): CompletableFuture<Boolean> = CompletableFuture<Boolean>().also { done ->
            executeAutoSave { done.complete(it) }
        }
        fun create() { stageDataCreation(current().id) }
        fun pending(): Boolean = hasPendingStoreOperation(current().id)
        var sidebarRefreshes = 0
        fun add(base: T, edit: T) { originalDataMap[base.id] = base; editingDataMap[base.id] = edit }
        fun data(id: String): T = editingDataMap.getValue(id)
        fun isSaved(id: String): Boolean = editingDataMap[id] == originalDataMap[id]
        fun current(): T = editingDataMap.getValue(currentSelectedDataId!!)
        fun unchanged(): Boolean = editingDataMap == originalDataMap
        fun rename(newId: String = "renamed") { stageDataRename("saved", newId); refreshLocalSidebar(newId) }
        fun delete() { stageDataDeletion("saved") }
        override fun setupSidebar(container: VBox, selectId: String?) {
            assertTrue(Platform.isFxApplicationThread())
            sidebarRefreshes++
            check(!failSidebar) { "保存後表示のfixture失敗" }
            loadSidebar(onFailed = { ready.completeExceptionally(IllegalStateException("一覧の取得に失敗しました")) }) { ids, _ ->
                (selectId ?: ids.firstOrNull())?.let(::selectTab)
                ready.complete(Unit)
            }
        }
        override fun setupMainContent(selectData: T) {
            if (showDraft) {
                draftField = javafx.scene.control.TextField("1")
                retainInputDraft(selectData.id, "test-number", draftField) { it.toDoubleOrNull() != null }
                main.mainContentContainer.children.setAll(draftField)
            } else main.mainContentContainer.children.setAll(Label(selectData.id))
        }
        override fun resolveSaveConflict(dataId: String, originalData: T, currentData: T, serverData: T): T = currentData
    }

    private class DelayedStore(
        val memory: InMemoryEditorDataStore = InMemoryEditorDataStore(), remote: Boolean = false
    ) : EditorDataStore by memory {
        override var kind = if (remote) EditorDataStoreKind.REMOTE else EditorDataStoreKind.IN_MEMORY
        override val documentSource = if (remote)
            EditorDocumentSource.Server("save-fixture", "declared/root")
            else EditorDocumentSource.Local
        var beforeLoad: (() -> Unit)? = null
        var beforeWrite: (() -> Unit)? = null
        val renames = mutableListOf<String>()
        var readFailure: StoreErrorCode? = null
        var loads = 0
        var writes = 0
        var deletes = 0

        override fun <T : ManagedData<T, *>> load(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<T> {
            loads++
            beforeLoad?.invoke()
            readFailure?.let { return StoreResult.Failure(StoreError(it, id)) }
            return memory.load(descriptor, id)
        }

        override fun <T : ManagedData<T, *>> save(descriptor: EditorDataDescriptor<T>, id: String, data: T): StoreResult<Unit> {
            writes++
            beforeWrite?.invoke()
            return memory.save(descriptor, id, data)
        }

        override fun <T : ManagedData<T, *>> delete(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<Unit> {
            deletes++
            return memory.delete(descriptor, id)
        }

        override fun <T : ManagedData<T, *>> rename(descriptor: EditorDataDescriptor<T>, oldId: String, newName: String): StoreResult<Unit> {
            renames += "$oldId:$newName"
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
