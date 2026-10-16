package io.github.sushiericworkspace.sushiericservermanager.feature.console

/**
 * コンソールへ表示するログの重大度です。
 *
 * 宣言の順序がそのまま重大度の低い順になります。表示の下限と比較して絞り込みます。
 *
 * サーバーはすべてのレベルを配信するため、画面側で必要な範囲だけを表示します。
 */
internal enum class ConsoleLogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,

    /** [ERROR]より重大な障害です。下限の選択にかかわらず表示します。 */
    FATAL;

    companion object {

        /** 表示の下限として選べるレベルです。[FATAL]は常に表示するため含めません。 */
        val SELECTABLE: List<ConsoleLogLevel> =
            listOf(TRACE, DEBUG, INFO, WARN, ERROR)

        /** 表示の下限の既定値です。 */
        val DEFAULT_MINIMUM: ConsoleLogLevel = INFO

        /**
         * ログレベルの文字列を変換します。
         *
         * @param level サーバーから受け取ったレベル。前後の空白と大文字小文字は無視します。
         * @return 対応するレベル。判別できない場合は`null`。
         */
        fun from(level: String): ConsoleLogLevel? =
            entries.firstOrNull { it.name == level.trim().uppercase() }
    }
}

/**
 * 表示の下限に対して、その行を表示するかを返します。
 *
 * レベルを持たない行はコマンドの入力と実行結果であり、下限にかかわらず表示します。
 * 判別できなかったレベルも同様に表示し、受信した内容を隠しません。
 *
 * @param level 行のレベル。コマンドの行や判別できないレベルの場合は`null`。
 * @param minimum 表示する下限のレベル。
 */
internal fun isVisibleAt(
    level: ConsoleLogLevel?,
    minimum: ConsoleLogLevel
): Boolean =
    level == null || level >= minimum
