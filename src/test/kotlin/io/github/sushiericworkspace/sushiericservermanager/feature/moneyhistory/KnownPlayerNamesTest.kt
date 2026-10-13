package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class KnownPlayerNamesTest {
    @Test
    fun `多数の名前キャッシュなしプレイヤー一覧でも履歴を読み込まない`() {
        val memory = InMemoryEditorDataStore()
        repeat(300) { index ->
            val uuid = UUID(0, index.toLong()).toString()
            memory.writeText("player_data/$uuid/money_history/day.yml", "entries: []")
        }
        var historyReads = 0
        val store = object : EditorDataStore by memory {
            override fun readText(relativePath: String): StoreResult<String> {
                if (relativePath.startsWith("player_data/")) historyReads++
                return memory.readText(relativePath)
            }
        }
        val players = readHistoryPlayers(store, LoggerFactory.getLogger(javaClass))
        assertEquals(300, players.size)
        assertEquals(0, historyReads)
        players.forEach { assertEquals(it.uuid, it.displayName) }
    }
}
