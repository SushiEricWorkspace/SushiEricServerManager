package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceView
import io.github.sushiericworkspace.sushiericservermanager.communication.management.*
import io.github.sushiericworkspace.sushiericservermanager.config.DockSide
import io.github.sushiericworkspace.sushiericservermanager.config.OS
import io.github.sushiericworkspace.sushiericservermanager.config.WorkspaceFeature
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.feature.console.*
import io.github.sushiericworkspace.sushiericservermanager.ui.theme.ThemeManager
import javafx.animation.AnimationTimer
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.geometry.Orientation
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.control.ListView
import javafx.scene.control.ScrollBar
import javafx.scene.control.Slider
import javafx.scene.control.TextArea
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.StackPane
import javafx.stage.Popup
import javafx.stage.Stage
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ConsoleWorkspaceTest {
    @Test fun `コンソールは単一領域で移動しcloseと再openで購読を重複させない`() = withWorkspace { fixture ->
        fx {
            val client = EditorSession.managementClient
            val states = listeners(client, "stateListeners")
            val messages = listeners(client, "messageListeners")
            ConsoleWindowManager.open()
            val first = assertNotNull(fixture.workspace.lookup("#outputListView"))
            ConsoleWindowManager.open()
            assertEquals(1, fixture.workspace.titles().count { it == WorkspaceFeature.CONSOLE })
            assertEquals(states + 1, listeners(client, "stateListeners"))
            assertEquals(messages + 1, listeners(client, "messageListeners"))
            assertEquals(1, fixture.transport.sent.count { it == ServerManagementRequest.ConsoleSubscribe })
            assertTrue(fixture.workspace.move(WorkspaceFeature.CONSOLE, WorkspaceFeature.HOME, DockSide.BOTTOM))
            assertSame(first, fixture.workspace.lookup("#outputListView"))
            ConsoleWindowManager.close()
            ConsoleWindowManager.close()
            assertEquals(states, listeners(client, "stateListeners"))
            assertEquals(messages, listeners(client, "messageListeners"))
            assertEquals(1, fixture.transport.sent.count { it == ServerManagementRequest.ConsoleUnsubscribe })
            ConsoleWindowManager.open()
            assertEquals(states + 1, listeners(client, "stateListeners"))
            assertEquals(messages + 1, listeners(client, "messageListeners"))
            assertEquals(2, fixture.transport.sent.count { it == ServerManagementRequest.ConsoleSubscribe })
            ConsoleWindowManager.close()
        }
    }

    @Test fun `大量ログを上限内で表示しレベルと非表示barの通常scrollを維持する`() = withWorkspace { fixture ->
        val console = fx { fixture.console() }
        fx {
            repeat(3000) { fixture.transport.message(log("line-$it")) }
            val buffer = field(console.controller, "incomingLogs") as ConsoleLogBuffer
            val timer = field(console.controller, "logDrainTimer") as AnimationTimer
            assertEquals(2000, buffer.size)
            repeat(10) { timer.handle(it.toLong()) }
            assertEquals(0, buffer.size)
            assertEquals(1000, console.output.items.size)
            assertEquals(1000, (field(console.controller, "allEntries") as List<*>).size)
            fixture.transport.message(log("debug-line", "DEBUG")); timer.handle(11)
            assertFalse(console.output.items.any { it.toString().contains("debug-line") })
            (console.root.lookup("#logLevelSlider") as Slider).value = 0.0
            assertTrue(console.output.items.any { it.toString().contains("debug-line") })
            console.output.scrollTo(console.output.items.lastIndex)
            console.root.applyCss(); console.root.layout()
        }
        val bar = fx { console.output.lookupAll(".scroll-bar").filterIsInstance<ScrollBar>().single { it.orientation == Orientation.VERTICAL } }
        var beforeScroll = "未採取"
        var afterScroll = "未採取"
        var beforeValue = Double.NaN
        val scrollState = {
            runCatching {
                val held = field(console.controller, "verticalScrollBar") as ScrollBar?
                "nano=${System.nanoTime()} fx=${Platform.isFxApplicationThread()} " +
                    "skin=${console.output.skin?.javaClass?.name} heldNull=${held == null} sameBar=${held === bar} " +
                    "value=${bar.value} min=${bar.min} max=${bar.max} visible=${bar.isVisible} " +
                    "managed=${bar.isManaged} opacity=${bar.opacity} size=${bar.width}x${bar.height} " +
                    "enabled=${(field(console.controller, "autoScrollPolicy") as ConsoleAutoScrollPolicy).isEnabled} epsilon=0.001"
            }.getOrElse { "診断取得失敗: ${it.javaClass.name}" }
        }
        fx {
            beforeScroll = scrollState()
            beforeValue = bar.value
            assertEquals(0.0, bar.opacity)
            val flow = console.output.lookup("#virtual-flow") as VirtualFlow<*>
            val target = assertNotNull(flow.firstVisibleCell)
            target.fireEvent(ScrollEvent(ScrollEvent.SCROLL, 10.0, 10.0, 10.0, 10.0,
                false, false, false, false, false, false, 0.0, 120.0, 0.0, 120.0,
                ScrollEvent.HorizontalTextScrollUnits.NONE, 0.0, ScrollEvent.VerticalTextScrollUnits.LINES, 3.0, 0, null))
        }
        awaitFx { bar.value < beforeValue && bar.value < bar.max - 0.001 }
        fx {
            val enabled = (field(console.controller, "autoScrollPolicy") as ConsoleAutoScrollPolicy).isEnabled
            afterScroll = scrollState()
            assertFalse(enabled, "scroll前: $beforeScroll / assert直前: $afterScroll")
            fixture.transport.message(log("tail")); (field(console.controller, "logDrainTimer") as AnimationTimer).handle(12)
            assertTrue(bar.value < bar.max)
        }
    }

    @Test fun `切断再接続と終了で旧callbackを拒否し完了と未確認と未送信を混同しない`() = withWorkspace { fixture ->
        val console = fx { fixture.console() }
        lateinit var oldMessage: (ServerManagementResponse) -> Unit
        lateinit var oldState: (ServerManagementState) -> Unit
        lateinit var oldConnected: ServerManagementState
        lateinit var current: Transport
        fx {
            val command = console.root.lookup("#commandField") as TextArea
            assertTrue(console.root.lookupAll(".console-connection-label").filterIsInstance<Label>().any { it.text.contains("保存・アップロード対象外") })
            command.text = "done\npending\nunsent"
            fixture.transport.reject = "unsent"
            invoke(console.controller, "executeCommands")
            val commands = fixture.transport.sent.filterIsInstance<ServerManagementRequest.CommandExecute>()
            assertEquals(listOf("done", "pending"), commands.map { it.command })
            assertEquals("unsent", command.text)
            fixture.transport.message(ServerManagementResponse.CommandResult(true, output = listOf("done-confirmed"), nonce = commands[0].nonce))
            command.text = "say"
            invoke(console.controller, "requestCompletion")
            val completion = fixture.transport.sent.filterIsInstance<ServerManagementRequest.CommandComplete>().last()
            @Suppress("UNCHECKED_CAST")
            oldMessage = field(console.controller, "messageListener") as (ServerManagementResponse) -> Unit
            @Suppress("UNCHECKED_CAST")
            oldState = field(console.controller, "stateListener") as (ServerManagementState) -> Unit
            oldConnected = EditorSession.managementClient.state
            val queued = Thread { oldMessage(ServerManagementResponse.Error("queued-old")) }.apply { start() }
            queued.join(5000); assertFalse(queued.isAlive)
            current = Transport()
            assertTrue(EditorSession.managementClient.connect(current))
            oldState(oldConnected)
            val late = Thread {
                oldMessage(log("late-old"))
                oldMessage(ServerManagementResponse.CommandResult(true, output = listOf("late-result"), nonce = commands[1].nonce))
                oldMessage(ServerManagementResponse.CommandCompleteResult(listOf(ServerManagementCommandSuggestion("old", 0, 3)), completion.nonce))
            }.apply { start() }
            late.join(5000); assertFalse(late.isAlive)
            assertTrue(current.sent.filterIsInstance<ServerManagementRequest.CommandExecute>().isEmpty())
            assertEquals(1, current.sent.count { it == ServerManagementRequest.ConsoleSubscribe })
        }
        fx {
            (field(console.controller, "logDrainTimer") as AnimationTimer).handle(0)
            val text = console.output.items.joinToString()
            assertTrue(text.contains("done-confirmed"))
            assertTrue(text.contains("実行結果未確認（再送しません）: pending"))
            assertFalse(text.contains("実行結果未確認（再送しません）: done"))
            assertFalse(text.contains("実行結果未確認（再送しません）: unsent"))
            assertFalse(text.contains("queued-old") || text.contains("late-old") || text.contains("late-result"))
            assertFalse((field(console.controller, "suggestionPopup") as Popup).isShowing)
            val command = console.root.lookup("#commandField") as TextArea
            command.text = "now"; invoke(console.controller, "executeCommands")
            val request = current.sent.filterIsInstance<ServerManagementRequest.CommandExecute>().single()
            current.message(ServerManagementResponse.CommandResult(true, output = listOf("now-confirmed"), nonce = request.nonce))
            assertTrue(console.output.items.any { it.toString().contains("now-confirmed") })
            val states = listeners(EditorSession.managementClient, "stateListeners")
            val messages = listeners(EditorSession.managementClient, "messageListeners")
            ConsoleWindowManager.close(); console.controller.dispose()
            assertEquals(states - 1, listeners(EditorSession.managementClient, "stateListeners"))
            assertEquals(messages - 1, listeners(EditorSession.managementClient, "messageListeners"))
            assertEquals(1, current.sent.count { it == ServerManagementRequest.ConsoleUnsubscribe })
            oldState(oldConnected); oldMessage(log("after-close"))
            assertEquals(0, (field(console.controller, "incomingLogs") as ConsoleLogBuffer).size)
        }
    }

    @Test fun `BG切断通知のFX適用より先の操作でも送信済みだけを一度結果未確認にする`() {
        for (entry in listOf("executeCommands", "requestCompletion")) withWorkspace { fixture ->
            val console = fx { fixture.console() }
            fx {
                val command = console.root.lookup("#commandField") as TextArea
                command.text = "done\npending\nunsent"
                fixture.transport.reject = "unsent"
                invoke(console.controller, "executeCommands")
                val sent = fixture.transport.sent.filterIsInstance<ServerManagementRequest.CommandExecute>()
                assertEquals(listOf("done", "pending"), sent.map { it.command })
                fixture.transport.message(ServerManagementResponse.CommandResult(true, output = listOf("done-confirmed"), nonce = sent[0].nonce))
                assertEquals("unsent", command.text)
                fun disconnectWhileFxBlocked() {
                    val operation = FutureTask { EditorSession.managementClient.disconnect() }
                    val worker = Thread(operation).apply { start() }
                    operation.get(5, TimeUnit.SECONDS)
                    worker.join(5000)
                    assertFalse(worker.isAlive)
                    assertIs<ServerManagementState.Disconnected>(EditorSession.managementClient.state)
                }
                if (entry == "executeCommands") {
                    disconnectWhileFxBlocked()
                } else {
                    fixture.transport.beforeSend = { request ->
                        if (request is ServerManagementRequest.CommandComplete) {
                            disconnectWhileFxBlocked()
                            false
                        } else true
                    }
                }
                invoke(console.controller, entry)
                // このFX turn中はBGから予約された接続状態適用がまだ走らない。
                assertEquals(1, console.output.items.count { it.toString().contains("実行結果未確認（再送しません）: pending") })
                val text = console.output.items.joinToString()
                assertTrue(text.contains("done-confirmed"))
                assertFalse(text.contains("実行結果未確認（再送しません）: done"))
                assertFalse(text.contains("実行結果未確認（再送しません）: unsent"))
                assertEquals(listOf("done", "pending"), fixture.transport.sent.filterIsInstance<ServerManagementRequest.CommandExecute>().map { it.command })
                assertEquals("unsent", command.text)
            }
            fx {
                assertEquals(1, console.output.items.count { it.toString().contains("実行結果未確認（再送しません）: pending") })
                val current = Transport()
                assertTrue(EditorSession.managementClient.connect(current))
                assertTrue(current.sent.filterIsInstance<ServerManagementRequest.CommandExecute>().isEmpty())
                assertEquals(1, console.output.items.count { it.toString().contains("実行結果未確認（再送しません）: pending") })
            }
        }
    }

    private data class Console(val root: Parent, val controller: ConsoleController) {
        val output: ListView<*> get() = root.lookup("#outputListView") as ListView<*>
    }
    private data class Fixture(val stage: Stage, val workspace: WorkspaceView, val transport: Transport) {
        fun console(): Console {
            val loader = FXMLLoader(ConsoleWorkspaceTest::class.java.getResource(AppScreen.CONSOLE.fxml!!))
            val root = loader.load<Parent>(); val controller = loader.getController<ConsoleController>()
            workspace.show(WorkspaceFeature.CONSOLE, root, dispose = controller::dispose)
            return Console(root, controller)
        }
    }
    private class Transport : ManagementTransport {
        lateinit var listener: ServerManagementListener
        val sent = mutableListOf<ServerManagementRequest>()
        var reject: String? = null
        var beforeSend: ((ServerManagementRequest) -> Boolean)? = null
        override fun connect(listener: ServerManagementListener, timeout: Duration): Int { this.listener = listener; return 12345 }
        override fun send(request: ServerManagementRequest): Boolean {
            if (beforeSend?.invoke(request) == false) return false
            if (request is ServerManagementRequest.CommandExecute && request.command == reject) return false
            sent += request; return true
        }
        override fun disconnect() = Unit
        fun message(message: ServerManagementResponse) = listener.onMessage(message)
    }
    private fun withWorkspace(action: (Fixture) -> Unit) {
        val isolated = System.getProperty("sushieric.test.configRoot") ?: error("隔離したGradle testを使用してください")
        assertTrue(Path.of(OS.dataConfigBase).toAbsolutePath().normalize().startsWith(Path.of(isolated).toAbsolutePath().normalize()))
        val ready = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
        catch (_: IllegalStateException) { Platform.runLater { Platform.setImplicitExit(false); ready.countDown() } }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        val fixture = fx {
            EditorSession.prepareOnlineMode()
            val transport = Transport(); assertTrue(EditorSession.managementClient.connect(transport))
            val home = StackPane()
            val frame = AppFrame(AppScreen.HOME, home)
            val workspace = WorkspaceView(frame, home, null, {}, { true }, {})
            frame.center = workspace
            val stage = Stage().apply { scene = Scene(frame, 1400.0, 800.0); ThemeManager.apply(scene); show() }
            Fixture(stage, workspace, transport)
        }
        try { action(fixture) } finally { fx { fixture.stage.close(); EditorSession.resetMode() } }
    }
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun listeners(client: ServerManagementClient, name: String) = (field(client, name) as Collection<*>).size
    private fun invoke(controller: ConsoleController, method: String) = ConsoleController::class.java.getDeclaredMethod(method).apply { isAccessible = true }.invoke(controller)
    private fun log(text: String, level: String = "INFO") = ServerManagementResponse.ConsoleLog("2026-10-21T00:00:00Z", level, text)
    private fun awaitFx(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) { if (fx(condition)) return; Thread.yield() }
        assertTrue(fx(condition))
    }
    private fun <T> fx(action: () -> T): T { val task = FutureTask(action); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS) }
}
