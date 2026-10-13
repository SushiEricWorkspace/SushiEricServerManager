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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * ローカルに名前の記録がないUUIDの公開プロフィールを取得します。
 * 通信失敗や未登録UUIDはnullとして扱い、同じUUIDの要求と結果を共有します。
 */
internal class PlayerNameLookup(
    private val fetchProfile: (String) -> String? = ::fetchMojangProfile
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val names = ConcurrentHashMap<String, CompletableFuture<String?>>()
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "history-player-names").apply { isDaemon = true }
    }

    /** 背景で名前を取得します。通信はこのインスタンス内で順次実行し、重複要求は同じ結果を返します。 */
    fun lookupAsync(uuid: String): CompletableFuture<String?> {
        val normalized = runCatching { UUID.fromString(uuid).toString().replace("-", "") }.getOrNull()
            ?: return CompletableFuture.completedFuture(null)
        return names.computeIfAbsent(normalized) {
            CompletableFuture.supplyAsync({ fetchName(normalized) }, executor)
        }
    }

    /** JavaFX Application Thread以外から、キャッシュされた名前または取得結果を待ちます。 */
    fun lookup(uuid: String): String? = lookupAsync(uuid).join()

    /** 画面終了時に未完了の名前要求を破棄します。終了後は再利用しません。 */
    override fun close() {
        executor.shutdownNow()
        names.values.forEach { it.cancel(false) }
    }

    private fun fetchName(normalized: String): String? {
        val name = runCatching {
            val body = fetchProfile(normalized) ?: return@runCatching null
            val profile = Json.parseToJsonElement(body).jsonObject
            if (profile["id"]?.jsonPrimitive?.contentOrNull != normalized) return@runCatching null
            profile["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        }.onFailure { logger.warn("公開プロフィールからプレイヤー名を取得できませんでした: uuid={}", normalized, it) }
            .getOrNull()
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
