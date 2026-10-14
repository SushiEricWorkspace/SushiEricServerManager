package io.github.sushiericworkspace.sushiericservermanager.editor.component

import io.github.sushiericworkspace.sushiericdatamodel.value.SushiEricHexColor
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.util.toCssHex
import javafx.scene.control.ButtonType
import javafx.scene.control.ColorPicker
import javafx.scene.control.Dialog
import javafx.scene.paint.Color
import javafx.stage.Window

object ColorPickerDialog {

    fun show(
        initialColor: SushiEricHexColor,
        owner: Window? = null,
        cssPath: String = AppScreen.WIDGETS_ONLY.css
    ): SushiEricHexColor? {
        val picker = ColorPicker(toFxColor(initialColor)).apply {
            styleClass.add("button")
        }

        val dialog = Dialog<Color>().apply {
            title = "色を選択"
            dialogPane.content = picker
            dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

            owner?.let {
                initOwner(it)
            }

            dialogPane.stylesheets.add(
                ColorPickerDialog::class.java
                    .getResource(cssPath)!!
                    .toExternalForm()
            )

            dialogPane.styleClass.add("custom-dialog")

            setResultConverter { buttonType ->
                if (buttonType == ButtonType.OK) picker.value else null
            }
        }

        return dialog.showAndWait()
            .map { toHexColor(it) }
            .orElse(null)
    }

    fun toFxColor(hexColor: SushiEricHexColor): Color {
        return try {
            Color.web(hexColor.value)
        } catch (_: Exception) {
            Color.WHITE
        }
    }

    fun toHexColor(color: Color): SushiEricHexColor {
        return SushiEricHexColor.of(color.toCssHex())
    }

    fun isBrightColor(hexColor: SushiEricHexColor): Boolean {
        val normalized = hexColor.value.removePrefix("#")
        if (normalized.length != 6) return true

        val red = normalized.take(2).toIntOrNull(16) ?: return true
        val green = normalized.substring(2, 4).toIntOrNull(16) ?: return true
        val blue = normalized.substring(4, 6).toIntOrNull(16) ?: return true

        val brightness = (red * 299 + green * 587 + blue * 114) / 1000
        return brightness >= 128

    }
}
