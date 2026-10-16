package io.github.sushiericworkspace.sushiericservermanager.feature.console

import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementResponse
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** コンソール画面に表示するログ1件です。 */
data class ConsoleLogEntry(val timestamp: String, val level: String, val message: String) {
    /** 画面表示用の時刻です。解釈できない値はそのまま返します。 */
    val displayTime: String
        get() = runCatching { DISPLAY_TIME_FORMAT.format(Instant.parse(timestamp)) }
            .getOrDefault(timestamp)

    /** CSSのレベル別クラスに使用する正規化済みログレベルです。 */
    val normalizedLevel: String
        get() = level.trim().uppercase().ifBlank { "INFO" }

    /** 時刻・レベル・本文をまとめた表示文字列です。 */
    val displayText: String
        get() = "[$displayTime] [$normalizedLevel] $message"

    companion object {
        private val DISPLAY_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault())

        /** Management APIの受信メッセージから表示データを作成します。 */
        fun from(message: ServerManagementResponse.ConsoleLog): ConsoleLogEntry = ConsoleLogEntry(
            timestamp = message.timestamp,
            level = message.level,
            message = message.message
        )
    }
}
