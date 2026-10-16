package io.github.sushiericworkspace.sushiericservermanager.config

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * profiles.jsonのconsoleLogLevelが読み書きできることを検証する。
 */
class ServerProfileConsoleLogLevelTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `consoleLogLevelを読み込む`() {
        val config =
            json.decodeFromString(
                ServerConfig.serializer(),
                CONFIG_WITH_LEVEL
            )

        assertEquals("DEBUG", config.list.single().consoleLogLevel)
    }

    @Test
    fun `consoleLogLevelが無い場合は既定値を使う`() {
        val config =
            json.decodeFromString(
                ServerConfig.serializer(),
                CONFIG_WITHOUT_LEVEL
            )

        assertEquals(
            ServerProfile.DEFAULT_CONSOLE_LOG_LEVEL,
            config.list.single().consoleLogLevel
        )
    }

    @Test
    fun `既定値はINFOとする`() {
        assertEquals("INFO", ServerProfile.DEFAULT_CONSOLE_LOG_LEVEL)
    }

    @Test
    fun `対象のプロファイルだけ表示レベルを差し替える`() {
        val profiles =
            listOf(
                profile("first", "INFO"),
                profile("second", "INFO"),
                profile("third", "INFO")
            )

        val replaced =
            replaceServerProfilePreservingOrder(
                profiles = profiles,
                originalName = "second",
                replacement = profile("second", "TRACE")
            )

        assertEquals(listOf("first", "second", "third"), replaced.map { it.name })
        assertEquals(
            listOf("INFO", "TRACE", "INFO"),
            replaced.map { it.consoleLogLevel }
        )
    }

    private fun profile(
        name: String,
        consoleLogLevel: String
    ): ServerProfile =
        ServerProfile(
            name = name,
            host = "127.0.0.1",
            port = 22,
            user = "user",
            path = "/tmp",
            key = "key",
            consoleLogLevel = consoleLogLevel
        )

    private companion object {
        const val CONFIG_WITH_LEVEL =
            "{\"list\":[{\"name\":\"claude\",\"host\":\"127.0.0.1\"," +
                    "\"port\":22,\"user\":\"ryuma\",\"path\":\"C:/run\"," +
                    "\"key\":\"C:/key\",\"consoleLogLevel\":\"DEBUG\"}]}"

        const val CONFIG_WITHOUT_LEVEL =
            "{\"list\":[{\"name\":\"claude\",\"host\":\"127.0.0.1\"," +
                    "\"port\":22,\"user\":\"ryuma\",\"path\":\"C:/run\"," +
                    "\"key\":\"C:/key\"}]}"
    }
}
