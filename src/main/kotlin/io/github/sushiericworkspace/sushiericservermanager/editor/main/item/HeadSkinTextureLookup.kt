package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.HeadSkinSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

/** テクスチャ値の取得結果です。 */
sealed interface HeadSkinTextureLookupResult {

    /**
     * 取得に成功した結果です。
     *
     * @property texture base64のテクスチャ値。
     */
    data class Success(val texture: String) : HeadSkinTextureLookupResult

    /**
     * 取得に失敗した結果です。
     *
     * @property message 画面へ表示する日本語メッセージ。
     */
    data class Failure(val message: String) : HeadSkinTextureLookupResult
}

/**
 * プレイヤー名またはUUIDからテクスチャ値を取得する契約です。
 *
 * 画面から通信処理を分離するために使用します。テストでは差し替えます。
 */
fun interface HeadSkinTextureLookup {

    /**
     * 指定方法と値からテクスチャ値を取得します。
     *
     * 通信を伴うため、JavaFXのアプリケーションスレッドから呼び出さないでください。
     *
     * @param source 入力されている指定方法。
     * @param value 指定方法に対応する入力値。
     * @return 取得結果。失敗時も例外ではなく[HeadSkinTextureLookupResult.Failure]を返します。
     */
    fun lookup(
        source: HeadSkinSource,
        value: String
    ): HeadSkinTextureLookupResult
}

/**
 * HTTP GETの応答です。
 *
 * @property statusCode HTTPステータスコード。
 * @property body 応答本文。本文がない場合は空文字。
 */
data class HeadSkinHttpResponse(
    val statusCode: Int,
    val body: String
)

/** テクスチャ取得で使用するHTTP GETの契約です。 */
fun interface HeadSkinHttpClient {

    /**
     * 指定URLへGETします。
     *
     * @param url 取得先URL。
     * @return 応答。
     */
    fun get(url: String): HeadSkinHttpResponse
}

/**
 * Mojang APIからテクスチャ値を取得します。
 *
 * ```text
 * 名前 → UUID   https://api.mojang.com/users/profiles/minecraft/<name>
 * UUID → 値     https://sessionserver.mojang.com/session/minecraft/profile/<uuid>
 * ```
 *
 * UUID指定では名前解決を行わず、プロフィール取得だけを行います。
 * 入力のUUIDはハイフンの有無どちらも受け付けます。
 *
 * 認証は不要で、取得できるのは公開プロフィールだけです。
 *
 * @property httpClient 通信の実装。既定はタイムアウト付きのHTTP GETです。
 */
