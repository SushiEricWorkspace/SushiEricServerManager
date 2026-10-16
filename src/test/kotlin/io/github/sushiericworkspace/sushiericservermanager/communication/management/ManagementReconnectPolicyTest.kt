package io.github.sushiericworkspace.sushiericservermanager.communication.management

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 自動再接続の待ち時間と打ち切りの判断を検証する。
 */
class ManagementReconnectPolicyTest {

    @Test
    fun `指定した順に待ち時間を返す`() {
        val policy = ManagementReconnectPolicy(listOf(3, 5, 10))

        assertEquals(3, policy.nextDelaySeconds())
        assertEquals(5, policy.nextDelaySeconds())
        assertEquals(10, policy.nextDelaySeconds())
    }

    @Test
    fun `上限へ達したら打ち切る`() {
        val policy = ManagementReconnectPolicy(listOf(3, 5))

        policy.nextDelaySeconds()
        policy.nextDelaySeconds()

        assertNull(policy.nextDelaySeconds())
        assertNull(policy.nextDelaySeconds())
    }

    @Test
    fun `リセットすると最初の待ち時間へ戻る`() {
        val policy = ManagementReconnectPolicy(listOf(3, 5))

        policy.nextDelaySeconds()
        policy.nextDelaySeconds()
        assertNull(policy.nextDelaySeconds())

        policy.reset()

        assertEquals(0, policy.attemptCount)
        assertEquals(3, policy.nextDelaySeconds())
    }

    @Test
    fun `試行回数と上限を返す`() {
        val policy = ManagementReconnectPolicy(listOf(3, 5, 10))

        assertEquals(0, policy.attemptCount)
        assertEquals(3, policy.maxAttempts)

        policy.nextDelaySeconds()

        assertEquals(1, policy.attemptCount)
    }

    @Test
    fun `既定の間隔は短くならない`() {
        val delays = ManagementReconnectPolicy.DEFAULT_DELAYS_SECONDS

        assertEquals(delays.sorted(), delays)
    }

    @Test
    fun `既定の間隔はサーバーの再起動を待てる長さにする`() {
        val total = ManagementReconnectPolicy.DEFAULT_DELAYS_SECONDS.sum()

        assertTrue(
            total >= 240,
            "再試行の合計が短すぎます: ${total}秒"
        )
    }

    @Test
    fun `不正な間隔を拒否する`() {
        assertFailsWith<IllegalArgumentException> {
            ManagementReconnectPolicy(emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            ManagementReconnectPolicy(listOf(3, 0))
        }
    }
}
