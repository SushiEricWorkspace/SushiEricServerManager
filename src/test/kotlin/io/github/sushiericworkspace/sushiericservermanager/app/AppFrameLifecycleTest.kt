package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.ButtonBase
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuBar
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.scene.layout.HeaderBar
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppFrameLifecycleTest {
    @Test
    fun `表示済StageのScene差替後も状態が更新され準備中の接続操作は無効になる`() {
        startFx()
        val modeField = EditorSession::class.java.getDeclaredField("mode").apply { isAccessible = true }
        val previousMode = fx { modeField.get(null) }
        var stage: Stage? = null
        try {
            val original = fx {
                modeField.set(null, null)
                val frame = AppFrame(AppScreen.MODE_SELECT, VBox(Label("選択画面")))
                stage = Stage().apply { scene = Scene(frame); show() }
                frame
            }
            val previousText = fx { (original.lookup("#connectionStatus") as Label).text }
            val preparing = fx {
                val frame = AppFrame(AppScreen.STARTUP_PREPARING, VBox(Label("準備中")))
                stage!!.scene = Scene(frame)
                assertTrue((frame.lookup("#changeMode") as Button).isDisabled)
                assertTrue((frame.lookup("#disconnectSession") as Button).isDisabled)
                val serverMenu = (frame.lookup("#applicationMenu") as MenuBar).menus[3]
                assertTrue(serverMenu.items[1].isDisable)
                assertTrue(serverMenu.items[2].isDisable)
                modeField.set(null, AppMode.OFFLINE)
                frame
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!fx { (preparing.lookup("#connectionStatus") as Label).text.contains("ローカル書込可") }) {
                assertTrue(System.nanoTime() < deadline, "表示済Stageへ付けたSceneの接続状態が更新されません")
                Thread.sleep(30)
            }
            fx { assertEquals(previousText, (original.lookup("#connectionStatus") as Label).text) }
        } finally {
            fx { stage?.close(); modeField.set(null, previousMode) }
        }
    }

    @Test
    fun `500pxの外枠でも上部操作と標準ボタン予約が重ならず接続状態を折り返せる`() {
        startFx()
        val stage = fx {
            Stage().apply {
                scene = Utility.createScene(AppScreen.MODE_SELECT, customRoot = VBox(Label("幅の検証")))
                show()
                width = 500.0
            }
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!fx { stage.width <= 500.5 && stage.scene.width in 470.0..500.5 }) {
                assertTrue(System.nanoTime() < deadline, "500pxへ縮小できません")
                Thread.sleep(30)
            }
            fx {
                val root = stage.scene.root
                root.applyCss()
                root.layout()
                val header = root.lookup("#applicationHeader") as HeaderBar
                val name = root.lookup("#applicationName") as Label
                val settings = root.lookup("#applicationSettings") as ButtonBase
                val details = root.lookup("#connectionDetails") as Button
                val status = root.lookup("#connectionStatus") as Label
                fun bounds(node: javafx.scene.Node) = node.localToScene(node.layoutBounds)
                assertTrue(header.width <= stage.scene.width + 1.0)
                assertTrue(bounds(name).minX >= HeaderBar.getLeftSystemInset(stage).width - 1.0)
                assertTrue(bounds(name).maxX <= bounds(settings).minX + 1.0)
                assertTrue(bounds(settings).maxX <= stage.scene.width - HeaderBar.getRightSystemInset(stage).width + 1.0)
                if (!System.getProperty("os.name").startsWith("Mac")) {
                    assertTrue(bounds(root.lookup("#applicationMenu")).maxX <= bounds(name).minX + 1.0)
                }
                assertTrue(details.width >= details.prefWidth(-1.0) - 1.0)
                assertTrue(bounds(status).maxX <= bounds(details).minX + 1.0)
                assertTrue(status.isWrapText)
                assertTrue(status.height >= status.prefHeight(status.width) - 1.0)
                listOf("SSH:", "API:", "MC:", "TPS:").forEach { assertTrue(status.text.contains(it)) }
            }
        } finally { fx { stage.close() } }
    }

    private fun startFx() {
        val ready = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
        catch (_: IllegalStateException) { Platform.runLater { Platform.setImplicitExit(false); ready.countDown() } }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
    }

    private fun <T> fx(action: () -> T): T {
        val task = FutureTask(action)
        Platform.runLater(task)
        return task.get(10, TimeUnit.SECONDS)
    }
}
