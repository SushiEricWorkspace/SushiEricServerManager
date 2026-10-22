package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceManager
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceView
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementState
import io.github.sushiericworkspace.sushiericservermanager.communication.management.MinecraftServerStatus
import io.github.sushiericworkspace.sushiericservermanager.communication.management.JvmStatus
import io.github.sushiericworkspace.sushiericservermanager.config.DockSide
import io.github.sushiericworkspace.sushiericservermanager.config.OS
import io.github.sushiericworkspace.sushiericservermanager.config.WorkspaceFeature
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.HomeController
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.feature.dashboard.DashboardController
import io.github.sushiericworkspace.sushiericservermanager.feature.dashboard.DashboardWindowManager
import io.github.sushiericworkspace.sushiericservermanager.monitor.ServerMonitorSnapshot
import io.github.sushiericworkspace.sushiericservermanager.ui.theme.ThemeManager
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.stage.Stage
import org.mockito.Mockito
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HomeDashboardWorkspaceTest {
    @Test fun `ホーム入口はDashboardを単一領域で再利用し閉じると購読と監視を解放する`() = withWorkspace { fixture ->
        fx {
            val baseline = listenerCount(EditorSession.serverMonitor)
            val hostBaseline = listenerCount(EditorSession.hostMetricsMonitor)
            val button = fixture.home.lookup("#dashboardButton") as Button
            assertEquals("ダッシュボード", button.text)
            val crop = fixture.home.lookupAll(".data-btn-unavailable").filterIsInstance<Button>().single()
            assertTrue(crop.isDisabled)
            assertTrue(crop.text.contains("未実装"))
            button.fire()
            val first = fixture.workspace.lookup("#tpsValue")
            val task = field(EditorSession.hostMetricsMonitor, "task")
            button.fire()
            assertEquals(1, fixture.workspace.titles().count { it == WorkspaceFeature.DASHBOARD })
            assertSame(task, field(EditorSession.hostMetricsMonitor, "task"))
            assertEquals(baseline + 1, listenerCount(EditorSession.serverMonitor))
            assertEquals(hostBaseline + 1, listenerCount(EditorSession.hostMetricsMonitor))
            assertTrue(fixture.workspace.move(WorkspaceFeature.DASHBOARD, WorkspaceFeature.HOME, DockSide.BOTTOM))
            assertSame(first, fixture.workspace.lookup("#tpsValue"))
            DashboardWindowManager.close()
            assertFalse(EditorSession.hostMetricsMonitor.isRunning)
            assertEquals(baseline, listenerCount(EditorSession.serverMonitor))
            assertEquals(hostBaseline, listenerCount(EditorSession.hostMetricsMonitor))
            button.fire()
            assertEquals(baseline + 1, listenerCount(EditorSession.serverMonitor))
            DashboardWindowManager.close()
        }
    }

    @Test fun `API未接続とSSH未接続を分離し未取得と実0および既Thread値を表示する`() {
        startFx()
        val manager = EditorSession.sshManager
        val fake = Mockito.mock(SshManager::class.java)
        Mockito.`when`(fake.isConnected).thenReturn(true)
        try {
            fx {
                EditorSession.sshManager = fake
                val loader = FXMLLoader(javaClass.getResource(AppScreen.DASHBOARD.fxml!!))
                val root = loader.load<ScrollPane>()
                val controller = loader.getController<DashboardController>()
                try {
                    state(controller, ServerManagementState.Failed("fixture API failure"))
                    assertEquals("接続済み", (root.content.lookup("#sshConnectionValue") as Label).text)
                    assertEquals("未確認（API未接続）", (root.content.lookup("#serverStateValue") as Label).text)
                    assertEquals("未取得", (root.content.lookup("#threadsValue") as Label).text)
                    Mockito.`when`(fake.isConnected).thenReturn(false)
                    state(controller, ServerManagementState.Connected(0))
                    assertEquals("未接続", (root.content.lookup("#sshConnectionValue") as Label).text)
                    assertEquals("稼働中（API確認）", (root.content.lookup("#serverStateValue") as Label).text)
                    val snapshot = ServerMonitorSnapshot(
                        server = null,
                        jvm = JvmStatus(
                            0, 0, 0, 0, 0, 0, 0, 0))
                    DashboardController::class.java.getDeclaredMethod("applyServerSnapshot", ServerMonitorSnapshot::class.java)
                        .apply { isAccessible = true }.invoke(controller, snapshot)
                    assertEquals("0", (root.content.lookup("#threadsValue") as Label).text)
                    assertEquals("0 B / 0 B", (root.content.lookup("#heapValue") as Label).text)
                    assertEquals("未取得", (root.content.lookup("#tpsValue") as Label).text)
                } finally { controller.dispose() }
            }
        } finally { fx { EditorSession.sshManager = manager } }
    }

    @Test fun `閉じる前にqueueされた更新は再openのHomeと終了済Dashboardを書き換えない`() = withWorkspace { fixture ->
        lateinit var dashboard: ScrollPane
        lateinit var currentSummary: String
        lateinit var controller: DashboardController
        var statesBefore = 0
        var snapshotsBefore = 0
        fx {
            val loader = FXMLLoader(javaClass.getResource(AppScreen.DASHBOARD.fxml!!))
            dashboard = loader.load()
            controller = loader.getController()
            @Suppress("UNCHECKED_CAST")
            val homeListener = field(fixture.controller, "workspaceStateListener") as (ServerManagementState) -> Unit
            @Suppress("UNCHECKED_CAST")
            val homeMonitorListener = field(fixture.controller, "workspaceMonitorListener") as (ServerMonitorSnapshot) -> Unit
            val failure = ServerManagementState.Failed("old fixture result")
            statesBefore = (field(EditorSession.managementClient, "stateListeners") as Collection<*>).size
            snapshotsBefore = listenerCount(EditorSession.serverMonitor)
            fixture.controller.setWorkspaceVisible(false)
            assertEquals(statesBefore - 1, (field(EditorSession.managementClient, "stateListeners") as Collection<*>).size)
            assertEquals(snapshotsBefore - 1, listenerCount(EditorSession.serverMonitor))
            fixture.controller.setWorkspaceVisible(true)
            assertEquals(statesBefore, (field(EditorSession.managementClient, "stateListeners") as Collection<*>).size)
            assertEquals(snapshotsBefore, listenerCount(EditorSession.serverMonitor))
            currentSummary = (fixture.home.lookup("#monitorLabel") as Label).text
            val late = Thread {
                homeListener(failure)
                homeMonitorListener(ServerMonitorSnapshot(
                    server = MinecraftServerStatus(3.0, 1.0, 1, 1, 99, 100), jvm = null))
            }.apply { start() }
            late.join(5000)
            assertFalse(late.isAlive)
        }
        fx {
            assertFalse((fixture.home.lookup("#managementLabel") as Label).text.contains("old fixture result"))
            assertEquals(currentSummary, (fixture.home.lookup("#monitorLabel") as Label).text)
        }
        fx {
            val failure = ServerManagementState.Failed("old fixture result")
            @Suppress("UNCHECKED_CAST")
            val currentListener = field(fixture.controller, "workspaceStateListener") as (ServerManagementState) -> Unit
            @Suppress("UNCHECKED_CAST")
            val dashboardListener = field(controller, "stateListener") as (ServerManagementState) -> Unit
            val queued = Thread { currentListener(failure); dashboardListener(failure) }.apply { start() }
            queued.join(5000)
            assertFalse(queued.isAlive)
            fixture.controller.setWorkspaceVisible(false)
            fixture.controller.setWorkspaceVisible(true)
            assertEquals(statesBefore, (field(EditorSession.managementClient, "stateListeners") as Collection<*>).size)
            assertEquals(snapshotsBefore, listenerCount(EditorSession.serverMonitor))
            controller.dispose()
        }
        fx {
            assertFalse((fixture.home.lookup("#managementLabel") as Label).text.contains("old fixture result"))
            assertEquals(currentSummary, (fixture.home.lookup("#monitorLabel") as Label).text)
            assertFalse((dashboard.content.lookup("#managementConnectionValue") as Label).text.contains("old fixture result"))
        }
    }

    private data class Fixture(val stage: Stage, val home: Parent, val controller: HomeController, val workspace: WorkspaceView)

    private fun withWorkspace(action: (Fixture) -> Unit) {
        startFx()
        val fixture = fx {
            EditorSession.prepareOnlineMode()
            val loader = FXMLLoader(javaClass.getResource(AppScreen.HOME.fxml!!))
            val home = loader.load<Parent>()
            val controller = loader.getController<HomeController>()
            val frame = AppFrame(AppScreen.HOME, home)
            val workspace = WorkspaceView(frame, home, null, {}, { true }, controller::setWorkspaceVisible)
            frame.center = workspace
            val stage = Stage().apply { scene = Scene(frame, 1400.0, 800.0); ThemeManager.apply(scene); show() }
            Fixture(stage, home, controller, workspace)
        }
        try { action(fixture) } finally { fx { fixture.stage.close(); fixture.controller.setWorkspaceVisible(false); EditorSession.resetMode() } }
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    private fun listenerCount(target: Any) = (field(target, "listeners") as Collection<*>).size
    private fun state(controller: DashboardController, state: ServerManagementState) {
        DashboardController::class.java.getDeclaredMethod("applyConnectionState", ServerManagementState::class.java)
            .apply { isAccessible = true }.invoke(controller, state)
    }
    private fun startFx() {
        val isolated = System.getProperty("sushieric.test.configRoot") ?: error("隔離したGradle testを使用してください")
        assertTrue(Path.of(OS.dataConfigBase).toAbsolutePath().normalize().startsWith(Path.of(isolated).toAbsolutePath().normalize()))
        val ready = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
        catch (_: IllegalStateException) { Platform.runLater { Platform.setImplicitExit(false); ready.countDown() } }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
    }
    private fun <T> fx(action: () -> T): T {
        val task = FutureTask(action); Platform.runLater(task); return task.get(10, TimeUnit.SECONDS)
    }
}
