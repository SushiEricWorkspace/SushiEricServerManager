package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.UploadDataCategory
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorConnection
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorView
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorWindowManager
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.DialogPane
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import javafx.scene.layout.VBox
import javafx.stage.Stage
import net.schmizz.sshj.SSHClient
import org.mockito.Mockito.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ConnectionCloseFlowTest {
    @Test fun `実行中操作と全画面退避と接続終了を順に待ち画面を保持する`() = fixture { f ->
        val operation = CountDownLatch(1)
        val backup = CountDownLatch(1)
        val backupEntered = CountDownLatch(1)
        val close = CountDownLatch(1)
        val closeEntered = CountDownLatch(1)
        f.writeBackup = { backupEntered.countDown(); assertTrue(backup.await(10, TimeUnit.SECONDS)) }
        f.connection.beforeClose = { closeEntered.countDown(); assertTrue(close.await(10, TimeUnit.SECONDS)) }
        fx { f.service.beginSave() }
        f.service.submitIo { assertTrue(operation.await(10, TimeUnit.SECONDS)) }
            .whenComplete { _, _ -> Platform.runLater { f.service.endSave() } }
        val done = CompletableFuture<Unit>()
        fx { Utility.closeConnection { done.complete(Unit) }; Utility.closeConnection { error("重複終了") } }
        try {
            awaitFx { status() == "処理の完了を待っています" }
            fx { assertTrue(f.home.isShowing); assertTrue(f.views.all { it.retained() }) }
            assertEquals(1L, backupEntered.count)
            operation.countDown()
            assertTrue(backupEntered.await(10, TimeUnit.SECONDS))
            fx { assertEquals("編集内容を退避中", status()); f.home.scene.root.applyCss(); f.home.scene.root.layout() }
            assertEquals(1L, closeEntered.count)
            backup.countDown()
            assertTrue(closeEntered.await(10, TimeUnit.SECONDS))
            fx {
                assertEquals("接続を終了中", status())
                assertTrue(f.views.all { it.retained() })
                assertTrue(f.home.isShowing)
                assertSame(f.service, EditorSession.dataService)
            }
            assertFalse(done.isDone)
            close.countDown()
            done.get(10, TimeUnit.SECONDS)
            fx { assertTrue(f.views.none { it.retained() }); assertFalse(f.home.isShowing); assertNull(EditorSession.dataService) }
            assertEquals(1, f.connection.closed)
        } finally { operation.countDown(); backup.countDown(); close.countDown() }
    }

    @Test fun `保存確認中の終了要求でも確認ボタンを操作して保存待ちを解消できる`() = fixture { f ->
        val confirmed = CompletableFuture<Boolean>()
        val done = CompletableFuture<Unit>()
        fx {
            f.service.beginSave()
            Platform.runLater {
                val result = CustomDialog.confirmation().header("保存を確認")
                    .owner(f.views.first().controller.currentStage).show()
                f.service.endSave()
                confirmed.complete(result)
            }
        }
        awaitFx { Stage.getWindows().any { it.scene?.root is DialogPane } }
        try {
            fx {
                Utility.closeConnection { done.complete(Unit) }
                val pane = Stage.getWindows().mapNotNull { it.scene?.root as? DialogPane }.single()
                assertFalse(pane.isDisable)
                val ok = pane.buttonTypes.first { it.buttonData == ButtonBar.ButtonData.OK_DONE }
                (pane.lookupButton(ok) as Button).fire()
            }
            assertTrue(confirmed.get(10, TimeUnit.SECONDS))
            done.get(10, TimeUnit.SECONDS)
        } finally {
            fx {
                Stage.getWindows().toList().mapNotNull { it.scene?.root as? DialogPane }.forEach { pane ->
                    (pane.lookupButton(pane.buttonTypes.last()) as Button).fire()
                }
            }
        }
    }

    @Test fun `ローカル取込の走査と確認と反映完了を待ってから退避する`() = fixture { f ->
        val descriptor = EditorDataDescriptors.item
        LocalEditorDataStore(f.remote.offlineDirectory).save(descriptor, "imported",
            descriptor.createDefault("imported").apply { editorMeta.comment.add("取込") })
        val scanEntered = CountDownLatch(1)
        val scanRelease = CountDownLatch(1)
        val loadEntered = CountDownLatch(1)
        val loadRelease = CountDownLatch(1)
        f.remote.beforeList = { scanEntered.countDown(); assertTrue(scanRelease.await(10, TimeUnit.SECONDS)) }
        f.remote.beforeLoad = { loadEntered.countDown(); assertTrue(loadRelease.await(10, TimeUnit.SECONDS)) }
        val done = CompletableFuture<Unit>()
        try {
            fx {
                EditorView::class.java.getDeclaredMethod("onUploadLocalData", UploadDataCategory::class.java)
                    .apply { isAccessible = true }.invoke(f.views.first(), UploadDataCategory.ITEM)
            }
            assertTrue(scanEntered.await(10, TimeUnit.SECONDS))
            fx { Utility.closeConnection { done.complete(Unit) }; assertEquals("処理の完了を待っています", status()) }
            scanRelease.countDown()
            awaitFx { dialog() != null }
            fx {
                val pane = dialog()!!
                assertTrue(f.views.first().closeBusy)
                (pane.lookupButton(pane.buttonTypes.first { it.buttonData == ButtonBar.ButtonData.OK_DONE }) as Button).fire()
            }
            assertTrue(loadEntered.await(10, TimeUnit.SECONDS))
            fx { assertEquals("処理の完了を待っています", status()); assertEquals(0, f.connection.closed) }
            loadRelease.countDown()
            awaitFx { dialog() != null && f.views.first().imported() }
            fx {
                assertTrue(f.views.first().closeBusy)
                val pane = dialog()!!
                (pane.lookupButton(pane.buttonTypes.first()) as Button).fire()
            }
            done.get(10, TimeUnit.SECONDS)
            assertEquals(listOf("取込"), assertNotNull(f.service.items.loadBackupPair("imported")).first.editorMeta.comment)
        } finally {
            scanRelease.countDown(); loadRelease.countDown()
            fx { dialog()?.let { (it.lookupButton(it.buttonTypes.last()) as Button).fire() } }
        }
    }

    @Test fun `二画面目の退避失敗では全画面を保持して切断せず再試行する`() = fixture { f ->
        f.writeBackup = { file -> if (file.name.startsWith(".second-")) error("退避失敗") }
        val done = CompletableFuture<Unit>()
        fx { Utility.closeConnection { done.complete(Unit) } }
        awaitFx { !ApplicationFlow.closingConnection }
        fx {
            assertTrue(status().contains("退避に失敗"))
            assertTrue(f.views.all { it.retained() })
            assertFalse(f.home.scene.root.isDisable)
            assertSame(f.connection, EditorSession.connection)
        }
        assertEquals(0, f.connection.closed)
        assertFalse(done.isDone)
        f.writeBackup = {}
        fx { Utility.closeConnection { done.complete(Unit) } }
        done.get(10, TimeUnit.SECONDS)
        assertEquals(1, f.connection.closed)
    }

    @Test fun `writer終了未確認では参照一覧と編集内容と接続を保持する`() = fixture { f ->
        f.connection.fail = true
        fx { Platform.setImplicitExit(true) }
        val done = CompletableFuture<Unit>()
        fx { Utility.closeConnection { done.complete(Unit) } }
        awaitFx { !ApplicationFlow.closingConnection }
        fx {
            assertTrue(status().contains("接続の終了が未確認"))
            assertTrue(Platform.isImplicitExit())
            assertSame(f.connection, EditorSession.connection)
            assertSame(f.service, EditorSession.dataService)
            assertTrue(f.views.all { it.retained() })
            assertFalse(f.home.scene.root.isDisable)
            assertFails { EditorSession.prepareOnlineMode() }
        }
        assertTrue(f.service.itemReferences.snapshot.items.isNotEmpty())
        assertFalse(done.isDone)
        f.connection.fail = false
        fx { Platform.setImplicitExit(false) }
        fx { Utility.closeConnection { done.complete(Unit) } }
        done.get(10, TimeUnit.SECONDS)
    }

    @Test fun `通常SSHの終了遅延中も画面が応答し失敗では接続情報を破棄しない`() {
        startFx()
        val previous = EditorSession.sshManager
        val ssh = SshManager()
        val client = mock(SSHClient::class.java)
        val field = SshManager::class.java.getDeclaredField("client").apply { isAccessible = true }
        field.set(ssh, client)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer { assertFalse(Platform.isFxApplicationThread()); entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); throw java.io.IOException("終了失敗") }.`when`(client).disconnect()
        val home = fx { EditorSession.sshManager = ssh; EditorSession.prepareOnlineMode(); stage() }
        val done = CompletableFuture<Unit>()
        try {
            fx { Utility.closeConnection { done.complete(Unit) } }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            fx { assertEquals("接続を終了中", status()); assertTrue(home.isShowing); home.scene.root.layout() }
            release.countDown()
            awaitFx { !ApplicationFlow.closingConnection }
            assertSame(client, field.get(ssh))
            assertFalse(done.isDone)
            doNothing().`when`(client).disconnect()
            fx { Utility.closeConnection { done.complete(Unit) } }
            done.get(10, TimeUnit.SECONDS)
            assertNull(field.get(ssh))
        } finally {
            release.countDown()
            doNothing().`when`(client).disconnect()
            EditorSession.resetMode()
            fx { EditorSession.sshManager = previous; Stage.getWindows().toList().filterIsInstance<Stage>().forEach { it.close() } }
        }
    }

    @Test fun `オフラインのモード遷移は終了後に行いimplicitExitを元に戻す`() {
        startFx()
        val old = ApplicationFlow.modeSelection
        val shown = CompletableFuture<Unit>()
        try {
            fx {
                EditorSession.startOfflineSession(InMemoryEditorDataStore())
                stage()
                Platform.setImplicitExit(true)
                ApplicationFlow.modeSelection = { next -> next.scene = Scene(VBox(Label("次の画面"))); next.show(); shown.complete(Unit) }
                Utility.navigateToModeSelect()
                assertFalse(Platform.isImplicitExit())
            }
            shown.get(10, TimeUnit.SECONDS)
            awaitFx { !ApplicationFlow.closingConnection }
            fx { assertTrue(Platform.isImplicitExit()); assertNull(EditorSession.mode); assertNull(EditorSession.dataService) }
        } finally {
            fx { Platform.setImplicitExit(false); ApplicationFlow.modeSelection = old; Stage.getWindows().toList().filterIsInstance<Stage>().forEach { it.close() } }
            EditorSession.resetMode()
        }
    }

    private fun fixture(action: (Fixture) -> Unit) {
        startFx()
        val directory = createTempDirectory("connection-close-").toFile()
        val memory = InMemoryEditorDataStore()
        val remote = RemoteStore(memory, directory.resolve("offline"))
        val service = EditorDataService(remote, directory)
        val connection = Connection(service)
        val f = Fixture(service, connection, remote)
        val descriptor = EditorDataDescriptors.item
        fun access() = service.DataAccess(EditorDataDescriptor(
            descriptor.dataType, descriptor.load, descriptor.save, descriptor.validate, descriptor.merger,
            loadBackup = descriptor.loadBackup,
            saveBackup = { file, data -> f.writeBackup(file); descriptor.saveBackup(file, data) },
            validateInStore = descriptor.validateInStore, storesIdInFile = descriptor.storesIdInFile
        ))
        for (id in listOf("first", "second")) memory.save(descriptor, id, descriptor.createDefault(id))
        fx {
            EditorSession.startConnection(connection)
            f.home = stage()
            for (id in listOf("first", "second")) {
                EditorWindowManager.openEditor(id, id, FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))) { main ->
                    View(main, service, access(), id).also { f.views += it }
                }
            }
        }
        awaitFx { f.views.all { !it.controller.loadingRunning } }
        try { action(f) }
        finally {
            fx { Platform.setImplicitExit(false) }
            connection.beforeClose = {}
            connection.fail = false
            f.writeBackup = {}
            awaitFx { !ApplicationFlow.closingConnection }
            val closed = CompletableFuture<Boolean>()
            fx { EditorWindowManager.closeAll { closed.complete(it) } }
            assertTrue(closed.get(10, TimeUnit.SECONDS))
            EditorSession.resetMode()
            fx { Stage.getWindows().toList().filterIsInstance<Stage>().forEach { it.close() } }
            directory.deleteRecursively()
        }
    }

    private class Fixture(val service: EditorDataService, val connection: Connection, val remote: RemoteStore) {
        lateinit var home: Stage
        val views = mutableListOf<View>()
        var writeBackup: (java.io.File) -> Unit = {}
    }
    private class RemoteStore(val memory: InMemoryEditorDataStore, override val offlineDirectory: java.io.File) : EditorDataStore by memory {
        override val kind = EditorDataStoreKind.REMOTE
        var beforeList: () -> Unit = {}
        var beforeLoad: () -> Unit = {}
        override fun <T : ManagedData<T, *>> list(descriptor: EditorDataDescriptor<T>): StoreResult<List<StoreResource>> {
            beforeList()
            return memory.list(descriptor)
        }
        override fun <T : ManagedData<T, *>> load(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<T> {
            beforeLoad()
            return memory.load(descriptor, id)
        }
    }
    private class Connection(private val service: EditorDataService) : EditorConnection {
        override val contextName = "管理接続の代替"
        override val compatibility = ManagerCompatibility.Unchecked
        var beforeClose: () -> Unit = {}
        var fail = false
        var closed = 0
        override fun open() = service
        override fun close() {
            assertFalse(Platform.isFxApplicationThread())
            beforeClose()
            check(!fail) { "writer終了未確認" }
            closed++
        }
        override fun abort() = Unit
    }
    private class View(val controller: MainController, service: EditorDataService, access: EditorDataService.DataAccess<MutableItemBaseData>, val id: String) :
        EditorView<MutableItemBaseData>(controller, service, access) {
        fun retained() = editingDataMap[id]?.editorMeta?.comment == listOf("編集内容")
        fun imported() = editingDataMap["imported"]?.editorMeta?.comment == listOf("取込")
        override fun setupSidebar(container: VBox, selectId: String?) {
            val original = EditorDataDescriptors.item.createDefault(id)
            originalDataMap[id] = original
            editingDataMap[id] = original.deepCopy().apply { editorMeta.comment.add("編集内容") }
            currentSelectedDataId = id
            main.finishLoading()
        }
        override fun setupMainContent(selectData: MutableItemBaseData) = Unit
        override fun resolveSaveConflict(dataId: String, originalData: MutableItemBaseData, currentData: MutableItemBaseData, serverData: MutableItemBaseData) = currentData
    }
    companion object {
        private fun startFx() {
            val ready = CountDownLatch(1)
            try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
            catch (_: IllegalStateException) { Platform.runLater { Platform.setImplicitExit(false); ready.countDown() } }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
        }
        private fun <R> fx(action: () -> R): R {
            val task = FutureTask(action)
            Platform.runLater(task)
            return task.get(15, TimeUnit.SECONDS)
        }
        private fun stage() = Stage().apply { scene = Scene(VBox(Label("Home")), 400.0, 150.0); show() }
        private fun dialog() = Stage.getWindows().mapNotNull { it.scene?.root as? DialogPane }.lastOrNull()
        private fun status() = Stage.getWindows().asSequence().mapNotNull { it.scene?.lookup("#connectionCloseStatus") as? Label }.lastOrNull()?.text.orEmpty()
        private fun awaitFx(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!fx(condition)) { check(System.nanoTime() < deadline) { "画面の完了待ちが期限を超えました" }; Thread.sleep(20) }
        }
    }
}
