package io.github.sushiericworkspace.sushiericservermanager.update

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppVersionTest {
    @Test
    fun `実行時の版はgradle_propertiesのappVersionと一致する`() {
        val properties = Properties().apply {
            File("gradle.properties").reader(Charsets.UTF_8).use(::load)
        }

        assertEquals(properties.getProperty("appVersion"), AppVersion.CURRENT)
    }

    @Test
    fun `実行時の版はX_Y_Z形式である`() {
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(AppVersion.CURRENT), AppVersion.CURRENT)
    }
}
