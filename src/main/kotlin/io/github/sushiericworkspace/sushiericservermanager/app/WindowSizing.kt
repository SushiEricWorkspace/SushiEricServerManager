package io.github.sushiericworkspace.sushiericservermanager.app

import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.collections.ListChangeListener
import javafx.geometry.Rectangle2D
import javafx.scene.Scene
import javafx.scene.control.Dialog
import javafx.stage.Screen
import javafx.stage.Stage
import javafx.stage.WindowEvent
import javafx.util.Duration
import kotlin.math.max
import kotlin.math.min

/**
 * Stageの装飾込み外寸を現在の画面作業領域へ収めます。JavaFXスレッドで呼び出してください。
 * 同じStageへの登録は一度だけ行い、既存の表示・終了ハンドラを保持します。
 * 最大化・最小化中は通常寸法を変更しません。通常寸法の記録と永続化は呼出し側の責務です。
 */
object WindowSizing {
    private val key = Any()

    fun install(stage: Stage) {
        if (stage.properties.containsKey(key)) return
        stage.properties[key] = Sizing(stage)
    }

    /**
     * 表示開始時または表示中の登録済みStageで、最大化の反映前に復元した通常外寸を補正します。
     * 未登録なら何もせず、最大化・最小化中は通常寸法を変更しません。
     */
    internal fun fit(stage: Stage) {
        (stage.properties[key] as? Sizing)?.fit(!stage.isShowing)
    }

    /** SceneをStageへ割り当てた時点で登録します。Sceneを差し替えても重複登録しません。 */
    fun install(scene: Scene) {
        (scene.window as? Stage)?.let(::install)
        scene.windowProperty().addListener { _, _, window -> (window as? Stage)?.let(::install) }
    }

    /** 標準DialogのStageにも同じ外寸制約を適用します。内容のスクロールは表示側で扱います。 */
    fun install(dialog: Dialog<*>) {
        dialog.dialogPane.scene?.let(::install)
        dialog.dialogPane.sceneProperty().addListener { _, _, scene -> scene?.let(::install) }
    }

    private class Sizing(private val stage: Stage) {
        private var minimumWidth = stage.minWidth
        private var minimumHeight = stage.minHeight
        private var maximumWidth = stage.maxWidth
        private var maximumHeight = stage.maxHeight
        private var adjusting = false
        private val delay = PauseTransition(Duration.millis(150.0)).apply {
            setOnFinished { fit(false) }
        }
        private val screensChanged = ListChangeListener<Screen> { schedule() }

        init {
            val changed = ChangeListener<Any?> { _, _, _ -> schedule() }
            listOf(stage.xProperty(), stage.yProperty(), stage.widthProperty(), stage.heightProperty(),
                stage.maximizedProperty(), stage.iconifiedProperty(), stage.sceneProperty()).forEach {
                it.addListener(changed)
            }
            stage.minWidthProperty().addListener { _, _, value ->
                if (!adjusting) { minimumWidth = value.toDouble(); schedule() }
            }
            stage.minHeightProperty().addListener { _, _, value ->
                if (!adjusting) { minimumHeight = value.toDouble(); schedule() }
            }
            stage.maxWidthProperty().addListener { _, _, value ->
                if (!adjusting) { maximumWidth = value.toDouble(); schedule() }
            }
            stage.maxHeightProperty().addListener { _, _, value ->
                if (!adjusting) { maximumHeight = value.toDouble(); schedule() }
            }
            stage.addEventHandler(WindowEvent.WINDOW_SHOWING) { fit(true) }
            stage.addEventHandler(WindowEvent.WINDOW_SHOWN) {
                Screen.getScreens().addListener(screensChanged)
                Platform.runLater { if (stage.isShowing) fit(false) }
            }
            stage.addEventHandler(WindowEvent.WINDOW_HIDDEN) {
                delay.stop()
                Screen.getScreens().removeListener(screensChanged)
            }
            if (stage.isShowing) {
                Screen.getScreens().addListener(screensChanged)
                schedule()
            }
        }

        private fun schedule() {
            if (!adjusting && stage.isShowing) delay.playFromStart()
        }

        fun fit(initial: Boolean) {
            if (adjusting || stage.isMaximized || stage.isIconified) return
            adjusting = true
            try {
                if (!stage.width.isFinite() || !stage.height.isFinite()) stage.sizeToScene()
                val owner = stage.owner
                val reference = if (initial && owner != null) {
                    Rectangle2D(owner.x, owner.y, owner.width, owner.height)
                } else {
                    Rectangle2D(stage.x.takeIf(Double::isFinite) ?: Screen.getPrimary().visualBounds.minX,
                        stage.y.takeIf(Double::isFinite) ?: Screen.getPrimary().visualBounds.minY,
                        stage.width, stage.height)
                }
                val bounds = selectWindowScreen(reference, Screen.getScreens().map { it.visualBounds })
                val limits = fitWindowSize(stage.width, stage.height, minimumWidth, minimumHeight,
                    maximumWidth, maximumHeight, bounds)
                // 制約更新の途中でもminとmaxを逆転させません。
                if (stage.minWidth > limits.minWidth) stage.minWidth = limits.minWidth
                if (stage.minHeight > limits.minHeight) stage.minHeight = limits.minHeight
                if (stage.maxWidth != limits.maxWidth) stage.maxWidth = limits.maxWidth
                if (stage.maxHeight != limits.maxHeight) stage.maxHeight = limits.maxHeight
                if (stage.minWidth != limits.minWidth) stage.minWidth = limits.minWidth
                if (stage.minHeight != limits.minHeight) stage.minHeight = limits.minHeight
                if (stage.width != limits.width) stage.width = limits.width
                if (stage.height != limits.height) stage.height = limits.height
                if (!initial || stage.x.isFinite()) {
                    val x = stage.x.coerceIn(bounds.minX, max(bounds.minX, bounds.maxX - limits.width))
                    if (stage.x != x) stage.x = x
                }
                if (!initial || stage.y.isFinite()) {
                    val y = stage.y.coerceIn(bounds.minY, max(bounds.minY, bounds.maxY - limits.height))
                    if (stage.y != y) stage.y = y
                }
            } finally {
                adjusting = false
            }
        }
    }
}

internal data class WindowSizeLimits(
    val width: Double, val height: Double,
    val minWidth: Double, val minHeight: Double,
    val maxWidth: Double, val maxHeight: Double
)

internal fun fitWindowSize(
    width: Double, height: Double, minWidth: Double, minHeight: Double,
    maxWidth: Double, maxHeight: Double, bounds: Rectangle2D
): WindowSizeLimits {
    val maximumWidth = min(maxWidth, bounds.width)
    val maximumHeight = min(maxHeight, bounds.height)
    val minimumWidth = min(minWidth, maximumWidth)
    val minimumHeight = min(minHeight, maximumHeight)
    return WindowSizeLimits(width.coerceIn(minimumWidth, maximumWidth), height.coerceIn(minimumHeight, maximumHeight),
        minimumWidth, minimumHeight, maximumWidth, maximumHeight)
}

internal fun selectWindowScreen(window: Rectangle2D, screens: List<Rectangle2D>): Rectangle2D =
    screens.maxWith(compareBy<Rectangle2D> {
        max(0.0, min(window.maxX, it.maxX) - max(window.minX, it.minX)) *
            max(0.0, min(window.maxY, it.maxY) - max(window.minY, it.minY))
    }.thenBy {
        -((window.minX + window.width / 2 - (it.minX + it.width / 2)).let { distance -> distance * distance } +
            (window.minY + window.height / 2 - (it.minY + it.height / 2)).let { distance -> distance * distance })
    })
