package io.github.sushiericworkspace.sushiericservermanager.editor.controller

import io.github.sushiericworkspace.sushiericservermanager.app.AppMode
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.app.ApplicationFlow
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorConnection
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.UpdateDialog
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import io.github.sushiericworkspace.sushiericservermanager.update.UpdateAsset
import io.github.sushiericworkspace.sushiericservermanager.update.UpdateCheckResult
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressIndicator
import javafx.stage.Stage
import javafx.stage.Window
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class RequiredUpdateViewTest {
    @Test
    fun `必須更新の待機と結果表示を確認し古いHomeや接続へ反映しない`() {
        val started = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); started.countDown() } }
        catch (_: IllegalStateException) { started.countDown() }
        assertTrue(started.await(20, TimeUnit.SECONDS))
        val compatibilityField = SshManager::class.java.getDeclaredField("compatibility").apply { isAccessible = true }
        val connectionField = EditorSession::class.java.getDeclaredField("connection").apply { isAccessible = true }
        val modeField = EditorSession::class.java.getDeclaredField("mode").apply { isAccessible = true }
        val originalUpdates = ApplicationFlow.updatesEnabled
        try {
            for (scenario in listOf("成功", "自動更新", "失敗", "切断失敗", "更新なし", "最低版未満", "終了", "再開", "画面切替", "切断", "別接続", "互換性変更", "オフライン", "開発接続")) {
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val calls = AtomicInteger()
                val shown = mutableListOf<UpdateCheckResult.Update>()
                lateinit var stage: Stage
                lateinit var button: Button
                lateinit var status: Label
                lateinit var progress: ProgressIndicator
                try {
                    fx {
                        ApplicationFlow.updatesEnabled = scenario != "開発接続"
                        EditorSession.prepareOnlineMode()
                        compatibilityField.set(EditorSession.sshManager, ManagerCompatibility.UpdateRequired("2.0.0"))
                        if (scenario == "オフライン") modeField.set(EditorSession, AppMode.OFFLINE)
                        if (scenario == "開発接続") connectionField.set(EditorSession, TestConnection())
                        val controller = HomeController(
                            checkUpdate = {
                                calls.incrementAndGet()
                                entered.countDown()
                                release.await(10, TimeUnit.SECONDS)
                                when (scenario) {
                                    "失敗", "切断失敗" -> error("テスト用の通信失敗")
                                    "更新なし" -> UpdateCheckResult.UpToDate
                                    "自動更新" -> UpdateCheckResult.Automatic(
                                        "2.0.0", "テスト", "https://example.invalid",
                                        UpdateAsset("installer", "https://example.invalid/installer", 1L, "a".repeat(64))
                                    )
                                    else -> UpdateCheckResult.Manual(
                                        if (scenario == "最低版未満") "1.5.0" else "2.0.0",
                                        "テスト", "https://example.invalid", "テスト用"
                                    )
                                }
                            },
                            showUpdate = { shown.add(it); UpdateDialog.Outcome.CONTINUE }
                        )
                        val loader = FXMLLoader(javaClass.getResource(AppScreen.HOME.fxml!!)).apply {
                            setControllerFactory { if (scenario in listOf("オフライン", "開発接続")) HomeController() else controller }
                        }
                        val root = loader.load<Parent>()
                        stage = Stage().apply {
                            scene = Scene(root, 1000.0, 720.0)
                            scene.stylesheets.add(javaClass.getResource(AppScreen.HOME.css)!!.toExternalForm())
                            show()
                        }
                        root.applyCss(); root.layout()
                        button = root.lookup("#updateButton") as Button
                        status = root.lookup("#updateCheckLabel") as Label
                        progress = root.lookup("#updateCheckProgress") as ProgressIndicator
                        button.fire(); button.fire()
                        if (scenario in listOf("オフライン", "開発接続")) {
                            assertFalse(button.isVisible)
                            assertFalse(progress.isVisible)
                        } else {
                            assertTrue(button.isDisable)
                            assertTrue(progress.isVisible)
                            assertEquals("アップデートを確認中", status.text)
                        }
                    }
                    if (scenario in listOf("オフライン", "開発接続")) {
                        assertEquals(0, calls.get())
                        continue
                    }
                    assertTrue(entered.await(10, TimeUnit.SECONDS))
                    assertEquals(1, calls.get())
                    fx {
                        when (scenario) {
                            "終了" -> stage.hide()
                            "再開" -> { stage.hide(); stage.show() }
                            "画面切替" -> stage.scene = Scene(javafx.scene.layout.VBox())
                            "切断", "切断失敗" -> EditorSession.disconnect()
                            "別接続" -> connectionField.set(EditorSession, TestConnection())
                            "互換性変更" -> compatibilityField.set(EditorSession.sshManager, ManagerCompatibility.Compatible("1.0.0"))
                        }
                    }
                    release.countDown()
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (fx { button.isDisable } && System.nanoTime() < deadline) Thread.sleep(20)
                    fx {
                        assertFalse(button.isDisable)
                        assertFalse(progress.isVisible)
                        if (scenario in listOf("成功", "自動更新")) {
                            assertEquals(1, shown.size)
                            assertEquals("アップデートの確認が完了しました。", status.text)
                        } else {
                            assertTrue(shown.isEmpty())
                            if (scenario in listOf("失敗", "更新なし", "最低版未満")) {
                                assertTrue(status.isVisible)
                                assertEquals(if (scenario == "失敗") "アップデートを確認できません。再試行してください。" else "必要な版の更新を取得できません。", status.text)
                                assertFalse(EditorSession.sshManager.compatibility.writable)
                                val dialogs = Window.getWindows().filterIsInstance<Stage>().filter { it.title == "更新が必要です" }
                                assertEquals(1, dialogs.size)
                                dialogs.forEach { it.hide() }
                            } else assertFalse(status.isVisible)
                        }
                    }
                } finally {
                    release.countDown()
                    fx { stage.hide(); connectionField.set(EditorSession, null); EditorSession.resetMode() }
                }
            }
        } finally {
            fx { ApplicationFlow.updatesEnabled = originalUpdates }
        }
    }

    private class TestConnection : EditorConnection {
        override val contextName = "テスト接続"
        override val compatibility = ManagerCompatibility.UpdateRequired("2.0.0")
        override fun open(): EditorDataService = error("通信は実行しません")
        override fun close() = Unit
        override fun abort() = Unit
    }

    private fun <T> fx(action: () -> T): T {
        val task = FutureTask(action)
        Platform.runLater(task)
        return task.get(20, TimeUnit.SECONDS)
    }
}
