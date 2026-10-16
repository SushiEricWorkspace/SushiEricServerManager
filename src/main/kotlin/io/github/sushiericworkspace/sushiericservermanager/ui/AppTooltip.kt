package io.github.sushiericworkspace.sushiericservermanager.ui

import javafx.scene.control.Tooltip
import javafx.util.Duration

/**
 * アプリ内で使用するツールチップを共通設定で生成します。
 *
 * マウスカーソルが対象上にある間は表示を維持し、カーソルを外した際の
 * 非表示タイミングはJavaFXの既定値を使用します。
 */
internal object AppTooltip {
    internal val displayDuration: Duration = Duration.INDEFINITE

    /** 指定した本文を持つツールチップを生成します。 */
    fun create(text: String): Tooltip = Tooltip(text).apply {
        showDuration = displayDuration
    }
}
