package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import javafx.application.Platform
import io.github.sushiericworkspace.sushiericservermanager.communication.database.PostgresHostReference
import io.github.sushiericworkspace.sushiericservermanager.editor.shop.LocalShopTransport
import io.github.sushiericworkspace.sushiericservermanager.editor.shop.ShopTransport
import java.util.UUID

/** 同じI/O worker上で開閉する読取専用検索。空ページを返すまで同じ初回集合を保持します。 */
interface HistorySource {
    fun open(filter: HostHistoryFilter = HostHistoryFilter()): HistorySearch
}

/** nextは次ページ、空一覧は終端です。取得失敗は例外で返し、呼出元は必ずcloseします。 */
interface HistorySearch : AutoCloseable {
    fun next(): List<HostHistoryRecord>
}

/** 同一ホストの承認済み非秘密参照を、固定helperへ渡します。 */
class LocalHistorySource(private val reference: PostgresHostReference) : HistorySource {
    override fun open(filter: HostHistoryFilter) = transportHistory(LocalShopTransport(reference), filter)
}

internal fun transportHistory(transport: ShopTransport, filter: HostHistoryFilter): HistorySearch {
    checkHistoryWorker()
    try {
        val search = HostHistorySource(transport.worldUuid(), transport::execute).open(filter)
        return object : HistorySearch {
            override fun next() = search.next()
            override fun close() {
                try { search.close() } finally { transport.close() }
            }
        }
    } catch (failure: Throwable) {
        try { transport.close() } catch (closing: Throwable) { failure.addSuppressed(closing) }
        throw failure
    }
}

/** D3のsnapshotを各要求へ引き継ぎ、SQL接続をページ間で保持しません。 */
class HostHistorySource(private val worldUuid: String,
    private val execute: (HostAdapterRequest) -> HostAdapterResponse) : HistorySource {
    override fun open(filter: HostHistoryFilter): HistorySearch {
        checkHistoryWorker()
        fun request(action: HostAdapterAction): HostAdapterResult {
            checkHistoryWorker()
            val request = HostAdapterRequest(requestId = UUID.randomUUID().toString(), worldUuid = worldUuid,
                action = action, deadlineEpochMillis = System.currentTimeMillis() + 30_000)
            val response = execute(request)
            check(response.requestId == request.requestId && response.status == HostAdapterStatus.SUCCESS) {
                response.message ?: response.code ?: "DB履歴を取得できません。移行と接続設定を確認してください。"
            }
            return requireNotNull(response.result)
        }
        val snapshot = (request(HostAdapterAction.HistoryStart) as HostAdapterResult.Snapshot).snapshot
        return object : HistorySearch {
            private var offset = 0
            private var closed = false
            override fun next(): List<HostHistoryRecord> {
                check(!closed)
                val rows = (request(HostAdapterAction.HistoryPage(snapshot, filter, offset, 100)) as HostAdapterResult.History).rows
                offset = Math.addExact(offset, rows.size)
                return rows
            }
            override fun close() { closed = true }
        }
    }
}

internal fun checkHistoryWorker() {
    check(!Platform.isFxApplicationThread()) { "履歴検索はI/O workerで実行してください。" }
    if (Thread.currentThread().isInterrupted) throw InterruptedException("履歴検索を中断しました。")
}

internal fun HistorySource.readAll(filter: HostHistoryFilter = HostHistoryFilter()): List<HostHistoryRecord> =
    open(filter).use { search -> buildList {
        while (true) {
            checkHistoryWorker()
            val page = search.next()
            if (page.isEmpty()) break
            addAll(page)
        }
    } }
