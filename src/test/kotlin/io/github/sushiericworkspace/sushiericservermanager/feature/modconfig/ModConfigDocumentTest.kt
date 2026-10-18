package io.github.sushiericworkspace.sushiericservermanager.feature.modconfig

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.service.*
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
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
        fx { f.text.text = "invalid: ["; f.save.fire() }
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

    private inner class Fixture(val service: EditorDataService, val memory: InMemoryEditorDataStore,
                                val sceneRoot: Parent, val controller: ModConfigController, val stage: Stage,
                                val newService: () -> EditorDataService) {
        var remoteWrites = 0
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
            override fun writeText(relativePath: String, text: String): StoreResult<Unit> {
                if (initialized) f.remoteWrites++
                error("接続元へ保存してはいけません")
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

    private fun respond(fixture: Fixture, buttonId: String, approve: Boolean = true) {
        fx {
            val button = fixture.sceneRoot.lookup("#$buttonId") as Button
            Platform.runLater { button.fire() }
        }
        waitFx { fixture.hasDialog() }
        fx {
            val pane = fixture.dialogs().single()
            val role = if (approve) ButtonBar.ButtonData.OK_DONE else ButtonBar.ButtonData.CANCEL_CLOSE
            val type = pane.buttonTypes.first { it.buttonData == role }
            Platform.runLater { (pane.lookupButton(type) as Button).fire() }
        }
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
