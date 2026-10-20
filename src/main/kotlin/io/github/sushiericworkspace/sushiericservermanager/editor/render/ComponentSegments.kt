package io.github.sushiericworkspace.sushiericservermanager.editor.render

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import lib.minecraft.text.TextSegment
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.TextDecoration

/** D1の表示用Componentを変換する。装飾の未指定とfalseを区別し、操作イベントは渡さない。 */
internal object ComponentSegments {
    fun convert(component: Component): TextSegment = TextSegment.fromJson(toJson(component))

    private fun toJson(component: Component): JsonObject {
        require(component is TextComponent) { "表示用ComponentはTextComponentである必要があります" }
        return JsonObject().apply {
            addProperty("text", component.content())
            component.font()?.let { addProperty("font", it.asString()) }
            component.color()?.let { addProperty("color", it.asHexString()) }
            TextDecoration.values().forEach { decoration ->
                when (component.decoration(decoration)) {
                    TextDecoration.State.TRUE -> addProperty(decoration.toString(), true)
                    TextDecoration.State.FALSE -> addProperty(decoration.toString(), false)
                    TextDecoration.State.NOT_SET -> Unit
                }
            }
            if (component.children().isNotEmpty()) {
                add("extra", JsonArray().apply { component.children().forEach { add(toJson(it)) } })
            }
        }
    }
}
