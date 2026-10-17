package io.github.sushiericworkspace.sushiericservermanager.ui.dialog

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.result.ValidationResult
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.control.Button
import javafx.scene.control.TextField
import javafx.stage.Modality
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.util.Duration
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ValidatedInputDialogTest {
    @Test
    @EnabledOnOs(OS.WINDOWS, OS.MAC)
    fun `実EditorFXMLの入力は各画面のowner中心と端に収まり入力中位置と決定と取消を保つ`() {
        onFx {
            for (bounds in Screen.getScreens().map { it.visualBounds }) {
                for (atEdge in listOf(false, true)) {
                    val loader = FXMLLoader(AppScreen::class.java.getResource(AppScreen.BASE.fxml!!))
                    val root = loader.load<Parent>()
                    val controller = loader.getController<MainController>()
                    val owner = Stage().apply {
                        scene = Utility.createScene(AppScreen.BASE,
                            if (atEdge) 180.0 else 850.0, if (atEdge) 120.0 else 600.0, root)
                        x = bounds.minX + 80.0
                        y = bounds.minY + 60.0
                        show()
                        if (atEdge) {
                            x = bounds.maxX - width
                            y = bounds.maxY - height
                        }
                    }
                    try {
                        for (cancel in listOf(false, true)) {
                            var failure: Throwable? = null
                            var dialog: Stage? = null
                            fun checkDialog(action: () -> Unit) {
                                try { action() } catch (error: Throwable) {
                                    failure = error
                                    val shown = dialog
                                    if (shown != null) shown.close()
                                    else Stage.getWindows().filterIsInstance<Stage>()
                                        .filter { it !== owner }.toList().forEach { it.close() }
                                }
                            }
                            PauseTransition(Duration.millis(350.0)).apply {
                                setOnFinished {
                                    checkDialog {
                                        val shown = Stage.getWindows().filterIsInstance<Stage>()
                                            .single { it.title == "データを追加 #303" }
                                        dialog = shown
                                        assertSame(owner, shown.owner)
                                        assertEquals(Modality.APPLICATION_MODAL, shown.modality)
                                        val expectedX = (owner.x + (owner.width - shown.width) / 2)
                                            .coerceIn(bounds.minX, max(bounds.minX, bounds.maxX - shown.width))
                                        val expectedY = (owner.y + (owner.height - shown.height) / 2)
                                            .coerceIn(bounds.minY, max(bounds.minY, bounds.maxY - shown.height))
                                        assertEquals(expectedX, shown.x, 3.0)
                                        assertEquals(expectedY, shown.y, 3.0)
                                        assertTrue(bounds.contains(shown.x, shown.y, shown.width, shown.height))
                                        shown.x = bounds.minX + 60.0
                                        shown.y = bounds.minY + 60.0
                                        val x = shown.x
                                        val y = shown.y
                                        val input = shown.scene.lookup(".text-field") as TextField
                                        val buttons = shown.scene.root.lookupAll(".button").filterIsInstance<Button>()
                                        val confirm = buttons.single { it.text == "決定" }
                                        val back = buttons.single { it.text == "キャンセル" }
                                        input.text = "invalid"
                                        assertTrue(confirm.isDisable)
                                        PauseTransition(Duration.millis(250.0)).apply {
                                            setOnFinished {
                                                checkDialog {
                                                    assertEquals(x, shown.x, 3.0)
                                                    assertEquals(y, shown.y, 3.0)
                                                    input.text = "new_data"
                                                    assertTrue(!confirm.isDisable)
                                                    println("入力Dialog bounds=$bounds edge=$atEdge cancel=$cancel owner=(${owner.x},${owner.y},${owner.width},${owner.height}) actual=(${shown.x},${shown.y},${shown.width},${shown.height})")
                                                    if (cancel) back.fire() else confirm.fire()
                                                }
                                            }
                                            play()
                                        }
                                    }
                                }
                                play()
                            }
                            val result = controller.requestInput("データを追加 #303") {
                                if (it == "invalid") ValidationResult.Error("名前を確認してください。".repeat(8))
                                else ValidationResult.Success
                            }
                            failure?.let { throw it }
                            if (cancel) assertNull(result) else assertEquals("new_data", result)
                        }
                    } finally { owner.close() }
                }
            }
        }
    }

    private fun onFx(action: () -> Unit) {
        val started = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); started.countDown() } }
        catch (_: IllegalStateException) { started.countDown() }
        assertTrue(started.await(20, TimeUnit.SECONDS))
        val task = FutureTask(action)
        Platform.runLater(task)
        task.get(30, TimeUnit.SECONDS)
    }
}
