package io.github.sushiericworkspace.sushiericservermanager.communication.management

import io.github.sushiericworkspace.sushiericservermanager.communication.management.codec.ServerManagementDecodeResult
import io.github.sushiericworkspace.sushiericservermanager.communication.management.codec.ServerManagementMessageCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Management APIメッセージの符号化と復号を検証する。
 *
 * SushiEricServerMod側の`type`と一致することを確認する。
 */
class ServerManagementMessageCodecTest {

    @Test
    fun `pingはtypeを含めて符号化される`() {
        val encoded =
            ServerManagementMessageCodec.encode(
                ServerManagementRequest.Ping(nonce = "n-1")
            )

        assertTrue(encoded.contains("\"type\":\"ping\""), encoded)
        assertTrue(encoded.contains("\"nonce\":\"n-1\""), encoded)
    }

    @Test
    fun `command_executeを符号化できる`() {
        val encoded = ServerManagementMessageCodec.encode(
            ServerManagementRequest.CommandExecute(
                command = "say hello",
                nonce = "execute-1"
            )
        )

        assertTrue(encoded.contains("\"type\":\"command_execute\""), encoded)
        assertTrue(encoded.contains("\"command\":\"say hello\""), encoded)
        assertTrue(encoded.contains("\"nonce\":\"execute-1\""), encoded)
    }

    @Test
    fun `command_completeをカーソル位置とともに符号化できる`() {
        val encoded = ServerManagementMessageCodec.encode(
            ServerManagementRequest.CommandComplete(
                command = "gamemode cre",
                cursor = 12,
                nonce = "complete-1"
            )
        )

        assertTrue(encoded.contains("\"type\":\"command_complete\""), encoded)
        assertTrue(encoded.contains("\"cursor\":12"), encoded)
        assertTrue(encoded.contains("\"nonce\":\"complete-1\""), encoded)
    }

    @Test
    fun `実績履歴要求と取得方法を含む応答を変換できる`() {
        val request = ServerManagementMessageCodec.encode(
            ServerManagementRequest.AchievementHistoryRequest(
                playerUuid = "11111111-1111-1111-1111-111111111111",
                nonce = "history-1"
            )
        )
        assertTrue(request.contains("\"type\":\"achievement_history_request\""), request)

        val result = ServerManagementMessageCodec.decode(
            """{"type":"achievement_history","success":true,"playerUuid":"11111111-1111-1111-1111-111111111111","entries":[{"achievementId":"monster_hunter_1","achievedAt":"2026-10-13T01:00:00Z","source":"admin","executor":{"uuid":"22222222-2222-2222-2222-222222222222","name":"管理者"}}],"nonce":"history-1"}"""
        )
        val response = assertIs<ServerManagementDecodeResult.Success>(result).message
        val history = assertIs<ServerManagementResponse.AchievementHistory>(response)
        assertEquals("history-1", history.nonce)
        assertEquals("admin", history.entries.single().source)
        assertEquals("管理者", history.entries.single().executor?.name)
    }

    @Test
    fun `pongを復号できる`() {
        val result =
            ServerManagementMessageCodec.decode(
                """{"type":"pong","nonce":"n-1"}"""
            )

        val success =
            assertIs<ServerManagementDecodeResult.Success>(result)

        assertEquals(
            ServerManagementResponse.Pong(nonce = "n-1"),
            success.message
        )
    }

    @Test
    fun `コンソール購読要求を符号化できる`() {
        val subscribe = ServerManagementMessageCodec.encode(
            ServerManagementRequest.ConsoleSubscribe
        )
        val unsubscribe = ServerManagementMessageCodec.encode(
            ServerManagementRequest.ConsoleUnsubscribe
        )

        assertTrue(subscribe.contains("\"type\":\"console_subscribe\""), subscribe)
        assertTrue(unsubscribe.contains("\"type\":\"console_unsubscribe\""), unsubscribe)
    }

