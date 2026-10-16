package io.github.sushiericworkspace.sushiericservermanager.communication.management

import io.github.sushiericworkspace.sushiericservermanager.communication.management.codec.ServerManagementDecodeResult
import io.github.sushiericworkspace.sushiericservermanager.communication.management.codec.ServerManagementMessageCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 監視情報のメッセージが、SushiEricServerModの構造どおり扱えることを検証する。
 *
 * 期待値にはServerMod側の実サーバーで受信したJSONを使用する。
 */
class ServerMonitorMessageCodecTest {

    @Test
    fun `monitor_subscribeはtypeと間隔を含めて符号化される`() {
        val encoded =
            ServerManagementMessageCodec.encode(
                ServerManagementRequest.MonitorSubscribe(
                    intervalTicks = 40,
                    nonce = "m-1"
                )
            )

        assertTrue(encoded.contains("\"type\":\"monitor_subscribe\""), encoded)
        assertTrue(encoded.contains("\"intervalTicks\":40"), encoded)
    }

    @Test
    fun `間隔を省略したmonitor_subscribeを符号化できる`() {
        val encoded =
            ServerManagementMessageCodec.encode(
                ServerManagementRequest.MonitorSubscribe()
            )

        assertTrue(encoded.contains("\"type\":\"monitor_subscribe\""), encoded)
    }

    @Test
    fun `monitor_unsubscribeを符号化できる`() {
        val encoded =
            ServerManagementMessageCodec.encode(
                ServerManagementRequest.MonitorUnsubscribe()
            )

        assertTrue(
            encoded.contains("\"type\":\"monitor_unsubscribe\""),
            encoded
        )
    }

    @Test
    fun `monitor_subscriptionを復号できる`() {
        val result =
            ServerManagementMessageCodec.decode(
                "{\"type\":\"monitor_subscription\",\"subscribed\":true," +
                        "\"intervalTicks\":40,\"nonce\":\"m-1\"}"
            )

        val success =
            assertIs<ServerManagementDecodeResult.Success>(result)

        assertEquals(
            ServerManagementResponse.MonitorSubscription(
                subscribed = true,
                intervalTicks = 40,
                nonce = "m-1"
            ),
            success.message
        )
    }

    @Test
    fun `購読停止の応答は間隔を持たない`() {
        val result =
            ServerManagementMessageCodec.decode(
                "{\"type\":\"monitor_subscription\",\"subscribed\":false," +
                        "\"nonce\":\"m-2\"}"
            )

        val success =
            assertIs<ServerManagementDecodeResult.Success>(result)

        assertEquals(
            ServerManagementResponse.MonitorSubscription(
                subscribed = false,
                intervalTicks = null,
                nonce = "m-2"
            ),
            success.message
        )
    }

    @Test
    fun `monitor_updateをModの構造どおり復号できる`() {
        val result =
            ServerManagementMessageCodec.decode(FULL_UPDATE)

        val success =
            assertIs<ServerManagementDecodeResult.Success>(result)

        val update =
            assertIs<ServerManagementResponse.MonitorUpdate>(success.message)

        assertEquals(20.0, update.server.ticksPerSecond)
        assertEquals(1, update.server.onlinePlayerCount)
        assertEquals(20, update.server.maxPlayerCount)
        assertEquals(92L, update.server.uptimeSeconds)

        assertEquals(1, update.server.worlds.size)
        assertEquals("minecraft:overworld", update.server.worlds.first().id)
        assertEquals(887, update.server.worlds.first().entityCount)
        assertEquals(2209, update.server.worlds.first().loadedChunkCount)

        assertEquals(46, update.jvm.threadCount)
        assertEquals(17163091968L, update.jvm.heapMaxBytes)
        assertEquals(1, update.jvm.gc.size)
        assertEquals("G1 Young Generation", update.jvm.gc.first().name)
        assertEquals(21L, update.jvm.gc.first().count)
    }

    @Test
    fun `サーバーが省略した値はnullまたは空として復号する`() {
        val result =
            ServerManagementMessageCodec.decode(MINIMAL_UPDATE)

        val success =
            assertIs<ServerManagementDecodeResult.Success>(result)

        val update =
            assertIs<ServerManagementResponse.MonitorUpdate>(success.message)

        assertEquals(null, update.jvm.heapMaxBytes)
        assertEquals(null, update.jvm.maxMemoryBytes)
        assertEquals(emptyList(), update.jvm.gc)
        assertEquals(emptyList(), update.server.worlds)
    }

    private companion object {
        const val FULL_UPDATE =
            "{\"type\":\"monitor_update\"," +
                    "\"server\":{" +
                    "\"ticksPerSecond\":20.0,\"millisPerTick\":1.42," +
                    "\"currentTick\":1845,\"uptimeSeconds\":92," +
                    "\"onlinePlayerCount\":1,\"maxPlayerCount\":20," +
                    "\"worlds\":[{\"id\":\"minecraft:overworld\"," +
                    "\"entityCount\":887,\"loadedChunkCount\":2209," +
                    "\"playerCount\":1}]}," +
                    "\"jvm\":{" +
                    "\"heapUsedBytes\":442457008,\"heapCommittedBytes\":528482304," +
                    "\"heapMaxBytes\":17163091968,\"nonHeapUsedBytes\":195566080," +
                    "\"totalMemoryBytes\":528482304,\"freeMemoryBytes\":80248736," +
                    "\"maxMemoryBytes\":17163091968,\"threadCount\":46," +
                    "\"gc\":[{\"name\":\"G1 Young Generation\"," +
                    "\"count\":21,\"totalTimeMillis\":213}]}}"

        const val MINIMAL_UPDATE =
            "{\"type\":\"monitor_update\"," +
                    "\"server\":{" +
                    "\"ticksPerSecond\":20.0,\"millisPerTick\":0.1," +
                    "\"currentTick\":1,\"uptimeSeconds\":0," +
                    "\"onlinePlayerCount\":0,\"maxPlayerCount\":20}," +
                    "\"jvm\":{" +
                    "\"heapUsedBytes\":1,\"heapCommittedBytes\":2," +
                    "\"nonHeapUsedBytes\":3,\"totalMemoryBytes\":4," +
                    "\"freeMemoryBytes\":5,\"threadCount\":6}}"
    }
}
