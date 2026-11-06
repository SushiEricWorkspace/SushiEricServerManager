package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import javafx.scene.control.Button
import javafx.scene.control.ToggleButton
import javafx.scene.control.TextField
import io.github.sushiericworkspace.sushiericdatamodel.database.TransactionKind
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
    fun `DB接続中はYAMLを読まず再読込で新しい検索を開き切断で行を消す`() {
        startFx()
        val fake = HistoryManagementFixture()
        val reads = CopyOnWriteArrayList<String>()
        val memory = InMemoryEditorDataStore()
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
            controller.initialize(store, fake.client)
            Stage().apply { scene = Scene(root, 1100.0, 640.0); show() }
        }
        try {
            awaitFx { table(stage).items.size == 2 }
            assertTrue(reads.isEmpty())
            val ends = fake.ends
            onFx { controller.reloadSelected() }
            awaitFx { table(stage).items.size == 2 && fake.ends > ends }
            assertTrue(reads.isEmpty())
            fake.listener.onClosed(1006, "検証切断")
            awaitFx { table(stage).items.isEmpty() }
        } finally {
            onFx { controller.dispose(); stage.close() }
            fake.client.disconnect()
        }
    }

    @Test
    fun `ショップDB履歴を商品部分一致とUUID表示で確認し再読込で新しい集合を取得する`() {
        startFx()
        val starts = java.util.concurrent.atomic.AtomicInteger()
        val memory = playerStore()
        val rows = listOf("tab.Ä商品", "tab.other").mapIndexed { index, product ->
            historyRecord(index + 1L).copy(kind = TransactionKind.SHOP_BUY,
                trade = HostShopTrade(FIRST, product, 2, 10))
        }
        val source = HostHistorySource(java.util.UUID.randomUUID().toString()) { request ->
            assertFalse(Platform.isFxApplicationThread())
            val result = when (val action = request.action) {
                HostAdapterAction.HistoryStart -> {
                    starts.incrementAndGet()
                    HostAdapterResult.Snapshot(
                        HostHistorySnapshot(2, emptyList()))
                }
                is HostAdapterAction.HistoryPage ->
                    HostAdapterResult.History(if (action.offset == 0) rows else emptyList())
                else -> error("履歴検索以外の要求です。")
            }
            HostAdapterResponse(requestId = request.requestId,
                status = HostAdapterStatus.SUCCESS, result = result)
        }
        val store = object : EditorDataStore by memory {
            override val historySource = source
            override fun readText(relativePath: String): StoreResult<String> = error("DB履歴でYAMLを取得しました。")
        }
        lateinit var view: ShopHistoryView
        var originalUuidDisplay = false
        val stage = onFx {
            view = ShopHistoryView(store, ServerManagementClient())
            Stage().apply { scene = Scene(view, 1100.0, 640.0); show() }.also {
                originalUuidDisplay = (view.lookup(".toggle-button") as ToggleButton).isSelected
            }
        }
        fun shopTable() = view.lookup(".table-view") as TableView<*>
        try {
            awaitFx { shopTable().items.size == 2 }
            onFx {
                view.lookupAll(".text-field").filterIsInstance<TextField>()
                    .single { it.promptText == "商品IDで絞り込み" }.text = "ä商品"
            }
            awaitFx { shopTable().items.size == 1 }
            onFx {
                val toggle = view.lookup(".toggle-button") as ToggleButton
                if (!toggle.isSelected) toggle.fire()
                val table = shopTable()
                assertEquals(FIRST, table.columns[3].getCellObservableValue(0).value)
                view.lookupAll(".button").filterIsInstance<Button>().single { it.text == "更新" }.fire()
            }
            awaitFx { shopTable().items.size == 1 && starts.get() == 2 }
        } finally { onFx {
            val toggle = view.lookup(".toggle-button") as ToggleButton
            if (toggle.isSelected != originalUuidDisplay) toggle.fire()
            view.dispose(); stage.close()
        } }
    }

    @Test
    fun `同じ取得元名でも再接続前の履歴応答を画面へ反映しない`() {
        startFx()
        val memory = playerStore()
        val connection = AtomicReference<Any>(Any())
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val store = object : EditorDataStore by memory {
            override val connectionIdentity: Any get() = connection.get()
            override fun readText(relativePath: String): StoreResult<String> {
                if (relativePath.contains("money_history")) {
                    worker.set(Thread.currentThread()); started.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS))
                }
                return memory.readText(relativePath)
            }
        }
        lateinit var controller: MoneyHistoryController
        val stage = onFx {
            val loader = FXMLLoader(javaClass.getResource("/fxml/moneyhistory/money-history.fxml"))
            val root = loader.load<Parent>()
            controller = loader.getController()
            controller.playerNameLookup = PlayerNameLookup { null }
            controller.initialize(store, ServerManagementClient())
            Stage().apply { scene = Scene(root, 1100.0, 640.0); show() }
        }
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            connection.set(Any()); release.countDown()
            worker.get().join(10_000)
            assertFalse(worker.get().isAlive)
            awaitFx { (content(stage).lookup("#statusLabel") as Label).text.contains("取得元") }
            onFx { assertTrue(table(stage).items.isEmpty()) }
        } finally {
            release.countDown()
            onFx { controller.dispose(); stage.close() }
        }
    }

    @Test fun `SSH sourceの入出金検索と表示はManagement再試行で中断せずSSH世代変更で消す`() {
        verifyIndependentSource(false)
    }

    @Test fun `SSH sourceのショップ検索と表示はManagement再試行で中断せずSSH世代変更で消す`() {
        verifyIndependentSource(true)
    }

    private fun verifyIndependentSource(shop: Boolean) {
        startFx()
        val client = ServerManagementClient()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val connection = AtomicReference<Any>(Any())
        val memory = InMemoryEditorDataStore()
        val source = HostHistorySource(java.util.UUID.randomUUID().toString()) { request ->
            val result = when (val action = request.action) {
                HostAdapterAction.HistoryStart -> HostAdapterResult.Snapshot(HostHistorySnapshot(1, emptyList()))
                is HostAdapterAction.HistoryPage -> {
                    entered.countDown()
                    assertTrue(release.await(10, TimeUnit.SECONDS))
                    val row = if (shop) historyRecord().copy(kind = TransactionKind.SHOP_BUY,
                        trade = HostShopTrade(FIRST, "tab.apple", 2, 10)) else historyRecord()
                    HostAdapterResult.History(if (action.offset == 0) listOf(row) else emptyList())
                }
                else -> error("履歴以外の要求です。")
            }
            HostAdapterResponse(requestId = request.requestId, status = HostAdapterStatus.SUCCESS, result = result)
        }
        val store = object : EditorDataStore by memory {
            override val kind = io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStoreKind.REMOTE
            override val connectionIdentity: Any get() = connection.get()
            override val historySource = source
        }
        val failing = object : ManagementTransport {
            override fun connect(listener: ServerManagementListener, timeout: Duration): Int = error("Management停止中")
            override fun disconnect() = Unit
            override fun send(request: ServerManagementRequest) = false
        }
        var controller: MoneyHistoryController? = null
        var view: ShopHistoryView? = null
        val stage = onFx {
            val root = if (shop) ShopHistoryView(store, client).also { view = it } else {
                val loader = FXMLLoader(javaClass.getResource("/fxml/moneyhistory/money-history.fxml"))
                loader.load<Parent>().also {
                    controller = loader.getController<MoneyHistoryController>().apply {
                        playerNameLookup = PlayerNameLookup { null }
                        initialize(store, client)
                    }
                }
            }
            Stage().apply { scene = Scene(root, 1100.0, 640.0); show() }
        }
        fun rows() = if (shop) (view!!.lookup(".table-view") as TableView<*>).items else table(stage).items
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertFalse(client.connect(failing))
            onFx { Unit }
            release.countDown()
            awaitFx { rows().isNotEmpty() }
            assertFalse(client.connect(failing))
            onFx { assertEquals(1, rows().size) }
            connection.set(Any())
            assertFalse(client.connect(failing))
            awaitFx { rows().isEmpty() }
        } finally {
            release.countDown()
            onFx { controller?.dispose(); view?.dispose(); stage.close() }
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