class MojangHeadSkinTextureLookup(
    private val httpClient: HeadSkinHttpClient =
        DefaultHeadSkinHttpClient()
) : HeadSkinTextureLookup {

    private val json = Json { ignoreUnknownKeys = true }

    override fun lookup(
        source: HeadSkinSource,
        value: String
    ): HeadSkinTextureLookupResult {
        val input = value.trim()

        headSkinLookupInputFailure(source, value)?.let { return it }

        val profileId = when (source) {
            HeadSkinSource.PLAYER_NAME ->
                when (val resolved = resolveIdByName(input)) {
                    is HeadSkinTextureLookupResult.Failure -> return resolved
                    is HeadSkinTextureLookupResult.Success -> resolved.texture
                }

            HeadSkinSource.PLAYER_UUID ->
                normalizeUuid(input)
                    ?: return HeadSkinTextureLookupResult.Failure(
                        "UUIDとして解釈できません。"
                    )

            HeadSkinSource.TEXTURE ->
                return HeadSkinTextureLookupResult.Failure(
                    "テクスチャ指定では取得できません。"
                )
        }

        return resolveTexture(profileId)
    }

    /**
     * プレイヤー名からUUIDを解決します。
     *
     * 成功時は[HeadSkinTextureLookupResult.Success]のtextureへ
     * ハイフンなしのUUIDを入れて返します。
     */
    private fun resolveIdByName(
        name: String
    ): HeadSkinTextureLookupResult {
        val response = runCatching {
            httpClient.get("$NAME_LOOKUP_URL/$name")
        }.getOrElse {
            return HeadSkinTextureLookupResult.Failure(
                NETWORK_FAILURE_MESSAGE
            )
        }

        failureFor(response)?.let { return it }

        if (response.body.isBlank()) {
            return HeadSkinTextureLookupResult.Failure(
                PLAYER_NOT_FOUND_MESSAGE
            )
        }

        val profile = runCatching {
            json.decodeFromString<MojangProfileName>(response.body)
        }.getOrElse {
            return HeadSkinTextureLookupResult.Failure(
                RESPONSE_FAILURE_MESSAGE
            )
        }

        if (profile.id.isBlank()) {
            return HeadSkinTextureLookupResult.Failure(
                PLAYER_NOT_FOUND_MESSAGE
            )
        }

        return HeadSkinTextureLookupResult.Success(profile.id)
    }

    /** UUIDからテクスチャ値を取得します。 */
    private fun resolveTexture(
        profileId: String
    ): HeadSkinTextureLookupResult {
        val response = runCatching {
            httpClient.get("$PROFILE_LOOKUP_URL/$profileId")
        }.getOrElse {
            return HeadSkinTextureLookupResult.Failure(
                NETWORK_FAILURE_MESSAGE
            )
        }

        failureFor(response)?.let { return it }

        if (response.body.isBlank()) {
            return HeadSkinTextureLookupResult.Failure(
                PLAYER_NOT_FOUND_MESSAGE
            )
        }

        val profile = runCatching {
            json.decodeFromString<MojangProfile>(response.body)
        }.getOrElse {
            return HeadSkinTextureLookupResult.Failure(
                RESPONSE_FAILURE_MESSAGE
            )
        }

        val texture = profile.properties
            .firstOrNull { it.name == TEXTURES_PROPERTY }
            ?.value

        if (texture.isNullOrBlank()) {
            return HeadSkinTextureLookupResult.Failure(
                "テクスチャが見つかりません。"
            )
        }

        return HeadSkinTextureLookupResult.Success(texture)
    }

    /**
     * ステータスコードから共通の失敗を判定します。
     *
     * 問題がない場合はnullを返します。
     */
    private fun failureFor(
        response: HeadSkinHttpResponse
    ): HeadSkinTextureLookupResult.Failure? = when {
        response.statusCode == HTTP_NO_CONTENT ||
            response.statusCode == HTTP_NOT_FOUND ->
            HeadSkinTextureLookupResult.Failure(
                PLAYER_NOT_FOUND_MESSAGE
            )

        response.statusCode == HTTP_TOO_MANY_REQUESTS ->
            HeadSkinTextureLookupResult.Failure(
                "Mojang APIのレート制限に達しました。しばらく待ってから再試行してください。"
            )

        response.statusCode !in HTTP_OK_RANGE ->
            HeadSkinTextureLookupResult.Failure(
                "Mojang APIの応答が異常です。ステータス: ${response.statusCode}"
            )

        else -> null
    }

    private companion object {
        const val NAME_LOOKUP_URL =
            "https://api.mojang.com/users/profiles/minecraft"

        const val PROFILE_LOOKUP_URL =
            "https://sessionserver.mojang.com/session/minecraft/profile"

        const val TEXTURES_PROPERTY = "textures"

        const val HTTP_NO_CONTENT = 204
        const val HTTP_NOT_FOUND = 404
        const val HTTP_TOO_MANY_REQUESTS = 429

        val HTTP_OK_RANGE = 200..299

        const val PLAYER_NOT_FOUND_MESSAGE =
            "プレイヤーが見つかりません。"

        const val NETWORK_FAILURE_MESSAGE =
            "Mojang APIへの通信に失敗しました。"

        const val RESPONSE_FAILURE_MESSAGE =
            "Mojang APIの応答を解釈できません。"
    }
}

/**
 * タイムアウト付きでHTTP GETを行う既定実装です。
 *
 * @property timeoutMillis 接続と読み取りのタイムアウト。
 */
class DefaultHeadSkinHttpClient(
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS
) : HeadSkinHttpClient {

    override fun get(url: String): HeadSkinHttpResponse {
        val connection = URI(url)
            .toURL()
            .openConnection() as HttpURLConnection

        connection.connectTimeout = timeoutMillis
        connection.readTimeout = timeoutMillis
        connection.requestMethod = "GET"

        return try {
            val statusCode = connection.responseCode

            val stream = if (statusCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val body = stream
                ?.bufferedReader()
                ?.use { reader -> reader.readText() }
                .orEmpty()

            HeadSkinHttpResponse(statusCode, body)
        } finally {
            connection.disconnect()
        }
    }
    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5000
    }
}

@Serializable
private data class MojangProfileName(
    val id: String = "",
    val name: String = ""
)

@Serializable
private data class MojangProfile(
    val id: String = "",
    val name: String = "",
    val properties: List<MojangProfileProperty> = emptyList()
)

@Serializable
private data class MojangProfileProperty(
    val name: String = "",
    val value: String = ""
)

private val UNDASHED_UUID_PATTERN = Regex("^[0-9a-fA-F]{32}$")

/**
 * 入力UUIDをAPIで使用するハイフンなし表記へ変換します。
 *
 * 解釈できない場合はnullを返します。
 */
private fun normalizeUuid(value: String): String? {
    if (UNDASHED_UUID_PATTERN.matches(value)) {
        return value.lowercase()
    }

    return runCatching { UUID.fromString(value) }
        .getOrNull()
        ?.toString()
        ?.replace("-", "")
}

/** 通信せずに判定できる入力エラーを返します。 */
internal fun headSkinLookupInputFailure(
    source: HeadSkinSource,
    value: String
): HeadSkinTextureLookupResult.Failure? = when {
    value.isBlank() -> HeadSkinTextureLookupResult.Failure("値を入力してください。")
    source == HeadSkinSource.TEXTURE ->
        HeadSkinTextureLookupResult.Failure("テクスチャ指定では取得できません。")
    source == HeadSkinSource.PLAYER_UUID && normalizeUuid(value.trim()) == null ->
        HeadSkinTextureLookupResult.Failure("UUIDとして解釈できません。")
    else -> null
}
