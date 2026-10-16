package io.github.sushiericworkspace.sushiericservermanager.feature.servercontrol

import io.github.sushiericworkspace.sushiericservermanager.config.ServerControlCommandSet

/**
 * サーバープロセスへ対する操作の種類です。
 *
 * 実行するコマンドは利用者が登録した文字列であり、systemd、起動スクリプト、
 * サービスなど接続先の運用方法を問わず扱えるようにしています。
 *
 * @property displayName 画面へ表示する操作名。
 */
enum class ServerControlCommand(
    val displayName: String
) {
    START("起動"),
    STOP("停止"),
    RESTART("再起動");

    /**
     * 登録されたコマンドを返します。
     *
     * 前後の空白は取り除きます。
     *
     * @return 登録されたコマンド。未設定の場合は`null`。
     */
    fun commandOf(commandSet: ServerControlCommandSet?): String? {
        val command =
            when (this) {
                START -> commandSet?.startCommand
                STOP -> commandSet?.stopCommand
                RESTART -> commandSet?.restartCommand
            }

        return command?.let(ServerControlCommandLine::combine)
    }

    /** コマンドが登録されているかを返します。 */
    fun isConfigured(commandSet: ServerControlCommandSet?): Boolean =
        commandOf(commandSet) != null
}
