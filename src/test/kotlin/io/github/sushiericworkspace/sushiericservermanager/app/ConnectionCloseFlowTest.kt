package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.ore.model.mutable.MutableOreBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.UploadDataCategory
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentSnapshot
import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentRecord
import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentVersion
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentSource
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorConnection
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorView
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorWindowManager
import io.github.sushiericworkspace.sushiericservermanager.feature.modconfig.ModConfigWindowManager
import io.github.sushiericworkspace.sushiericservermanager.feature.modconfig.ModConfigController
import javafx.animation.PauseTransition
import javafx.scene.control.TextArea
import io.github.sushiericworkspace.sushiericservermanager.config.*
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceManager
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceView
import javafx.stage.WindowEvent
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
        f.writeBackup = { _, _ -> backupEntered.countDown(); assertTrue(backup.await(10, TimeUnit.SECONDS)) }
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

    @Test fun `HOMEなし配置復元後もnativecloseは退避を待ち終了失敗で画面と入力を保全する`() = fixture(withoutHome = true) { f ->
        val backup = CountDownLatch(1)
        val entered = CountDownLatch(1)
        f.writeBackup = { _, _ -> entered.countDown(); assertTrue(backup.await(10, TimeUnit.SECONDS)) }
        f.connection.fail = true
        try {
            fx {
                assertFalse(WorkspaceManager.requireWorkspace().titles().contains(WorkspaceFeature.HOME))
                assertNotNull(f.home.onCloseRequest)
                f.home.fireEvent(WindowEvent(f.home, WindowEvent.WINDOW_CLOSE_REQUEST))
                assertTrue(f.home.isShowing)
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            fx { assertTrue(f.views.all { it.retained() }); assertTrue(f.home.isShowing) }
            backup.countDown()
            awaitFx { !ApplicationFlow.closingConnection && status().contains("終了が未確認") }
            fx {
                assertTrue(f.views.all { it.retained() })
                assertTrue(f.home.isShowing)
                assertNotNull(f.home.onCloseRequest)
                assertSame(f.service, EditorSession.dataService)
                val progress = Stage.getWindows().filterIsInstance<Stage>().single { it.scene.lookup("#connectionCloseStatus") != null }
                val back = progress.scene.root.lookupAll(".button").filterIsInstance<Button>().single { it.text == "戻る" }
                back.fire()
                assertTrue(f.home.isShowing)
            }
            assertEquals(0, f.connection.closed)
        } finally { backup.countDown() }
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
        val local = f.service.localService
        assertIs<StoreResult.Success<*>>(local.items.saveLocalDocument("imported",
            descriptor.createDefault("imported").apply { editorMeta.comment.add("取込") }, emptyMap()))
        assertIs<StoreResult.Success<*>>(local.items.listStoreResources())
        fx { f.views.first().selectServerSource() }
        awaitFx { f.views.first().serverSourceReady() }
        val localBefore = assertIs<StoreResult.Success<LocalDocumentSnapshot>>(local.documents.read()).value
        val scanEntered = CountDownLatch(1)
        val scanRelease = CountDownLatch(1)
        val loadEntered = CountDownLatch(1)
        val loadRelease = CountDownLatch(1)
        f.service.submitIo { scanEntered.countDown(); assertTrue(scanRelease.await(10, TimeUnit.SECONDS)) }
        assertTrue(scanEntered.await(10, TimeUnit.SECONDS))
        val done = CompletableFuture<Unit>()
        try {
            fx {
                EditorView::class.java.getDeclaredMethod("onUploadLocalData", UploadDataCategory::class.java)
                    .apply { isAccessible = true }.invoke(f.views.first(), UploadDataCategory.ITEM)
            }
            fx { Utility.closeConnection { done.complete(Unit) }; assertEquals("処理の完了を待っています", status()) }
            scanRelease.countDown()
            awaitFx { dialog() != null }
            f.service.submitIo { loadEntered.countDown(); assertTrue(loadRelease.await(10, TimeUnit.SECONDS)) }
            assertTrue(loadEntered.await(10, TimeUnit.SECONDS))
            fx {
                val pane = dialog()!!
                assertTrue(f.views.first().closeBusy)
                pane.lookupAll(".check-box").filterIsInstance<javafx.scene.control.CheckBox>()
                    .filter { !it.text.contains("imported") }.forEach { it.isSelected = false }
                (pane.lookupButton(pane.buttonTypes.first { it.buttonData == ButtonBar.ButtonData.OK_DONE }) as Button).fire()
            }
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
            val localAfter = assertIs<StoreResult.Success<LocalDocumentSnapshot>>(local.documents.read()).value
            assertEquals(localBefore.documents.single { it.key.id == "imported" },
                localAfter.documents.single { it.key.id == "imported" })
            assertEquals(localBefore.documents.associate { it.key to listOf(it.saved, it.savedRaw, it.savedPending, it.savedDeleted) },
                localAfter.documents.associate { it.key to listOf(it.saved, it.savedRaw, it.savedPending, it.savedDeleted) })
        } finally {
            scanRelease.countDown(); loadRelease.countDown()
            fx { dialog()?.let { (it.lookupButton(it.buttonTypes.last()) as Button).fire() } }
        }
    }

    @Test fun `二画面目の退避失敗では全画面を保持して切断せず再試行する`() = fixture { f ->
        f.writeBackup = { _, id -> if (id == "second") error("退避失敗") }
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
        f.writeBackup = { _, _ -> }
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

    @Test fun `Mod設定の単一領域を移動しても空draftを接続終了前に退避する`() = fixture { f ->
        assertIs<StoreResult.Success<*>>(f.service.localService.modConfig.save("invalid: ["))
        fx { ModConfigWindowManager.open(f.service) }
        awaitFx { (f.home.scene.root.lookup("#contentArea") as? TextArea)?.isEditable == true }
        val done = CompletableFuture<Unit>()
        fx {
            val workspace = assertIs<WorkspaceView>(
                assertIs<AppFrame>(f.home.scene.root).center)
            val text = assertIs<TextArea>(f.home.scene.root.lookup("#contentArea"))
            ModConfigWindowManager.open(f.service)
            assertEquals(1, workspace.titles().count { it == WorkspaceFeature.MOD_CONFIG })
            assertSame(text, f.home.scene.root.lookup("#contentArea"))
            assertTrue(workspace.move(WorkspaceFeature.MOD_CONFIG, WorkspaceFeature.ITEM_EDITOR, DockSide.LEFT))
            assertSame(text, f.home.scene.root.lookup("#contentArea"))
            f.home.width = 640.0
            f.home.scene.root.applyCss(); f.home.scene.root.layout()
            assertTrue((text.parent.lookup("#pathLabel") as Label).isWrapText)
            assertTrue((text.parent.lookup("#statusLabel") as Label).isWrapText)
            text.text = ""
            stopModBackupTimer()
            Utility.closeConnection { done.complete(Unit) }
        }
        done.get(10, TimeUnit.SECONDS)
        val record = assertIs<StoreResult.Success<LocalDocumentRecord>>(f.service.localService.modConfig.localState()).value
        assertEquals("invalid: [", record.saved)
        assertEquals("", record.editing)
        assertTrue(record.unsaved)
        assertEquals(1, f.connection.closed)
    }

    @Test fun `Mod設定退避の世代不一致では空draftと接続と他writerの保存を保持する`() = fixture { f ->
        val local = f.service.localService
        assertIs<StoreResult.Success<*>>(local.modConfig.save("saved: ["))
        fx { ModConfigWindowManager.open(f.service) }
        awaitFx { (f.home.scene.root.lookup("#contentArea") as? TextArea)?.isEditable == true }
        val observed = assertIs<StoreResult.Success<LocalDocumentRecord>>(local.modConfig.localState()).value
        assertIs<StoreResult.Success<*>>(local.modConfig.saveObserved("other-writer: [",
            LocalDocumentVersion(observed.key, observed.revision)))
        val done = CompletableFuture<Unit>()
        fx {
            (f.home.scene.root.lookup("#contentArea") as TextArea).text = ""
            stopModBackupTimer()
            Utility.closeConnection { done.complete(Unit) }
        }
        awaitFx { !ApplicationFlow.closingConnection }
        fx {
            assertTrue(status().contains("Mod設定を退避できませんでした"))
            assertEquals("", (f.home.scene.root.lookup("#contentArea") as TextArea).text)
            assertTrue(f.home.isShowing)
            assertSame(f.connection, EditorSession.connection)
            assertFalse(f.home.scene.root.isDisable)
        }
        assertEquals(0, f.connection.closed)
        assertFalse(done.isDone)
        assertEquals("other-writer: [", assertIs<StoreResult.Success<LocalDocumentRecord>>(local.modConfig.localState()).value.saved)
    }

    private fun stopModBackupTimer() {
        val controller = assertIs<ModConfigController>(ModConfigWindowManager::class.java
            .getDeclaredField("activeController").apply { isAccessible = true }.get(ModConfigWindowManager))
        assertIs<PauseTransition>(ModConfigController::class.java.getDeclaredField("backupDelay")
            .apply { isAccessible = true }.get(controller)).stop()
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

    private fun fixture(withoutHome: Boolean = false, action: (Fixture) -> Unit) {
        startFx()
        val directory = createTempDirectory("connection-close-").toFile()
        val memory = InMemoryEditorDataStore()
        val remote = RemoteStore(memory, directory.resolve("offline"))
        val service = EditorDataService(remote, directory)
        val connection = Connection(service)
        val f = Fixture(service, connection, remote)
        val settings = AppSettingsManager.load()
        val layoutKey = "close-without-home-test"
        fun <T : ManagedData<T, *>> access(descriptor: EditorDataDescriptor<T>) = service.DataAccess(EditorDataDescriptor(
            descriptor.dataType, descriptor.load, descriptor.save, descriptor.validate, descriptor.merger,
            loadBackup = descriptor.loadBackup,
            saveBackup = { file, data -> f.writeBackup(file, data.id); descriptor.saveBackup(file, data) },
            validateInStore = descriptor.validateInStore, storesIdInFile = descriptor.storesIdInFile
        ))
        val item = EditorDataDescriptors.item.createDefault("first")
        val ore = EditorDataDescriptors.ore.createDefault("second")
        assertIs<StoreResult.Success<*>>(memory.save(EditorDataDescriptors.item, item.id, item))
        assertIs<StoreResult.Success<*>>(memory.save(EditorDataDescriptors.ore, ore.id, ore))
        assertIs<StoreResult.Success<*>>(service.localService.items.saveLocalDocument(item.id, item, emptyMap()))
        assertIs<StoreResult.Success<*>>(service.localService.ores.saveLocalDocument(ore.id, ore, emptyMap()))
        fx {
            EditorSession.startConnection(connection)
            if (withoutHome) AppSettingsManager.save(settings.copy(workspaceLayouts = settings.workspaceLayouts +
                (layoutKey to WorkspaceLayout(root = WorkspaceNode.Feature(WorkspaceFeature.ITEM_EDITOR), active = WorkspaceFeature.ITEM_EDITOR))))
            f.home = workspaceStage(if (withoutHome) layoutKey else null)
            fun <T : ManagedData<T, *>> open(feature: WorkspaceFeature, id: String, descriptor: EditorDataDescriptor<T>) {
                EditorWindowManager.openEditor(feature, id, FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))) { main ->
                    View(main, service, access(descriptor), id).also { f.views += it }
                }
            }
            open(WorkspaceFeature.ITEM_EDITOR, item.id, EditorDataDescriptors.item)
            open(WorkspaceFeature.ORE_EDITOR, ore.id, EditorDataDescriptors.ore)
        }
        awaitFx { f.views.all { !it.controller.loadingRunning } }
        var primary: Throwable? = null
        try { action(f) }
        catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            try {
                fx { Platform.setImplicitExit(false) }
                connection.beforeClose = {}
                connection.fail = false
                f.writeBackup = { _, _ -> }
                awaitFx { !ApplicationFlow.closingConnection }
                val closed = CompletableFuture<Boolean>()
                fx { EditorWindowManager.closeAll { closed.complete(it) } }
                val closedSuccessfully = closed.get(10, TimeUnit.SECONDS)
                val records = (service.documents.read() as? StoreResult.Success)?.value?.documents
                    ?.map { Triple(it.key.category, it.key.id, it.revision) }
                assertTrue(closedSuccessfully, "正常退避終了=$closedSuccessfully / 文書revision=$records")
                EditorSession.resetMode()
                fx { Stage.getWindows().toList().filterIsInstance<Stage>().forEach { it.close() } }
                if (withoutHome) AppSettingsManager.save(settings)
                directory.deleteRecursively()
            } catch (error: Throwable) {
                if (primary == null) throw error
                primary.addSuppressed(error)
            }
        }
    }

    private class Fixture(val service: EditorDataService, val connection: Connection, val remote: RemoteStore) {
        lateinit var home: Stage
        val views = mutableListOf<View<*>>()
        var writeBackup: (java.io.File, String) -> Unit = { _, _ -> }
    }
    private class RemoteStore(val memory: InMemoryEditorDataStore, override val offlineDirectory: java.io.File) : EditorDataStore by memory {
        override val kind = EditorDataStoreKind.REMOTE
        override val documentSource = EditorDocumentSource.Server("connection-close-fixture", offlineDirectory.parentFile.absolutePath)
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
    private class View<T : ManagedData<T, *>>(val controller: MainController, service: EditorDataService,
        access: EditorDataService.DataAccess<T>, val id: String) : EditorView<T>(controller, service, access) {
        private fun meta(data: ManagedData<*, *>) = when (data) {
            is MutableItemBaseData -> data.editorMeta
            is MutableOreBaseData -> data.editorMeta
            else -> error("接続終了試験はItem/Oreを使用します")
        }
        fun retained() = editingDataMap[id]?.let { meta(it).comment == listOf("編集内容") } == true
        fun imported() = (editingDataMap["imported"] as? MutableItemBaseData)?.editorMeta?.comment == listOf("取込")
        fun selectServerSource() {
            controller.actionButtonContainer.children.filterIsInstance<javafx.scene.control.MenuButton>()
                .singleOrNull { it.text == "ローカル" }?.items?.get(1)?.fire()
        }
        fun serverSourceReady() = dataService.isRemote && !controller.loadingRunning && !closeBusy
        override fun setupSidebar(container: VBox, selectId: String?) {
            val original = originalDataMap.getValue(id)
            editingDataMap[id] = original.deepCopy().apply { meta(this).comment.add("編集内容") }
            currentSelectedDataId = id
            main.finishLoading()
        }
        override fun setupMainContent(selectData: T) = Unit
        override fun resolveSaveConflict(dataId: String, originalData: T, currentData: T, serverData: T) = currentData
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
