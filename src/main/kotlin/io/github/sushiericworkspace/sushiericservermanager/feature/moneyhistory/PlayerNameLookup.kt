package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * ローカルに名前の記録がないUUIDの公開プロフィールを取得します。
 * JavaFX Application Thread以外から呼び出し、通信失敗や未登録UUIDはnullとして扱います。
 */
internal class PlayerNameLookup(
    private val fetchProfile: (String) -> String? = ::fetchMojangProfile
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val names = mutableMapOf<String, String?>()

    /** 正規化したUUIDに対応する名前を取得し、このインスタンス内で結果をキャッシュします。 */
    fun lookup(uuid: String): String? {
        val normalized = runCatching { UUID.fromString(uuid).toString().replace("-", "") }.getOrNull()
            ?: return null
        if (names.containsKey(normalized)) return names[normalized]
        val name = runCatching {
            val body = fetchProfile(normalized) ?: return@runCatching null
            val profile = Json.parseToJsonElement(body).jsonObject
            if (profile["id"]?.jsonPrimitive?.contentOrNull != normalized) return@runCatching null
            profile["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        }.onFailure { logger.warn("公開プロフィールからプレイヤー名を取得できませんでした: uuid={}", uuid, it) }
            .getOrNull()
        names[normalized] = name
        return name
    }
}

private fun fetchMojangProfile(uuid: String): String? {
    val request = HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/$uuid"))
        .timeout(Duration.ofSeconds(6)).GET().build()
    val response = profileHttpClient.send(request, HttpResponse.BodyHandlers.ofString())
    return response.body().takeIf { response.statusCode() == 200 }
}

private val profileHttpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(6)).build()
