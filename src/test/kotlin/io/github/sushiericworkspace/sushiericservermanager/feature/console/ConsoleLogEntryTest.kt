package io.github.sushiericworkspace.sushiericservermanager.feature.console

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConsoleLogEntryTest {
    @Test
    fun `表示文字列に時刻とログレベルと本文を含める`() {
        val entry = ConsoleLogEntry(
            timestamp = "2026-09-09T01:02:03Z",
            level = "warn",
            message = "Server overloaded"
        )

        assertEquals("WARN", entry.normalizedLevel)
        assertTrue(entry.displayText.startsWith("["))
        assertTrue(entry.displayText.contains("] [WARN] Server overloaded"))
    }

    @Test
    fun `解釈できない時刻はそのまま表示する`() {
        val entry = ConsoleLogEntry("unknown", "INFO", "message")

        assertEquals("unknown", entry.displayTime)
    }

    @Test
    fun `ログレベルに対応する表示クラスを選ぶ`() {
        assertEquals("console-log-trace", ConsoleController.levelStyleClass("TRACE"))
        assertEquals("console-log-debug", ConsoleController.levelStyleClass("DEBUG"))
        assertEquals("console-log-info", ConsoleController.levelStyleClass("INFO"))
        assertEquals("console-log-warn", ConsoleController.levelStyleClass("WARN"))
        assertEquals("console-log-error", ConsoleController.levelStyleClass("ERROR"))
        assertEquals("console-log-error", ConsoleController.levelStyleClass("FATAL"))
    }
}
