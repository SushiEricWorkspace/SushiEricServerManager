package io.github.sushiericworkspace.sushiericservermanager.feature.console

import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementCommandSuggestion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConsoleCommandModelTest {
    @Test
    fun `サーバーが返した範囲だけを補完候補で置換する`() {
        val result = ConsoleCommandModel().applySuggestion(
            command = "gamemode cre Steve",
            suggestion = ServerManagementCommandSuggestion(
                text = "creative",
                start = 9,
                end = 12
            )
        )

        assertEquals(
            ConsoleCompletionApplication(
                text = "gamemode creative Steve",
                caretPosition = 17
            ),
            result
        )
    }

    @Test
    fun `不正な置換範囲は適用しない`() {
        val model = ConsoleCommandModel()

        assertNull(
            model.applySuggestion(
                "say hello",
                ServerManagementCommandSuggestion("world", -1, 3)
            )
        )
        assertNull(
            model.applySuggestion(
                "say hello",
                ServerManagementCommandSuggestion("world", 4, 99)
            )
        )
    }

    @Test
    fun `上下操作で履歴と操作前の入力を往復する`() {
        val model = ConsoleCommandModel()
        model.record("say first")
        model.record("say second")

        assertEquals("say second", model.previous("say draft"))
        assertEquals("say first", model.previous("say second"))
        assertEquals("say second", model.next())
        assertEquals("say draft", model.next())
        assertNull(model.next())
    }

    @Test
    fun `連続して同じコマンドを実行しても履歴は重複しない`() {
        val model = ConsoleCommandModel()
        model.record("list")
        model.record("list")

        assertEquals("list", model.previous(""))
        assertEquals("", model.next())
    }

    @Test
    fun `候補が未選択の場合は先頭候補を返す`() {
        val suggestions = listOf(
            ServerManagementCommandSuggestion("creative", 9, 12),
            ServerManagementCommandSuggestion("spectator", 9, 12)
        )

        assertEquals(
            suggestions.first(),
            ConsoleCommandModel().selectSuggestion(suggestions, selectedIndex = -1)
        )
    }

    @Test
    fun `複数行から空行を除いて入力順に実行対象を返す`() {
        assertEquals(
            listOf("say first", "list", "say last"),
            ConsoleCommandModel().executableCommands("say first\n\n list \n  \nsay last")
        )
    }

    @Test
    fun `キャレットがある行だけを補完する`() {
        val text = "say first\ngamemode cre Steve\nlist"
        val result = ConsoleCommandModel().applySuggestionToCurrentLine(
            text = text,
            caretPosition = text.indexOf("cre") + 3,
            suggestion = ServerManagementCommandSuggestion(
                text = "creative",
                start = 9,
                end = 12
            )
        )

        assertEquals(
            ConsoleCompletionApplication(
                text = "say first\ngamemode creative Steve\nlist",
                caretPosition = text.indexOf("gamemode") + 17
            ),
            result
        )
    }

    @Test
    fun `行頭と行末でキャレットの属する行を取得する`() {
        val model = ConsoleCommandModel()
        val text = "first\nsecond\nthird"

        assertEquals(ConsoleCommandLine("first", 0, 5, 0), model.currentLine(text, 0))
        assertEquals(ConsoleCommandLine("second", 6, 12, 3), model.currentLine(text, 9))
        assertEquals(ConsoleCommandLine("third", 13, 18, 5), model.currentLine(text, text.length))
    }
}
