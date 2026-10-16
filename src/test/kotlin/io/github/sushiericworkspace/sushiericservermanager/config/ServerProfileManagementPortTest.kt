package io.github.sushiericworkspace.sushiericservermanager.config

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * profiles.jsonのmanagementPortが読み書きできることを検証する。
 */
class ServerProfileManagementPortTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `managementPortを読み込む`() {
        val config =
            json.decodeFromString(
                ServerConfig.serializer(),
                CONFIG_WITH_PORT
            )

        assertEquals(25581, config.list.single().managementPort)
        assertEquals(25581, config.list.single().resolvedManagementPort())
    }

    @Test
    fun `managementPortが無い場合は既定値を使う`() {
        val config =
            json.decodeFromString(
                ServerConfig.serializer(),
                CONFIG_WITHOUT_PORT
            )

        assertEquals(
            ServerProfile.DEFAULT_MANAGEMENT_PORT,
            config.list.single().managementPort
        )
    }

    @Test
    fun `範囲外のmanagementPortは丸める`() {
        assertEquals(1, profile(managementPort = 0).resolvedManagementPort())
        assertEquals(
            65535,
            profile(managementPort = 99999).resolvedManagementPort()
        )
    }

    private fun profile(managementPort: Int): ServerProfile =
        ServerProfile(
            name = "test",
            host = "127.0.0.1",
            port = 22,
            user = "user",
            path = "/tmp",
            key = "key",
            managementPort = managementPort
        )

    private companion object {
        const val CONFIG_WITH_PORT =
            "{\"list\":[{\"name\":\"claude\",\"host\":\"127.0.0.1\"," +
                    "\"port\":22,\"user\":\"ryuma\",\"path\":\"C:/run\"," +
                    "\"key\":\"C:/key\",\"authenticationType\":\"EXISTING_PRIVATE_KEY\"," +
                    "\"generatedKey\":false,\"keyFormat\":null," +
                    "\"remoteOperatingSystem\":\"WINDOWS\"," +
                    "\"managementPort\":25581}]}"

        const val CONFIG_WITHOUT_PORT =
            "{\"list\":[{\"name\":\"claude\",\"host\":\"127.0.0.1\"," +
                    "\"port\":22,\"user\":\"ryuma\",\"path\":\"C:/run\"," +
                    "\"key\":\"C:/key\"}]}"
    }
}
