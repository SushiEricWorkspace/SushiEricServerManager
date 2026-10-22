package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceView
import io.github.sushiericworkspace.sushiericservermanager.communication.SshCommandResult
import io.github.sushiericworkspace.sushiericservermanager.communication.SshCommandRunner
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.config.DockSide
import io.github.sushiericworkspace.sushiericservermanager.config.OS
import io.github.sushiericworkspace.sushiericservermanager.config.ServerControlCommandSet
import io.github.sushiericworkspace.sushiericservermanager.config.ServerControlCommandsManager
import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfile
import io.github.sushiericworkspace.sushiericservermanager.config.WorkspaceFeature
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.feature.servercontrol.ServerControlController
import io.github.sushiericworkspace.sushiericservermanager.feature.servercontrol.ServerControlWindowManager
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import javafx.animation.Animation
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Scene
import javafx.scene.control.ButtonBase
import javafx.scene.control.ButtonBar
import javafx.scene.control.DialogPane
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.BorderPane
import javafx.stage.Stage
import javafx.stage.Window
import net.schmizz.sshj.SSHClient
import org.mockito.Mockito
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServerControlWorkspaceTest {
    @Test fun `サーバー操作は単一分割領域で全設定と実行結果を保持する`() = withControl { fixture ->
        fx {
            val workspace = fixture.stage.scene.root as AppFrame
            val view = workspace.center as WorkspaceView
            ServerControlWindowManager.open()
            val first = view.lookup("#outputArea")
            ServerControlWindowManager.open()
            assertEquals(1, view.titles().count { it == WorkspaceFeature.SERVER_CONTROL })
            assertSame(first, view.lookup("#outputArea"))
            val scroll = view.lookup("#serverControlScroll") as ScrollPane
            assertTrue(scroll.isFitToWidth)
            for (id in listOf("workingDirectoryField", "startCommandField", "stopCommandField", "restartCommandField", "saveButton", "importButton", "exportButton", "outputArea")) {
                assertNotNull(scroll.content.lookup("#$id"), id)
            }
            assertTrue(view.move(WorkspaceFeature.SERVER_CONTROL, WorkspaceFeature.HOME, DockSide.BOTTOM))
            assertSame(first, view.lookup("#outputArea"))
            ServerControlWindowManager.close()
            assertFalse(view.titles().contains(WorkspaceFeature.SERVER_CONTROL))
        }
    }

    @Test fun `確認取消は送信せず終了コードと未確認結果を区別する`() = withControl { fixture ->
        val runner = Mockito.mock(SshCommandRunner::class.java)
        fx { setField(fixture.controller, "commandRunner", runner); confirm(false) { fixture.controller.handleStop() } }
        Mockito.verifyNoInteractions(runner)
        for (result in listOf(SshCommandResult(0, "fixture completed", ""), SshCommandResult(7, "", "fixture failed"), SshCommandResult(null, "", ""))) {
            Mockito.`when`(runner.run(fixture.client, "stop.sh")).thenReturn(result)
            fx { confirm(true) { fixture.controller.handleStop() } }
            awaitFx { field(fixture.controller, "running") == false }
            fx {
                val status = (fixture.root.lookup("#statusLabel") as Label).text
                when (result.exitStatus) {
                    0 -> assertTrue(status.contains("コマンドが正常に終了"))
                    7 -> assertTrue(status.contains("終了コード 7"))
                    else -> assertTrue(status.contains("未確認"))
                }
            }
        }
    }

    @Test fun `切断と領域終了は送信済み作用を取消扱いにせず旧完了を拒否する`() = withControl { fixture ->
        for (close in listOf(false, true)) {
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val worker = AtomicReference<Thread>()
            val runner = Mockito.mock(SshCommandRunner::class.java)
            Mockito.`when`(runner.run(fixture.client, "stop.sh")).thenAnswer {
                worker.set(Thread.currentThread()); entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                SshCommandResult(0, "old-success-must-not-appear", "")
            }
            fx { setField(fixture.controller, "commandRunner", runner); confirm(true) { fixture.controller.handleStop() } }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                fx {
                    if (close) fixture.controller.dispose() else {
                        Mockito.`when`(fixture.manager.sshClient).thenReturn(null)
                        Mockito.`when`(fixture.manager.isConnected).thenReturn(false)
                        invoke(fixture.controller, "refreshConnection")
                        assertTrue((fixture.root.lookup("#statusLabel") as Label).text.contains("送信済み操作の結果は未確認"))
                    }
                }
            } finally { release.countDown(); worker.get()?.join(5000) }
            assertFalse(worker.get().isAlive)
            fx {
                assertFalse((fixture.root.lookup("#outputArea") as TextArea).text.contains("old-success-must-not-appear"))
                if (!close) {
                    Mockito.`when`(fixture.manager.sshClient).thenReturn(fixture.client)
                    Mockito.`when`(fixture.manager.isConnected).thenReturn(true)
                    invoke(fixture.controller, "refreshConnection")
                } else assertEquals(Animation.Status.STOPPED, (field(fixture.controller, "connectionRefresh") as Animation).status)
            }
        }
    }

    @Test fun `実行確認中に対象が変わった場合はOKでも送信しない`() = withControl { fixture ->
        val runner = Mockito.mock(SshCommandRunner::class.java)
        fx {
            setField(fixture.controller, "commandRunner", runner)
            confirm(true, beforeReply = {
                Mockito.`when`(fixture.manager.sshClient).thenReturn(Mockito.mock(SSHClient::class.java))
                Mockito.`when`(fixture.manager.currentProfile).thenReturn(ServerProfile("fixture258-other", "other.invalid", 22, "fixture", "/other", ""))
            }) { fixture.controller.handleStop() }
            assertFalse(field(fixture.controller, "running") as Boolean)
            assertFalse((fixture.root.lookup("#outputArea") as TextArea).text.contains("> stop.sh"))
        }
        Mockito.verifyNoInteractions(runner)
    }

    @Test fun `import確認中の対象変更後は新対象の未保存入力を旧定義で上書きしない`() = withControl { fixture ->
        fx {
            val imported = ServerControlCommandSet("import-start", "import-stop", "import-restart", "/imported")
            val generation = field(fixture.controller, "generation") as Long
            confirm(true, header = "読み込んだコマンド", beforeReply = {
                Mockito.`when`(fixture.manager.sshClient).thenReturn(Mockito.mock(SSHClient::class.java))
                Mockito.`when`(fixture.manager.currentProfile).thenReturn(ServerProfile("fixture258-other", "other.invalid", 22, "fixture", "/other", ""))
                invoke(fixture.controller, "refreshConnection")
                (fixture.root.lookup("#workingDirectoryField") as TextField).text = "/other-unsaved"
                (fixture.root.lookup("#startCommandField") as TextArea).text = "other-unsaved-start"
                (fixture.root.lookup("#stopCommandField") as TextArea).text = "other-unsaved-stop"
                (fixture.root.lookup("#restartCommandField") as TextArea).text = "other-unsaved-restart"
            }) {
                fixture.controller.javaClass.getDeclaredMethod("confirmImported", ServerControlCommandSet::class.java, java.lang.Long.TYPE)
                    .apply { isAccessible = true }.invoke(fixture.controller, imported, generation)
            }
            assertEquals("/other-unsaved", (fixture.root.lookup("#workingDirectoryField") as TextField).text)
            assertEquals("other-unsaved-start", (fixture.root.lookup("#startCommandField") as TextArea).text)
            assertEquals("other-unsaved-stop", (fixture.root.lookup("#stopCommandField") as TextArea).text)
            assertEquals("other-unsaved-restart", (fixture.root.lookup("#restartCommandField") as TextArea).text)
            assertFalse((fixture.root.lookup("#statusLabel") as Label).text.contains("コマンドを読み込みました"))
        }
    }

    private data class Fixture(val stage: Stage, val root: BorderPane, val controller: ServerControlController, val manager: SshManager, val client: SSHClient)
    private fun withControl(action: (Fixture) -> Unit) {
        val isolated = System.getProperty("sushieric.test.configRoot") ?: error("隔離したGradle testを使用してください")
        assertTrue(Path.of(OS.dataConfigBase).toAbsolutePath().normalize().startsWith(Path.of(isolated).toAbsolutePath().normalize()))
        val ready = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
        catch (_: IllegalStateException) { Platform.runLater { Platform.setImplicitExit(false); ready.countDown() } }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        val previous = EditorSession.sshManager
        val loader = FXMLLoader(javaClass.getResource(AppScreen.SERVER_CONTROL.fxml!!))
        var stage: Stage? = null
        try {
            val fixture = fx {
                val manager = Mockito.mock(SshManager::class.java); val client = Mockito.mock(SSHClient::class.java)
                Mockito.`when`(manager.isConnected).thenReturn(true)
                Mockito.`when`(manager.sshClient).thenReturn(client)
                Mockito.`when`(manager.compatibility).thenReturn(ManagerCompatibility.Unchecked)
                Mockito.`when`(manager.currentProfile).thenReturn(ServerProfile("fixture258", "fixture.invalid", 22, "fixture", "/fixture", ""))
                EditorSession.sshManager = manager
                ServerControlCommandsManager.saveFor("fixture258", ServerControlCommandSet(startCommand="start.sh", stopCommand="stop.sh", restartCommand="restart.sh"))
                val root = loader.load<BorderPane>(); val controller = loader.getController<ServerControlController>()
                val owner = workspaceStage(width = 900.0).also { stage = it }
                val view = (owner.scene.root as AppFrame).center as WorkspaceView
                view.show(WorkspaceFeature.SERVER_CONTROL, root, dispose = controller::dispose)
                Fixture(owner, root, controller, manager, client)
            }
            action(fixture)
        } finally {
            fx {
                try { loader.getController<ServerControlController>()?.dispose() }
                finally {
                    try { stage?.close() }
                    finally { EditorSession.sshManager = previous }
                }
            }
        }
    }
    private fun confirm(accepted: Boolean, header: String = "fixture258", beforeReply: () -> Unit = {}, action: () -> Unit) {
        val reply = FutureTask {
            val pane = Window.getWindows().filter { it.isShowing }.mapNotNull { it.scene?.root as? DialogPane }.single()
            try {
                assertTrue(pane.headerText.contains(header)); beforeReply()
                val role = if (accepted) ButtonBar.ButtonData.OK_DONE else ButtonBar.ButtonData.CANCEL_CLOSE
                val type = pane.buttonTypes.single { it.buttonData == role }
                (pane.lookupButton(type) as ButtonBase).fire()
            } catch (error: Throwable) {
                pane.scene.window.hide()
                throw error
            }
        }
        Platform.runLater(reply); action(); reply.get(5, TimeUnit.SECONDS)
    }
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun setField(target: Any, name: String, value: Any) { target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value) }
    private fun invoke(target: Any, name: String) { target.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(target) }
    private fun awaitFx(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) { if (fx(condition)) return; Thread.yield() }
        assertTrue(fx(condition))
    }
    private fun <T> fx(action: () -> T): T { val task = FutureTask(action); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS) }
}
