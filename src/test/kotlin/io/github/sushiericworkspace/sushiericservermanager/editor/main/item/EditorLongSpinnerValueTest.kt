package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.sushiericservermanager.editor.component.decrementLongSpinnerValue
import io.github.sushiericworkspace.sushiericservermanager.editor.component.incrementLongSpinnerValue
import io.github.sushiericworkspace.sushiericservermanager.editor.component.normalizeLongSpinnerValue
import kotlin.test.Test
import kotlin.test.assertEquals

class EditorLongSpinnerValueTest {
    @Test
    fun `入力値をLongの範囲内へ補正する`() {
        assertEquals(25L, normalizeLongSpinnerValue("25", 0L, 100L))
        assertEquals(100L, normalizeLongSpinnerValue("101", 0L, 100L))
        assertEquals(0L, normalizeLongSpinnerValue("-1", 0L, 100L))
        assertEquals(0L, normalizeLongSpinnerValue("", 0L, 100L))
        assertEquals(0L, normalizeLongSpinnerValue("9223372036854775808", 0L, Long.MAX_VALUE))
    }

    @Test
    fun `最大値を超える増加は最大値に留める`() {
        assertEquals(Long.MAX_VALUE, incrementLongSpinnerValue(Long.MAX_VALUE - 1L, 2, 1L, Long.MAX_VALUE))
        assertEquals(15L, incrementLongSpinnerValue(5L, 2, 5L, 100L))
    }

    @Test
    fun `最小値を下回る減少は最小値に留める`() {
        assertEquals(0L, decrementLongSpinnerValue(1L, 2, 1L, 0L))
        assertEquals(5L, decrementLongSpinnerValue(15L, 2, 5L, 0L))
    }
}
