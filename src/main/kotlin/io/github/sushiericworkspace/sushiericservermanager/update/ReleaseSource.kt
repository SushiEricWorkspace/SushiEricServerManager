package io.github.sushiericworkspace.sushiericservermanager.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * GitHubのReleases APIが返すReleaseのうち、更新に使う項目です。
 *
 * @property tagName タグ。`v`を先頭に付けた版（`v0.3.0`など）。
 * @property htmlUrl ReleaseのWebページ。自動更新できない場合の案内に使う。
 * @property body Releaseの本文。変更内容として表示する。
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val body: String? = null,
    val assets: List<GitHubReleaseAsset> = emptyList()
)

/**
 * Releaseに添付された成果物です。
 *
 * @property digest GitHubが付ける`sha256:<16進数64桁>`形式のハッシュ。付いていない成果物ではnull。
 */
@Serializable
data class GitHubReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
    val digest: String? = null
)

/** 最新のReleaseを取得する手段です。通信を伴うため、JavaFX Application Threadでは呼び出しません。 */
fun interface ReleaseSource {
    /** 最新のReleaseを返します。取得または解析に失敗した場合は例外を送出します。 */
    fun fetchLatest(): GitHubRelease
}

/**
 * GitHubのReleases APIから最新のReleaseを取得します。
 *
 * 認証は使いません。待ち時間は接続と応答のそれぞれで[TIMEOUT]です。
 */
class GitHubReleaseSource(
    private val latestReleaseUrl: String = LATEST_RELEASE_URL
) : ReleaseSource {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun fetchLatest(): GitHubRelease {
        val request = HttpRequest.newBuilder(URI.create(latestReleaseUrl))
            .timeout(TIMEOUT)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SushiEricServerManager/${AppVersion.CURRENT}")
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "最新のReleaseを取得できません: HTTP ${response.statusCode()}" }
        return json.decodeFromString(response.body())
    }

    companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/SushiEricWorkspace/SushiEricServerManager/releases/latest"

        /** 最新のReleaseの取得で待つ時間。確認に失敗しても起動は続けるため、短くしている。 */
        val TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}
