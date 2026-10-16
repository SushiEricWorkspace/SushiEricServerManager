package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementClient
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ManagementTransport
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementListener
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementRequest
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementResponse
import java.time.Duration
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TableView
import javafx.scene.layout.VBox
import javafx.scene.layout.BorderPane
import javafx.scene.control.TabPane
import javafx.stage.Stage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryLoadingViewTest {
    @Test
    fun `名前取得と最初の履歴が遅れても選択でき古い履歴を反映しない`() {
        startFx()
        val firstThread = AtomicReference<Thread>()
        val firstStarted = CountDownLatch(1)
        val firstRelease = CountDownLatch(1)
        val nameRelease = CountDownLatch(1)
        val historyReads = CopyOnWriteArrayList<String>()
        val memory = playerStore()
        val store = object : EditorDataStore by memory {
            override fun readText(relativePath: String): StoreResult<String> {
                if (relativePath.contains("money_history")) {
                    historyReads += relativePath
                    if (relativePath.contains(FIRST)) {
                        firstThread.set(Thread.currentThread())
                        firstStarted.countDown()
                        assertTrue(firstRelease.await(10, TimeUnit.SECONDS))
                    }
                }
                return memory.readText(relativePath)
            }
        }
        val lookup = PlayerNameLookup { uuid ->
            assertTrue(nameRelease.await(10, TimeUnit.SECONDS))
            val name = if (uuid.startsWith("1")) "ZFirst" else "ASecond"
            """{"id":"$uuid","name":"$name"}"""
        }
        lateinit var controller: MoneyHistoryController
        val stage = onFx {
            val loader = FXMLLoader(javaClass.getResource("/fxml/moneyhistory/money-history.fxml"))
            val root = loader.load<Parent>()
            controller = loader.getController()
            controller.playerNameLookup = lookup
            controller.initialize(store, ServerManagementClient())
            Stage().apply { scene = Scene(root, 1100.0, 640.0); show() }
        }
        try {
            assertTrue(firstStarted.await(10, TimeUnit.SECONDS))
            awaitFx { selector(stage).items.size == 3 }
            assertEquals(1, historyReads.size)
            onFx { selector(stage).selectionModel.select(1) }
            awaitFx { table(stage).items.isNotEmpty() }
            onFx {
                assertEquals(SECOND, (selector(stage).value as HistoryPlayer).uuid)
                assertEquals(2L, (table(stage).items.single() as MoneyHistoryRow).amount)
                assertEquals(FIRST, (table(stage).items.single() as MoneyHistoryRow).counterparty)
            }
            nameRelease.countDown()
            awaitFx { (selector(stage).value as HistoryPlayer).displayName == "ASecond" }
            onFx {
                assertEquals(SECOND, (selector(stage).value as HistoryPlayer).uuid)
                assertEquals(2, historyReads.size)
                assertEquals("ZFirst ($FIRST)", (table(stage).items.single() as MoneyHistoryRow).counterparty)
            }
            firstRelease.countDown()
            firstThread.get().join(10_000)
            assertFalse(firstThread.get().isAlive)
            awaitFx { (content(stage).lookup("#nameStatusLabel") as Label).text.isEmpty() }
            onFx {
                assertEquals(2L, (table(stage).items.single() as MoneyHistoryRow).amount)
                controller.reloadSelected()
            }
            awaitFx { historyReads.size == 3 && table(stage).items.isNotEmpty() }
        } finally {
            firstRelease.countDown()
            nameRelease.countDown()
            onFx { controller.dispose(); stage.close() }
        }
    }

    @Test
    fun `実績の名前取得失敗でも選択した履歴だけを読み込み終了後の応答を反映しない`() {
        startFx()
        val nameRelease = CountDownLatch(1)
        val historyRelease = CountDownLatch(1)
        val historyThread = AtomicReference<Thread>()
        val historyStarted = CountDownLatch(1)
        val reads = CopyOnWriteArrayList<String>()
        val memory = playerStore()
        val store = object : EditorDataStore by memory {
            override fun readText(relativePath: String): StoreResult<String> {
                if (relativePath.endsWith("achievements.yml")) {
                    reads += relativePath
                    if (relativePath.contains(SECOND)) {
                        historyThread.set(Thread.currentThread())
                        historyStarted.countDown()
                        assertTrue(historyRelease.await(10, TimeUnit.SECONDS))
                    }
                }
                return memory.readText(relativePath)
            }
        }
        lateinit var controller: AchievementHistoryController
        val stage = onFx {
            val loader = FXMLLoader(javaClass.getResource("/fxml/moneyhistory/achievement-history.fxml"))
            val root = loader.load<Parent>()
            controller = loader.getController()
            controller.playerNameLookup = PlayerNameLookup {
                assertTrue(nameRelease.await(10, TimeUnit.SECONDS))
                throw IllegalStateException("検証用通信失敗")
            }
            controller.initialize(store, ServerManagementClient())
            Stage().apply { scene = Scene(root, 1100.0, 640.0); show() }
        }
        try {
            awaitFx { table(stage).items.size == 1 }
            assertEquals(1, reads.size)
            onFx { selector(stage).selectionModel.select(1) }
            assertTrue(historyStarted.await(10, TimeUnit.SECONDS))
            nameRelease.countDown()
            awaitFx { (content(stage).lookup("#nameStatusLabel") as Label).text.isEmpty() }
            onFx {
                assertEquals(SECOND, (selector(stage).value as HistoryPlayer).uuid)
                assertEquals(SECOND, (selector(stage).value as HistoryPlayer).displayName)
                assertTrue(table(stage).items.isEmpty())
                controller.dispose()
            }
            historyRelease.countDown()
            historyThread.get().join(10_000)
            assertFalse(historyThread.get().isAlive)
            onFx { assertTrue(table(stage).items.isEmpty()) }
            assertEquals(2, reads.size)
        } finally {
            nameRelease.countDown()
            historyRelease.countDown()
            onFx { controller.dispose(); stage.close() }
        }
    }

    @Test
    fun `選択したプレイヤーのサーバー保存を待って読み込み再読み込みも保存要求する`() {
        startFx()
        val requests = CopyOnWriteArrayList<ServerManagementRequest.MoneyHistorySave>()
        lateinit var listener: ServerManagementListener
        val client = ServerManagementClient()
        assertTrue(client.connect(object : ManagementTransport {
            override fun connect(receiver: ServerManagementListener, timeout: Duration): Int {
                listener = receiver
                return 12345
            }
            override fun send(request: ServerManagementRequest): Boolean {
                requests += request as ServerManagementRequest.MoneyHistorySave
                return true
            }
            override fun disconnect() = Unit
        }))
        val reads = CopyOnWriteArrayList<String>()
        val memory = playerStore()
        val store = object : EditorDataStore by memory {
            override fun readText(relativePath: String): StoreResult<String> {
                if (relativePath.contains("money_history")) reads += relativePath
                return memory.readText(relativePath)
            }
        }
        lateinit var controller: MoneyHistoryController
        val stage = onFx {
            val loader = FXMLLoader(javaClass.getResource("/fxml/moneyhistory/money-history.fxml"))
            val root = loader.load<Parent>()
            controller = loader.getController()
            controller.playerNameLookup = PlayerNameLookup { null }
            controller.initialize(store, client)
            Stage().apply { scene = Scene(root, 1100.0, 640.0); show() }
        }
        try {
            awaitFx { requests.size == 1 }
            assertTrue(reads.isEmpty())
            onFx {
                assertTrue((content(stage).lookup("#statusLabel") as Label).text.contains("サーバー保存"))
                selector(stage).selectionModel.select(1)
            }
            awaitFx { requests.size == 2 }
            listener.onMessage(ServerManagementResponse.MoneyHistorySaveResult(true, nonce = requests[1].nonce))
            awaitFx { table(stage).items.isNotEmpty() }
            assertEquals(listOf("player_data/$SECOND/money_history/day.yml"), reads.toList())
            listener.onMessage(ServerManagementResponse.MoneyHistorySaveResult(false, detail = "古い選択の失敗", nonce = requests[0].nonce))
            onFx { controller.reloadSelected() }
            awaitFx { requests.size == 3 }
            assertEquals(SECOND, requests.last().playerUuid)
            listener.onMessage(ServerManagementResponse.MoneyHistorySaveResult(true, nonce = requests.last().nonce))
            awaitFx { table(stage).items.isNotEmpty() && reads.size == 2 }
            onFx {
                assertEquals(SECOND, (selector(stage).value as HistoryPlayer).uuid)
                assertEquals(2L, (table(stage).items.single() as MoneyHistoryRow).amount)
                assertTrue((content(stage).lookup("#statusLabel") as Label).text.contains("件"))
            }
        } finally {
            requests.forEach { listener.onMessage(ServerManagementResponse.MoneyHistorySaveResult(false, nonce = it.nonce)) }
            onFx { controller.dispose(); stage.close() }
            client.disconnect()
        }
    }

    private fun playerStore(): InMemoryEditorDataStore = InMemoryEditorDataStore().apply {
        listOf(FIRST, SECOND, THIRD).forEachIndexed { index, uuid ->
            writeText("player_data/$uuid/money_history/day.yml", """
                entries:
                  - timestamp: '2026-10-13T00:00:00Z'
                    player-uuid: $uuid
                    type: PAY_SENT
                    amount: ${index + 1}
                    balance-after: 100
                    other-player: $FIRST
            """.trimIndent())
            writeText("player_data/$uuid/achievements.yml", """
                achievements:
                  monster_hunter_1:
                    at: 2026-10-13T00:00:00Z
                    source: natural
            """.trimIndent())
        }
    }

    private fun content(stage: Stage): Parent = stage.scene.root.let { root ->
        if (root is BorderPane) (root.center as TabPane).tabs.first().content as Parent else root
    }

    private fun selector(stage: Stage): ComboBox<*> =
        (content(stage).lookup("#playerSelectorPane") as VBox).children.single()
            .let { it as VBox }.children.filterIsInstance<ComboBox<*>>().single()

    private fun table(stage: Stage): TableView<*> = content(stage).lookup("#historyTable") as TableView<*>

    private fun startFx() {
        val started = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); started.countDown() } }
        catch (_: IllegalStateException) { started.countDown() }
        assertTrue(started.await(20, TimeUnit.SECONDS))
    }

    private fun <T> onFx(block: () -> T): T {
        val task = FutureTask(block)
        Platform.runLater(task)
        return task.get(10, TimeUnit.SECONDS)
    }

    private fun awaitFx(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!onFx(condition)) {
            assertTrue(System.nanoTime() < deadline, "画面の更新を待機しましたが完了しませんでした。")
            Thread.sleep(20)
        }
    }

    private companion object {
        const val FIRST = "11111111-1111-1111-1111-111111111111"
        const val SECOND = "22222222-2222-2222-2222-222222222222"
        const val THIRD = "33333333-3333-3333-3333-333333333333"
    }
}
