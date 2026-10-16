package io.github.sushiericworkspace.sushiericservermanager.communication

import net.schmizz.sshj.SSHClient
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * SSH経由でコマンドを実行し、その出力を返します。
 *
 * 接続の確立と認証は呼び出し側の[SSHClient]が済ませている前提です。
 * ホストOSの状態取得のように、標準出力を読み取って利用する用途で使用します。
 *
 * 実行は呼び出したスレッドで完了まで待つため、画面を更新するスレッドから直接呼び出しません。
 *
 * @param timeoutSeconds 1回の実行を待つ上限。応答しないコマンドで待ち続けないために使用します。
 */
class SshCommandRunner(
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * コマンドを実行します。
     *
     * @param client 認証済みのSSHクライアント。
     * @param commandLine 実行するコマンド行。
     * @return 実行結果。セッションを開けなかった場合と例外が発生した場合は`null`。
     */
    fun run(
        client: SSHClient,
        commandLine: String
    ): SshCommandResult? =
        try {
            client.startSession().use { session ->
                session.exec(commandLine).use { command ->
                    /*
                     * 出力を読み切ってから終了を待つ。
                     * 先に終了を待つと、出力がバッファを超えた場合に相手が書き込みを続けられない。
                     */
                    val output =
                        command.inputStream
                            .bufferedReader(StandardCharsets.UTF_8)
                            .use { it.readText() }

                    val errorOutput =
                        command.errorStream
                            .bufferedReader(StandardCharsets.UTF_8)
                            .use { it.readText() }

                    command.join(timeoutSeconds, TimeUnit.SECONDS)

                    SshCommandResult(
                        exitStatus = command.exitStatus,
                        output = output,
                        errorOutput = errorOutput
                    )
                }
            }
        } catch (exception: Exception) {
            logger.warn("SSHコマンドを実行できませんでした。", exception)
            null
        }

    companion object {
        /** 1回の実行を待つ既定の上限です。 */
        const val DEFAULT_TIMEOUT_SECONDS: Long = 15
    }
}

/**
 * SSHで実行したコマンドの結果です。
 *
 * @property exitStatus 終了コード。取得できない場合は`null`。
 * @property output 標準出力。
 * @property errorOutput 標準エラー出力。
 */
data class SshCommandResult(
    val exitStatus: Int?,
    val output: String,
    val errorOutput: String
) {
    /** 正常終了したかを返します。 */
    val isSuccess: Boolean
        get() = exitStatus == 0
}
