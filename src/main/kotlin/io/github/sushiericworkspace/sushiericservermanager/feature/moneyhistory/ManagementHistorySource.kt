package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericservermanager.communication.management.*
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** 同じManagement接続の検索handleを開閉します。拒否時にYAMLや別接続へ切り替えません。 */
class ManagementHistorySource(private val client: ServerManagementClient,
    private val timeoutMillis: Long = 20_000) : HistorySource {
    private val logger = LoggerFactory.getLogger(javaClass)
    internal val generation = client.connectionGeneration
    override fun open(filter: HostHistoryFilter): HistorySearch {
        checkHistoryWorker()
        client.historySearchLock.lockInterruptibly()
        try {
            client.historyCleanup?.takeIf { it.first == generation }?.second?.let {
                try { it.get(timeoutMillis, TimeUnit.MILLISECONDS) }
                catch (error: java.util.concurrent.TimeoutException) {
                    throw IllegalStateException("前の履歴検索の終了を待っています。再試行または再接続してください。", error)
                }
                catch (error: java.util.concurrent.ExecutionException) {
                    throw IllegalStateException("前の履歴検索の終了を確認できません。再接続してください。", error.cause)
                }
            }
            return openSearch(filter)
        }
        catch (error: Throwable) { client.historySearchLock.unlock(); throw error }
    }

    private fun openSearch(filter: HostHistoryFilter): HistorySearch {
        val contextNonce = UUID.randomUUID().toString()
        val context = await(ServerManagementRequest.DatabaseContextRequest(contextNonce)) {
            (it as? ServerManagementResponse.DatabaseContext)?.takeIf { response -> response.nonce == contextNonce }
        }
        check(context.success) { context.detail ?: "DB接続を確認できません。" }
        val binding = requireNotNull(context.binding)
        val nonce = UUID.randomUUID().toString()
        // 開始の応答は割込み後も回収し、発行済みhandleを取り残さない。
        val started = await(ServerManagementRequest.DatabaseHistoryStart(binding, filter, nonce), deferInterrupt = true) {
            (it as? ServerManagementResponse.DatabaseHistory)?.takeIf { response -> response.nonce == nonce }
        }
        check(started.success) { started.detail ?: started.code ?: "履歴検索を開始できません。" }
        val handle = requireNotNull(started.handle)
        return object : HistorySearch {
            private var offset = 0
            private var ended = false
            private var closed = false
            override fun next(): List<HostHistoryRecord> {
                checkHistoryWorker()
                check(!closed && client.connectionGeneration == generation) { "検索元の接続が終了しました。" }
                if (ended) return emptyList()
                val pageNonce = UUID.randomUUID().toString()
                val response = await(ServerManagementRequest.DatabaseHistoryPage(handle, offset, 100, pageNonce)) {
                    (it as? ServerManagementResponse.DatabaseHistory)?.takeIf { value -> value.nonce == pageNonce }
                }
                check(response.success && response.handle == handle) { response.detail ?: response.code ?: "履歴検索に失敗しました。" }
                check(response.nextOffset == Math.addExact(offset, response.rows.size) && (!response.hasMore || response.rows.isNotEmpty()))
                offset = response.nextOffset
                ended = !response.hasMore
                return response.rows
            }
            override fun close() {
                if (closed) return
                closed = true
                val interrupted = Thread.interrupted()
                try {
                    if (client.isConnected && client.connectionGeneration == generation) {
                        val endNonce = UUID.randomUUID().toString()
                        val response = await(ServerManagementRequest.DatabaseHistoryEnd(handle, endNonce), deferInterrupt = true) {
                            (it as? ServerManagementResponse.DatabaseHistory)?.takeIf { value -> value.nonce == endNonce }
                        }
                        check(response.success) { response.detail ?: "履歴検索を終了できません。" }
                    }
                } finally {
                    client.historySearchLock.unlock()
                    if (interrupted) Thread.currentThread().interrupt()
                }
            }
        }
    }

    private fun <T> await(request: ServerManagementRequest, deferInterrupt: Boolean = false,
        select: (ServerManagementResponse) -> T?): T {
        val result = CompletableFuture<T>()
        val messages: (ServerManagementResponse) -> Unit = { message ->
            if (message is ServerManagementResponse.Error) result.completeExceptionally(IllegalStateException(message.reason))
            else select(message)?.let(result::complete)
            Unit
        }
        val states: (ServerManagementState) -> Unit = { state ->
            if (state !is ServerManagementState.Connected)
                result.completeExceptionally(IllegalStateException("Management接続が切断されました。"))
        }
        client.addMessageListener(messages)
        client.addStateListener(states)
        var interrupted = false
        var delivered = false
        var sent = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        try {
            check(client.isConnected && client.connectionGeneration == generation) { "検索元の接続が終了しました。" }
            sent = client.send(request)
            check(sent) { "Management要求を送信できません。" }
            while (true) {
                try {
                    val response = result.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
                    check(client.isConnected && client.connectionGeneration == generation) { "検索元の接続が終了しました。" }
                    delivered = true
                    return response
                }
                catch (error: InterruptedException) {
                    if (!deferInterrupt) throw error
                    interrupted = true
                }
            }
        } finally {
            if (request is ServerManagementRequest.DatabaseHistoryStart && sent && !delivered &&
                client.isConnected && client.connectionGeneration == generation) {
                val released = CompletableFuture<Unit>()
                client.historyCleanup = generation to released
                result.whenComplete { response, error ->
                    client.removeMessageListener(messages)
                    client.removeStateListener(states)
                    cleanup.execute {
                        try {
                            check(error == null) { "開始要求の結果を確認できません: ${error?.message}" }
                            if (response is ServerManagementResponse.DatabaseHistory && response.success) {
                                val handle = requireNotNull(response.handle)
                                val nonce = UUID.randomUUID().toString()
                                val ended = await(ServerManagementRequest.DatabaseHistoryEnd(handle, nonce)) {
                                    (it as? ServerManagementResponse.DatabaseHistory)?.takeIf { value -> value.nonce == nonce }
                                }
                                check(ended.success && ended.handle == handle) { ended.detail ?: ended.code ?: "遅延した履歴検索を終了できません。" }
                            }
                            released.complete(Unit)
                        } catch (failure: Throwable) {
                            logger.warn("遅延した履歴検索の終了を確認できません。この接続の検索を停止します。", failure)
                            released.completeExceptionally(failure)
                        }
                    }
                }
            } else {
                client.removeMessageListener(messages)
                client.removeStateListener(states)
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
    private companion object {
        val cleanup = java.util.concurrent.ScheduledThreadPoolExecutor(1) {
            Thread(it, "history-start-cleanup").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true; setKeepAliveTime(1, TimeUnit.SECONDS); allowCoreThreadTimeOut(true) }
    }

}
