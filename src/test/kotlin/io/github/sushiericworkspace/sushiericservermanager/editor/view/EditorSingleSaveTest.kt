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
                fixture.logic.onClose { assertFalse(it) }
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
        try {
            assertEquals(EditorView.SaveOutcome.SAVED_WITH_ERRORS, completed.get(10, TimeUnit.SECONDS))
            fx { assertTrue(fixture.logic.unchanged()) }
        } finally {
            backup.resolve("undeletable").delete()
            backup.delete()
        }
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
        val otherClosed = CompletableFuture<Boolean>()
        fx { assertFalse(other.first.sidebarContainer.isDisabled); other.second.onClose { otherClosed.complete(it) } }
        assertTrue(otherClosed.get(10, TimeUnit.SECONDS))
    }

    @Test
    fun `全種別の背景退避中は編集でき終了は同じ世代の完了を待つ`() {
        verifyBackup(EditorDataDescriptors.item)
        verifyBackup(EditorDataDescriptors.ore)
        verifyBackup(EditorDataDescriptors.shop)
        verifyBackup(EditorDataDescriptors.recipe)
    }

    private fun <T : ManagedData<T, *>> verifyBackup(descriptor: EditorDataDescriptor<T>) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = java.util.concurrent.atomic.AtomicInteger()
        val observedThreads = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        withFixture(descriptor, backupWriter = { file, data ->
            observedThreads += Platform.isFxApplicationThread()
            if (writes.incrementAndGet() == 1) {
                entered.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
            }
            descriptor.saveBackup(file, data)
        }) { fixture ->
            val first = fx { fixture.logic.backup() }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                val closed = CompletableFuture<Boolean>()
                fx {
                    assertFalse(fixture.main.operationRunning)
                    assertFalse(fixture.main.mainContentContainer.isDisabled)
                    fixture.root.applyCss(); fixture.root.layout()
                    fixture.logic.onClose { assertTrue(Platform.isFxApplicationThread()); closed.complete(it) }
                    assertTrue(fixture.main.operationRunning)
                    assertTrue(fixture.logic.storeOperationBlocked)
                    fixture.logic.onCreateData()
                    fixture.logic.onSaveAll { assertEquals(EditorView.SaveOutcome.BUSY, it.outcome) }
                    fixture.logic.onClose { assertFalse(it) }
                    assertFalse(closed.isDone)
                }
                release.countDown()
                assertTrue(first.get(10, TimeUnit.SECONDS))
                assertTrue(closed.get(10, TimeUnit.SECONDS))
                assertEquals(2, writes.get())
                val pair = fixture.service.DataAccess(descriptor).loadBackupPair(fixture.id)
                assertNotNull(pair)
                assertEquals(fixture.id, pair.first.id)
                assertEquals(fixture.id, pair.second.id)
            } finally { release.countDown() }
        }
        assertTrue(observedThreads.all { !it })
    }

    @Test
    fun `終了退避のoriginal失敗は編集を保持し再試行で復元可能になる`() {
        val descriptor = EditorDataDescriptors.item
        var failOriginal = true
        withFixture(descriptor, backupWriter = { file, data ->
            if (file.parentFile.name == "original" && failOriginal) error("片側の書き込み失敗")
            descriptor.saveBackup(file, data)
        }) { fixture ->
            val first = CompletableFuture<Boolean>()
            fx { fixture.logic.onClose { first.complete(it) } }
            assertFalse(first.get(10, TimeUnit.SECONDS))
            fx {
                assertFalse(fixture.main.operationRunning)
                assertFalse(fixture.logic.unchanged())
                assertEquals(fixture.id, fixture.logic.current().id)
                assertTrue((fixture.root.lookup("#topInfoLabel") as Label).text.contains("失敗"))
                fixture.logic.create()
            }
            failOriginal = false
            val retry = CompletableFuture<Boolean>()
            fx { fixture.logic.onClose { retry.complete(it) } }
            assertTrue(retry.get(10, TimeUnit.SECONDS))
            val access = fixture.service.items
            assertNotNull(access.loadBackupPair(fixture.id))
            assertEquals(fixture.id, access.loadPendingStoreOperations().single().currentId)
        }
    }

    @Test
    fun `全種別の一括保存は件数を表示して一覧を最後に一回取得する`() {
        verifyBatch(EditorDataDescriptors.item)
        verifyBatch(EditorDataDescriptors.ore)
        verifyBatch(EditorDataDescriptors.shop)
        verifyBatch(EditorDataDescriptors.recipe)
    }

    private fun <T : ManagedData<T, *>> verifyBatch(descriptor: EditorDataDescriptor<T>) = withFixture(descriptor) { fixture ->
        val second = descriptor.createDefault(if (descriptor == EditorDataDescriptors.shop) "tab.second" else "second")
        fixture.store.memory.save(descriptor, second.id, second)
        fx { fixture.logic.add(second, second.deepCopy().apply { completed = !completed }) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.store.beforeSave = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        val completed = CompletableFuture<EditorView.BatchSaveResult>()
        val phases = mutableListOf<String>()
        fx {
            (fixture.root.lookup("#topInfoLabel") as Label).textProperty().addListener { _, _, value -> phases += value }
            fixture.logic.onSaveAll { completed.complete(it) }
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            fx {
                assertTrue(fixture.main.operationRunning)
                assertTrue(fixture.main.mainContentContainer.isDisabled)
                assertTrue(fixture.main.sidebarContainer.isDisabled)
                assertTrue((fixture.root.lookup("#topInfoLabel") as Label).text.contains("保存中 0/2 件"))
                fixture.logic.onSaveAll { assertEquals(EditorView.SaveOutcome.BUSY, it.outcome) }
                fixture.logic.onClose { assertFalse(it) }
                fixture.root.applyCss(); fixture.root.layout()
            }
        } finally { release.countDown() }
        val result = completed.get(15, TimeUnit.SECONDS)
        assertEquals(EditorView.SaveOutcome.SAVED, result.outcome)
        assertEquals(2, result.saved.size)
        assertEquals(1, fixture.store.lists)
        assertTrue(fixture.store.ioThreads.none { it })
        fx {
            assertEquals(1, fixture.logic.sidebarRefreshes)
            assertTrue(fixture.logic.unchanged())
            assertTrue(phases.any { it.startsWith("保存準備 2/2 件") })
            assertTrue(phases.any { it.startsWith("保存中 2/2 件") })
            assertFalse(fixture.main.operationRunning)
        }
    }

    private fun addItem(fixture: Fixture<MutableItemBaseData>, id: String) {
        val base = MutableItemBaseData(id = id)
        fixture.store.memory.save(EditorDataDescriptors.item, id, base)
        fx { fixture.logic.add(base, base.deepCopy().apply { itemDetail.maxStackSize = 32 }) }
    }

    @Test
    fun `一括保存の準備失敗と書込み失敗は対象別に保持して他の対象を保存する`() = withFixture(EditorDataDescriptors.item) { fixture ->
        addItem(fixture, "read_failed")
        addItem(fixture, "write_failed")
        fixture.store.failedReadId = "read_failed"
        fixture.store.failedWriteId = "write_failed"
        val completed = CompletableFuture<EditorView.BatchSaveResult>()
        fx { fixture.logic.onSaveAll { completed.complete(it) } }
        val result = completed.get(15, TimeUnit.SECONDS)
        assertEquals(EditorView.SaveOutcome.SAVED_WITH_ERRORS, result.outcome)
        assertEquals(listOf("saved"), result.saved)
        assertEquals(setOf("read_failed", "write_failed"), result.failed.keys)
        assertEquals(listOf("saved", "write_failed"), fixture.store.writeIds)
        assertEquals(1, fixture.store.lists)
        fx {
            assertTrue(fixture.logic.isSaved("saved"))
            assertFalse(fixture.logic.isSaved("read_failed"))
            assertFalse(fixture.logic.isSaved("write_failed"))
            assertFalse(fixture.main.operationRunning)
        }
    }

    @Test
    fun `退避中の編集を成功済みと表示せず最新要求で削除を順序付ける`() {
        val descriptor = EditorDataDescriptors.item
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var firstWrite = true
        withFixture(descriptor, backupWriter = { file, data ->
            if (firstWrite) {
                firstWrite = false
                entered.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
            }
            descriptor.saveBackup(file, data)
        }) { fixture ->
            val first = fx { fixture.logic.backup() }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                fx { fixture.logic.current().completed = !fixture.logic.current().completed }
                release.countDown()
                assertTrue(first.get(10, TimeUnit.SECONDS))
                fx { assertTrue((fixture.root.lookup("#topInfoLabel") as Label).text.contains("次回")) }
                val latest = fx { fixture.logic.backup() }
                assertTrue(latest.get(10, TimeUnit.SECONDS))
                assertNull(fixture.service.items.loadBackupPair(fixture.id))
            } finally { release.countDown() }
        }
    }

    @Test
    fun `背景退避の後に単件保存すると古いバックアップを復活させない`() {
        withFixture(EditorDataDescriptors.item) { fixture ->
            val backup = fx { fixture.logic.backup() }
            val saved = CompletableFuture<EditorView.SaveOutcome>()
            fx { fixture.logic.onSave(fixture.id) { saved.complete(it) } }
            assertTrue(backup.get(10, TimeUnit.SECONDS))
            assertEquals(EditorView.SaveOutcome.SAVED, saved.get(10, TimeUnit.SECONDS))
            assertNull(fixture.service.items.loadBackupPair(fixture.id))
        }
    }

    @Test
    fun `背景退避の後に一括保存すると古いバックアップを復活させない`() {
        withFixture(EditorDataDescriptors.item) { fixture ->
            val backup = fx { fixture.logic.backup() }
            val saved = CompletableFuture<EditorView.BatchSaveResult>()
            fx { fixture.logic.onSaveAll { saved.complete(it) } }
            assertTrue(backup.get(10, TimeUnit.SECONDS))
            assertEquals(EditorView.SaveOutcome.SAVED, saved.get(15, TimeUnit.SECONDS).outcome)
            assertNull(fixture.service.items.loadBackupPair(fixture.id))
        }
    }

    @Test
    fun `一括保存の退避整理失敗は保存済みと区別して返す`() = withFixture(EditorDataDescriptors.item) { fixture ->
        val backup = fixture.service.autoSaveDirectory.resolve(fixture.service.cacheIdentity)
            .resolve(EditorDataDescriptors.item.dataType.categoryDirName).resolve("editing/saved.yml")
        assertTrue(backup.mkdirs())
        backup.resolve("undeletable").writeText("test")
        val completed = CompletableFuture<EditorView.BatchSaveResult>()
        fx { fixture.logic.onSaveAll { completed.complete(it) } }
        try {
            val result = completed.get(15, TimeUnit.SECONDS)
            assertEquals(EditorView.SaveOutcome.SAVED_WITH_ERRORS, result.outcome)
            assertEquals(listOf("saved"), result.saved)
            assertEquals(1, result.postProcessingErrors.size)
            fx { assertTrue(fixture.logic.unchanged()) }
        } finally { backup.deleteRecursively() }
    }

    @Test
    fun `最新の編集とoriginalと保留操作を新しいサービスで復元できる`() {
        val descriptor = EditorDataDescriptors.item
        withFixture(descriptor) { fixture ->
            fx {
                fixture.logic.current().editorMeta.comment.add("最新の編集")
                fixture.logic.create()
            }
            val completed = fx { fixture.logic.backup() }
            assertTrue(completed.get(10, TimeUnit.SECONDS))
            val reopenedService = EditorDataService(fixture.store, fixture.service.autoSaveDirectory)
            val access = reopenedService.items
            val pair = assertNotNull(access.loadBackupPair(fixture.id))
            assertEquals(listOf("最新の編集"), pair.first.editorMeta.comment)
            assertTrue(pair.second.editorMeta.comment.isEmpty())
            val closed = CompletableFuture<Boolean>()
            val reopened = fx {
                val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
                val root = loader.load<Parent>()
                val main = loader.getController<MainController>()
                val stage = Stage().apply { scene = Scene(root); show() }
                val restored = TestView(main, reopenedService, access)
                main.switchView(restored)
                Triple(stage, main, restored)
            }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (fx { reopened.second.loadingRunning }) {
                    assertTrue(System.nanoTime() < deadline, "復元が完了しません")
                    Thread.sleep(20)
                }
                fx {
                    reopened.third.selectTab(fixture.id)
                    assertTrue(reopened.third.pending())
                    assertEquals(listOf("最新の編集"), reopened.third.current().editorMeta.comment)
                    reopened.third.onClose { closed.complete(it) }
                }
                assertTrue(closed.get(10, TimeUnit.SECONDS))
            } finally {
                fx { reopened.first.close() }
                reopenedService.closeIo()
            }
        }
    }

    @Test
    fun `一括保存後の一覧失敗は保存済みと未保存を混同しない`() = withFixture(EditorDataDescriptors.item) { fixture ->
        addItem(fixture, "failed")
        fixture.store.failedWriteId = "failed"
        fixture.store.failListAfterSave = true
        val completed = CompletableFuture<EditorView.BatchSaveResult>()
        fx { fixture.logic.onSaveAll { completed.complete(it) } }
        val result = completed.get(15, TimeUnit.SECONDS)
        assertEquals(listOf("saved"), result.saved)
        assertEquals(setOf("failed"), result.failed.keys)
        assertEquals(1, result.postProcessingErrors.size)
        assertEquals(1, fixture.store.lists)
        fx { assertTrue(fixture.logic.isSaved("saved")); assertFalse(fixture.logic.isSaved("failed")); assertFalse(fixture.main.operationRunning) }
    }

    @Test
    fun `一括差分確認の取消は書き込まず全準備対象を未実行にする`() = withFixture(EditorDataDescriptors.item) { fixture ->
        addItem(fixture, "second")
        fixture.cancel = true
        val completed = CompletableFuture<EditorView.BatchSaveResult>()
        fx { fixture.logic.onSaveAll { completed.complete(it) } }
        val result = completed.get(15, TimeUnit.SECONDS)
        assertEquals(EditorView.SaveOutcome.CANCELLED, result.outcome)
        assertEquals(setOf("saved", "second"), result.notExecuted.toSet())
        assertEquals(0, fixture.store.writes)
        assertEquals(0, fixture.store.lists)
        fx { assertFalse(fixture.logic.unchanged()); assertFalse(fixture.main.operationRunning) }
    }

    @Test
    fun `一括の競合確認取消は対象を保持し承認対象だけ保存する`() = withFixture(EditorDataDescriptors.item) { fixture ->
        addItem(fixture, "conflicted")
        val remote = (fixture.store.memory.load(EditorDataDescriptors.item, "conflicted") as StoreResult.Success).value
        remote.itemDetail.maxStackSize = 16
        fixture.store.memory.save(EditorDataDescriptors.item, "conflicted", remote)
        fx {
            fixture.onDialog = { pane ->
                fixture.cancel = pane.headerText == null
            }
        }
        val completed = CompletableFuture<EditorView.BatchSaveResult>()
        fx { fixture.logic.onSaveAll { completed.complete(it) } }
        val result = completed.get(15, TimeUnit.SECONDS)
        assertEquals(listOf("conflicted"), result.cancelled)
        assertEquals(listOf("saved"), result.saved)
        assertEquals(listOf("saved"), fixture.store.writeIds)
        fx { assertEquals(32, fixture.logic.data("conflicted").itemDetail.maxStackSize) }
    }

    @Test
    fun `一括保存は削除とID変更を背景実行して保留操作を整理する`() = withFixture(EditorDataDescriptors.item) { fixture ->
        addItem(fixture, "delete_me")
        fx { fixture.logic.renameId("saved", "renamed"); fixture.logic.deleteId("delete_me") }
        val completed = CompletableFuture<EditorView.BatchSaveResult>()
        fx { fixture.logic.onSaveAll { completed.complete(it) } }
        val result = completed.get(15, TimeUnit.SECONDS)
        assertEquals(EditorView.SaveOutcome.SAVED, result.outcome)
        assertEquals(setOf("renamed", "delete_me"), result.saved.toSet())
        assertIs<StoreResult.Success<*>>(fixture.store.memory.load(EditorDataDescriptors.item, "renamed"))
        assertIs<StoreResult.Failure>(fixture.store.memory.load(EditorDataDescriptors.item, "saved"))
        assertIs<StoreResult.Failure>(fixture.store.memory.load(EditorDataDescriptors.item, "delete_me"))
        assertTrue(fixture.service.items.loadPendingStoreOperations().isEmpty())
        assertEquals(1, fixture.store.lists)
    }

    @Test
    fun `一括オフライン保存は最新取得を行わず対象コピーを保存する`() = withFixture(EditorDataDescriptors.item) { fixture ->
        addItem(fixture, "second")
        fixture.store.kind = EditorDataStoreKind.LOCAL
        val completed = CompletableFuture<EditorView.BatchSaveResult>()
        fx { fixture.logic.onSaveAll { completed.complete(it) } }
        assertEquals(EditorView.SaveOutcome.SAVED, completed.get(15, TimeUnit.SECONDS).outcome)
        assertEquals(0, fixture.store.loads)
        assertEquals(2, fixture.store.writes)
        assertEquals(1, fixture.store.lists)
    }

    @Test
    fun `一括準備待機中もFXが応答し開始時の編集コピーを保存する`() = withFixture(EditorDataDescriptors.item) { fixture ->
        addItem(fixture, "second")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        fixture.store.beforeLoad = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        val completed = CompletableFuture<EditorView.BatchSaveResult>()
        fx { fixture.logic.onSaveAll { completed.complete(it) } }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            fx {
                assertTrue((fixture.root.lookup("#topInfoLabel") as Label).text.contains("保存準備 0/2 件"))
                fixture.logic.data("second").itemDetail.maxStackSize = 16
                fixture.root.applyCss(); fixture.root.layout()
            }
        } finally { release.countDown() }
        assertEquals(EditorView.SaveOutcome.SAVED, completed.get(15, TimeUnit.SECONDS).outcome)
        val saved = (fixture.store.memory.load(EditorDataDescriptors.item, "second") as StoreResult.Success).value
        assertEquals(32, saved.itemDetail.maxStackSize)
    }

    private fun <T : ManagedData<T, *>> withFixture(
        descriptor: EditorDataDescriptor<T>,
        backupWriter: ((java.io.File, T) -> Unit)? = null,
        action: (Fixture<T>) -> Unit
    ) {
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
            val backupDescriptor = if (backupWriter == null) descriptor else EditorDataDescriptor(
                descriptor.dataType, descriptor.load, descriptor.save, descriptor.validate, descriptor.merger,
                loadBackup = descriptor.loadBackup, saveBackup = backupWriter,
                validateInStore = descriptor.validateInStore, storesIdInFile = descriptor.storesIdInFile
            )
            val dataAccess = service.DataAccess(backupDescriptor)
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
            val closed = CompletableFuture<Boolean>()
            try {
                fx { fixture.logic.onClose { closed.complete(it) } }
                assertTrue(closed.get(10, TimeUnit.SECONDS))
            } finally {
                fx { fixture.timer.stop(); fixture.stage.close() }
                service.itemReferences.close()
                service.closeIo()
                directory.deleteRecursively()
            }
        }
    }

    private class Fixture<T : ManagedData<T, *>>(
        val store: DelayedStore, val service: EditorDataService, val main: MainController,
        val root: Parent, val stage: Stage, val logic: TestView<T>
    ) {
        val id = logic.current().id
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
        fun backup(): CompletableFuture<Boolean> = CompletableFuture<Boolean>().also { done ->
            executeAutoSave { done.complete(it) }
        }
        fun create() { stageDataCreation(current().id) }
        fun pending(): Boolean = hasPendingStoreOperation(current().id)
        var sidebarRefreshes = 0
        fun add(base: T, edit: T) { originalDataMap[base.id] = base; editingDataMap[base.id] = edit }
        fun data(id: String): T = editingDataMap.getValue(id)
        fun isSaved(id: String): Boolean = editingDataMap[id] == originalDataMap[id]
        fun renameId(oldId: String, newId: String) { stageDataRename(oldId, newId) }
        fun deleteId(id: String) { stageDataDeletion(id) }
        fun current(): T = editingDataMap.getValue(currentSelectedDataId!!)
        fun unchanged(): Boolean = editingDataMap == originalDataMap
        fun rename(newId: String = "renamed") { stageDataRename("saved", newId) }
        fun delete() { stageDataDeletion("saved") }
        override fun setupSidebar(container: VBox, selectId: String?) {
            assertTrue(Platform.isFxApplicationThread())
            sidebarRefreshes++
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
        var lists = 0
        var failedReadId: String? = null
        var failedWriteId: String? = null
        var failedDeleteId: String? = null
        val loadIds = mutableListOf<String>()
        val writeIds = mutableListOf<String>()
        var writeFailure: StoreErrorCode? = null
        var failListAfterSave = false
        var failedRename: String? = null
        var savedCompleted = false
        var writes = 0
        override fun <T : ManagedData<T, *>> load(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<T> {
            ioThreads += Platform.isFxApplicationThread()
            loads++
            loadIds += id
            if (id == failedReadId) return StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, id))
            beforeLoad?.invoke()
            readFailure?.let { return StoreResult.Failure(StoreError(it, id)) }
            return memory.load(descriptor, id)
        }
        override fun <T : ManagedData<T, *>> save(descriptor: EditorDataDescriptor<T>, id: String, data: T): StoreResult<Unit> {
            ioThreads += Platform.isFxApplicationThread()
            beforeSave?.invoke()
            writes++
            writeIds += id
            if (id == failedWriteId) return StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, id))
            writeFailure?.let { return StoreResult.Failure(StoreError(it, id)) }
            data.completed = !data.completed
            savedCompleted = data.completed
            return memory.save(descriptor, id, data)
        }
        override fun <T : ManagedData<T, *>> list(descriptor: EditorDataDescriptor<T>): StoreResult<List<StoreResource>> {
            ioThreads += Platform.isFxApplicationThread()
            lists++
            beforeList?.invoke()
            return if (failListAfterSave && writes > 0) StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR)) else memory.list(descriptor)
        }
        override fun <T : ManagedData<T, *>> delete(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<Unit> {
            ioThreads += Platform.isFxApplicationThread()
            if (id == failedDeleteId) return StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, id))
            return memory.delete(descriptor, id)
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
