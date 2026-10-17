package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.config.AppSettingsManager
import io.github.sushiericworkspace.sushiericservermanager.config.WindowSize
import javafx.application.Platform
import javafx.scene.Scene
import javafx.stage.Stage
import javafx.stage.WindowEvent

/**
 * 画面種別ごとの通常外寸と最大化状態をconfig.jsonへ保存します。JavaFXスレッドで使用してください。
 * 終了要求の取消時は保存せず、実際に非表示になった時点で最新設定の対象キーだけを更新します。
 */
object WindowSizePersistence {
    private val propertyKey = Any()

    /** SceneがStageへ接続されたときに復元します。同じStageとSceneへの再登録で監視を増やしません。 */
    fun install(scene: Scene, key: String) {
        if (scene.properties[propertyKey] == key) return
        scene.properties[propertyKey] = key
        fun attach(stage: Stage) {
            val state = stage.properties[propertyKey] as? SavedWindow
                ?: SavedWindow(stage).also { stage.properties[propertyKey] = it }
            state.activate(scene, key)
        }
        (scene.window as? Stage)?.let(::attach)
        scene.windowProperty().addListener { _, _, window -> (window as? Stage)?.let(::attach) }
    }

    /** 準備画面等へSceneを差し替える直前に通常寸法を確定し、一時サイズの記録を止めます。 */
    fun suspend(stage: Stage) {
        (stage.properties[propertyKey] as? SavedWindow)?.suspend()
    }

    private class SavedWindow(private val stage: Stage) {
        private var scene: Scene? = null
        private var key = ""
        private var normal: WindowSize? = null
        private var active = false
        private var restoring = false
        private var maximized = false
        private var applying = false

        init {
            stage.widthProperty().addListener { _, _, _ -> resized() }
            stage.heightProperty().addListener { _, _, _ -> resized() }
            stage.maximizedProperty().addListener { _, _, value ->
                if (active) {
                    maximized = value
                    stateChanged(value || stage.isIconified || stage.isFullScreen)
                }
            }
            stage.iconifiedProperty().addListener { _, _, value ->
                if (active) stateChanged(value || stage.isMaximized || stage.isFullScreen)
            }
            stage.fullScreenProperty().addListener { _, _, value ->
                if (active) stateChanged(value || stage.isMaximized || stage.isIconified)
            }
            stage.addEventHandler(WindowEvent.WINDOW_SHOWING) {
                restoring = true
                if (stage.scene === scene) {
                    active = false
                    stage.isMaximized = false
                    stage.isIconified = false
                    stage.isFullScreen = false
                    applyNormal()
                    WindowSizing.fit(stage)
                    active = true
                }
            }
            stage.addEventHandler(WindowEvent.WINDOW_SHOWN) {
                if (active) afterShown()
            }
            stage.addEventHandler(WindowEvent.WINDOW_HIDING) { suspend() }
            stage.addEventHandler(WindowEvent.WINDOW_HIDDEN) {
                normal?.takeIf(WindowSize::isValid)?.let { size ->
                    val settings = AppSettingsManager.load()
                    AppSettingsManager.save(settings.copy(windowSizes = settings.windowSizes +
                        (key to size)))
                }
            }
        }

        fun activate(scene: Scene, key: String) {
            if (this.scene === scene && active) return
            check(this.key.isEmpty() || this.key == key) { "同じStageで保存する画面種別は変更できません" }
            this.scene = scene
            this.key = key
            if (normal == null) normal = AppSettingsManager.load().windowSizes[key]?.takeIf(WindowSize::isValid)
            maximized = normal?.maximized ?: false
            active = false
            restoring = true
            stage.isMaximized = false
            applyNormal()
            active = true
            if (stage.isShowing) afterShown()
        }

        fun suspend() {
            if (!active) return
            if (!restoring) capture()
            normal = normal?.copy(maximized = maximized)
            active = false
        }

        private fun afterShown() {
            Platform.runLater {
                if (!active || !stage.isShowing) return@runLater
                WindowSizing.fit(stage)
                restoring = false
                capture()
                if (maximized) stage.isMaximized = true
            }
        }

        private fun stateChanged(unavailable: Boolean) {
            restoring = true
            if (!unavailable) {
                applyNormal()
                WindowSizing.fit(stage)
                restoring = false
                capture()
            }
        }

        private fun resized() {
            if (active && !restoring && !applying && stage.isShowing) capture()
        }

        private fun applyNormal() {
            applying = true
            try {
                normal?.let {
                    if (stage.width != it.width) stage.width = it.width
                    if (stage.height != it.height) stage.height = it.height
                }
            } finally { applying = false }
        }

        private fun capture() {
            if (!active || restoring || stage.isMaximized || stage.isIconified || stage.isFullScreen) return
            WindowSize(stage.width, stage.height, maximized).takeIf(WindowSize::isValid)?.let { normal = it }
        }
    }
}
