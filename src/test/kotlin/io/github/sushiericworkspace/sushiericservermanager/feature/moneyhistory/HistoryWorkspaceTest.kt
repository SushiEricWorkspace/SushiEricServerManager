package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceManager
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceView
import io.github.sushiericworkspace.sushiericservermanager.app.workspaceStage
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementClient
import io.github.sushiericworkspace.sushiericservermanager.config.DockSide
import io.github.sushiericworkspace.sushiericservermanager.config.OS
import io.github.sushiericworkspace.sushiericservermanager.config.WorkspaceFeature
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.Button
import javafx.scene.control.ButtonBase
import javafx.scene.control.ComboBox
import javafx.scene.control.CheckBox
import javafx.scene.control.CustomMenuItem
import javafx.scene.control.ScrollBar
import javafx.scene.control.DatePicker
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.TableRow
import javafx.scene.control.TableView
import javafx.scene.control.TabPane
import javafx.scene.control.TextField
import javafx.scene.layout.BorderPane
import javafx.scene.layout.FlowPane
import javafx.stage.Stage
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
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

class HistoryWorkspaceTest {
    @Test fun 三履歴の全条件と状態を狭い単一領域で折り返して表示する() = withHistory { f ->
        fx {
            f.stage.width = 620.0
            f.workspace.openHome()
            assertTrue(f.workspace.move(WorkspaceFeature.MONEY_HISTORY, WorkspaceFeature.HOME, DockSide.RIGHT))
        }
        awaitFx { f.stage.scene.width >= 590.0 }
        for (index in 0..2) {
            fx { f.tabs.selectionModel.select(index) }
            awaitFx { table(f.content()).items.isNotEmpty() }
            fx {
                f.stage.scene.root.applyCss(); f.stage.scene.root.layout()
                val content = f.content()
                val display = content.localToScene(content.layoutBounds)
                assertTrue(display.width in 180.0..330.0, "340px未満の実本文幅で検証します")
                val panes = descendants(content).filterIsInstance<FlowPane>()
                assertTrue(panes.size >= 4)
                for (pane in panes) {
                    assertTrue(pane.width > 0)
                    val row = pane.localToScene(pane.layoutBounds)
                    assertTrue(row.minX >= display.minX - 1 && row.maxX <= display.maxX + 1,
                        "条件行自体が本文領域を超えています")
                    val controls = descendants(pane).filter {
                        it.parent === pane || it is ButtonBase || it is ComboBox<*> || it is TextField || it is DatePicker
                    }
                    for (child in controls.filter(Node::isManaged)) {
                        val bounds = child.localToScene(child.layoutBounds)
                        assertTrue(bounds.minX >= display.minX - 1 && bounds.maxX <= display.maxX + 1,
                            "入力欄が本文領域を超えています")
                    }
                }
                val labels = descendants(f.content()).filterIsInstance<Label>()
                    .filter { it.text.contains("件") || it.id == "nameStatusLabel" }
                assertTrue(labels.isNotEmpty()); assertTrue(labels.all(Label::isWrapText))
                assertEquals(listOf(7, 4, 7)[index], table(f.content()).columns.size)
                val buttons = descendants(f.content()).filterIsInstance<Button>().map { it.text }
                assertTrue(buttons.containsAll(listOf("時刻を選択", "現在時刻", "今日", "リセット")))
            }
        }
    }

    @Test fun 領域移動と履歴切替で秒境界と条件と同じ表示Nodeを保持する() = withHistory { f ->
        awaitFx { table(f.content()).items.isNotEmpty() }
        fx {
            val content = f.content()
            val from = content.lookup("#fromDate") as DatePicker
            val time = content.lookup("#fromTime") as TextField
            val local = AT.atZone(ZoneId.systemDefault())
            from.value = local.toLocalDate()
            time.text = local.toLocalTime().withNano(0).toString()
            assertEquals(AT, (table(content).items.single() as MoneyHistoryRow).timestamp)
            val menu = content.lookup("#typeMenu") as MenuButton
            val filter = (menu.items.first() as CustomMenuItem).content as CheckBox
            filter.fire()
            val original = menu.text
            assertFalse(filter.isSelected)
            assertTrue(table(content).items.isEmpty())
            f.workspace.openHome()
            assertTrue(f.workspace.move(WorkspaceFeature.MONEY_HISTORY, WorkspaceFeature.HOME, DockSide.BOTTOM))
            f.tabs.selectionModel.select(1); f.tabs.selectionModel.select(0)
            assertSame(content, f.content())
            assertEquals(local.toLocalDate(), from.value)
            assertEquals(local.toLocalTime().withNano(0).toString(), time.text)
            assertEquals(original, menu.text)
            assertFalse(filter.isSelected)
            assertTrue(table(content).items.isEmpty())
            assertEquals(1, f.workspace.titles().count { it == WorkspaceFeature.MONEY_HISTORY })
        }
    }

    @Test fun 大量ショップ履歴は表の仮想化と末尾への通常スクロールを維持する() = withHistory(10_000) { f ->
        fx { f.tabs.selectionModel.select(2) }
        awaitFx { table(f.content()).items.size == 10_000 }
        fx { f.stage.scene.root.applyCss(); f.stage.scene.root.layout() }
        awaitFx {
            val table = table(f.content())
            table.height > 0 && descendants(table).filterIsInstance<TableRow<*>>().any { !it.isEmpty }
        }
        fx { table(f.content()).scrollTo(9_999) }
        awaitFx { descendants(table(f.content())).filterIsInstance<TableRow<*>>().any { !it.isEmpty && it.index == 9_999 } }
        fx {
            val table = table(f.content())
            assertTrue(descendants(table).filterIsInstance<TableRow<*>>().size < 200)
            assertEquals(10_000, table.items.size); assertNotNull(table.lookup(".virtual-flow"))
            val bars = table.lookupAll(".scroll-bar").filterIsInstance<ScrollBar>()
            assertTrue(bars.isNotEmpty())
            assertTrue(bars.all { it.opacity == 0.0 })
        }
    }

