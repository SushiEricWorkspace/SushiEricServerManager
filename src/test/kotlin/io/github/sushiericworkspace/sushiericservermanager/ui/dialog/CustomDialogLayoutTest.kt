package io.github.sushiericworkspace.sushiericservermanager.ui.dialog

import io.github.sushiericworkspace.sushiericservermanager.config.AppSettingsManager
import io.github.sushiericworkspace.sushiericservermanager.config.AppTheme
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.awaitFx
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.export
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.fx
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.startFx
import io.github.sushiericworkspace.sushiericservermanager.ui.theme.ThemeManager
import javafx.application.ColorScheme
import javafx.application.Platform
import javafx.scene.control.Button
import javafx.scene.control.DialogPane
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HeaderBar
import javafx.scene.layout.VBox
import javafx.scene.text.Text
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.stage.Window
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.*

@EnabledOnOs(OS.WINDOWS, OS.MAC)
class CustomDialogLayoutTest {
    @Test
    fun `保存確認は配色変更に追従してボタン全文と本文の余白を保つ`() {
        startFx()
        val previous = AppSettingsManager.load()
        val originalTheme = fx { ThemeManager.current }
        var shown: Stage? = null
        val extra = fx { Label("編集・保存予定: 新規レシピ") }
        val task = FutureTask {
            CustomDialog.confirmation().title("保存確認 #262")
                .content("差分内容").scrollableContent()
                .additionalContent(VBox(extra)).okButton("すべて保存").show()
        }
        try {
            fx { ThemeManager.select(AppTheme.DARK); Platform.runLater(task) }
            awaitFx {
                shown = Window.getWindows().filterIsInstance<Stage>().singleOrNull { it.title == "保存確認 #262" }
                shown != null
            }
            for (theme in listOf(AppTheme.LIGHT, AppTheme.DARK)) fx {
                assertTrue(ThemeManager.select(theme))
                val stage = assertNotNull(shown)
                val pane = stage.scene.root as DialogPane
                pane.applyCss(); pane.layout()
                assertEquals(StageStyle.DECORATED, stage.style)
                assertEquals(if (theme == AppTheme.LIGHT) ColorScheme.LIGHT else ColorScheme.DARK, stage.scene.preferences.colorScheme)
                assertEquals(if (theme == AppTheme.LIGHT) ColorScheme.LIGHT else ColorScheme.DARK,
                    HeaderBar.getSystemColorScheme(stage))
                val button = pane.lookupAll(".button").filterIsInstance<Button>().single { it.text == "すべて保存" }
                assertEquals(button.text, (button.lookup(".text") as Text).text)
                assertTrue(button.width >= button.prefWidth(-1.0) - 1.0)
                val scroll = pane.content as ScrollPane
                val body = scroll.content as VBox
                assertTrue(body.padding.left >= 12.0 && body.padding.right >= 12.0)
                assertTrue(extra.localToScene(extra.layoutBounds).minX >=
                    body.localToScene(body.layoutBounds).minX + 12.0)
                export(stage.scene.snapshot(null), "save-dialog-$theme")
            }
            fx {
                val pane = shown!!.scene.root as DialogPane
                val cancel = pane.buttonTypes.single { it.buttonData.isCancelButton }
                (pane.lookupButton(cancel) as Button).fire()
            }
            assertFalse(task.get(10, TimeUnit.SECONDS))
        } finally {
            fx {
                shown?.close()
                ThemeManager.select(originalTheme)
                AppSettingsManager.save(previous)
            }
        }
    }
}
