package io.github.sushiericworkspace.sushiericservermanager.update

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.UpdateDialog
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** WindowsのJavaFXでホームの互換通知と必須更新ダイアログを検証します。 */
@EnabledOnOs(OS.WINDOWS)
class ManagerCompatibilityViewTest {
    @Test fun `ホームで読み取り専用と必須更新を表示しダイアログを閉じると終了を要求する`() {
        val started = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); started.countDown() } }
        catch (_: IllegalStateException) { started.countDown() }
        assertTrue(started.await(20, TimeUnit.SECONDS))
        val task = FutureTask {
            EditorSession.prepareOnlineMode()
            val field = SshManager::class.java.getDeclaredField("compatibility").apply { isAccessible = true }
            try {
                for (state in listOf(ManagerCompatibility.UpdateRequired("999.0.0"), ManagerCompatibility.Missing, ManagerCompatibility.Unreadable)) {
                    field.set(EditorSession.sshManager, state)
                    val root = FXMLLoader(javaClass.getResource(AppScreen.HOME.fxml!!)).load<Parent>()
                    Scene(root, 1000.0, 720.0)
                    root.applyCss(); root.layout()
                    val label = assertIs<Label>(root.lookup("#compatibilityLabel"))
                    assertTrue(label.isVisible)
                    assertEquals(state.message, label.text)
                    assertEquals(state is ManagerCompatibility.UpdateRequired, assertIs<Button>(root.lookup("#updateButton")).isVisible)
                }
                Platform.runLater {
                    val stage = Window.getWindows().filterIsInstance<Stage>().single { it.title == "アップデート確認" }
                    val button = stage.scene.root.lookupAll(".button").filterIsInstance<Button>().single { it.text == "アプリを終了" }
                    button.fire()
                }
                assertEquals(UpdateDialog.Outcome.EXIT, UpdateDialog.show(
                    UpdateCheckResult.Manual("999.0.0", "テスト", "https://example.invalid", "テスト用"),
                    openUrl = {}, updater = null, required = true
                ))
            } finally { EditorSession.disconnect() }
        }
        Platform.runLater(task)
        task.get(30, TimeUnit.SECONDS)
    }
}
