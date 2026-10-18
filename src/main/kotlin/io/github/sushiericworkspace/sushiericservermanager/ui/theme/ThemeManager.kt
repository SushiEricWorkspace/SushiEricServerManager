package io.github.sushiericworkspace.sushiericservermanager.ui.theme

import io.github.sushiericworkspace.sushiericservermanager.config.AppSettingsManager
import io.github.sushiericworkspace.sushiericservermanager.config.AppTheme
import javafx.application.ColorScheme
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.collections.ListChangeListener
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HeaderBar
import javafx.stage.Stage
import javafx.stage.Window

/** 共通CSSをSceneとPopupへ適用し、表示中のウィンドウを選択された配色へ即時更新します。 */
object ThemeManager {
    private val stylesheet = requireNotNull(javaClass.getResource("/css/common/theme.css")).toExternalForm()
    private val sceneKey = Any()
    private val windowListeners = mutableMapOf<Window, ChangeListener<Scene>>()
    var current: AppTheme = AppSettingsManager.load().theme
        private set

    init {
        Window.getWindows().addListener(ListChangeListener { change ->
            while (change.next()) {
                change.removed.forEach { window ->
                    val listener = windowListeners.remove(window)
                    listener?.let { window.sceneProperty().removeListener(it) }
                }
                change.addedSubList.forEach { window ->
                    val listener = ChangeListener<Scene> { _, _, scene -> scene?.let(::apply) }
                    windowListeners[window] = listener
                    window.sceneProperty().addListener(listener)
                    window.scene?.let(::apply)
                }
            }
        })
    }

    /** 設定の他項目を保持して保存し、再読込で一致した場合だけ表示を変更します。JavaFXスレッドで呼びます。 */
    fun select(theme: AppTheme): Boolean {
        check(Platform.isFxApplicationThread())
        val settings = AppSettingsManager.load()
        AppSettingsManager.save(settings.copy(theme = theme))
        if (AppSettingsManager.load().theme != theme) return false
        current = theme
        Window.getWindows().toList().forEach { it.scene?.let(::apply) }
        return true
    }

    /** 表示前のSceneにも配色を設定します。繰り返し呼んでも監視を重複登録しません。 */
    fun apply(scene: Scene) {
        if (!scene.stylesheets.contains(stylesheet)) scene.stylesheets.add(stylesheet)
        if (!scene.properties.containsKey(sceneKey)) {
            scene.properties[sceneKey] = true
            scene.rootProperty().addListener { _, _, _ -> apply(scene) }
            scene.windowProperty().addListener { _, _, _ -> apply(scene) }
            scene.focusOwnerProperty().addListener { _, _, node ->
                if (node != null) Platform.runLater {
                    if (node.scene === scene) revealFocus(node)
                }
            }
        }
        scene.root.styleClass.removeAll("theme-light", "theme-dark")
        scene.root.styleClass.add(if (current == AppTheme.LIGHT) "theme-light" else "theme-dark")
        (scene.window as? Stage)?.let {
            HeaderBar.setSystemColorScheme(it, if (current == AppTheme.LIGHT) ColorScheme.LIGHT else ColorScheme.DARK)
        }
    }

    private fun revealFocus(node: Node) {
        var parent = node.parent
        while (parent != null) {
            if (parent is ScrollPane) {
                val pane = parent
                val viewport = pane.lookup(".viewport")
                if (viewport != null && pane.content != null) {
                    val area = viewport.localToScene(viewport.layoutBounds)
                    val target = node.localToScene(node.boundsInLocal)
                    val height = pane.content.layoutBounds.height - area.height
                    val width = pane.content.layoutBounds.width - area.width
                    val dy = when {
                        target.minY < area.minY -> target.minY - area.minY
                        target.maxY > area.maxY -> target.maxY - area.maxY
                        else -> 0.0
                    }
                    val dx = when {
                        target.minX < area.minX -> target.minX - area.minX
                        target.maxX > area.maxX -> target.maxX - area.maxX
                        else -> 0.0
                    }
                    if (height > 0) pane.vvalue = (pane.vvalue + dy / height * (pane.vmax - pane.vmin)).coerceIn(pane.vmin, pane.vmax)
                    if (width > 0) pane.hvalue = (pane.hvalue + dx / width * (pane.hmax - pane.hmin)).coerceIn(pane.hmin, pane.hmax)
                }
            }
            parent = parent.parent
        }
    }
}
