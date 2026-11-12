package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.config.AppSettings
import io.github.sushiericworkspace.sushiericservermanager.config.AppSettingsManager
import io.github.sushiericworkspace.sushiericservermanager.config.WindowSize
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.application.Platform
import javafx.scene.layout.StackPane
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.stage.WindowEvent
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowSizePersistenceTest {
    @Test fun `旧設定を読み書きしても既存項目を保持する`() {
        val json = Json { encodeDefaults = true }
        val settings = json.decodeFromString<AppSettings>(
            """{"monitorIntervalTicks":53,"sidebarDirectoryExpanded":{"server":{"folder":true}},"showPlayerUuidInHistory":true}""")
        assertTrue(settings.windowSizes.isEmpty())
        val saved = settings.copy(windowSizes = mapOf("HOME" to WindowSize(810.0, 570.0, true)))
        assertEquals(saved, json.decodeFromString<AppSettings>(json.encodeToString(AppSettings.serializer(), saved)))
        assertEquals(settings, saved.copy(windowSizes = emptyMap()))
    }

    @Test fun `非正と非有限の保存寸法は復元対象にならない`() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertFalse(WindowSize(it, 500.0).isValid())
            assertFalse(WindowSize(600.0, it).isValid())
        }
        assertTrue(WindowSize(600.0, 500.0).isValid())
    }

    @Test @EnabledOnOs(OS.WINDOWS, OS.MAC)
    fun `終了時の最新設定へ対象キーだけ保存し再生成したStageへ復元する`() = withSettings {
        val first = open("FIRST")
        try {
            resize(first, 720.0, 510.0)
            onFx {
                AppSettingsManager.save(AppSettingsManager.load().copy(
                    monitorIntervalTicks = 37,
                    sidebarDirectoryExpanded = mapOf("recent" to mapOf("dir" to false)),
                    showPlayerUuidInHistory = true))
                first.close()
                val saved = AppSettingsManager.load()
                assertEquals(37, saved.monitorIntervalTicks)
                assertTrue(saved.showPlayerUuidInHistory)
                assertEquals(mapOf("recent" to mapOf("dir" to false)), saved.sidebarDirectoryExpanded)
                assertSize(720.0, 510.0, saved.windowSizes.getValue("FIRST"))
            }
            val reopened = open("FIRST")
            try {
                onFx { assertSize(720.0, 510.0, WindowSize(reopened.width, reopened.height)) }
            } finally { onFx { reopened.close() } }
        } finally { onFx { first.close() } }
    }

    @Test @EnabledOnOs(OS.WINDOWS, OS.MAC)
    fun `同じキーは最後に非表示になった寸法を採用し別キーを消さない`() = withSettings {
        val first = open("SHARED")
        val last = open("SHARED")
        val other = open("OTHER")
        try {
            resize(first, 710.0, 500.0)
            resize(last, 820.0, 540.0)
            resize(other, 690.0, 490.0)
            onFx {
                last.close(); other.close(); first.close()
                val sizes = AppSettingsManager.load().windowSizes
                assertSize(710.0, 500.0, sizes.getValue("SHARED"))
                assertSize(690.0, 490.0, sizes.getValue("OTHER"))
            }
        } finally { onFx { first.close(); last.close(); other.close() } }
    }

    @Test @EnabledOnOs(OS.WINDOWS, OS.MAC)
    fun `取消された終了は保存せず再表示しても既存イベントと通常寸法を保つ`() = withSettings {
        val stage = open("CANCEL")
        var hidden = 0
        try {
            resize(stage, 760.0, 520.0)
            onFx {
                stage.setOnHidden { hidden++ }
                WindowSizePersistence.install(stage.scene, "CANCEL")
                WindowSizePersistence.install(stage.scene, "CANCEL")
                stage.setOnCloseRequest { it.consume() }
                stage.fireEvent(WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST))
                assertTrue(stage.isShowing)
                assertFalse(AppSettingsManager.load().windowSizes.containsKey("CANCEL"))
                stage.hide()
                assertEquals(1, hidden)
                stage.show()
            }
            settle()
            onFx { assertSize(760.0, 520.0, WindowSize(stage.width, stage.height)); stage.close() }
            assertEquals(2, hidden)
        } finally { onFx { stage.close() } }
    }

    @Test @EnabledOnOs(OS.WINDOWS, OS.MAC)
    fun `準備Sceneの寸法を保存せず同じStageの再選択にも通常寸法を復元する`() = withSettings {
        val stage = open("MODE_SELECT")
        try {
            resize(stage, 750.0, 510.0)
            onFx {
                ApplicationFlow.showPreparing(stage, "検証")
                stage.width = 420.0; stage.height = 140.0
            }
            settle()
            onFx {
                assertFalse(AppSettingsManager.load().windowSizes.containsKey("MODE_SELECT"))
                stage.scene = Utility.createScene(AppScreen.MODE_SELECT, customRoot = StackPane())
            }
            settle()
            onFx {
                assertSize(750.0, 510.0, WindowSize(stage.width, stage.height))
                ApplicationFlow.showPreparing(stage, "終了")
                stage.width = 420.0; stage.height = 140.0
                stage.close()
                assertSize(750.0, 510.0, AppSettingsManager.load().windowSizes.getValue("MODE_SELECT"))
            }
        } finally { onFx { stage.close() } }
    }

    @Test @EnabledOnOs(OS.WINDOWS, OS.MAC)
    fun `最大化を保存して復元し解除と最小化の遷移外寸で通常サイズを上書きしない`() = withSettings {
        val stage = open("MAX")
        lateinit var normal: WindowSize
        try {
            resize(stage, 740.0, 520.0)
            onFx {
                normal = WindowSize(stage.width, stage.height)
                stage.isMaximized = true
            }
            settle()
            onFx {
                assertTrue(stage.isMaximized)
                stage.hide()
                stage.show()
            }
            settle()
            onFx {
                assertTrue(stage.isMaximized)
                stage.close()
                val saved = AppSettingsManager.load().windowSizes.getValue("MAX")
                assertTrue(saved.maximized)
                assertSize(normal.width, normal.height, saved)
            }
            val reopened = open("MAX")
            try {
                onFx { assertTrue(reopened.isMaximized); reopened.isMaximized = false }
                settle()
                onFx {
                    assertFalse(reopened.isMaximized)
                    assertSize(normal.width, normal.height, WindowSize(reopened.width, reopened.height))
                }
                resize(reopened, 790.0, 550.0)
                onFx { reopened.isIconified = true }
                settle()
                onFx { reopened.isIconified = false }
                settle()
                onFx {
                    assertSize(790.0, 550.0, WindowSize(reopened.width, reopened.height))
                    reopened.close()
                    val saved = AppSettingsManager.load().windowSizes.getValue("MAX")
                    assertFalse(saved.maximized)
                    assertSize(790.0, 550.0, saved)
                }
            } finally { onFx { reopened.close() } }
        } finally { onFx { stage.close() } }
    }

    @Test @EnabledOnOs(OS.WINDOWS, OS.MAC)
    fun `大きすぎる保存寸法は現在の作業領域へ補正され不正な寸法は初期値で開く`() = withSettings {
        onFx { AppSettingsManager.save(AppSettings(windowSizes = mapOf(
            "LARGE" to WindowSize(6000.0, 4000.0), "INVALID" to WindowSize(-5.0, 0.0, true)))) }
        val large = open("LARGE")
        val invalid = open("INVALID")
        try {
            onFx {
                assertTrue(Screen.getScreens().any { large.width <= it.visualBounds.width && large.height <= it.visualBounds.height })
                assertTrue(invalid.width > 100.0 && invalid.height > 100.0)
                assertFalse(invalid.isMaximized)
            }
        } finally { onFx { large.close(); invalid.close() } }
    }

    @Test @EnabledOnOs(OS.WINDOWS, OS.MAC)
    fun `フルスクリーンは保存せず解除後の通常寸法を保持する`() = withSettings {
        val stage = open("FULL")
        try {
            resize(stage, 720.0, 500.0)
            onFx { stage.isFullScreen = true }
            settle()
            onFx { stage.isFullScreen = false }
            settle()
            onFx {
                assertSize(720.0, 500.0, WindowSize(stage.width, stage.height))
                stage.close()
                val saved = AppSettingsManager.load().windowSizes.getValue("FULL")
                assertSize(720.0, 500.0, saved)
                assertFalse(saved.maximized)
            }
        } finally { onFx { stage.close() } }
    }

    @Test @EnabledOnOs(OS.WINDOWS, OS.MAC)
    fun `キーを指定しない補助Sceneは保存対象に含めない`() = withSettings {
        onFx {
            listOf(AppScreen.WIDGETS_ONLY, AppScreen.BASE).forEach { screen ->
                Stage().apply {
                    scene = Utility.createScene(screen, 300.0, 200.0, StackPane())
                    show()
                    close()
                }
            }
            assertTrue(AppSettingsManager.load().windowSizes.isEmpty())
        }
    }

    @Test @EnabledOnOs(OS.WINDOWS, OS.MAC)
    fun `最大化解除と同じFXターンの通常寸法要求を遅延処理で巻き戻さない`() = withSettings {
        val stage = open("IMMEDIATE")
        try {
            resize(stage, 740.0, 520.0)
            onFx { stage.isMaximized = true }
            settle()
            onFx {
                stage.isMaximized = false
                stage.width = 790.0; stage.height = 550.0
            }
            settle()
            onFx {
                assertFalse(stage.isMaximized)
                assertSize(790.0, 550.0, WindowSize(stage.width, stage.height))
                stage.close()
                assertSize(790.0, 550.0, AppSettingsManager.load().windowSizes.getValue("IMMEDIATE"))
            }
        } finally { onFx { stage.close() } }
    }

    @Test @EnabledOnOs(OS.WINDOWS, OS.MAC)
    fun `同じFXターンの通常寸法要求を非表示前に確定して再表示する`() = withSettings {
        val stage = open("HIDING")
        try {
            onFx {
                stage.width = 760.0; stage.height = 520.0
                stage.hide()
                assertSize(760.0, 520.0, AppSettingsManager.load().windowSizes.getValue("HIDING"))
                stage.show()
            }
            settle()
            onFx { assertSize(760.0, 520.0, WindowSize(stage.width, stage.height)) }
        } finally { onFx { stage.close() } }
    }

    private fun resize(stage: Stage, width: Double, height: Double) {
        var contentWidth = 0.0
        var contentHeight = 0.0
        onFx {
            contentWidth = width - (stage.width - stage.scene.width)
            contentHeight = height - (stage.height - stage.scene.height)
            stage.width = width
            stage.height = height
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var reflected = false
        while (!reflected && System.nanoTime() < deadline) {
            onFx {
                reflected = abs(stage.scene.width - contentWidth) <= 2 &&
                    abs(stage.scene.height - contentHeight) <= 2
            }
            if (!reflected) Thread.sleep(20)
        }
        assertTrue(reflected, "Sceneの実寸へ通常サイズが反映されませんでした")
    }

    private fun open(key: String): Stage {
        lateinit var stage: Stage
        onFx {
            stage = Stage().apply {
                title = "WindowSizePersistence #294 検証"
                scene = Utility.createScene(AppScreen.WIDGETS_ONLY, 620.0, 420.0, StackPane(), key)
                show()
            }
        }
        settle()
        return stage
    }

    private fun withSettings(action: () -> Unit) {
        lateinit var previous: AppSettings
        onFx { previous = AppSettingsManager.load(); AppSettingsManager.save(AppSettings()) }
        try { action() } finally { onFx { AppSettingsManager.save(previous) } }
    }

    private fun assertSize(width: Double, height: Double, size: WindowSize) {
        assertEquals(width, size.width, 2.0)
        assertEquals(height, size.height, 2.0)
    }

    private fun settle() { Thread.sleep(600); onFx {} }

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
