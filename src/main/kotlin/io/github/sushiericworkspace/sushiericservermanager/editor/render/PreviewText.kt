package io.github.sushiericworkspace.sushiericservermanager.editor.render

/** 未対応の指定は代替した事実を表示し、保存元の文字列やfontを変更しない。 */
internal object PreviewText {
    private val fonts = setOf("minecraft:default", "minecraft:uniform")
    private const val MASK = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    data class Display(val runs: List<ComponentSegments.Run>, val notice: String?)

    fun display(run: ComponentSegments.Run, codepoints: Set<Int>): Display {
        val unsupportedFont = run.style.font !in fonts
        val missing = run.text.codePoints().filter { it != '\t'.code && it !in codepoints }
            .distinct().toArray()
        val notice = buildList {
            if (unsupportedFont) add("未対応フォント: ${run.style.font}")
            if (missing.isNotEmpty()) add("未対応文字: " + missing.joinToString(", ") { "U+%04X".format(it) })
        }.takeIf { it.isNotEmpty() }?.joinToString(" / ")
        val text = buildString {
            run.text.codePoints().forEach { cp ->
                when {
                    cp == '\t'.code -> append("    ")
                    cp !in codepoints -> append("[U+%04X]".format(cp))
                    !unsupportedFont && run.style.obfuscated && !Character.isWhitespace(cp) -> append(MASK[cp % MASK.length])
                    else -> appendCodePoint(cp)
                }
            }
        }
        val bodyStyle = if (unsupportedFont) run.style.copy(obfuscated = false) else run.style
        val runs = buildList {
            if (notice != null) add(ComponentSegments.Run("[$notice] ", ComponentSegments.Style(color = 0xffcc66)))
            add(ComponentSegments.Run(text, bodyStyle))
        }
        return Display(runs, notice)
    }
}
