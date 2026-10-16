package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MoneyHistoryTest {
    private val player = "00000000-0000-0000-0000-000000000001"

    private fun parse(text: String, skipped: MutableList<String> = mutableListOf()): List<MoneyHistoryRow> =
        parseMoneyHistory(text.trimIndent(), player, skipped::add, ZoneOffset.UTC)

    @Test
    fun `ショップの購入は負の金額と理由を読み込む`() {
        val rows = parse(
            """
            entries:
              - timestamp: '2026-10-07T01:02:03Z'
                player-uuid: $player
                player-name: Steve
                type: SHOP_BUY
                amount: -300
                balance-after: 700
                reason: weapon.iron_sword x3
                exempt: false
            """
        )
        val row = rows.single()

        assertEquals(MoneyHistoryType.SHOP_BUY, row.type)
        assertEquals("ショップ購入", row.type.displayName)
        assertEquals(-300L, row.amount)
        assertEquals(700L, row.balanceAfter)
        assertEquals("weapon.iron_sword x3", row.reason)
        assertEquals("Steve", row.playerName)
        assertEquals("2026-10-07 01:02:03", row.timeText)
        assertEquals(Instant.parse("2026-10-07T01:02:03Z"), row.timestamp)
        assertEquals(null, row.counterparty)
        assertEquals(null, row.executor)
    }

    @Test
    fun `ショップの販売は正の金額を読み込む`() {
        val row = parse(
            """
            entries:
              - timestamp: '2026-10-07T02:00:00Z'
                player-name: Steve
                type: SHOP_SELL
                amount: 400
                balance-after: 1400
                reason: tool.paper x80
            """
        ).single()

        assertEquals(MoneyHistoryType.SHOP_SELL, row.type)
        assertEquals("ショップ販売", row.type.displayName)
        assertEquals(400L, row.amount)
        assertEquals(1400L, row.balanceAfter)
    }

    @Test
    fun `管理者モードで免除された購入は、要求された金額と変わらない残高をそのまま表示する`() {
        val row = parse(
            """
            entries:
              - timestamp: '2026-10-07T03:00:00Z'
                player-name: Admin
                type: SHOP_BUY
                amount: -50
                balance-after: 10
                reason: tool.paper x5
                exempt: true
            """
        ).single()

        assertEquals(MoneyHistoryType.SHOP_BUY, row.type)
        assertEquals(-50L, row.amount)
        assertEquals(10L, row.balanceAfter)
    }

    @Test
    fun `既存の種別も引き続き読み込める`() {
        val rows = parse(
            """
            entries:
              - {timestamp: '2026-10-07T01:00:00Z', player-name: A, type: PAY_SENT, amount: -10, balance-after: 90, other-player: 00000000-0000-0000-0000-000000000002}
              - {timestamp: '2026-10-07T01:00:01Z', player-name: A, type: PAY_RECEIVED, amount: 10, balance-after: 100}
              - {timestamp: '2026-10-07T01:00:02Z', player-name: A, type: ADMIN_SET, amount: 5, balance-after: 5, executor: {name: Console}}
              - {timestamp: '2026-10-07T01:00:03Z', player-name: A, type: ADMIN_ADD, amount: 5, balance-after: 10}
              - {timestamp: '2026-10-07T01:00:04Z', player-name: A, type: ADMIN_REMOVE, amount: -5, balance-after: 5}
            """
        )

        assertEquals(
            listOf(
                MoneyHistoryType.PAY_SENT, MoneyHistoryType.PAY_RECEIVED, MoneyHistoryType.ADMIN_SET,
                MoneyHistoryType.ADMIN_ADD, MoneyHistoryType.ADMIN_REMOVE
            ),
            rows.map { it.type }
        )
        assertEquals("00000000-0000-0000-0000-000000000002", rows.first().counterparty)
    }

    @Test
    fun `未知の種別と壊れた記録は通知して読み飛ばし、他の記録は読み込む`() {
        val skipped = mutableListOf<String>()
        val rows = parse(
            """
            entries:
              - {timestamp: '2026-10-07T01:00:00Z', player-name: A, type: FUTURE_TYPE, amount: 1, balance-after: 1}
              - {timestamp: '2026-10-07T01:00:01Z', player-name: A, type: SHOP_BUY, amount: abc, balance-after: 1}
              - {timestamp: broken, player-name: A, type: SHOP_BUY, amount: -1, balance-after: 1}
              - {timestamp: '2026-10-07T01:00:03Z', player-name: A, type: SHOP_SELL, amount: 1, balance-after: 2}
            """,
            skipped
        )

        assertEquals(listOf(MoneyHistoryType.SHOP_SELL), rows.map { it.type })
        assertEquals(3, skipped.size)
    }

    @Test
    fun `別のプレイヤーの記録は読み飛ばす`() {
        val skipped = mutableListOf<String>()
        val rows = parse(
            """
            entries:
              - {timestamp: '2026-10-07T01:00:00Z', player-uuid: 00000000-0000-0000-0000-000000000009, player-name: B, type: SHOP_BUY, amount: -1, balance-after: 1}
            """,
            skipped
        )

        assertTrue(rows.isEmpty())
        assertEquals(1, skipped.size)
    }

    @Test
    fun `entriesがない文書は空の履歴になる`() {
        assertTrue(parse("").isEmpty())
        assertTrue(parse("other: 1").isEmpty())
    }

    @Test
    fun `種別の列挙名はServerModの履歴ファイルの値と同じで、表示名は重複しない`() {
        assertEquals(
            setOf("PAY_SENT", "PAY_RECEIVED", "ADMIN_SET", "ADMIN_ADD", "ADMIN_REMOVE", "SHOP_BUY", "SHOP_SELL"),
            MoneyHistoryType.entries.map { it.name }.toSet()
        )
        assertEquals(MoneyHistoryType.entries.size, MoneyHistoryType.entries.map { it.displayName }.toSet().size)
    }
}
