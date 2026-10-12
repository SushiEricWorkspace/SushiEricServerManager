package io.github.sushiericworkspace.sushiericservermanager.editor.service

import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.item.model.ItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import java.util.concurrent.CompletableFuture

internal enum class ItemReferenceStatus { EMPTY, LOADING, READY, FAILED }

internal data class ItemReferenceSnapshot(
    val status: ItemReferenceStatus = ItemReferenceStatus.EMPTY,
    val items: List<ItemBaseData> = emptyList()
) {
    val byInternalId: Map<ItemInternalId, ItemBaseData> = items.associateBy { it.internalId }
}

/**
 * 保存先ごとに不変なアイテム定義を共有します。読み込みはバックグラウンドで行い、
 * 同時要求をまとめます。購読通知は状態を変更したスレッドで届きます。
 */
internal class ItemReferenceCache(private val service: EditorDataService) : AutoCloseable {
    @Volatile
    var snapshot = ItemReferenceSnapshot()
        private set
    private val listeners = mutableSetOf<(ItemReferenceSnapshot) -> Unit>()
    private var loading: CompletableFuture<StoreResult<List<ItemBaseData>>>? = null
    private val changes = mutableListOf<(MutableMap<String, ItemBaseData>) -> Unit>()
    private var closed = false
    private var revision = 0L

    @Synchronized
    fun addListener(listener: (ItemReferenceSnapshot) -> Unit) {
        if (closed) return
        listeners += listener
    }

    @Synchronized
    fun removeListener(listener: (ItemReferenceSnapshot) -> Unit) {
        listeners -= listener
    }

    /** キャッシュ済みなら即座に返します。forceは保存先からの手動更新に使用します。 */
    @Synchronized
    fun load(force: Boolean = false): CompletableFuture<StoreResult<List<ItemBaseData>>> {
        if (closed) return CompletableFuture.completedFuture(unavailable())
        loading?.let { return it }
        if (!force && snapshot.status == ItemReferenceStatus.READY) {
            return CompletableFuture.completedFuture(StoreResult.Success(snapshot.items))
        }
        val future = CompletableFuture<StoreResult<List<ItemBaseData>>>()
        loading = future
        publish(ItemReferenceStatus.LOADING, snapshot.items)
        Thread({
            while (true) {
                val startedAt = synchronized(this) { revision }
                val result = try {
                    loadItems()
                } catch (e: Exception) {
                    StoreResult.Failure(StoreError(StoreErrorCode.STORE_UNAVAILABLE, detail = "アイテム一覧の読み込みに失敗しました。", cause = e))
                }
                synchronized(this) {
                    if (closed) return@Thread
                    if (result is StoreResult.Failure && revision != startedAt) return@synchronized
                    loading = null
                    when (result) {
                        is StoreResult.Success -> {
                            val items = result.value.associateByTo(linkedMapOf()) { it.id }
                            changes.forEach { it(items) }
                            changes.clear()
                            publish(ItemReferenceStatus.READY, items.values.toList())
                            future.complete(StoreResult.Success(snapshot.items))
                        }
                        is StoreResult.Failure -> {
                            changes.clear()
                            publish(ItemReferenceStatus.FAILED, snapshot.items)
                            future.complete(result)
                        }
                    }
                    return@Thread
                }
            }
        }, "item-reference-cache").apply { isDaemon = true; start() }
        return future
    }

    private fun loadItems(): StoreResult<List<ItemBaseData>> {
        val resources = when (val result = service.items.listStoreResources()) {
            is StoreResult.Success -> result.value
            is StoreResult.Failure -> return result
        }
        val items = mutableListOf<ItemBaseData>()
        for (resource in resources) {
            when (val result = service.items.loadStore(resource.id)) {
                is StoreResult.Success -> items += result.value.freeze().copy(id = resource.id)
                is StoreResult.Failure -> return result
            }
        }
        return StoreResult.Success(items)
    }

    fun saved(id: String, item: ItemBaseData) = update { it[id] = item.copy(id = id) }

    fun deleted(id: String) = update { it.remove(id) }

    fun renamed(oldId: String, newId: String) = update { items ->
        items.remove(oldId)?.let { items[newId] = it.copy(id = newId) }
    }

    @Synchronized
    private fun update(change: (MutableMap<String, ItemBaseData>) -> Unit) {
        if (closed) return
        revision++
        if (loading != null) changes += change
        val items = snapshot.items.associateByTo(linkedMapOf()) { it.id }
        change(items)
        publish(snapshot.status, items.values.toList())
    }

    private fun publish(status: ItemReferenceStatus, items: List<ItemBaseData>) {
        snapshot = ItemReferenceSnapshot(status, items)
        listeners.toList().forEach { it(snapshot) }
    }

    @Synchronized
    override fun close() {
        closed = true
        listeners.clear()
        changes.clear()
        snapshot = ItemReferenceSnapshot()
        loading?.complete(unavailable())
        loading = null
    }

    private fun unavailable() = StoreResult.Failure(StoreError(StoreErrorCode.STORE_UNAVAILABLE))
}
