package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import io.github.sushiericworkspace.sushiericservermanager.config.AppSettingsManager
import io.github.sushiericworkspace.sushiericservermanager.config.WindowSize
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.geometry.Rectangle2D
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.stage.WindowEvent
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowSizingTest {
    @Test fun `作業領域より大きい最小値でも制約を逆転させない`() {
        val result = fitWindowSize(1560.0, 900.0, 850.0, 600.0, Double.MAX_VALUE, Double.MAX_VALUE,
            Rectangle2D(-960.0, 40.0, 800.0, 500.0))
        assertEquals(WindowSizeLimits(800.0, 500.0, 800.0, 500.0, 800.0, 500.0), result)
    }

    @Test fun `画面が大きい場合は要求した通常サイズと制約を保つ`() {
        val result = fitWindowSize(900.0, 650.0, 640.0, 420.0, 1100.0, 800.0,
            Rectangle2D(0.0, 0.0, 1920.0, 1040.0))
        assertEquals(WindowSizeLimits(900.0, 650.0, 640.0, 420.0, 1100.0, 800.0), result)
    }

    @Test fun `負座標の画面と画面外位置から作業領域を選べる`() {
        val left = Rectangle2D(-1280.0, 0.0, 1280.0, 720.0)
        val right = Rectangle2D(0.0, 40.0, 1920.0, 1040.0)
        assertEquals(left, selectWindowScreen(Rectangle2D(-1000.0, 60.0, 800.0, 600.0), listOf(right, left)))
        assertEquals(right, selectWindowScreen(Rectangle2D(4000.0, 200.0, 800.0, 600.0), listOf(right, left)))
    }

    @Test
    @EnabledOnOs(OS.WINDOWS, OS.MAC)
    fun `装飾付きStageの表示と再表示で外寸制約と既存イベントを維持する`() {
        lateinit var stage: Stage
        var hidden = 0
        onFx {
            stage = Stage().apply {
                Screen.getScreens().forEach { screen ->
                    println("Screen bounds=${screen.bounds} visualBounds=${screen.visualBounds} dpi=${screen.dpi} outputScale=${screen.outputScaleX}x${screen.outputScaleY}")
                }
                title = "WindowSizing #292 検証"
                scene = Scene(StackPane(), 6000.0, 4000.0)
                minWidth = 4000.0
                minHeight = 3000.0
                setOnHidden { hidden++ }
                WindowSizing.install(this)
                WindowSizing.install(this)
                show()
            }
        }
        try {
            settle()
            onFx {
                val bounds = Screen.getPrimary().visualBounds
                assertTrue(stage.width <= bounds.width + 1.0)
                assertTrue(stage.height <= bounds.height + 1.0)
                assertTrue(stage.minWidth <= stage.maxWidth)
                assertTrue(stage.minHeight <= stage.maxHeight)
                stage.x = -50000.0
                stage.y = -50000.0
            }
            settle()
            onFx {
                val screens = Screen.getScreens().map { it.visualBounds }
                assertTrue(screens.any { it.contains(stage.x, stage.y, stage.width - 1.0, stage.height - 1.0) })
                stage.hide()
                stage.show()
            }
            settle()
        } finally {
            onFx { stage.close() }
        }
        assertEquals(2, hidden)
    }

    @Test
    @EnabledOnOs(OS.WINDOWS, OS.MAC)
    fun `共通Scene入口でScene流用と取消を妨げない`() {
        lateinit var stage: Stage
        onFx {
            stage = Stage().apply {
                scene = Utility.createScene(
                    AppScreen.WIDGETS_ONLY, 620.0, 500.0, StackPane())
                setOnCloseRequest { it.consume() }
                show()
            }
        }
        try {
            settle()
            onFx {
                stage.fireEvent(WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST))
                assertTrue(stage.isShowing)
                stage.scene = Utility.createScene(
                    AppScreen.WIDGETS_ONLY, 420.0, 140.0, StackPane())
            }
            settle()
            onFx { assertTrue(stage.isShowing) }
        } finally {
            onFx { stage.close() }
        }
    }


    @Test
    @EnabledOnOs(OS.WINDOWS, OS.MAC)
    fun `標準DialogのStageにも外寸制約を適用する`() {
        lateinit var dialog: Dialog<ButtonType>
        onFx {
            dialog = Dialog<ButtonType>().apply {
                title = "WindowSizing #292 Dialog検証"
                dialogPane.prefWidth = 6000.0
                dialogPane.prefHeight = 4000.0
                dialogPane.buttonTypes.add(ButtonType.CANCEL)
                WindowSizing.install(this)
                show()
            }
        }
        try {
            settle()
            onFx {
                val stage = dialog.dialogPane.scene.window as Stage
                val bounds = Screen.getPrimary().visualBounds
                assertTrue(stage.width <= bounds.width + 1.0)
                assertTrue(stage.height <= bounds.height + 1.0)
            }
        } finally {
            onFx { dialog.close() }
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS, OS.MAC)
    fun `最大化解除を維持し通常サイズの変更と最小化解除を妨げない`() {
        lateinit var stage: Stage
        var width = 0.0
        var height = 0.0
        onFx {
            stage = Stage().apply {
                scene = Scene(StackPane(), 620.0, 420.0)
                WindowSizing.install(this)
                show()
            }
        }
        try {
            settle()
            onFx { width = stage.width; height = stage.height; stage.isMaximized = true }
            settle()
            onFx { assertTrue(stage.isMaximized); stage.isMaximized = false }
            Thread.sleep(400)
            settle()
            onFx {
                assertTrue(!stage.isMaximized)
                stage.width = width
                stage.height = height
            }
            settle()
            onFx {
                assertTrue(!stage.isMaximized)
                assertEquals(width, stage.width, 2.0)
                assertEquals(height, stage.height, 2.0)
                stage.isIconified = true
            }
            settle()
            onFx { stage.isIconified = false }
            settle()
            onFx {
                assertTrue(!stage.isIconified)
                assertTrue(!stage.isMaximized)
                assertEquals(width, stage.width, 2.0)
                assertEquals(height, stage.height, 2.0)
            }
        } finally {
            onFx { stage.close() }
        }
    }

    @Test fun `小さい画面から大きい画面へ移る際に元の制約を再利用できる`() {
        val small = fitWindowSize(1000.0, 800.0, 850.0, 600.0, 1600.0, 1000.0,
            Rectangle2D(0.0, 0.0, 800.0, 500.0))
        val large = fitWindowSize(small.width, small.height, 850.0, 600.0, 1600.0, 1000.0,
            Rectangle2D(800.0, 0.0, 1920.0, 1040.0))
        assertEquals(850.0, large.minWidth)
        assertEquals(600.0, large.minHeight)
        assertEquals(1600.0, large.maxWidth)
        assertEquals(1000.0, large.maxHeight)
    }


    @Test
    @EnabledOnOs(OS.WINDOWS, OS.MAC)
    fun `接続済みの各画面へ移動し再表示と最大化解除で同じ画面へ収まる`() {
        lateinit var stage: Stage
        lateinit var screens: List<Rectangle2D>
        onFx {
            screens = Screen.getScreens().map { it.visualBounds }
            stage = Stage().apply {
                scene = Scene(StackPane(), 620.0, 420.0)
                WindowSizing.install(this)
                show()
            }
        }
        try {
            for (bounds in screens) {
                onFx { stage.x = bounds.minX + 40.0; stage.y = bounds.minY + 40.0 }
                settle()
                onFx {
                    assertTrue(bounds.contains(stage.x, stage.y, stage.width, stage.height))
                    stage.hide()
                    stage.show()
                }
                settle()
                onFx { stage.isMaximized = true }
                settle()
                onFx { stage.isMaximized = false }
                awaitContained(stage, bounds)
                onFx { println("移動・再show・最大化解除 成功: $bounds") }
            }
        } finally {
            onFx { stage.close() }
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS, OS.MAC)
    fun `未確定外寸に制約を書かず選択FXMLの初回自動配置を維持する`() {
        val settings = AppSettingsManager.load()
        try {
            for (screen in listOf(AppScreen.MODE_SELECT, AppScreen.SERVER_SELECT)) {
                for (saved in listOf(false, true)) {
                    lateinit var stage: Stage
                    onFx {
                        AppSettingsManager.save(settings.copy(windowSizes = if (saved)
                            mapOf(screen.name to WindowSize(720.0, 510.0)) else emptyMap()))
                        stage = Stage().apply {
                            scene = Utility.createScene(screen)
                            addEventHandler(WindowEvent.WINDOW_SHOWING) {
                                assertTrue(x.isNaN() && y.isNaN())
                                if (!saved) {
                                    assertTrue(width.isNaN() && height.isNaN())
                                    assertEquals(Double.MAX_VALUE, maxWidth)
                                    assertEquals(Double.MAX_VALUE, maxHeight)
                                }
                            }
                            show()
                        }
                    }
                    try {
                        settle()
                        onFx {
                            val bounds = Screen.getPrimary().visualBounds
                            assertEquals(bounds.minX + (bounds.width - stage.width) / 2, stage.x, 3.0)
                            assertEquals(bounds.minY + (bounds.height - stage.height) / 3, stage.y, 3.0)
                            assertTrue(bounds.contains(stage.x, stage.y, stage.width, stage.height))
                            if (saved) {
                                assertEquals(720.0, stage.width, 2.0)
                                assertEquals(510.0, stage.height, 2.0)
                            }
                            println("初回配置 screen=$screen saved=$saved bounds=$bounds actual=(${stage.x},${stage.y},${stage.width},${stage.height})")
                        }
                    } finally { onFx { stage.close() } }
                }
            }
        } finally { onFx { AppSettingsManager.save(settings) } }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS, OS.MAC)
    fun `明示した初回座標と移動後の再表示座標を維持する`() {
        lateinit var stage: Stage
        lateinit var bounds: Rectangle2D
        onFx { bounds = Screen.getPrimary().visualBounds }
        val x = bounds.minX + 70.0
        val y = bounds.minY + 80.0
        onFx {
            stage = Stage().apply {
                scene = Utility.createScene(AppScreen.WIDGETS_ONLY, 420.0, 280.0, StackPane())
                this.x = x
                this.y = y
                show()
            }
        }
        try {
            settle()
            onFx {
                assertEquals(x, stage.x, 2.0)
                assertEquals(y, stage.y, 2.0)
                stage.x += 40.0
                stage.y += 30.0
                stage.hide()
                stage.show()
            }
            settle()
            onFx {
                assertEquals(x + 40.0, stage.x, 2.0)
                assertEquals(y + 30.0, stage.y, 2.0)
            }
        } finally { onFx { stage.close() } }
    }

    private fun awaitContained(stage: Stage, bounds: Rectangle2D) {
        var lastEvent: Long? = null
        val changed = InvalidationListener { lastEvent = System.nanoTime() }
        onFx {
            listOf(stage.xProperty(), stage.yProperty(), stage.widthProperty(), stage.heightProperty(),
                stage.maximizedProperty(), stage.iconifiedProperty()).forEach { it.addListener(changed) }
        }
        val started = System.nanoTime()
        val deadline = started + TimeUnit.SECONDS.toNanos(3)
        var stableSince: Long? = null
        var details = "Stageを採取していません"
        try {
            while (System.nanoTime() < deadline) {
                var inside = false
                var sampledAt = 0L
                var lastChangedAt = 0L
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
                onFx(remaining) {
                    sampledAt = System.nanoTime()
                    lastChangedAt = lastEvent ?: started
                    inside = !stage.isMaximized && !stage.isIconified &&
                        bounds.contains(stage.x, stage.y, stage.width, stage.height)
                    val elapsed = lastEvent?.let { TimeUnit.NANOSECONDS.toMillis(sampledAt - it) }
                    details = "expected=$bounds actual=(${stage.x},${stage.y},${stage.width},${stage.height}) " +
                        "scene=(${stage.scene.width},${stage.scene.height}) maximized=${stage.isMaximized} " +
                        "iconified=${stage.isIconified} min=(${stage.minWidth},${stage.minHeight}) " +
                        "max=(${stage.maxWidth},${stage.maxHeight}) lastEventMs=${elapsed ?: "観測なし"}"
                }
                if (inside) {
                    val stable = stableSince ?: sampledAt
                    stableSince = stable
                    if (sampledAt - stable.coerceAtLeast(lastChangedAt) >= TimeUnit.MILLISECONDS.toNanos(200)) return
                } else stableSince = null
                Thread.sleep(20)
            }
            throw AssertionError("3秒以内に200ms連続で作業領域へ収まりませんでした: $details")
        } catch (error: java.util.concurrent.TimeoutException) {
            throw AssertionError("作業領域のFX読取が期限を超えました: $details", error)
        } finally {
            onFx {
                listOf(stage.xProperty(), stage.yProperty(), stage.widthProperty(), stage.heightProperty(),
                    stage.maximizedProperty(), stage.iconifiedProperty()).forEach { it.removeListener(changed) }
            }
        }
    }

    private fun settle() { Thread.sleep(350); onFx {} }

    private fun onFx(timeoutMillis: Long = 30000, action: () -> Unit) {
        val started = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); started.countDown() } }
        catch (_: IllegalStateException) { started.countDown() }
        assertTrue(started.await(20, TimeUnit.SECONDS))
        val task = FutureTask(action)
        Platform.runLater(task)
        task.get(timeoutMillis, TimeUnit.MILLISECONDS)
    }
}
