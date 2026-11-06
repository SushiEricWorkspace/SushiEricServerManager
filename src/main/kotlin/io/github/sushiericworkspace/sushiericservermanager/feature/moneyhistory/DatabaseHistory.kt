package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericdatamodel.database.TransactionKind
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementClient
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStoreKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

internal class HistoryConnection(private val store: EditorDataStore, private val client: ServerManagementClient) {
    private val storeIdentity = store.identity
    private val connectionIdentity = store.connectionIdentity
    val source = runCatching { historySource(store, client) }
    private val managementGeneration = (source.getOrNull() as? ManagementHistorySource)?.generation
    val isCurrent: Boolean get() = store.isAvailable && store.identity == storeIdentity && store.connectionIdentity === connectionIdentity &&
        (managementGeneration == null || (client.isConnected && client.connectionGeneration == managementGeneration))
}

internal fun historySource(store: EditorDataStore, client: ServerManagementClient): HistorySource? = when {
    client.isConnected -> ManagementHistorySource(client)
    store.historySource != null -> store.historySource
    store.kind == EditorDataStoreKind.LOCAL || store.kind == EditorDataStoreKind.IN_MEMORY -> null
    else -> error("DB履歴の接続設定がありません。Managementまたは同一ホストの固定DB runtimeを設定してください。")
}

internal fun HostHistoryRecord.moneyRow(uuid: String, zone: ZoneId = ZoneId.systemDefault()): MoneyHistoryRow? {
    val entry = money.firstOrNull { it.playerUuid == uuid } ?: return null
    val type = MoneyHistoryType.entries.firstOrNull { it.name == entry.type.name } ?: return null
    val timestamp = Instant.ofEpochSecond(epochSecond, nano.toLong())
    val other = players.firstOrNull { it.uuid != uuid }
    return MoneyHistoryRow(timestamp, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(timestamp.atZone(zone)),
        players.firstOrNull { it.uuid == uuid }?.name ?: uuid, type, entry.requestedDelta, entry.balanceAfter,
        other?.let { formatHistoryActor(it.name, it.uuid) }, formatHistoryActor(executorName, executorUuid), reason,
        entry.exempt)
}

internal fun HostHistoryRecord.shopRow(): ShopHistoryEntry? {
    val value = trade ?: return null
    val mode = when (kind) {
        TransactionKind.SHOP_BUY -> ShopHistoryMode.BUY
        TransactionKind.SHOP_SELL -> ShopHistoryMode.SELL
        else -> return null
    }
    return ShopHistoryEntry(Instant.ofEpochSecond(epochSecond, nano.toLong()), value.productId, mode,
        UUID.fromString(value.playerUuid), players.firstOrNull { it.uuid == value.playerUuid }?.name ?: value.playerUuid,
        value.quantity, value.unitPrice, Math.multiplyExact(value.quantity.toLong(), value.unitPrice))
}
