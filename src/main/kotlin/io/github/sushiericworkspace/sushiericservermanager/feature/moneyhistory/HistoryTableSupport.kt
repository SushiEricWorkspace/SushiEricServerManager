package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import javafx.scene.control.TableView
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent

/** 履歴表のセル文字を右クリックメニューとコピーショートカットでクリップボードへ送ります。 */
internal object HistoryTableSupport {
    private const val LAST_CLICKED_TEXT_KEY = "history.lastClickedText"

    /** 表にコピー用のショートカットを登録します。 */
    fun install(table: TableView<*>) {
        table.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (event.code == KeyCode.C && event.isShortcutDown) {
                val value = table.properties[LAST_CLICKED_TEXT_KEY] as? String ?: return@addEventFilter
                copy(value)
                event.consume()
            }
        }
    }

    /** 最後にクリックしたセルの文字をショートカット用に保持します。 */
    fun rememberClickedText(table: TableView<*>, text: String) {
        table.properties[LAST_CLICKED_TEXT_KEY] = text
    }

    /** 文字列をシステムクリップボードへコピーします。 */
    fun copy(text: String) {
        ClipboardContent().apply { putString(text) }.let(Clipboard.getSystemClipboard()::setContent)
    }
}
