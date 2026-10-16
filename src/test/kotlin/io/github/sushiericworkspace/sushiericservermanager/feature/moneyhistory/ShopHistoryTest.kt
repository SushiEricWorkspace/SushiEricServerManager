package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.LocalEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ShopHistoryTest {
    private val date = LocalDate.of(2026, 10, 8)
    private fun yaml(mode: String = "BUY", count: String = "3") = """
        entries:
          - timestamp: '2026-10-08T01:02:03Z'
            product-id: weapon.sword
            mode: $mode
            player-uuid: 00000000-0000-0000-0000-000000000001
            player-name: Steve
            count: $count
            unit-price: 100
            total: 300
    """.trimIndent()

    @Test
    fun `Modの記録形式とLong金額を読める`() {
        val entry = parseShopHistory(yaml().replace("total: 300", "total: 9223372036854775807")).single()
        assertEquals("weapon.sword", entry.productId)
        assertEquals("Steve", entry.playerName)
        assertEquals(3, entry.count)
        assertEquals(Long.MAX_VALUE, entry.total)
    }

    @Test
    fun `未知の種別と数量オーバーフローは通知して読み飛ばす`() {
        val skipped = mutableListOf<String>()
        assertTrue(parseShopHistory(yaml("UNKNOWN"), skipped::add).isEmpty())
        assertTrue(parseShopHistory(yaml(count = "2147483648"), skipped::add).isEmpty())
        assertEquals(2, skipped.size)
        assertFailsWith<IllegalArgumentException> { parseShopHistory("entries: invalid") }
    }

    @Test
    fun `商品とプレイヤーの部分一致と複数種別で絞り込む`() {
        val entries = parseShopHistory(yaml()) + parseShopHistory(yaml("SELL"))
        assertEquals(2, filterShopHistory(entries, " SWORD ", "ste", ShopHistoryMode.entries.toSet()).size)
        assertEquals(1, filterShopHistory(entries, "", "", setOf(ShopHistoryMode.SELL)).size)
        assertTrue(filterShopHistory(entries, "", "", emptySet()).isEmpty())
        assertTrue(filterShopHistory(entries, "missing", "", ShopHistoryMode.entries.toSet()).isEmpty())
        val other = entries.first().copy(playerUuid = java.util.UUID.randomUUID(), playerName = "Steve")
        assertEquals(2, filterShopHistory(entries + other, "", "", ShopHistoryMode.entries.toSet(),
            playerUuid = entries.first().playerUuid).size)
    }

    @Test
    fun `日付範囲外のファイルとディレクトリは読み込まない`() {
        val delegate = InMemoryEditorDataStore()
        delegate.writeText("shop_history/2026-10-08.yml", yaml())
        delegate.writeText("shop_history/2026-10-09.yml", "broken: [")
        delegate.writeText("shop_history/notes.yml", "broken: [")
        delegate.writeText("shop_history/2026-10-08.yml/child", "broken: [")
        val readPaths = mutableListOf<String>()
        val store = object : EditorDataStore by delegate {
            override fun readText(relativePath: String): StoreResult<String> {
                readPaths += relativePath
                return delegate.readText(relativePath)
            }
        }
        assertEquals(1, readShopHistory(store, date, date).size)
        assertEquals(listOf("shop_history/2026-10-08.yml"), readPaths)
        assertFailsWith<IllegalArgumentException> { readShopHistory(store, date.plusDays(1), date) }
    }

    @Test
    fun `ローカルストアで単日と範囲を読み込める`() {
        val store = LocalEditorDataStore(createTempDirectory("shop-history").toFile())
        store.writeText("shop_history/2026-10-08.yml", yaml())
        store.writeText("shop_history/2026-10-09.yml", yaml("SELL"))
        assertEquals(1, readShopHistory(store, date, date).size)
        assertEquals(2, readShopHistory(store, date, date.plusDays(1)).size)
        assertEquals(2, readShopHistory(store, null, null).size)
        assertEquals(1, readShopHistory(store, date.plusDays(1), null).size)
        assertEquals(1, readShopHistory(store, null, date).size)
        assertTrue(readShopHistory(InMemoryEditorDataStore(), date, date).isEmpty())
    }

    @Test
    fun `時刻未指定は日全体で分秒指定は共通の日時境界を使用する`() {
        val entries = parseShopHistory(yaml())
        val modes = ShopHistoryMode.entries.toSet()
        val zone = java.time.ZoneOffset.UTC
        assertEquals(1, filterShopHistory(entries, "", "", modes,
            historyTimeRange(date, "", date, ""), zone).size)
        assertEquals(1, filterShopHistory(entries, "", "", modes,
            historyTimeRange(date, "01:02", date, "01:02"), zone).size)
        assertTrue(filterShopHistory(entries, "", "", modes,
            historyTimeRange(date, "01:02:04", date, ""), zone).isEmpty())
    }
}
