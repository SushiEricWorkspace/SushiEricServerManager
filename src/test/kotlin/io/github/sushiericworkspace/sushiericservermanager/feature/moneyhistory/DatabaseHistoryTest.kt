package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericdatamodel.database.*
import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericdatamodel.database.run.WorldRunLifecycle
import io.github.sushiericworkspace.sushiericservermanager.communication.management.*
import io.github.sushiericworkspace.sushiericservermanager.communication.management.codec.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

internal fun historyRecord(id: Long = 1, uuid: String = "11111111-1111-1111-1111-111111111111") =
    HostHistoryRecord(id, UUID.randomUUID().toString(), 100, 123456789, TransactionKind.MONEY,
        listOf(HostHistoryPlayer(uuid, "Ä日本語")),
        listOf(HostMoneyEntry(uuid, MoneyEntryType.PAY_SENT, -4, true, 20)), null, null,
        null, "Console", "理由", true)

class DatabaseHistoryTest {
    @TempDir lateinit var root: Path

    @Test fun `実DBのページ間で追加と遅延確定があっても初回集合だけを返し更新で切り替わる`() {
        root = root.toRealPath()
        val world = Files.createDirectory(root.resolve("world"))
        val shop = Files.createDirectory(root.resolve("shop_data"))
        val worldId = UUID.randomUUID()
        val player = DatabasePlayer(UUID.randomUUID(), "Ä日本語")
        val lifecycle = WorldRunLifecycle.adoptInitial(world, shop, worldId, "a".repeat(64))
        fun <T> edit(create: Boolean = false, block: (SushiEricDatabase) -> T): T {
            val writer = lifecycle.openOfflineWriter(lifecycle.readStatus())
            val id = UUID.randomUUID()
            val operation = writer.beginOperation(id, "b".repeat(64))
            val value = operation.openDatabase(create).use(block)
            writer.complete(operation.finish(HostAdapterResponse(requestId = id.toString(), status = HostAdapterStatus.SUCCESS,
                result = HostAdapterResult.Edited(emptyList()))))
            writer.close()
            return value
        }
        lateinit var delayed: DatabaseRequest
        edit(true) { db ->
            db.initializeAccount(player.uuid, 1000)
            repeat(105) {
                val account = db.readAccount(player.uuid)
                val request = DatabaseRequest(UUID.randomUUID(), Instant.ofEpochSecond(100), TransactionKind.MONEY,
                    listOf(MoneyChange(player, MoneyEntryType.ADMIN_ADD, 1, account.revision)))
                db.submit(request)
            }
            db.initializeStock("tab.apple", 10)
            delayed = DatabaseRequest(UUID.randomUUID(), Instant.ofEpochSecond(100, 500), TransactionKind.SHOP_BUY,
                listOf(MoneyChange(player, MoneyEntryType.SHOP_BUY, -1, db.readAccount(player.uuid).revision)),
                ShopTradeRequest(player, "tab.apple", 1, 1, 0, 9,
                    delivery = TradeDelivery(1, 0, byteArrayOf(1), byteArrayOf(2))))
            db.submit(delayed)
            Unit
        }
        val source = LocalHistorySource(world, shop, worldId.toString())
        val search = source.open(HostHistoryFilter(playerName = "ä日"))
        val first = search.next()
        assertEquals(100, first.size)
        edit { db ->
            db.commitTrade(delayed.requestId)
            val request = DatabaseRequest(UUID.randomUUID(), Instant.ofEpochSecond(100, 700), TransactionKind.MONEY,
                listOf(MoneyChange(player, MoneyEntryType.ADMIN_ADD, 1, db.readAccount(player.uuid).revision)))
            db.submit(request)
            Unit
        }
        val second = search.next()
        assertEquals(5, second.size)
        assertTrue(search.next().isEmpty())
        search.close()
        assertEquals(105, (first + second).map { it.id }.distinct().size)
        assertEquals((first + second).map { it.id }.sorted(), (first + second).map { it.id })
        assertTrue((first + second).none { it.requestId == delayed.requestId.toString() })
        val before = Files.walk(world).use { files -> files.filter { Files.isRegularFile(it) }.toList().associateWith {
            Files.readAllBytes(it).toList() to Files.getLastModifiedTime(it)
        } }
        assertEquals(107, source.readAll().size)
        assertEquals(before, Files.walk(world).use { files -> files.filter { Files.isRegularFile(it) }.toList().associateWith {
            Files.readAllBytes(it).toList() to Files.getLastModifiedTime(it)
        } })
    }

