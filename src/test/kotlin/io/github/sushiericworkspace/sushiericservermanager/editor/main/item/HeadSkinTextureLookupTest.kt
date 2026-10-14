package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.HeadSkinSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * プレイヤー名とUUIDからのテクスチャ値取得を検証します。
 */
class HeadSkinTextureLookupTest {

    @Test
    fun `プレイヤー名からテクスチャ値を取得できる`() {
        val client = FakeHttpClient(
            mapOf(
                nameUrl("SushiEric") to ok(nameBody(PROFILE_ID)),
                profileUrl(PROFILE_ID) to ok(profileBody(TEXTURE))
            )
        )

        val result = MojangHeadSkinTextureLookup(client)
            .lookup(HeadSkinSource.PLAYER_NAME, "SushiEric")

        assertEquals(
            HeadSkinTextureLookupResult.Success(TEXTURE),
            result
        )
    }

    @Test
    fun `ハイフンなしUUIDからテクスチャ値を取得できる`() {
        val client = FakeHttpClient(
            mapOf(profileUrl(PROFILE_ID) to ok(profileBody(TEXTURE)))
        )

        val result = MojangHeadSkinTextureLookup(client)
            .lookup(HeadSkinSource.PLAYER_UUID, PROFILE_ID)

        assertEquals(
            HeadSkinTextureLookupResult.Success(TEXTURE),
            result
        )
    }

    @Test
    fun `ハイフンありUUIDからテクスチャ値を取得できる`() {
        val client = FakeHttpClient(
            mapOf(profileUrl(PROFILE_ID) to ok(profileBody(TEXTURE)))
        )

        val result = MojangHeadSkinTextureLookup(client)
            .lookup(HeadSkinSource.PLAYER_UUID, DASHED_PROFILE_ID)

        assertEquals(
            HeadSkinTextureLookupResult.Success(TEXTURE),
            result
        )
    }

    @Test
    fun `UUID指定では名前解決を行わない`() {
        val client = FakeHttpClient(
            mapOf(profileUrl(PROFILE_ID) to ok(profileBody(TEXTURE)))
        )

        MojangHeadSkinTextureLookup(client)
            .lookup(HeadSkinSource.PLAYER_UUID, PROFILE_ID)

        assertEquals(
            listOf(profileUrl(PROFILE_ID)),
            client.requestedUrls
        )
    }

    @Test
    fun `存在しないプレイヤー名は失敗になる`() {
        val client = FakeHttpClient(
            mapOf(nameUrl("Unknown") to HeadSkinHttpResponse(204, ""))
        )

        val result = MojangHeadSkinTextureLookup(client)
            .lookup(HeadSkinSource.PLAYER_NAME, "Unknown")

        val failure =
            assertIs<HeadSkinTextureLookupResult.Failure>(result)

        assertTrue(failure.message.contains("見つかりません"))
    }

    @Test
    fun `UUIDとして解釈できない値は失敗になる`() {
        val client = FakeHttpClient(emptyMap())

        val result = MojangHeadSkinTextureLookup(client)
            .lookup(HeadSkinSource.PLAYER_UUID, "not-a-uuid")

        val failure =
            assertIs<HeadSkinTextureLookupResult.Failure>(result)

        assertTrue(failure.message.contains("UUID"))
        assertTrue(client.requestedUrls.isEmpty())
    }

    @Test
    fun `空の入力は通信せず失敗になる`() {
        val client = FakeHttpClient(emptyMap())

        val result = MojangHeadSkinTextureLookup(client)
            .lookup(HeadSkinSource.PLAYER_NAME, "   ")

        assertIs<HeadSkinTextureLookupResult.Failure>(result)
        assertTrue(client.requestedUrls.isEmpty())
    }

    @Test
    fun `レート制限は専用のメッセージになる`() {
        val client = FakeHttpClient(
            mapOf(nameUrl("SushiEric") to HeadSkinHttpResponse(429, ""))
        )

        val result = MojangHeadSkinTextureLookup(client)
            .lookup(HeadSkinSource.PLAYER_NAME, "SushiEric")

        val failure =
            assertIs<HeadSkinTextureLookupResult.Failure>(result)

        assertTrue(failure.message.contains("レート制限"))
    }

    @Test
    fun `通信失敗は例外にせず失敗を返す`() {
        val client = HeadSkinHttpClient {
            throw java.io.IOException("接続できません")
        }

        val result = MojangHeadSkinTextureLookup(client)
            .lookup(HeadSkinSource.PLAYER_NAME, "SushiEric")

        val failure =
            assertIs<HeadSkinTextureLookupResult.Failure>(result)

        assertTrue(failure.message.contains("通信"))
    }

    @Test
    fun `テクスチャを持たないプロフィールは失敗になる`() {
        val client = FakeHttpClient(
            mapOf(
                profileUrl(PROFILE_ID) to
                    ok("""{"id":"$PROFILE_ID","name":"SushiEric","properties":[]}""")
            )
        )

        val result = MojangHeadSkinTextureLookup(client)
            .lookup(HeadSkinSource.PLAYER_UUID, PROFILE_ID)

        val failure =
            assertIs<HeadSkinTextureLookupResult.Failure>(result)

        assertTrue(failure.message.contains("テクスチャ"))
    }

    @Test
    fun `解釈できない応答は失敗になる`() {
        val client = FakeHttpClient(
            mapOf(profileUrl(PROFILE_ID) to ok("not json"))
        )

        val result = MojangHeadSkinTextureLookup(client)
            .lookup(HeadSkinSource.PLAYER_UUID, PROFILE_ID)

        assertIs<HeadSkinTextureLookupResult.Failure>(result)
    }

    @Test
    fun `テクスチャ指定では取得しない`() {
        val client = FakeHttpClient(emptyMap())

        val result = MojangHeadSkinTextureLookup(client)
            .lookup(HeadSkinSource.TEXTURE, TEXTURE)

        assertIs<HeadSkinTextureLookupResult.Failure>(result)
        assertTrue(client.requestedUrls.isEmpty())
    }

    private fun ok(body: String) = HeadSkinHttpResponse(200, body)

    private fun nameUrl(name: String) =
        "https://api.mojang.com/users/profiles/minecraft/$name"

    private fun profileUrl(id: String) =
        "https://sessionserver.mojang.com/session/minecraft/profile/$id"

    private fun nameBody(id: String) =
        """{"id":"$id","name":"SushiEric"}"""

    private fun profileBody(texture: String) =
        """{"id":"$PROFILE_ID","name":"SushiEric",""" +
            """"properties":[{"name":"textures","value":"$texture"}]}"""

    /** 事前に用意した応答だけを返すテスト用の通信実装です。 */
    private class FakeHttpClient(
        private val responses: Map<String, HeadSkinHttpResponse>
    ) : HeadSkinHttpClient {

        val requestedUrls = mutableListOf<String>()

        override fun get(url: String): HeadSkinHttpResponse {
            requestedUrls.add(url)

            return responses[url]
                ?: error("想定外のURLです: $url")
        }
    }

    private companion object {
        const val PROFILE_ID = "5bc9fd7d3a9c4025b332a7fd8fc9b142"
        const val DASHED_PROFILE_ID = "5bc9fd7d-3a9c-4025-b332-a7fd8fc9b142"
        const val TEXTURE = "ewogICJ0aW1lc3RhbXAiIDogMSB9"
    }
}
