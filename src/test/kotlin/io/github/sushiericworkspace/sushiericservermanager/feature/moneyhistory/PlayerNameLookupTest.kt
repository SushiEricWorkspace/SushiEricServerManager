package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerNameLookupTest {
    @Test
    fun `履歴なしでもUUIDから名前を解決し取得結果を再利用する`() {
        var requests = 0
        val lookup = PlayerNameLookup { uuid ->
            requests++
            assertEquals("916f5122dab9498ab3a0dcb880a803e0", uuid)
            """{"id":"$uuid","name":"SushiEric"}"""
        }
        repeat(2) { assertEquals("SushiEric", lookup.lookup(UUID)) }
        assertEquals(1, requests)
    }

    @Test
    fun `取得失敗は例外にせず名前なしとして扱う`() {
        val lookup = PlayerNameLookup { throw IllegalStateException("通信失敗") }
        assertNull(lookup.lookup(UUID))
    }

    @Test
    fun `別UUIDや不正な応答の名前は採用しない`() {
        assertNull(PlayerNameLookup { """{"id":"other","name":"SushiEric"}""" }.lookup(UUID))
        assertNull(PlayerNameLookup { "invalid" }.lookup(UUID))
        assertNull(PlayerNameLookup { null }.lookup(UUID))
    }

    @Test
    fun `不正UUIDでは通信しない`() {
        var requests = 0
        val lookup = PlayerNameLookup { requests++; null }
        assertNull(lookup.lookup("invalid"))
        assertEquals(0, requests)
    }

    private companion object {
        const val UUID = "916f5122-dab9-498a-b3a0-dcb880a803e0"
    }
}
