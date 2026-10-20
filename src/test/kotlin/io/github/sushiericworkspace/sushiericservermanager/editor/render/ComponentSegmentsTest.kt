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
    fun inheritsColorAndDecorationsWithoutLosingChildren() {
        val source = Component.text("親", NamedTextColor.RED, TextDecoration.BOLD)
            .append(Component.text("継承"))
            .append(Component.text("解除").decoration(TextDecoration.BOLD, false))
        val runs = ComponentSegments.convert(source).toLines()[0].segments
        assertEquals(listOf("親", "継承", "解除"), runs.map { it.text })
        assertTrue(runs[0].isBold)
        assertTrue(runs[1].isBold)
        assertFalse(runs[2].isBold)
        assertTrue(runs.all { it.color.orElseThrow().rgb() and 0xffffff == 0xff5555 })
    }

    @Test
    fun preservesJapaneseEmptyLinesAndAllDecorations() {
        val source = Component.text("日本語\n\n装飾")
            .decorate(TextDecoration.BOLD, TextDecoration.ITALIC, TextDecoration.UNDERLINED,
                TextDecoration.STRIKETHROUGH, TextDecoration.OBFUSCATED)
        val lines = ComponentSegments.convert(source).toLines()
        assertEquals(3, lines.size)
        assertEquals("日本語", lines[0].segments.single().text)
        assertTrue(lines[1].segments.isEmpty())
        val run = lines[2].segments.single()
        assertEquals("装飾", run.text)
        assertTrue(run.isBold && run.isItalic && run.isUnderlined && run.isStrikethrough && run.isObfuscated)
    }

    @Test
    fun doesNotTransferEventsOrMutateSource() {
        val source = Component.text("本文").clickEvent(ClickEvent.runCommand("/unused"))
            .hoverEvent(HoverEvent.showText(Component.text("非表示")))
            .append(Component.text("子").clickEvent(ClickEvent.openUrl("https://example.invalid")))
        val segment = ComponentSegments.convert(source)
        assertTrue(segment.clickEvent.isEmpty)
        assertTrue(segment.hoverEvent.isEmpty)
        assertTrue(segment.extra.single().clickEvent.isEmpty)
        assertTrue(source.clickEvent() != null)
        assertEquals("本文子", segment.toPlainText())
    }

    @Test
    fun rejectsUnresolvedTranslationInsteadOfReplacingItsMeaning() {
        assertFailsWith<IllegalArgumentException> { ComponentSegments.convert(Component.translatable("item.test")) }
    }
    @Test
    fun inheritsDeclaredFontAcrossChildren() {
        val source = Component.text("親").font(Key.key("minecraft:alt"))
            .append(Component.text("子"))
        val runs = ComponentSegments.convert(source).toLines()[0].segments
        assertTrue(runs.all { it.toJson().asJsonObject.get("font").asString == "minecraft:alt" })
    }

    @Test
    fun explicitDefaultFontStopsParentInheritance() {
        val source = Component.text("親").font(Key.key("minecraft:alt"))
            .append(Component.text("解除").font(Key.key("minecraft:default")))
        val runs = ComponentSegments.convert(source).toLines()[0].segments
        assertEquals("minecraft:alt", runs[0].toJson().asJsonObject.get("font").asString)
        assertEquals("minecraft:default", runs[1].toJson().asJsonObject.get("font").asString)
    }

}
