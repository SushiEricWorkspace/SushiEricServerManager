package io.github.sushiericworkspace.sushiericservermanager.editor.render

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.TextDecoration

/** 表示専用の行へ展開する。未指定の装飾は親を継承し、操作イベントは渡さない。 */
internal object ComponentSegments {
    data class Style(
        val font: String = "minecraft:default",
        val color: Int = 0xffffff,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underlined: Boolean = false,
        val strikethrough: Boolean = false,
        val obfuscated: Boolean = false
    )

    data class Run(val text: String, val style: Style)

    fun convert(component: Component): List<List<Run>> {
        val lines = mutableListOf(mutableListOf<Run>())
        val pending = StringBuilder()
        var pendingStyle = Style()
        fun flush() {
            if (pending.isNotEmpty()) {
                lines.last().add(Run(pending.toString(), pendingStyle))
                pending.setLength(0)
            }
        }
        fun visit(current: Component, parent: Style) {
            require(current is TextComponent) { "表示用ComponentはTextComponentである必要があります" }
            fun decoration(type: TextDecoration, inherited: Boolean): Boolean =
                when (current.decoration(type)) {
                    TextDecoration.State.TRUE -> true
                    TextDecoration.State.FALSE -> false
                    TextDecoration.State.NOT_SET -> inherited
                }
            val style = Style(
                current.font()?.asString() ?: parent.font,
                current.color()?.value() ?: parent.color,
                decoration(TextDecoration.BOLD, parent.bold),
                decoration(TextDecoration.ITALIC, parent.italic),
                decoration(TextDecoration.UNDERLINED, parent.underlined),
                decoration(TextDecoration.STRIKETHROUGH, parent.strikethrough),
                decoration(TextDecoration.OBFUSCATED, parent.obfuscated)
            )
            current.content().replace("\r\n", "\n").replace('\r', '\n').split('\n').forEachIndexed { index, text ->
                if (index > 0) {
                    flush()
                    lines.add(mutableListOf())
                }
                if (text.isNotEmpty()) {
                    if (pendingStyle != style) flush()
                    pendingStyle = style
                    pending.append(text)
                }
            }
            current.children().forEach { visit(it, style) }
        }
        visit(component, Style())
        flush()
        return lines.map { it.toList() }
    }
}
