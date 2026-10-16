package io.github.sushiericworkspace.sushiericservermanager.feature.console

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsoleLogBufferTest {
    @Test
    fun `上限を超えると古いログから破棄する`() {
        val buffer = ConsoleLogBuffer(capacity = 3)
        repeat(5) { index -> buffer.offer(entry(index)) }

        assertEquals(listOf("2", "3", "4"), buffer.drain(10).map { it.message })
    }

    @Test
    fun `指定件数ずつ古い順に取り出す`() {
        val buffer = ConsoleLogBuffer(capacity = 10)
        repeat(5) { index -> buffer.offer(entry(index)) }

        assertEquals(listOf("0", "1"), buffer.drain(2).map { it.message })
        assertEquals(3, buffer.size)
    }

    @Test
    fun `画面表示上限を超えると古いログから破棄する`() {
        val displayed = mutableListOf<ConsoleLogEntry>()
        appendConsoleLogs(displayed, (0..999).map(::entry), limit = 1_000)
        appendConsoleLogs(displayed, (1000..1099).map(::entry), limit = 1_000)

        assertEquals(1_000, displayed.size)
        assertEquals("100", displayed.first().message)
        assertEquals("1099", displayed.last().message)
    }

    @Test
    fun `大量ログを複数スレッドから追加しても上限を維持する`() {
        val capacity = 2_000
        val buffer = ConsoleLogBuffer(capacity)
        val start = CountDownLatch(1)
        val workers = List(4) { worker ->
            thread(start = true) {
                start.await()
                repeat(2_000) { index -> buffer.offer(entry(worker * 2_000 + index)) }
            }
        }

        start.countDown()
        workers.forEach(Thread::join)

        assertEquals(capacity, buffer.size)
        assertEquals(capacity, buffer.drain(capacity).size)
    }

    @Test
    fun `選択した行を改行で連結する`() {
        val separator = System.lineSeparator()

        assertEquals(
            "1行目${separator}2行目${separator}3行目",
            joinConsoleLines(listOf("1行目", "2行目", "3行目"))
        )
    }

    @Test
    fun `1行だけの場合は改行を付けない`() {
        assertEquals("1行目", joinConsoleLines(listOf("1行目")))
    }

    @Test
    fun `選択が無い場合は空文字を返す`() {
        assertEquals("", joinConsoleLines(emptyList()))
    }

    @Test
    fun `過去位置では自動スクロールを止め末尾で再開する`() {
        val policy = ConsoleAutoScrollPolicy()

        policy.update(current = 0.4, maximum = 1.0, scrollBarVisible = true)
        assertFalse(policy.isEnabled)

        policy.update(current = 1.0, maximum = 1.0, scrollBarVisible = true)
        assertTrue(policy.isEnabled)
    }

    private fun entry(index: Int) = ConsoleLogEntry(
        timestamp = "2026-09-09T01:02:03Z",
        level = "INFO",
        message = index.toString()
    )
}
