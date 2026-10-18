package io.github.sushiericworkspace.sushiericservermanager.config

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppSettingsThemeTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `themeのない既存設定は既存値を維持してDARKになる`() {
        val settings = json.decodeFromString(AppSettings.serializer(), """{"monitorIntervalTicks":40,"sidebarDirectoryExpanded":{"item":{"sample":true}},"showPlayerUuidInHistory":true}""")
        assertEquals(AppTheme.DARK, settings.theme)
        assertEquals(40, settings.monitorIntervalTicks)
        assertEquals(mapOf("item" to mapOf("sample" to true)), settings.sidebarDirectoryExpanded)
        assertEquals(true, settings.showPlayerUuidInHistory)
    }

    @Test
    fun `LIGHTの保存と再読込では他項目を維持する`() {
        val original = AppSettings(40, mapOf("ore" to mapOf("group" to false)), true)
        val changed = original.copy(theme = AppTheme.LIGHT)
        val reloaded = json.decodeFromString(AppSettings.serializer(), json.encodeToString(AppSettings.serializer(), changed))
        assertEquals(changed, reloaded)
        assertEquals(original, reloaded.copy(theme = AppTheme.DARK))
    }

    @Test
    fun `未知themeは既存JSONハンドラと同じデコード失敗になる`() {
        assertFailsWith<SerializationException> {
            json.decodeFromString(AppSettings.serializer(), """{"theme":"UNKNOWN"}""")
        }
    }
}
