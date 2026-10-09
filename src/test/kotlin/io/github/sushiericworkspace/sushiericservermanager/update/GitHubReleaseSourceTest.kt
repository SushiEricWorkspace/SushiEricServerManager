package io.github.sushiericworkspace.sushiericservermanager.update

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitHubReleaseSourceTest {
    private val responseJson = """
        {
          "url": "https://api.github.com/repos/example/releases/1",
          "tag_name": "v0.3.0",
          "html_url": "https://github.com/example/releases/tag/v0.3.0",
          "body": "変更内容",
          "draft": false,
          "assets": [
            {
              "name": "SushiEricServerManager-0.3.0-Windows-Installer.exe",
              "size": 84845056,
              "browser_download_url": "https://github.com/example/releases/download/v0.3.0/installer.exe",
              "digest": "sha256:${"b".repeat(64)}",
              "uploader": {"login": "someone"}
            },
            {
              "name": "no-digest.dmg",
              "browser_download_url": "https://github.com/example/releases/download/v0.3.0/no-digest.dmg"
            }
          ]
        }
    """.trimIndent()

    private inline fun withServer(status: Int, body: String, block: (String, MutableMap<String, String?>) -> Unit) {
        val headers = mutableMapOf<String, String?>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/latest") { exchange ->
            headers["Accept"] = exchange.requestHeaders.getFirst("Accept")
            headers["User-Agent"] = exchange.requestHeaders.getFirst("User-Agent")
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/latest", headers)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `Releases APIの応答から更新に使う項目を読み取る`() = withServer(200, responseJson) { url, headers ->
        val release = GitHubReleaseSource(url).fetchLatest()

        assertEquals("v0.3.0", release.tagName)
        assertEquals("https://github.com/example/releases/tag/v0.3.0", release.htmlUrl)
        assertEquals("変更内容", release.body)
        assertEquals(2, release.assets.size)
        val installer = release.assets.first()
        assertEquals("SushiEricServerManager-0.3.0-Windows-Installer.exe", installer.name)
        assertEquals(84845056L, installer.size)
        assertEquals("sha256:${"b".repeat(64)}", installer.digest)
        assertEquals(null, release.assets.last().digest)
        assertEquals("application/vnd.github+json", headers["Accept"])
        assertTrue(headers["User-Agent"].orEmpty().startsWith("SushiEricServerManager/"))
    }

    @Test
    fun `HTTPの状態が200以外の場合は例外になる`() = withServer(403, "{}") { url, _ ->
        val error = assertFailsWith<IllegalStateException> { GitHubReleaseSource(url).fetchLatest() }

        assertTrue("403" in error.message.orEmpty())
    }
}
