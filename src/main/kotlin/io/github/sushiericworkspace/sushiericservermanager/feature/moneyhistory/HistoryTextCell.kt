package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericservermanager.ui.AppTooltip
import javafx.scene.input.MouseButton
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.geometry.Pos
import javafx.scene.control.TableCell
import javafx.scene.text.Text
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 値なしの記号を中央に置き、幅に収まらない文字列だけをツールチップで補います。 */
internal class HistoryTextCell<S>(
    private val onSetFromTime: ((LocalDateTime) -> Unit)? = null,
    private val onSetToTime: ((LocalDateTime) -> Unit)? = null
) : TableCell<S, String>() {
    private val fullTextTooltip = AppTooltip.create("")
    private val measureText = Text()

    init {
        setOnMousePressed { event ->
            if (event.button == MouseButton.PRIMARY && !isEmpty) {
                tableView?.let { HistoryTableSupport.rememberClickedText(it, text.orEmpty()) }
            }
        }
    }

    override fun updateItem(item: String?, empty: Boolean) {
        super.updateItem(item, empty)
        text = if (empty) null else item
        graphic = null
        alignment = if (item == "-") Pos.CENTER else Pos.CENTER_LEFT
        tooltip = null
        contextMenu = if (empty || item == null) null else createContextMenu(item)
        requestLayout()
    }

    private fun createContextMenu(value: String): ContextMenu = ContextMenu().apply {
        items.add(MenuItem("コピー").apply { setOnAction { HistoryTableSupport.copy(value) } })
        val time = runCatching { LocalDateTime.parse(value, TIME_FORMAT) }.getOrNull()
        if (time != null) {
            onSetFromTime?.let { action ->
                items.add(MenuItem("開始時刻に設定").apply { setOnAction { action(time) } })
            }
            onSetToTime?.let { action ->
                items.add(MenuItem("終了時刻に設定").apply { setOnAction { action(time) } })
            }
        }
    }

    override fun layoutChildren() {
        super.layoutChildren()
        val value = text
        if (isEmpty || value.isNullOrBlank() || value == "-") {
            tooltip = null
            return
        }
        measureText.text = value
        measureText.font = font
        val availableWidth = (width - insets.left - insets.right).coerceAtLeast(0.0)
        if (measureText.layoutBounds.width > availableWidth) {
            fullTextTooltip.text = value
            tooltip = fullTextTooltip
        } else {
            tooltip = null
        }
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
