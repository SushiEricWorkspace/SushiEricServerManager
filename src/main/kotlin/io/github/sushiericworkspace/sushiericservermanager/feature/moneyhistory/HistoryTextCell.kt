package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericservermanager.ui.AppTooltip
import javafx.geometry.Pos
import javafx.scene.control.TableCell
import javafx.scene.text.Text

/** 値なしの記号を中央に置き、幅に収まらない文字列だけをツールチップで補います。 */
internal class HistoryTextCell<S> : TableCell<S, String>() {
    private val fullTextTooltip = AppTooltip.create("")
    private val measureText = Text()

    override fun updateItem(item: String?, empty: Boolean) {
        super.updateItem(item, empty)
        text = if (empty) null else item
        graphic = null
        alignment = if (item == "-") Pos.CENTER else Pos.CENTER_LEFT
        tooltip = null
        requestLayout()
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
}
