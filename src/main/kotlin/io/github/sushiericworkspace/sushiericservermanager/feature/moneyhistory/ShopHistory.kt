package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.UUID

/** 保存されたショップ売買履歴の読み取り専用データです。 */
internal data class ShopHistoryEntry(
    val timestamp: Instant, val productId: String, val mode: ShopHistoryMode,
    val playerUuid: UUID, val playerName: String, val count: Int, val unitPrice: Long, val total: Long
)

internal enum class ShopHistoryMode(val displayName: String) { BUY("購入"), SELL("販売") }

/** 商品IDと記録時点のプレイヤー名は部分一致、種別は選択集合で絞り込みます。 */
internal fun filterShopHistory(entries: List<ShopHistoryEntry>, product: String, player: String, modes: Set<ShopHistoryMode>,
    range: HistoryTimeRange = HistoryTimeRange(null, null), zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    playerUuid: UUID? = null): List<ShopHistoryEntry> =
    entries.filter {
        it.productId.contains(product.trim(), ignoreCase = true) &&
            it.playerName.contains(player.trim(), ignoreCase = true) && it.mode in modes &&
            range.contains(it.timestamp.atZone(zone).toLocalDateTime()) && (playerUuid == null || it.playerUuid == playerUuid)
    }

/** Modのショップ履歴形式を読み込み、不正な行は通知して読み飛ばします。 */
internal fun parseShopHistory(text: String, onSkipped: (String) -> Unit = {}): List<ShopHistoryEntry> {
    val document = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text) as? Map<*, *> ?: return emptyList()
    val entries = document["entries"] ?: return emptyList()
    require(entries is List<*>) { "entriesが一覧ではありません。" }
    return entries.mapIndexedNotNull { index, raw ->
        runCatching {
            require(raw is Map<*, *>) { "履歴がマップではありません。" }
            fun text(key: String) = requireNotNull(raw[key]) { "${key}がありません。" }.toString()
            fun number(key: String) = text(key).toLong()
            ShopHistoryEntry(
                (raw["timestamp"] as? Date)?.toInstant() ?: Instant.parse(text("timestamp")),
                text("product-id"), ShopHistoryMode.valueOf(text("mode")), UUID.fromString(text("player-uuid")),
                text("player-name"), Math.toIntExact(number("count")), number("unit-price"), number("total")
            )
        }.getOrElse { onSkipped("${index + 1}行目: ${it.message}"); null }
    }
}

/** 指定範囲の日別ファイルだけを読みます。範囲外のファイルは取得しません。 */
internal fun readShopHistory(
    store: EditorDataStore, from: LocalDate?, to: LocalDate?, onSkipped: (String) -> Unit = {}
): List<ShopHistoryEntry> {
    require(from == null || to == null || !from.isAfter(to)) { "開始日が終了日より後です。" }
    val paths = when (val result = store.listPath("shop_history")) {
        is StoreResult.Success -> result.value
        is StoreResult.Failure -> if (result.error.code == StoreErrorCode.FILE_NOT_FOUND) return emptyList()
            else error("ショップ履歴の一覧を取得できません: ${result.error.code}")
    }
    return paths.filter { entry ->
        !entry.isDirectory && entry.name.endsWith(".yml") &&
            runCatching { LocalDate.parse(entry.name.removeSuffix(".yml")) }.getOrNull()
                ?.let { (from == null || !it.isBefore(from)) && (to == null || !it.isAfter(to)) } == true
    }.flatMap { entry ->
        val path = "shop_history/${entry.name}"
        when (val result = store.readText(path)) {
            is StoreResult.Success -> parseShopHistory(result.value) { onSkipped("$path: $it") }
            is StoreResult.Failure -> error("$path を読み込めません: ${result.error.code}")
        }
    }.sortedWith(compareByDescending<ShopHistoryEntry> { it.timestamp }.thenBy { it.playerUuid.toString() })
}