    @Test fun 取得待ちで領域を閉じた後のショップ完了を旧表示へ戻さない() {
        val started = CountDownLatch(1); val released = CountDownLatch(1); val completed = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        try {
            withHistory(decorate = { memory ->
                object : EditorDataStore by memory {
                    override fun readText(relativePath: String): StoreResult<String> {
                        if (!relativePath.startsWith("shop_history/")) return memory.readText(relativePath)
                        worker.set(Thread.currentThread()); started.countDown()
                        assertTrue(released.await(10, TimeUnit.SECONDS))
                        return memory.readText(relativePath).also { completed.countDown() }
                    }
                }
            }) { f ->
                fx { f.tabs.selectionModel.select(2) }; assertTrue(started.await(10, TimeUnit.SECONDS))
                val oldTable = fx { table(f.content()) }
                fx {
                    assertTrue(f.workspace.close(WorkspaceFeature.MONEY_HISTORY))
                    assertFalse(f.workspace.titles().contains(WorkspaceFeature.MONEY_HISTORY))
                }
                released.countDown(); assertTrue(completed.await(10, TimeUnit.SECONDS))
                worker.get().join(10_000); assertFalse(worker.get().isAlive)
                fx { assertTrue(oldTable.items.isEmpty()) }
            }
        } finally { released.countDown(); worker.get()?.join(10_000) }
    }

    private data class Fixture(val stage: Stage, val root: BorderPane, val workspace: WorkspaceView) {
        val tabs: TabPane get() = root.center as TabPane
        fun content(): Parent = tabs.selectionModel.selectedItem.content as Parent
    }
    private fun withHistory(count: Int = 1, decorate: (EditorDataStore) -> EditorDataStore = { it },
                            action: (Fixture) -> Unit) {
        startFx()
        val memory = InMemoryEditorDataStore()
        memory.writeText("usercache.json", """[{"uuid":"$PLAYER","name":"履歴プレイヤー"}]""")
        memory.writeText("player_data/$PLAYER/money_history/day.yml", """
            entries:
              - timestamp: '$AT'
                player-uuid: $PLAYER
                type: PAY_SENT
                amount: 1
                balance-after: 100
        """.trimIndent())
        memory.writeText("player_data/$PLAYER/achievements.yml", """
            achievements:
              monster_hunter_1:
                at: 2026-10-13T00:00:00Z
                source: natural
        """.trimIndent())
        memory.writeText("shop_history/2026-10-13.yml", buildString {
            appendLine("entries:")
            repeat(count) { index ->
                appendLine("  - timestamp: '$AT'"); appendLine("    product-id: fixture.item$index")
                appendLine("    mode: BUY"); appendLine("    player-uuid: $PLAYER")
                appendLine("    player-name: 履歴プレイヤー"); appendLine("    count: 1")
                appendLine("    unit-price: 10"); appendLine("    total: 10")
            }
        })
        val client = ServerManagementClient()
        var controller: MoneyHistoryController? = null; var stage: Stage? = null
        try {
            val fixture = fx {
                val loader = FXMLLoader(javaClass.getResource("/fxml/moneyhistory/money-history.fxml"))
                val root = loader.load<BorderPane>()
                val current = loader.getController<MoneyHistoryController>(); controller = current
                current.playerNameLookup = PlayerNameLookup { error("外部プロフィールを取得しません") }
                current.initialize(decorate(memory), client)
                val window = workspaceStage(width = 500.0); stage = window
                val workspace = WorkspaceManager.requireWorkspace()
                workspace.show(WorkspaceFeature.MONEY_HISTORY, root, dispose = current::dispose)
                assertTrue(workspace.close(WorkspaceFeature.HOME))
                Fixture(window, root, workspace)
            }
            action(fixture)
        } finally { fx { controller?.dispose(); stage?.close() }; client.disconnect() }
    }
    private fun descendants(node: Node): List<Node> =
        listOf(node) + if (node is Parent) node.childrenUnmodifiable.flatMap(::descendants) else emptyList()
    private fun table(root: Parent): TableView<*> = descendants(root).filterIsInstance<TableView<*>>().single()
    private fun startFx() {
        val isolated = System.getProperty("sushieric.test.configRoot") ?: error("隔離したGradle testを使用してください")
        assertTrue(Path.of(OS.dataConfigBase).toAbsolutePath().normalize().startsWith(Path.of(isolated).toAbsolutePath().normalize()))
        val ready = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
        catch (_: IllegalStateException) { Platform.runLater { Platform.setImplicitExit(false); ready.countDown() } }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
    }
    private fun <T> fx(action: () -> T): T =
        FutureTask(action).also(Platform::runLater).get(10, TimeUnit.SECONDS)
    private fun awaitFx(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!fx(condition)) { assertTrue(System.nanoTime() < deadline, "履歴画面の更新が完了しません"); Thread.sleep(20) }
    }
    private companion object {
        const val PLAYER = "11111111-1111-1111-1111-111111111111"
        val AT: Instant = Instant.parse("2026-10-13T00:00:00.123456789Z")
    }
}
