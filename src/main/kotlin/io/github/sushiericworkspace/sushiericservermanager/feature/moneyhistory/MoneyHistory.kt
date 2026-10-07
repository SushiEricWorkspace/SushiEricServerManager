package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 入出金履歴の種別です。ServerModの`MoneyTransactionType`の列挙名と同じ名前で、履歴ファイルの`type`に保存されます。
 *
 * [displayName]は、画面とServerModの`/se money history`で共通の表示名です。
 */
internal enum class MoneyHistoryType(val displayName: String) {
    PAY_SENT("送金"),
    PAY_RECEIVED("受取"),
    ADMIN_SET("管理者設定"),
    ADMIN_ADD("管理者加算"),
    ADMIN_REMOVE("管理者減算"),
    SHOP_BUY("ショップ購入"),
    SHOP_SELL("ショップ販売")
}

/** 入出金履歴1件の表示用の行です。 */
internal data class MoneyHistoryRow(
    val timestamp: Instant,
    val timeText: String,
    val playerName: String,
    val type: MoneyHistoryType,
    val amount: Long,
    val balanceAfter: Long,
    val counterparty: String?,
    val executor: String?,
    val reason: String?
)

/** 入出金履歴の時刻の表示形式です。 */
private val MONEY_HISTORY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * `money_history/<yyyy-MM-dd>.yml`の`entries`を、入出金履歴の行へ変換します。
 *
 * 記録ごとに変換し、未知の種別、読めない項目、別のプレイヤーの記録は[onSkipped]へ理由を通知して読み飛ばします。
 * 一部の記録が読めなくても、他の記録は返します。`entries`がない文書は空の一覧です。
 * YAML全体が不正な場合は例外を送出します。
 *
 * 相手のUUIDの名前解決はこの関数では行わず、`counterparty`はファイルの値のままです。
 *
 * @param playerUuid 履歴ファイルの持ち主のUUID。記録の`player-uuid`と異なる記録は読み飛ばします。
 * @param zone 時刻の表示に使うタイムゾーン
 */
internal fun parseMoneyHistory(
    text: String,
    playerUuid: String,
    onSkipped: (String) -> Unit = {},
    zone: ZoneId = ZoneId.systemDefault()
): List<MoneyHistoryRow> {
    val yaml = Yaml(SafeConstructor(LoaderOptions()))
    val document = yaml.load<Any?>(text) as? Map<*, *> ?: return emptyList()
    val entries = document["entries"] as? List<*> ?: return emptyList()

    return entries.mapNotNull { raw ->
        val entry = raw as? Map<*, *> ?: return@mapNotNull null

        try {
            val uuid = entry["player-uuid"]?.toString() ?: playerUuid

            require(uuid == playerUuid)

            val timestamp = Instant.parse(entry["timestamp"].toString())

            MoneyHistoryRow(
                timestamp = timestamp,
                timeText = MONEY_HISTORY_TIME_FORMAT.format(timestamp.atZone(zone)),
                playerName = entry["player-name"]?.toString() ?: playerUuid,
                type = MoneyHistoryType.valueOf(entry["type"].toString()),
                amount = entry["amount"].toString().toLong(),
                balanceAfter = entry["balance-after"].toString().toLong(),
                counterparty = entry["other-player"]?.toString(),
                executor = (entry["executor"] as? Map<*, *>)?.let { actor ->
                    formatHistoryActor(actor["name"]?.toString(), actor["uuid"]?.toString())
                },
                reason = entry["reason"]?.toString()
            )
        } catch (e: Exception) {
            onSkipped(e.message ?: e.javaClass.simpleName)
            null
        }
    }
}
