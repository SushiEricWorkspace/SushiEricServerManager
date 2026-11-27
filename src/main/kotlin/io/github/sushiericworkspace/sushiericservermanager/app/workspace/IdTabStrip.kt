package io.github.sushiericworkspace.sushiericservermanager.app.workspace

import io.github.sushiericworkspace.sushiericservermanager.config.IdTabsState
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.css.PseudoClass
import javafx.beans.value.ChangeListener
import javafx.stage.Window
import javafx.event.EventTarget
import javafx.scene.Node
import javafx.scene.SnapshotParameters
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.image.ImageView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.HBox
import javafx.scene.paint.Color
import javafx.stage.Popup
import javafx.util.Duration
import kotlin.math.abs

/** IDタブの選択、並べ替え、横スクロールを扱う列です。 */
internal class IdTabStrip(
    private val onSelect: (String) -> Unit,
    private val onClose: (String) -> Unit,
    private val onMove: (String, Int) -> Unit
) : ScrollPane() {
    private val row = HBox()
    private var state = IdTabsState()
    private val tabs = linkedMapOf<String, HBox>()
    private val dragImage = ImageView().apply { opacity = 0.65; isMouseTransparent = true }
    private val dragPreview = Popup().apply {
        content.add(dragImage)
        setOnAutoHide { stopDrag() }
    }
    private var pressed = false
    private var moved = false
    private var startX = 0.0
    private var pointerX = 0.0
    private var draggedId: String? = null
    private var destination = 0
    private var owner: Window? = null
    private val windowFocusListener = ChangeListener<Boolean> { _, _, focused -> if (!focused) stopDrag() }
    private val windowListener = ChangeListener<Window> { _, _, next -> watchWindow(next) }
    private val autoScroll = Timeline(KeyFrame(Duration.millis(40.0), {
        val direction = when { pointerX < 24.0 -> -1; pointerX > width - 24.0 -> 1; else -> 0 }
        if (direction != 0) { scroll(direction * 16.0); updateDestination() }
    })).apply { cycleCount = Timeline.INDEFINITE }

    init {
        styleClass.add("workspace-id-tabs")
        content = row
        hbarPolicy = ScrollBarPolicy.NEVER
        vbarPolicy = ScrollBarPolicy.NEVER
        isFitToHeight = true
        isPannable = false
        isFocusTraversable = true
        minHeight = 32.0
        prefHeight = 32.0
        maxHeight = 32.0
        minWidth = 0.0
        addEventFilter(ScrollEvent.SCROLL) { event ->
            val delta = if (event.deltaX != 0.0) event.deltaX else if (event.isShiftDown) event.deltaY else 0.0
            if (delta != 0.0) { scroll(-delta); event.consume() }
        }
        addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            stopDrag()
            moved = false
            if (event.button == MouseButton.PRIMARY && !closeButton(event.target)) {
                pressed = true
                startX = event.sceneX
                draggedId = tabs.entries.firstOrNull { (_, tab) -> tab.localToScene(tab.boundsInLocal).contains(event.sceneX, event.sceneY) }?.key
                destination = state.ids.indexOf(draggedId)
            }
        }
        addEventFilter(MouseEvent.MOUSE_DRAGGED) { event ->
            if (pressed && abs(event.sceneX - startX) > 4.0) moved = true
            if (pressed && moved) {
                pointerX = sceneToLocal(event.sceneX, event.sceneY).x
                if (draggedId != null) {
                    updateDestination()
                    showDragPreview(event.sceneX, event.sceneY)
                    autoScroll.play()
                }
                event.consume()
            }
        }
        addEventFilter(MouseEvent.MOUSE_RELEASED) { event ->
            val id = draggedId
            if (pressed && moved) {
                if (id != null) onMove(id, destination)
                event.consume()
            }
            stopDrag()
        }
        addEventFilter(MouseEvent.MOUSE_CLICKED) { if (moved) it.consume() }
        addEventFilter(KeyEvent.KEY_PRESSED, ::handleKey)
        sceneProperty().addListener { _, old, next ->
            stopDrag()
            old?.windowProperty()?.removeListener(windowListener)
            next?.windowProperty()?.addListener(windowListener)
            watchWindow(next?.window)
        }
    }

    fun show(next: IdTabsState, source: String? = null, modified: (String) -> Boolean) {
        state = next
        tabs.keys.retainAll(next.ids.toSet())
        for (id in next.ids) {
            val tab = tabs.getOrPut(id) {
                HBox(4.0).apply {
                    styleClass.add("workspace-id-tab")
                    isFocusTraversable = true
                    children.addAll(Label(), Button("×").apply {
                        styleClass.add("workspace-close-button")
                        setOnAction { onClose(id) }
                    })
                    setOnMouseClicked { if (it.button == MouseButton.PRIMARY && !moved && !closeButton(it.target)) onSelect(id) }
                }
            }
            (tab.children[0] as Label).text = (source?.let { it + " / " }.orEmpty()) + id + if (modified(id)) " *" else ""
            tab.pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"), id == next.selected)
        }
        val offset = hvalue
        row.children.setAll(next.ids.map(tabs::getValue))
        hvalue = offset
    }

    fun revealSelected() {
        Platform.runLater {
            val tab = tabs[state.selected] ?: return@runLater
            val left = tab.boundsInParent.minX
            val right = tab.boundsInParent.maxX
            val offset = hvalue * range()
            when { left < offset -> scroll(left - offset); right > offset + viewportBounds.width -> scroll(right - offset - viewportBounds.width) }
        }
    }

    fun dispose() {
        stopDrag()
        scene?.windowProperty()?.removeListener(windowListener)
        watchWindow(null)
    }

    private fun watchWindow(next: Window?) {
        owner?.focusedProperty()?.removeListener(windowFocusListener)
        owner = next
        owner?.focusedProperty()?.addListener(windowFocusListener)
    }

    private fun range() = (row.layoutBounds.width - viewportBounds.width).coerceAtLeast(0.0)
    private fun scroll(pixels: Double) { if (range() > 0.0) hvalue = (hvalue + pixels / range()).coerceIn(0.0, 1.0) }
    private fun stopDrag() {
        autoScroll.stop()
        clearDropMarker()
        dragPreview.hide()
        dragImage.image = null
        pressed = false
        draggedId = null
    }

    private fun showDragPreview(sceneX: Double, sceneY: Double) {
        if (!dragPreview.isShowing) {
            val tab = tabs[draggedId] ?: return
            dragImage.image = tab.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
            val window = scene?.window ?: return
            dragPreview.show(window)
        }
        val point = localToScreen(sceneToLocal(sceneX, sceneY)) ?: return
        val image = dragImage.image
        dragPreview.x = point.x - image.width / 2
        dragPreview.y = point.y - image.height / 2
    }

    private fun updateDestination() {
        val x = pointerX + hvalue * range()
        val remaining = state.ids.filterNot { it == draggedId }
        destination = remaining.indexOfFirst { x < tabs.getValue(it).boundsInParent.centerX }.takeIf { it >= 0 } ?: remaining.size
        clearDropMarker()
        if (destination < remaining.size) tabs.getValue(remaining[destination]).pseudoClassStateChanged(DROP_BEFORE, true)
        else remaining.lastOrNull()?.let { tabs.getValue(it).pseudoClassStateChanged(DROP_AFTER, true) }
    }

    private fun clearDropMarker() {
        tabs.values.forEach {
            it.pseudoClassStateChanged(DROP_BEFORE, false)
            it.pseudoClassStateChanged(DROP_AFTER, false)
        }
    }
    private fun closeButton(target: EventTarget): Boolean {
        var node = target as? Node
        while (node != null && node !== this) {
            if ("workspace-close-button" in node.styleClass) return true
            node = node.parent
        }
        return false
    }
    private fun handleKey(event: KeyEvent) {
        if (event.code == KeyCode.ESCAPE && pressed) { stopDrag(); event.consume(); return }
        val index = state.ids.indexOf(state.selected)
        if (index < 0 || event.isShortcutDown || event.isControlDown || event.isMetaDown) return
        val destination = when (event.code) {
            KeyCode.LEFT -> index - 1
            KeyCode.RIGHT -> index + 1
            KeyCode.HOME -> 0
            KeyCode.END -> state.ids.lastIndex
            KeyCode.DELETE -> { state.selected?.let(onClose); event.consume(); return }
            else -> return
        }.coerceIn(0, state.ids.lastIndex)
        if (event.isAltDown) onMove(state.ids[index], destination) else onSelect(state.ids[destination])
        revealSelected()
        event.consume()
    }

    companion object {
        private val DROP_BEFORE = PseudoClass.getPseudoClass("drop-before")
        private val DROP_AFTER = PseudoClass.getPseudoClass("drop-after")
    }
}
