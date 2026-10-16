package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    @Test
    fun `重複要求をまとめて異なるUUIDも同時通信しない`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val lookup = PlayerNameLookup { uuid ->
            requests.incrementAndGet()
            started.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
            """{"id":"$uuid","name":"SushiEric"}"""
        }
        val first = lookup.lookupAsync(UUID)
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS))
            assertSame(first, lookup.lookupAsync(UUID))
            val second = lookup.lookupAsync("11111111-1111-1111-1111-111111111111")
            assertEquals(1, requests.get())
            release.countDown()
            assertEquals("SushiEric", first.get(10, TimeUnit.SECONDS))
            assertEquals("SushiEric", second.get(10, TimeUnit.SECONDS))
            assertEquals(2, requests.get())
        } finally {
            release.countDown()
        }
    }

    private companion object {
        const val UUID = "916f5122-dab9-498a-b3a0-dcb880a803e0"
    }
}
