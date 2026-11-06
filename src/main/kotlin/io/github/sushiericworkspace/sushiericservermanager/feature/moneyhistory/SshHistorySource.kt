package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import javafx.application.Platform
import net.schmizz.sshj.SSHClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 非管理Hostの固定CLIを、認証済みSSHのstdin/stdoutで呼びます。
 * commandLineは配備済み固定runtimeと絶対world/shop pathを指すOS別起動コマンドです。
 * 履歴JSONを引数へ埋め込まず、timeout・切断を成功扱いにしません。管理rootはCLIが拒否します。
 */
class SshHistorySource(
    private val client: () -> SSHClient,
    private val commandLine: String,
    private val worldId: String,
    private val timeoutMillis: Long = 30_000
) : HistorySource {
    private val fixedClient by lazy { client() }

    override fun open(filter: HostHistoryFilter): HistorySearch = HostHistorySource(worldId, ::execute).open(filter)

    private fun execute(request: HostAdapterRequest): HostAdapterResponse {
        check(!Platform.isFxApplicationThread()) { "履歴DB操作はI/O workerで実行してください。" }
        require(request.worldUuid == worldId)
        check(client() === fixedClient) { "検索元のSSH接続が変更されました。" }
        val input = HostAdapterCodec.encodeRequest(request)
        val workers = Executors.newFixedThreadPool(3)
        try {
            fixedClient.startSession().use { session ->
                session.exec(commandLine).use { command ->
                    val output = workers.submit<ByteArray> { command.inputStream.readNBytes(HostAdapterCodec.MAX_OUTPUT_BYTES + 1) }
                    val error = workers.submit<Unit> {
                        val buffer = ByteArray(8192)
                        while (command.errorStream.read(buffer) != -1) Unit
                    }
                    val sent = workers.submit<Unit> { command.outputStream.use { it.write(input) } }
                    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                    fun remaining() = (deadline - System.nanoTime()).coerceAtLeast(1)
                    try {
                        sent.get(remaining(), TimeUnit.NANOSECONDS)
                        val bytes = output.get(remaining(), TimeUnit.NANOSECONDS)
                        require(bytes.size <= HostAdapterCodec.MAX_OUTPUT_BYTES)
                        error.get(remaining(), TimeUnit.NANOSECONDS)
                        command.join(remaining(), TimeUnit.NANOSECONDS)
                        check(client() === fixedClient) { "検索元のSSH接続が変更されました。" }
                        val response = HostAdapterCodec.decodeResponse(bytes)
                        val status = when (response.status) {
                            HostAdapterStatus.SUCCESS -> 0
                            HostAdapterStatus.REJECTED -> 2
                            HostAdapterStatus.UNKNOWN -> 3
                        }
                        require(command.exitStatus == status && response.requestId == request.requestId)
                        return response
                    } finally {
                        sent.cancel(true)
                        output.cancel(true)
                        error.cancel(true)
                    }
                }
            }
        } finally {
            workers.shutdownNow()
            check(workers.awaitTermination(5, TimeUnit.SECONDS)) { "SSH履歴操作の終了を確認できません。" }
        }
    }
}
