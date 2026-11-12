package io.github.sushiericworkspace.sushiericservermanager.editor.render

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ComponentSegmentsTest {
    @Test
    fun inheritsStyleAndHonorsExplicitFalseAcrossChildren() {
        val source = Component.text("親", NamedTextColor.RED, TextDecoration.BOLD)
            .font(Key.key("minecraft:alt"))
            .append(Component.text("子"))
            .append(Component.text("解除").decoration(TextDecoration.BOLD, false)
                .font(Key.key("minecraft:default")))
        val runs = ComponentSegments.convert(source).single()
        assertEquals(listOf("親子", "解除"), runs.map { it.text })
        assertTrue(runs.all { it.style.color == 0xff5555 })
        assertTrue(runs.first().style.bold)
        assertEquals("minecraft:alt", runs.first().style.font)
        assertFalse(runs.last().style.bold)
        assertEquals("minecraft:default", runs.last().style.font)
    }

    @Test
    fun preservesJapaneseEmptyAndTrailingLinesAndAllDecorations() {
        val source = Component.text("日本語\n\n装飾\n")
            .decorate(TextDecoration.BOLD, TextDecoration.ITALIC, TextDecoration.UNDERLINED,
                TextDecoration.STRIKETHROUGH, TextDecoration.OBFUSCATED)
        val lines = ComponentSegments.convert(source)
        assertEquals(4, lines.size)
        assertEquals("日本語", lines[0].single().text)
        assertTrue(lines[1].isEmpty() && lines[3].isEmpty())
        val style = lines[2].single().style
        assertTrue(style.bold && style.italic && style.underlined && style.strikethrough && style.obfuscated)
    }

    @Test
    fun readsWithoutChangingEventsOrSourceComponents() {
        val source = Component.text("本文").clickEvent(ClickEvent.runCommand("/unused"))
            .hoverEvent(HoverEvent.showText(Component.text("非表示")))
            .append(Component.text("子").font(Key.key("custom:test")))
        val before = source
        val runs = ComponentSegments.convert(source).single()
        assertEquals("本文子", runs.joinToString("") { it.text })
        assertEquals("custom:test", runs.last().style.font)
        assertEquals(before, source)
        assertTrue(source.clickEvent() != null && source.hoverEvent() != null)
    }

    @Test
    fun 同じ有効Styleの文字と結合文字とサロゲートを検査前に連結する() {
        for ((left, right) in listOf("A" to "B", "e" to "\u0301", "\uD842" to "\uDFB7")) {
            val source = Component.text(left).append(Component.text(right).font(Key.key("minecraft:default")))
            val run = ComponentSegments.convert(source).single().single()
            assertEquals(left + right, run.text)
            val display = PreviewText.display(run, run.text.codePoints().toArray().toSet())
            assertEquals(null, display.notice)
            assertEquals(left + right, display.runs.single().text)
            assertEquals(left, source.content())
        }
    }

    @Test
    fun 異なるStyleと改行を越えて連結しない() {
        val source = Component.text("a").append(Component.text("b", NamedTextColor.RED))
            .append(Component.text("c\nd"))
        val lines = ComponentSegments.convert(source)
        assertEquals(listOf("a", "b", "c"), lines[0].map { it.text })
        assertEquals("d", lines[1].single().text)
        assertEquals(0xffffff, lines[0][0].style.color)
        assertEquals(0xff5555, lines[0][1].style.color)
    }

    @Test
    fun rejectsUnresolvedTranslation() {
        assertFailsWith<IllegalArgumentException> { ComponentSegments.convert(Component.translatable("item.test")) }
    }
}
