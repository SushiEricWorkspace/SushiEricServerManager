package io.github.sushiericworkspace.sushiericservermanager.ui.theme

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.config.AppSettingsManager
import io.github.sushiericworkspace.sushiericservermanager.config.AppTheme
import io.github.sushiericworkspace.sushiericservermanager.config.OS
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.application.ColorScheme
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.css.PseudoClass
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.control.cell.CheckBoxTreeCell
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.HeaderBar
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import javafx.stage.Window
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ThemeManagerTest {
    @Test
    fun `開いたSceneと確認DialogとTooltipが保存した配色へ即時追従する`() {
        startFx()
        val previous = AppSettingsManager.load()
        val originalTheme = fx { ThemeManager.current }
        val button = fx { Button("操作") }
        val label = fx { Label("配色") }
        val owner = fx { Stage().apply { scene = Utility.createScene(AppScreen.MODE_SELECT, customRoot = VBox(label, button)); show() } }
        val alert = fx { Alert(Alert.AlertType.CONFIRMATION, "確認本文").apply { initOwner(owner); show() } }
        val tooltip = fx { Tooltip("ホバー本文").apply { show(owner, owner.x + 10, owner.y + 100) } }
        try {
            for (theme in listOf(AppTheme.LIGHT, AppTheme.DARK)) {
                fx {
                    assertTrue(ThemeManager.select(theme))
                    val text = if (theme == AppTheme.LIGHT) Color.web("#25282c") else Color.WHITE
                    listOf(owner.scene, alert.dialogPane.scene, tooltip.scene).forEach { scene ->
                        scene.root.applyCss(); scene.root.layout()
                        assertTrue(scene.root.styleClass.contains("theme-${theme.name.lowercase()}"))
                    }
                    assertEquals(text, label.textFill)
                    assertEquals(text, (alert.dialogPane.lookup(".content.label") as Label).textFill)
                    assertEquals(text, (tooltip.skin.node as Label).textFill)
                    assertEquals(if (theme == AppTheme.LIGHT) ColorScheme.LIGHT else ColorScheme.DARK, HeaderBar.getSystemColorScheme(owner))
                    button.pseudoClassStateChanged(PseudoClass.getPseudoClass("hover"), true)
                    owner.scene.root.applyCss()
                    assertEquals(if (theme == AppTheme.LIGHT) Color.web("#e0e5eb") else Color.web("#26282a"), button.background.fills.last().fill)
                    assertEquals(0.0, button.background.fills.last().radii.topLeftHorizontalRadius)
                    assertEquals(theme, AppSettingsManager.load().theme)
                }
            }
        } finally {
            fx { tooltip.hide(); alert.close(); owner.close(); ThemeManager.select(originalTheme); AppSettingsManager.save(previous) }
        }
    }

    @Test
    fun `Windowの監視は非表示で外れ再表示後も一度だけ反映する`() {
        startFx()
        val previous = AppSettingsManager.load()
        val originalTheme = fx { ThemeManager.current }
        val listeners = fx { ThemeManager::class.java.getDeclaredField("windowListeners").apply { isAccessible = true }.get(null) as Map<*, *> }
        val count = fx { listeners.size }
        val stage = fx { Stage().apply { scene = Utility.createScene(AppScreen.WIDGETS_ONLY, customRoot = VBox(Label("監視"))); show() } }
        try {
            fx {
                assertEquals(count + 1, listeners.size)
                repeat(3) {
                    stage.hide()
                    assertFalse(listeners.containsKey(stage))
                    assertEquals(count, listeners.size)
                    stage.show()
                    assertTrue(listeners.containsKey(stage))
                    assertEquals(count + 1, listeners.size)
                }
                val root = VBox(Label("Scene差替"))
                var notifications = 0
                root.styleClass.addListener(ListChangeListener { change ->
                    while (change.next()) notifications += change.addedSubList.count { it.startsWith("theme-") }
                })
                stage.scene = Scene(root)
                assertEquals(1, notifications)
                val theme = if (ThemeManager.current == AppTheme.DARK) AppTheme.LIGHT else AppTheme.DARK
                assertTrue(ThemeManager.select(theme))
                assertEquals(2, notifications)
                assertTrue(root.styleClass.contains("theme-${theme.name.lowercase()}"))
            }
        } finally { fx { stage.close(); assertEquals(count, listeners.size); ThemeManager.select(originalTheme); AppSettingsManager.save(previous) } }
    }

    @Test
    fun `非表示barの一万件一覧でwheelとキーと末尾選択を保ちセルを仮想化する`() {
        startFx()
        val list = fx { ListView(FXCollections.observableArrayList((0 until 10000).map { "行$it" })).apply { fixedCellSize = 24.0 } }
        val stage = fx { Stage().apply { scene = Utility.createScene(AppScreen.WIDGETS_ONLY, 360.0, 220.0, customRoot = list); show() } }
        try {
            fx {
                list.applyCss(); list.layout(); list.requestFocus()
                assertTrue(list.lookupAll(".scroll-bar").filterIsInstance<ScrollBar>().all { it.opacity == 0.0 })
                assertTrue(list.lookupAll(".list-cell").size < 100)
                list.lookup(".virtual-flow").fireEvent(ScrollEvent(ScrollEvent.SCROLL, 20.0, 40.0, 20.0, 40.0, false, false, false, false, false, false,
                    0.0, -120.0, 0.0, -120.0, ScrollEvent.HorizontalTextScrollUnits.NONE, 0.0, ScrollEvent.VerticalTextScrollUnits.LINES, -3.0, 0, null))
            }
            await { (list.lookup(".virtual-flow") as VirtualFlow<*>).firstVisibleCell?.index?.let { it > 0 } == true }
            fx { list.fireEvent(KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.END, false, false, false, false)) }
            await { list.selectionModel.selectedIndex == 9999 }
            await { (list.lookup(".virtual-flow") as VirtualFlow<*>).lastVisibleCell?.index == 9999 }
            fx { assertTrue(list.lookupAll(".list-cell").size < 100) }
        } finally { fx { stage.close() } }
    }

    @Test
    fun `表示外のエラー入力欄へのフォーカスでScrollPaneが表示位置を合わせる`() {
        startFx()
        val fields = fx { (0 until 60).map { TextField("入力$it") } }
        val pane = fx { ScrollPane(VBox(4.0, *fields.toTypedArray())).apply { isFitToWidth = true } }
        val stage = fx { Stage().apply { scene = Utility.createScene(AppScreen.WIDGETS_ONLY, 350.0, 220.0, customRoot = pane); show() } }
        try {
            fx { fields.last().requestFocus() }
            await {
                val viewport = pane.lookup(".viewport")
                val visible = viewport.localToScene(viewport.layoutBounds)
                val target = fields.last().localToScene(fields.last().boundsInLocal)
                pane.vvalue > 0.0 && target.minY >= visible.minY - 1 && target.maxY <= visible.maxY + 1
            }
        } finally { fx { stage.close() } }
    }

    @Test
    fun `SceneのfocusOwnerだけでは未表示Windowのfocus枠を表示しない`() {
        startFx()
        val checkBox = fx { CheckBox("焦点の前提") }
        val stage = fx { Stage().apply {
            scene = Utility.createScene(AppScreen.MODE_SELECT, customRoot = VBox(checkBox))
        } }
        try {
            fx {
                checkBox.requestFocus()
                stage.scene.root.applyCss()
                assertSame(checkBox, stage.scene.focusOwner)
                assertFalse(stage.isFocused)
                assertFalse(checkBox.isFocused)
                val box = checkBox.lookup(".box") as javafx.scene.layout.Region
                assertEquals(1.0, box.border.strokes.last().widths.top)
                stage.show()
            }
            await(diagnostic = {
                "showing=${stage.isShowing} focused=${stage.isFocused} sceneOwner=${stage.scene.focusOwner?.javaClass?.simpleName} " +
                    "checkBoxFocused=${checkBox.isFocused} windows=${Window.getWindows().map { "${it.javaClass.simpleName}@${System.identityHashCode(it)} showing=${it.isShowing} focused=${it.isFocused}" }}"
            }) { stage.isFocused && checkBox.isFocused }
            fx {
                stage.scene.root.applyCss()
                val box = checkBox.lookup(".box") as javafx.scene.layout.Region
                assertEquals(2.0, box.border.strokes.last().widths.top)
            }
        } finally { fx { stage.close() } }
    }

    @Test
    fun `両テーマで未選択CheckBoxと確認ボタンのキーボードfocusを表示する`() {
        startFx()
        val previous = AppSettingsManager.load()
        val originalTheme = fx { ThemeManager.current }
        val checkBox = fx { CheckBox("対象を含める") }
        val alert = fx { Alert(Alert.AlertType.CONFIRMATION).apply {
            dialogPane.content = checkBox
            buttonTypes.setAll(ButtonType.OK)
            dialogPane.scene.let(ThemeManager::apply)
            show()
        } }
        try {
            val button = fx { alert.dialogPane.lookupButton(ButtonType.OK) as Button }
            for (theme in listOf(AppTheme.LIGHT, AppTheme.DARK)) {
                fx { assertTrue(ThemeManager.select(theme)); checkBox.requestFocus() }
                await(diagnostic = {
                    val window = alert.dialogPane.scene.window
                    "showing=${window.isShowing} focused=${window.isFocused} sceneOwner=${alert.dialogPane.scene.focusOwner?.javaClass?.simpleName} " +
                        "checkBoxFocused=${checkBox.isFocused} windows=${Window.getWindows().map { "${it.javaClass.simpleName}@${System.identityHashCode(it)} showing=${it.isShowing} focused=${it.isFocused}" }}"
                }) { alert.dialogPane.scene.window.isFocused && checkBox.isFocused }
                fx {
                    val scene = alert.dialogPane.scene
                    scene.root.applyCss()
                    assertSame(checkBox, scene.focusOwner)
                    assertFalse(checkBox.isSelected)
                    val accent = if (theme == AppTheme.LIGHT) Color.web("#1762a2") else Color.web("#4a8cc7")
                    val box = checkBox.lookup(".box") as javafx.scene.layout.Region
                    assertEquals(accent, box.border.strokes.last().topStroke)
                    assertEquals(2.0, box.border.strokes.last().widths.top)
                    checkBox.fireEvent(KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, false, false, false, false))
                }
                await { alert.dialogPane.scene.window.isFocused && button.isFocused }
                fx {
                    alert.dialogPane.scene.root.applyCss()
                    assertSame(button, alert.dialogPane.scene.focusOwner)
                    val accent = if (theme == AppTheme.LIGHT) Color.web("#1762a2") else Color.web("#4a8cc7")
                    assertEquals(accent, button.border.strokes.last().topStroke)
                    assertEquals(2.0, button.border.strokes.last().widths.top)
                }
            }
        } finally { fx { alert.close(); ThemeManager.select(originalTheme); AppSettingsManager.save(previous) } }
    }

    @Test
    fun `両テーマで差分確認のCheckBoxTreeCell選択を表示する`() {
        startFx()
        val previous = AppSettingsManager.load()
        val originalTheme = fx { ThemeManager.current }
        val tree = fx { TreeView(CheckBoxTreeItem("root").apply {
            isExpanded = true
            children.addAll(CheckBoxTreeItem("変更項目1"), CheckBoxTreeItem("変更項目2"))
        }).apply {
            isShowRoot = false
            styleClass.add("diff-tree-view")
            cellFactory = CheckBoxTreeCell.forTreeView()
        } }
        val stage = fx { Stage().apply { scene = Utility.createScene(AppScreen.WIDGETS_ONLY, 360.0, 220.0, customRoot = tree); show() } }
        try {
            for (theme in listOf(AppTheme.LIGHT, AppTheme.DARK)) {
                fx {
                    assertTrue(ThemeManager.select(theme))
                    tree.selectionModel.select(1)
                    tree.focusModel.focus(1)
                    tree.applyCss(); tree.layout()
                    val selected = tree.lookupAll(".tree-cell").filterIsInstance<TreeCell<*>>().first { it.index == 1 }
                    assertTrue(selected.isSelected)
                    assertEquals(if (theme == AppTheme.LIGHT) Color.web("#d8e6f4") else Color.web("#2f3235"), selected.background.fills.last().fill)
                }
            }
        } finally { fx { stage.close(); ThemeManager.select(originalTheme); AppSettingsManager.save(previous) } }
    }

    private fun await(diagnostic: (() -> String)? = null, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!fx(condition)) {
            val withinDeadline = System.nanoTime() < deadline
            val detail = if (withinDeadline || diagnostic == null) "" else {
                runCatching { fx(diagnostic) }.getOrElse { "diagnosticError=${it.javaClass.simpleName}" }
            }
            assertTrue(withinDeadline, "画面状態の待機超過" + if (detail.isEmpty()) "" else ": $detail")
            Thread.sleep(30)
        }
    }

    private fun startFx() {
        val root = System.getProperty("sushieric.test.configRoot")
            ?: error("設定を変更するFX試験は隔離されたGradle testタスクから実行してください")
        assertTrue(Path.of(OS.dataConfigBase).toAbsolutePath().normalize().startsWith(Path.of(root).toAbsolutePath().normalize()), "設定保存先が隔離領域外です")
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