    @Test fun `片側移行履歴の名前不明と理由免除実行者とnano精度を保持する`() {
        val record = historyRecord().copy(players = listOf(HostHistoryPlayer("11111111-1111-1111-1111-111111111111", null),
            HostHistoryPlayer("22222222-2222-2222-2222-222222222222", null)))
        val row = assertNotNull(record.moneyRow(record.money.single().playerUuid, ZoneId.of("Asia/Tokyo")))
        assertEquals(123456789, row.timestamp.nano)
        assertEquals(-4L, row.amount)
        assertTrue(row.exempt)
        assertEquals("理由", row.reason)
        assertEquals("Console", row.executor)
        assertEquals("22222222-2222-2222-2222-222222222222", row.counterparty)
    }

    @Test fun `ショップ取得の日付境界はDSTとnanoを保ち既存の日別件数を維持する`() {
        val filters = mutableListOf<HostHistoryFilter>()
        val source = HostHistorySource(UUID.randomUUID().toString()) { request ->
            val result = when (val action = request.action) {
                HostAdapterAction.HistoryStart -> HostAdapterResult.Snapshot(HostHistorySnapshot(0, emptyList()))
                is HostAdapterAction.HistoryPage -> {
                    filters += action.filter
                    HostAdapterResult.History(emptyList())
                }
                else -> error("履歴以外の要求です。")
            }
            HostAdapterResponse(requestId = request.requestId, status = HostAdapterStatus.SUCCESS, result = result)
        }
        val date = java.time.LocalDate.of(2026, 3, 8)
        assertTrue(readShopHistory(source, date, date, ZoneId.of("America/New_York")).isEmpty())
        val filter = filters.single()
        assertEquals(Instant.parse("2026-03-08T05:00:00Z").epochSecond, filter.fromEpochSecond)
        assertEquals(Instant.parse("2026-03-09T03:59:59Z").epochSecond, filter.untilEpochSecond)
        assertEquals(999999999, filter.untilNano)
    }

    @Test fun `DB履歴のないキャッシュと既存ディレクトリのプレイヤーも一覧に残す`() {
        val memory = io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore()
        val cached = "22222222-2222-2222-2222-222222222222"
        val directory = "33333333-3333-3333-3333-333333333333"
        memory.writeText("usercache.json", """[{"uuid":"$cached","name":"CacheOnly"}]""")
        memory.writeText("player_data/$directory/achievements.yml", "achievements: {}")
        val source = HostHistorySource(UUID.randomUUID().toString()) { request ->
            val result = when (val action = request.action) {
                HostAdapterAction.HistoryStart -> HostAdapterResult.Snapshot(HostHistorySnapshot(1, emptyList()))
                is HostAdapterAction.HistoryPage -> HostAdapterResult.History(if (action.offset == 0) listOf(historyRecord()) else emptyList())
                else -> error("検索以外の要求です。")
            }
            HostAdapterResponse(requestId = request.requestId, status = HostAdapterStatus.SUCCESS, result = result)
        }
        val players = readHistoryPlayers(memory, org.slf4j.LoggerFactory.getLogger(javaClass), source)
        assertEquals(setOf(historyRecord().players.single().uuid, cached, directory), players.map { it.uuid }.toSet())
        assertEquals("CacheOnly", players.single { it.uuid == cached }.displayName)
        assertEquals(directory, players.single { it.uuid == directory }.displayName)
    }

    @Test fun `DB済みworldと未設定の遠隔Storeをコピー済みYAMLとして読まない`() {
        val local = io.github.sushiericworkspace.sushiericservermanager.editor.store.LocalEditorDataStore(root.toFile())
        assertNull(local.historySource)
        val absent = root.resolve("未作成のworldデータ")
        val bound = io.github.sushiericworkspace.sushiericservermanager.editor.store.LocalEditorDataStore(absent.toFile(),
            HostHistorySource(UUID.randomUUID().toString()) { error("接続確認でDBを読みません。") })
        assertFalse(bound.isAvailable)
        assertFalse(Files.exists(absent))
        Files.writeString(root.resolve("world-id"), UUID.randomUUID().toString())
        assertFailsWith<IllegalStateException> { local.historySource }
        val memory = io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore()
        val remote = object : io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore by memory {
            override val kind = io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStoreKind.REMOTE
        }
        assertFailsWith<IllegalStateException> { historySource(remote, ServerManagementClient()) }
    }

    @Test fun `管理codecはDB型とnano精度を復号する`() {
        val text = """{"type":"database_history","success":true,"handle":"h","rows":[],"nextOffset":7,"hasMore":true,"nonce":"n"}"""
        val response = assertIs<ServerManagementDecodeResult.Success>(ServerManagementMessageCodec.decode(text)).message
        assertEquals(7, assertIs<ServerManagementResponse.DatabaseHistory>(response).nextOffset)
        val encoded = ServerManagementMessageCodec.encode(ServerManagementRequest.DatabaseHistoryStart(
            ManagementDatabaseBinding("world", "run"), HostHistoryFilter(fromEpochSecond = 1, fromNano = 123), "n"))
        assertTrue(encoded.contains("database_history_start"))
        assertTrue(encoded.contains("123"))
    }

