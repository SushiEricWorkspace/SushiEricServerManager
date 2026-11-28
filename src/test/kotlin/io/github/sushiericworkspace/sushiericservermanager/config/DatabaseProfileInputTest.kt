package io.github.sushiericworkspace.sushiericservermanager.config

import io.github.sushiericworkspace.sushiericservermanager.communication.database.postgresReference
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.test.*

class DatabaseProfileInputTest {
    private val json = Json { encodeDefaults = true }

    private fun valid(): JsonObject {
        val reference = postgresReference()
        val profile = ServerProfile("fixture", "localhost", 22, "fixture", "/world", "", profileId = "fixture",
            databaseHistory = DatabaseHistoryHost(reference.status.state.binding.worldUuid, postgres = reference))
        return json.encodeToJsonElement(ServerConfig.serializer(), ServerConfig(listOf(profile))).jsonObject
    }

    private fun withHost(config: JsonObject, transform: (JsonObject) -> JsonObject): JsonObject {
        val profile = config.getValue("list").jsonArray.single().jsonObject
        val host = profile.getValue("databaseHistory").jsonObject
        return JsonObject(config + ("list" to JsonArray(listOf(JsonObject(profile + ("databaseHistory" to transform(host)))))))
    }

    private fun withProfilesFile(block: (java.io.File) -> Unit) {
        val root = Path.of(requireNotNull(System.getProperty("sushieric.test.configRoot"))).toAbsolutePath().normalize()
        val file = FilePath.SERVER_PROFILES.toFile()
        check(file.toPath().toAbsolutePath().normalize().startsWith(root))
        val original = file.takeIf { it.exists() }?.readBytes()
        file.parentFile.mkdirs()
        try { block(file) } finally {
            if (original == null) file.delete() else file.writeBytes(original)
        }
    }

    @Test fun actualLoaderRejectsExtraDatabaseFieldsAndPreservesInput() = withProfilesFile { file ->
        val valid = valid()
        for (field in listOf("jdbcUrl", "conninfo", "role", "passfile", "password", "properties")) {
            val value = if (field == "properties") buildJsonObject { put("password", "injected") } else JsonPrimitive("injected")
            val changed = withHost(valid) { host ->
                JsonObject(host + ("postgres" to JsonObject(host.getValue("postgres").jsonObject + (field to value))))
            }.toString()
            file.writeText(changed)
            assertEquals(ServerConfig(), SettingConfigManager.load(), field)
            assertEquals(ServerConfig(), SettingConfigManager.loadRepaired(), field)
            assertEquals(changed, file.readText(), field)
        }
        val extraHost = withHost(valid) { JsonObject(it + ("properties" to JsonObject(emptyMap()))) }.toString()
        file.writeText(extraHost)
        assertEquals(ServerConfig(), SettingConfigManager.load())
        assertEquals(extraHost, file.readText())
    }

    @Test fun actualLoaderPreservesForwardCompatibilityOutsideDatabaseSettings() = withProfilesFile { file ->
        val valid = valid()
        val profile = valid.getValue("list").jsonArray.single().jsonObject
        val future = JsonObject(valid + ("futureRoot" to JsonPrimitive("allowed")) + ("list" to JsonArray(listOf(
            JsonObject(profile + ("futureProfile" to JsonPrimitive("allowed")))))))
        file.writeText(future.toString())
        val loaded = SettingConfigManager.loadRepaired()
        assertEquals(1, loaded.list.size)
        assertNotNull(loaded.list.single().databaseHistory).reference()
        assertTrue(SettingConfigManager.saveAndVerify(loaded))
        assertEquals(loaded, SettingConfigManager.load())
        file.writeText("""{"list":[{"name":"legacy","host":"localhost","port":22,"user":"fixture","path":"/world","key":"","future":true}]}""")
        assertNull(SettingConfigManager.load().list.single().databaseHistory)
    }
}
