package io.github.sushiericworkspace.sushiericservermanager.editor.render

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewTextTest {
    private val coverage get() = PreviewResources.codepoints

    @Test
    fun retainsSupportedStylesAndCombiningCharacters() {
        val run = ComponentSegments.Run("日本語e\u0301", ComponentSegments.Style(bold = true, italic = true))
        val result = PreviewText.display(run, coverage)
        assertEquals(run.text, result.runs.last().text)
        assertEquals(run.style, result.runs.last().style)
        assertNull(result.notice)
    }

    @Test
    fun identifiesUnsupportedFontWithoutChangingSource() {
        val run = ComponentSegments.Run("元の文字", ComponentSegments.Style(font = "minecraft:alt"))
        val result = PreviewText.display(run, coverage)
        assertTrue(result.runs.first().text.contains("未対応フォント: minecraft:alt"))
        assertTrue(result.runs.last().text.endsWith(run.text))
        assertEquals("minecraft:alt", run.style.font)
        assertNotNull(result.notice)
    }

    @Test
    fun marksMissingSupplementaryCharacterAsOneCodepoint() {
        val run = ComponentSegments.Run("A\uD83D\uDE00B", ComponentSegments.Style())
        val result = PreviewText.display(run, coverage)
        assertTrue(result.runs.last().text.endsWith("A[U+1F600]B"))
        assertEquals("未対応文字: U+1F600", result.notice)
        assertEquals(3, run.text.codePointCount(0, run.text.length))
    }

    @Test
    fun obfuscatesWholeCodepointsDeterministicallyWithoutMutatingSource() {
        val run = ComponentSegments.Run("日本 A", ComponentSegments.Style(obfuscated = true))
        val first = PreviewText.display(run, coverage)
        assertEquals(first, PreviewText.display(run, coverage))
        assertEquals(4, first.runs.single().text.length)
        assertEquals(' ', first.runs.single().text[2])
        assertEquals("日本 A", run.text)
    }

    @Test
    fun keepsBodyDecorationsSeparateFromMissingCharacterWarning() {
        val style = ComponentSegments.Style(color = 0x123456, bold = true, italic = true,
            underlined = true, strikethrough = true)
        val source = ComponentSegments.Run("日本語\uD83D\uDE00", style)
        val result = PreviewText.display(source, coverage)
        assertEquals(2, result.runs.size)
        assertEquals(0xffcc66, result.runs.first().style.color)
        assertEquals(style, result.runs.last().style)
        assertEquals("日本語[U+1F600]", result.runs.last().text)
    }

    @Test
    fun showsOriginalUnsupportedFontContentWithoutObfuscationAndKeepsOtherDecorations() {
        val style = ComponentSegments.Style(font = "custom:unsupported", color = 0x123456,
            bold = true, italic = true, underlined = true, strikethrough = true, obfuscated = true)
        val source = ComponentSegments.Run("元の内容 ABC", style)
        val result = PreviewText.display(source, coverage)
        assertTrue(result.runs.first().text.contains("未対応フォント: custom:unsupported"))
        assertEquals(source.text, result.runs.last().text)
        assertEquals(style.copy(obfuscated = false), result.runs.last().style)
        assertTrue(source.style.obfuscated)
        assertEquals("custom:unsupported", source.style.font)
        assertEquals("元の内容 ABC", source.text)
    }
}