    @Test fun `byte上限で短いページもnextOffsetで進み終了を送る`() {
        val fake = HistoryManagementFixture()
        fake.client.let { client ->
            val rows = ManagementHistorySource(client).readAll()
            assertEquals(listOf(1L, 2L), rows.map { it.id })
            assertEquals(listOf(0, 1), fake.offsets)
            assertEquals(1, fake.ends)
            client.disconnect()
        }
    }

    @Test fun `切断認証エラーとtimeoutは旧結果を返さずhandleを終了する`() {
        for (failure in listOf("disconnect", "auth", "timeout")) {
            val fake = HistoryManagementFixture()
            fake.failure = failure
            assertFails { ManagementHistorySource(fake.client, 50).readAll() }
            assertEquals(if (failure == "disconnect") 0 else 1, fake.ends)
            fake.client.disconnect()
        }
    }

    @Test fun `再接続前に作った検索元を新接続へ継続しない`() {
        val fake = HistoryManagementFixture()
        val source = ManagementHistorySource(fake.client)
        fake.client.disconnect()
        assertTrue(fake.client.connect(fake))
        assertFailsWith<IllegalStateException> { source.open() }
        assertTrue(fake.offsets.isEmpty())
        assertEquals(2, ManagementHistorySource(fake.client).readAll().size)
        fake.client.disconnect()
    }

    @Test fun `開始timeoutの遅延応答は表示せずhandleだけ終了する`() {
        val fake = HistoryManagementFixture()
        fake.delayStart = CountDownLatch(1)
        assertFails { ManagementHistorySource(fake.client, 30).readAll() }
        assertEquals(0, fake.ends)
        fake.finishStart()
        assertEquals(1, fake.ends)
        fake.client.disconnect()
    }

    @Test fun `検索中断後も開始応答を回収してhandleを解放する`() {
        val fake = HistoryManagementFixture()
        val started = CountDownLatch(1)
        fake.delayStart = started
        val done = CompletableFuture<Unit>()
        val thread = Thread {
            try { ManagementHistorySource(fake.client).readAll(); done.completeExceptionally(AssertionError()) }
            catch (_: InterruptedException) { done.complete(Unit) }
            catch (error: Throwable) { done.completeExceptionally(error) }
        }
        thread.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        thread.interrupt()
        fake.finishStart()
        done.get(5, TimeUnit.SECONDS)
        thread.join(5000)
        assertFalse(thread.isAlive)
        assertEquals(1, fake.ends)
        fake.client.disconnect()
    }
}

internal class HistoryManagementFixture : ManagementTransport {
    val client = ServerManagementClient()
    lateinit var listener: ServerManagementListener
    val offsets = mutableListOf<Int>()
    var ends = 0
    var failure: String? = null
    var delayStart: CountDownLatch? = null
    private var startNonce: String? = null
    init { assertTrue(client.connect(this)) }
    override fun connect(listener: ServerManagementListener, timeout: Duration): Int { this.listener = listener; return 12345 }
    override fun disconnect() = Unit
    fun finishStart() { listener.onMessage(ServerManagementResponse.DatabaseHistory(true, "h", nonce = startNonce)) }
    override fun send(request: ServerManagementRequest): Boolean {
        when (request) {
            is ServerManagementRequest.DatabaseContextRequest -> listener.onMessage(ServerManagementResponse.DatabaseContext(true,
                ManagementDatabaseBinding(UUID.randomUUID().toString(), UUID.randomUUID().toString()), nonce = request.nonce))
            is ServerManagementRequest.DatabaseHistoryStart -> {
                startNonce = request.nonce
                if (delayStart == null) finishStart() else delayStart!!.countDown()
            }
            is ServerManagementRequest.DatabaseHistoryPage -> {
                when (failure) {
                    "disconnect" -> listener.onClosed(1006, "切断")
                    "auth" -> listener.onMessage(ServerManagementResponse.Error("unauthorized"))
                    "timeout" -> Unit
                    else -> {
                        offsets += request.offset
                        listener.onMessage(ServerManagementResponse.DatabaseHistory(true, "h", listOf(historyRecord(request.offset + 1L)),
                            request.offset + 1, request.offset == 0, nonce = request.nonce))
                    }
                }
            }
            is ServerManagementRequest.DatabaseHistoryEnd -> {
                ends++
                listener.onMessage(ServerManagementResponse.DatabaseHistory(true, "h", nonce = request.nonce))
            }
            else -> error("履歴以外の要求です。")
        }
        return true
    }
}
