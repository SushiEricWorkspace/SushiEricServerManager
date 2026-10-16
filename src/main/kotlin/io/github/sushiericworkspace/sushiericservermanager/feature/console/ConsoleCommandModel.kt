package io.github.sushiericworkspace.sushiericservermanager.feature.console

import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementCommandSuggestion

/** コマンド履歴と補完候補の適用を、JavaFXから独立して管理します。 */
class ConsoleCommandModel {
    private val history = mutableListOf<String>()
    private var historyPosition = 0
    private var draft = ""

    /** 実行したコマンドを履歴へ追加します。 */
    fun record(command: String) {
        if (history.lastOrNull() != command) {
            history.add(command)
        }
        resetHistoryNavigation()
    }

    /** 1つ前の履歴を返します。履歴が無い場合は`null`です。 */
    fun previous(current: String): String? {
        if (history.isEmpty()) return null
        if (historyPosition == history.size) draft = current
        if (historyPosition > 0) historyPosition--
        return history[historyPosition]
    }

    /** 1つ後の履歴、または履歴操作前の入力を返します。 */
    fun next(): String? {
        if (historyPosition >= history.size) return null
        historyPosition++
        return if (historyPosition == history.size) draft else history[historyPosition]
    }

    /** 入力編集後の履歴位置を末尾へ戻します。 */
    fun resetHistoryNavigation() {
        historyPosition = history.size
        draft = ""
    }

    /** 複数行入力から空行を除き、実行対象のコマンドを入力順で返します。 */
    fun executableCommands(text: String): List<String> = text
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toList()

    /** キャレットがある論理行と、その行内でのキャレット位置を返します。 */
    fun currentLine(text: String, caretPosition: Int): ConsoleCommandLine {
        val caret = caretPosition.coerceIn(0, text.length)
        val start = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0))
            .let { if (it < 0 || caret == 0) 0 else it + 1 }
        val end = text.indexOf('\n', caret).let { if (it < 0) text.length else it }
        return ConsoleCommandLine(
            text = text.substring(start, end),
            start = start,
            end = end,
            caretPosition = caret - start
        )
    }

    /** キャレットがある行へ、サーバーから返された補完候補を適用します。 */
    fun applySuggestionToCurrentLine(
        text: String,
        caretPosition: Int,
        suggestion: ServerManagementCommandSuggestion
    ): ConsoleCompletionApplication? {
        val line = currentLine(text, caretPosition)
        val applied = applySuggestion(line.text, suggestion) ?: return null
        return ConsoleCompletionApplication(
            text = text.replaceRange(line.start, line.end, applied.text),
            caretPosition = line.start + applied.caretPosition
        )
    }

    /** サーバーから返された置換範囲を使用して候補を適用します。 */
    fun applySuggestion(
        command: String,
        suggestion: ServerManagementCommandSuggestion
    ): ConsoleCompletionApplication? {
        if (suggestion.start !in 0..command.length) return null
        if (suggestion.end !in suggestion.start..command.length) return null

        val completed = command.replaceRange(
            suggestion.start,
            suggestion.end,
            suggestion.text
        )
        return ConsoleCompletionApplication(
            text = completed,
            caretPosition = suggestion.start + suggestion.text.length
        )
    }

    /**
     * 選択位置の候補を返します。
     *
     * Popup表示直後など選択位置が無い場合は先頭候補を返します。
     */
    fun selectSuggestion(
        suggestions: List<ServerManagementCommandSuggestion>,
        selectedIndex: Int
    ): ServerManagementCommandSuggestion? =
        suggestions.getOrNull(selectedIndex) ?: suggestions.firstOrNull()
}

/** 補完候補を適用した入力文字列とキャレット位置です。 */
data class ConsoleCompletionApplication(
    val text: String,
    val caretPosition: Int
)

/** 複数行入力内でキャレットが属する論理行です。 */
data class ConsoleCommandLine(
    val text: String,
    val start: Int,
    val end: Int,
    val caretPosition: Int
)
