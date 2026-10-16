package io.github.sushiericworkspace.sushiericservermanager.feature.console

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 表示するログレベルの判定を検証する。
 */
class ConsoleLogLevelTest {

    @Test
    fun `レベルの文字列を変換する`() {
        assertEquals(ConsoleLogLevel.INFO, ConsoleLogLevel.from("INFO"))
        assertEquals(ConsoleLogLevel.WARN, ConsoleLogLevel.from("warn"))
        assertEquals(ConsoleLogLevel.ERROR, ConsoleLogLevel.from(" Error "))
        assertEquals(ConsoleLogLevel.FATAL, ConsoleLogLevel.from("FATAL"))
    }

    @Test
    fun `判別できないレベルはnullを返す`() {
        assertNull(ConsoleLogLevel.from("VERBOSE"))
        assertNull(ConsoleLogLevel.from(""))
    }

    @Test
    fun `既定はINFO以上を表示する`() {
        val minimum = ConsoleLogLevel.DEFAULT_MINIMUM

        assertEquals(ConsoleLogLevel.INFO, minimum)
        assertFalse(isVisibleAt(ConsoleLogLevel.DEBUG, minimum))
        assertFalse(isVisibleAt(ConsoleLogLevel.TRACE, minimum))
        assertTrue(isVisibleAt(ConsoleLogLevel.INFO, minimum))
        assertTrue(isVisibleAt(ConsoleLogLevel.WARN, minimum))
        assertTrue(isVisibleAt(ConsoleLogLevel.ERROR, minimum))
    }

    @Test
    fun `TRACEまで下げるとすべて表示する`() {
        ConsoleLogLevel.entries.forEach { level ->
            assertTrue(
                isVisibleAt(level, ConsoleLogLevel.TRACE),
                "$level が表示されません"
            )
        }
    }

    @Test
    fun `ERRORまで上げると下位のレベルを表示しない`() {
        val minimum = ConsoleLogLevel.ERROR

        assertFalse(isVisibleAt(ConsoleLogLevel.INFO, minimum))
        assertFalse(isVisibleAt(ConsoleLogLevel.WARN, minimum))
        assertTrue(isVisibleAt(ConsoleLogLevel.ERROR, minimum))
    }

    @Test
    fun `FATALはどの下限でも表示する`() {
        ConsoleLogLevel.SELECTABLE.forEach { minimum ->
            assertTrue(
                isVisibleAt(ConsoleLogLevel.FATAL, minimum),
                "下限が $minimum のときにFATALが表示されません"
            )
        }
    }

    @Test
    fun `レベルを持たない行はどの下限でも表示する`() {
        ConsoleLogLevel.SELECTABLE.forEach { minimum ->
            assertTrue(
                isVisibleAt(null, minimum),
                "下限が $minimum のときにコマンドの行が表示されません"
            )
        }
    }

    @Test
    fun `選べる下限はTRACEからERRORまでとする`() {
        assertEquals(
            listOf(
                ConsoleLogLevel.TRACE,
                ConsoleLogLevel.DEBUG,
                ConsoleLogLevel.INFO,
                ConsoleLogLevel.WARN,
                ConsoleLogLevel.ERROR
            ),
            ConsoleLogLevel.SELECTABLE
        )
    }
}