    @Test
    fun `コンソールログを復号できる`() {
        val result = ServerManagementMessageCodec.decode(
            """{"type":"console_log","timestamp":"2026-09-09T01:02:03Z","level":"INFO","message":"Server started"}"""
        )

        val success = assertIs<ServerManagementDecodeResult.Success>(result)
        assertEquals(
            ServerManagementResponse.ConsoleLog(
                timestamp = "2026-09-09T01:02:03Z",
                level = "INFO",
                message = "Server started"
            ),
            success.message
        )
    }

    @Test
    fun `nonceを持たないpongを復号できる`() {
        val result =
            ServerManagementMessageCodec.decode("""{"type":"pong"}""")

        val success =
            assertIs<ServerManagementDecodeResult.Success>(result)

        assertEquals(
            ServerManagementResponse.Pong(nonce = null),
            success.message
        )
    }

    @Test
    fun `command_resultを復号できる`() {
        val result = ServerManagementMessageCodec.decode(
            """{"type":"command_result","success":true,"returnValue":1,"output":["実行しました"],"nonce":"execute-1"}"""
        )

        val success = assertIs<ServerManagementDecodeResult.Success>(result)
        assertEquals(
            ServerManagementResponse.CommandResult(
                success = true,
                returnValue = 1,
                output = listOf("実行しました"),
                nonce = "execute-1"
            ),
            success.message
        )
    }

    @Test
    fun `command_complete_resultを置換範囲とともに復号できる`() {
        val result = ServerManagementMessageCodec.decode(
            """{"type":"command_complete_result","suggestions":[{"text":"creative","start":9,"end":12,"tooltip":"ゲームモード"}],"nonce":"complete-1"}"""
        )

        val success = assertIs<ServerManagementDecodeResult.Success>(result)
        assertEquals(
            ServerManagementResponse.CommandCompleteResult(
                suggestions = listOf(
                    ServerManagementCommandSuggestion(
                        text = "creative",
                        start = 9,
                        end = 12,
                        tooltip = "ゲームモード"
                    )
                ),
                nonce = "complete-1"
            ),
            success.message
        )
    }

    @Test
    fun `errorを復号できる`() {
        val result =
            ServerManagementMessageCodec.decode(
                """{"type":"error","reason":"unknown_type","detail":"詳細"}"""
            )

        val success =
            assertIs<ServerManagementDecodeResult.Success>(result)

        assertEquals(
            ServerManagementResponse.Error(
                reason = "unknown_type",
                detail = "詳細"
            ),
            success.message
        )
    }

    @Test
    fun `サーバー側が追加したフィールドは無視する`() {
        val result =
            ServerManagementMessageCodec.decode(
                """{"type":"pong","nonce":"n-2","future":1}"""
            )

        val success =
            assertIs<ServerManagementDecodeResult.Success>(result)

        assertEquals(
            ServerManagementResponse.Pong(nonce = "n-2"),
            success.message
        )
    }

    @Test
    fun `未対応の種別は失敗として返す`() {
        val result =
            ServerManagementMessageCodec.decode(
                """{"type":"console_log","message":"hello"}"""
            )

        assertIs<ServerManagementDecodeResult.Failure>(result)
    }

    @Test
    fun `JSONとして解釈できない場合は失敗として返す`() {
        assertIs<ServerManagementDecodeResult.Failure>(
            ServerManagementMessageCodec.decode("これはJSONではない")
        )
    }

    @Test
    fun `JSONオブジェクト以外は失敗として返す`() {
        listOf("\"pong\"", "[]", "1", "null").forEach { text ->
            assertIs<ServerManagementDecodeResult.Failure>(
                ServerManagementMessageCodec.decode(text),
                text
            )
        }
    }

    @Test
    fun `送信側の種別は受信側として復号できない`() {
        assertIs<ServerManagementDecodeResult.Failure>(
            ServerManagementMessageCodec.decode("""{"type":"ping"}""")
        )
    }
}
