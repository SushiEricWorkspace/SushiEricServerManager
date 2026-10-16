package io.github.sushiericworkspace.sushiericservermanager.util

import javafx.scene.paint.Color

fun Color.toCssHex(): String {
    val red = (red * 255).toInt().coerceIn(0, 255)
    val green = (green * 255).toInt().coerceIn(0, 255)
    val blue = (blue * 255).toInt().coerceIn(0, 255)
    return "#%02X%02X%02X".format(red, green, blue)
}
