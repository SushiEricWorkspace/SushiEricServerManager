package io.github.sushiericworkspace.sushiericservermanager.serverselect.controller

import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfile

/**
 * 画面へ入力されたManagement APIポートを解釈する。
 *
 * 作成画面と編集画面で同じ規則を使うため、両方のControllerから利用する。
 */
internal object ManagementPortInput {

    /**
     * 入力文字列をポート番号へ変換する。
     *
     * 空欄は未指定として[ServerProfile.DEFAULT_MANAGEMENT_PORT]を返す。
     * ポートを指定しない利用者にも、既定のポートで接続を試せる状態を保つためである。
     *
     * @param text 入力欄の文字列。前後の空白は無視する。
     * @return 変換したポート番号。数値でない場合と[ServerProfile.MANAGEMENT_PORT_RANGE]の
     *         範囲外の場合は`null`。
     */
    fun parse(text: String): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ServerProfile.DEFAULT_MANAGEMENT_PORT

        val port = trimmed.toIntOrNull() ?: return null
        return port.takeIf { it in ServerProfile.MANAGEMENT_PORT_RANGE }
    }
}
