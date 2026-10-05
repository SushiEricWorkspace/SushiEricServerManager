package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericservermanager.editor.component.EditorSpinnerFactory
import javafx.geometry.Pos
import javafx.geometry.Side
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.CustomMenuItem
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 時・分・秒を個別に増減できる選択欄を開き、適用時だけ直接入力欄へ反映します。 */
internal fun showHistoryTimePicker(field: TextField) {
    val initial = runCatching { LocalTime.parse(field.text.trim()) }.getOrDefault(LocalTime.now())
    val hour = EditorSpinnerFactory.intSpinner(initial.hour, max = 23, prefWidth = 80.0) {}
    val minute = EditorSpinnerFactory.intSpinner(initial.minute, max = 59, prefWidth = 80.0) {}
    val second = EditorSpinnerFactory.intSpinner(initial.second, max = 59, prefWidth = 80.0) {}
    val picker = HBox(8.0,
        VBox(4.0, Label("時"), hour),
        VBox(4.0, Label("分"), minute),
        VBox(4.0, Label("秒"), second)
    )
    val apply = Button("適用")
    val cancel = Button("キャンセル")
    val buttons = HBox(8.0, apply, cancel).apply { alignment = Pos.CENTER_RIGHT }
    val content = VBox(10.0, picker, buttons)
    val menu = ContextMenu(CustomMenuItem(content, false))
    apply.setOnAction {
        listOf(hour, minute, second).forEach { spinner ->
            spinner.valueFactory.value = spinner.editor.text.toIntOrNull()
                ?.coerceIn(0, if (spinner === hour) 23 else 59) ?: spinner.value
        }
        field.text = LocalTime.of(hour.value, minute.value, second.value)
            .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        menu.hide()
    }
    cancel.setOnAction { menu.hide() }
    menu.show(field, Side.BOTTOM, 0.0, 0.0)
}
