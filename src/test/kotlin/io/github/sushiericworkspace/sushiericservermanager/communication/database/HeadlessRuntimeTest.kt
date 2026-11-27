package io.github.sushiericworkspace.sushiericservermanager.communication.database

import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import kotlin.test.Test
import kotlin.test.assertEquals

/** task runnerで物理画面を使用しないことを、property指定だけでなく実装実体から確認します。 */
class HeadlessRuntimeTest {
    @Test
    @EnabledIfSystemProperty(named = "postgresPortHeadlessValidation", matches = "true")
    fun `JavaFXは同梱Headless実装で起動する`() {
        PreviewCanvasFxTest.startFx()
        PreviewCanvasFxTest.fx {
            val application = Class.forName("com.sun.glass.ui.Application").getMethod("GetApplication").invoke(null)
            assertEquals("com.sun.glass.ui.headless.HeadlessApplication", application.javaClass.name)
        }
    }
}
