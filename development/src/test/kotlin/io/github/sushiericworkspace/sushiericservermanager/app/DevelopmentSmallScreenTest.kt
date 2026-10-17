package io.github.sushiericworkspace.sushiericservermanager.app

import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ScrollPane
import javafx.stage.Stage
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

@EnabledOnOs(OS.WINDOWS, OS.MAC)
class DevelopmentSmallScreenTest {
    @Test fun `管理入口の選択操作へ縮小後もスクロールで到達する`() {
        val stage = onFx {
            val root = FXMLLoader(javaClass.getResource("/fxml/development/mode-select.fxml")).load<Parent>()
            Stage().apply {
                title = "development 小画面 #293"
                scene = Scene(root, 600.0, 400.0).apply {
                    stylesheets.add(javaClass.getResource("/css/startup/mode-select.css")!!.toExternalForm())
                }
                width = 600.0
                height = 400.0
                WindowSizing.install(this)
                show()
            }
        }
        try {
            Thread.sleep(250)
            onFx {
                println("#293 development stage=${stage.width}x${stage.height} scene=${stage.scene.width}x${stage.scene.height}")
                val root = stage.scene.root as ScrollPane
                root.applyCss(); root.layout()
                assertTrue(root.content.layoutBounds.height > root.viewportBounds.height)
                root.vvalue = 1.0
                root.layout()
                val managed = root.lookup("#managedButton") as Button
                val bounds = managed.localToScene(managed.boundsInLocal)
                assertTrue(bounds.minY >= 0 && bounds.maxY <= stage.scene.height)
                managed.requestFocus()
                assertTrue(stage.scene.focusOwner == managed)
                assertTrue(root.lookup("#onlineButton") != null && root.lookup("#offlineButton") != null)
            }
        } finally { onFx { stage.close() } }
    }

    private fun <T> onFx(action: () -> T): T {
        val started = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); started.countDown() } }
        catch (_: IllegalStateException) { started.countDown() }
        assertTrue(started.await(20, TimeUnit.SECONDS))
        val task = FutureTask(action)
        Platform.runLater(task)
        return task.get(30, TimeUnit.SECONDS)
    }
}
