package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentKey
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentSource
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.SavedUploadOperation
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.SavedUploadProgress
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.SavedUploadState
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.SavedUploadStep
import javafx.animation.Animation
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.paint.Color
import io.github.sushiericworkspace.sushiericservermanager.config.AppTheme
import io.github.sushiericworkspace.sushiericservermanager.ui.theme.ThemeManager
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javafx.scene.control.Label
import javafx.scene.control.ButtonBase
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.event.Event
import javafx.stage.WindowEvent
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Stage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationViewTest {
    private val state get() = EditorSession.notifications

    @Test fun `分割した機能の結果は画面遷移後も共通枠に残り旧Viewは購読を解放する`() {
        startFx()
        fx {
            fun editor(target: String): Pair<Parent, MainController> {
                val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
                val root = loader.load<Parent>()
                val controller = loader.getController<MainController>()
                val scope = state.scope("fixture255", "通知検証", target)
                controller.notificationScope = { scope }
                return root to controller
            }
            val (firstRoot, first) = editor("A")
            val (secondRoot, second) = editor("B")
            val scene = Utility.createScene(AppScreen.HOME, 1300.0, 800.0, HBox(firstRoot, secondRoot))
            val previous = scene.root as AppFrame
            val stage = Stage().apply { this.scene = scene; show() }
            val initialTheme = ThemeManager.current
            try {
                first.showOperation("Aを保存中")
                second.showOperation("Bを検証中")
                assertTrue(text(previous).contains("Aを保存中"))
                assertTrue(text(previous).contains("Bを検証中"))
                first.finishOperation()
                first.showTimedTopLabel("Aを保存しました")
                assertTrue(text(previous).contains("Aを保存しました"))
                assertTrue(text(previous).contains("Bを検証中"))
                for (theme in AppTheme.entries) {
                    assertTrue(ThemeManager.select(theme))
                    previous.applyCss(); previous.layout()
                    val row = (previous.lookup("#applicationNotifications") as VBox).children.filterIsInstance<HBox>()
                        .single { it.children.filterIsInstance<Label>().any { label -> label.text.contains("Aを保存しました") } }
                    val label = row.children.filterIsInstance<Label>().single()
                    assertTrue(contrast(label.textFill as Color, row.background.fills.first().fill as Color) >= 4.5,
                        "$theme の結果通知が背景と区別できません")
                    System.getProperty("ui255.snapshotDirectory")?.let { directory ->
                        val image = previous.snapshot(null, null)
                        val output = BufferedImage(image.width.toInt(), image.height.toInt(), BufferedImage.TYPE_INT_ARGB)
                        for (y in 0 until output.height) for (x in 0 until output.width) output.setRGB(x, y, image.pixelReader.getArgb(x, y))
                        val path = Path.of(directory, "notification-${theme.name.lowercase()}.png")
                        Files.createDirectories(path.parent)
                        assertTrue(ImageIO.write(output, "png", path.toFile()))
                    }
                }
                val before = text(previous)
                stage.scene = Utility.createScene(AppScreen.HOME, 1300.0, 800.0, VBox(Label("別画面")))
                val next = stage.scene.root as AppFrame
                assertTrue(text(next).contains("Aを保存しました"))
                assertTrue(text(next).contains("Bを検証中"))
                second.finishOperation()
                second.showTimedTopLabel("Bの検証を完了しました")
                assertTrue(text(next).contains("Bの検証を完了しました"))
                assertFalse(text(next).contains("Bを検証中"))
                assertEquals(before, text(previous))
                assertEquals(Animation.Status.STOPPED, timer(previous).status)
            } finally { first.finishOperation(); second.finishOperation(); ThemeManager.select(initialTheme); stage.close() }
        }
    }

    @Test fun `送信のUNKNOWNは同要求の確認済み結果を読戻すまでBannerに残る`() {
        startFx()
        fx {
            val source = EditorDocumentSource.Server("fixture255", "fixture255")
            val key = EditorDocumentKey(source, "item", "A")
            val unknown = SavedUploadOperation("fixture-request255", source, emptyList(), emptyList(),
                listOf(SavedUploadStep(key, "before", "after", state = SavedUploadState.UNKNOWN)))
            val scene = Utility.createScene(AppScreen.HOME, 1000.0, 700.0, VBox())
            val frame = scene.root as AppFrame
            val stage = Stage().apply { this.scene = scene; show() }
            try {
                val sending = SavedUploadProgress(stage, "fixture255")
                sending.show()
                sending.attach(unknown)
                sending.update(unknown)
                sending.finish(listOf(unknown))
                assertTrue(text(frame).contains("結果不明"))
                assertFalse(state.snapshot().any { it.scope.target == unknown.id && it.progress != null })
                val confirmed = unknown.copy(steps = unknown.steps.map { it.copy(state = SavedUploadState.CONFIRMED) })
                val readback = SavedUploadProgress(stage, "fixture255")
                readback.show()
                readback.attach(confirmed)
                readback.finish(listOf(confirmed))
                assertFalse(state.snapshot().any { it.scope.target == unknown.id && it.key == "upload:${unknown.id}" })
                assertTrue(text(frame).contains("全件の反映を確認しました"))
            } finally { stage.close() }
        }
    }

    @Test fun `Window非表示で期限timerと購読を解除し再表示時に一度だけ登録する`() {
        startFx()
        fx {
            val before = listenerCount()
            val frame = AppFrame(AppScreen.HOME, VBox())
            val stage = Stage().apply { scene = Scene(frame); show() }
            try {
                assertEquals(before + 1, listenerCount())
                assertEquals(Animation.Status.RUNNING, timer(frame).status)
                stage.hide()
                assertEquals(before, listenerCount())
                assertEquals(Animation.Status.STOPPED, timer(frame).status)
                stage.show()
                assertEquals(before + 1, listenerCount())
            } finally { stage.close() }
            assertEquals(before, listenerCount())
        }
    }

    @Test fun `取消buttonとWindowの閉じる要求は直ちに同じ取消待ちを表示する`() {
        startFx()
        fx {
            val scene = Utility.createScene(AppScreen.HOME, 1000.0, 700.0, VBox())
            val frame = scene.root as AppFrame
            val stage = Stage().apply { this.scene = scene; show() }
            try {
                for (closeWindow in listOf(false, true)) {
                    val source = EditorDocumentSource.Server("fixture255", "fixture255")
                    val operation = SavedUploadOperation("fixture-cancel255-$closeWindow", source, emptyList(), emptyList(),
                        listOf(SavedUploadStep(EditorDocumentKey(source, "item", "A"), "before", "after")))
                    val progress = SavedUploadProgress(stage, "fixture255")
                    progress.show(); progress.attach(operation)
                    val dialog = SavedUploadProgress::class.java.getDeclaredField("dialog").apply { isAccessible = true }.get(progress) as Dialog<*>
                    if (closeWindow) {
                        val window = dialog.dialogPane.scene.window
                        Event.fireEvent(window, WindowEvent(window, WindowEvent.WINDOW_CLOSE_REQUEST))
                    } else (dialog.dialogPane.lookupButton(ButtonType.CANCEL) as ButtonBase).fire()
                    assertTrue(progress.cancelled)
                    assertTrue(dialog.isShowing)
                    assertTrue((dialog.dialogPane.content as Label).text.contains("取消を要求"))
                    assertTrue(text(frame).contains("取消要求済み・進行中の反映確認"))
                    progress.finish(listOf(operation.copy(steps = operation.steps.map { it.copy(state = SavedUploadState.CANCELLED) })))
                    state.resolve(state.scope("fixture255", "アップロード", operation.id), "upload:${operation.id}")
                }
            } finally { stage.close() }
        }
    }

    private fun contrast(first: Color, second: Color): Double {
        fun luminance(color: Color): Double {
            fun linear(value: Double) = if (value <= 0.04045) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
            return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
        }
        val a = luminance(first); val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun text(frame: AppFrame): String = (frame.lookup("#applicationNotifications") as VBox).children
        .filterIsInstance<HBox>().flatMap { it.children.filterIsInstance<Label>() }.joinToString("\n") { it.text }
    private fun timer(frame: AppFrame): Timeline = AppFrame::class.java.getDeclaredField("refresh")
        .apply { isAccessible = true }.get(frame) as Timeline
    private fun listenerCount(): Int = (NotificationState::class.java.getDeclaredField("listeners")
        .apply { isAccessible = true }.get(state) as Set<*>).size
    private fun startFx() {
        val ready = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
        catch (_: IllegalStateException) { Platform.runLater { Platform.setImplicitExit(false); ready.countDown() } }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
    }
    private fun <T> fx(action: () -> T): T {
        val task = FutureTask(action)
        Platform.runLater(task)
        return task.get(20, TimeUnit.SECONDS)
    }
}
