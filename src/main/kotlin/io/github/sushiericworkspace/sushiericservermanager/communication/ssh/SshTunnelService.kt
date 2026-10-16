package io.github.sushiericworkspace.sushiericservermanager.communication.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.ServerSocket

/**
 * 確立済みのSSH接続上へローカルポート転送を作成します。
 *
 * 新しいSSH接続を張らず、[SSHClient]をそのまま利用します。
 * ホスト鍵の検証は接続時に済んでいるため、Tunnelの作成で検証が飛ばされることはありません。
 *
 * 転送先はサーバー上のループバックへ固定します。
 * Management APIはサーバーの`127.0.0.1`のみでListenしており、
 * 接続先を外部ホストへ向けられる設定を持たせないためです。
 */
class SshTunnelService {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * ローカルポート転送を開始します。
     *
     * ローカル側はループバックの空きポートへbindします。
     * 同じ端末の他プロセスから転送を利用されないようにするためです。
     *
     * @param client 認証済みのSSHクライアント。
     * @param remotePort サーバー側の転送先ポート。
     * @return 開始したTunnel。
     * @throws java.io.IOException bindまたは転送の開始に失敗した場合。
     */
    fun open(
        client: SSHClient,
        remotePort: Int
    ): SshTunnel {
        val loopback =
            InetAddress.getLoopbackAddress()

        val serverSocket =
            ServerSocket(0, BACKLOG, loopback)

        return try {
            val tunnel =
                SshTunnel(
                    client = client,
                    serverSocket = serverSocket,
                    remotePort = remotePort
                )

            tunnel.start()

            logger.info(
                "SSH Tunnelを開始しました: 127.0.0.1:{} -> {}:{}",
                tunnel.localPort,
                REMOTE_HOST,
                remotePort
            )

            tunnel
        } catch (exception: Exception) {
            runCatching { serverSocket.close() }
            throw exception
        }
    }

    companion object {
        /**
         * サーバー側の転送先ホストです。
         *
         * Management APIはサーバーのループバックのみでListenするため固定します。
         */
        const val REMOTE_HOST: String = "127.0.0.1"

        private const val BACKLOG: Int = 8
    }
}

/**
 * 開始済みのローカルポート転送です。
 *
 * 転送の待ち受けはブロッキング処理のため、専用のデーモンスレッドで実行します。
 * [close]で待ち受け用のソケットを閉じ、スレッドを終了させます。
 *
 * SSH接続が切断された場合も待ち受けは終了します。
 * その場合、このTunnelは以降利用できません。
 *
 * @property localPort ローカル側の待ち受けポート。
 */
class SshTunnel internal constructor(
    private val client: SSHClient,
    private val serverSocket: ServerSocket,
    private val remotePort: Int
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var closed = false

    private var thread: Thread? = null

    val localPort: Int
        get() = serverSocket.localPort

    /** 待ち受けが継続しているかを返します。 */
    val isActive: Boolean
        get() = !closed && !serverSocket.isClosed && client.isConnected

    internal fun start() {
        val forwarder =
            client.newLocalPortForwarder(
                Parameters(
                    serverSocket.inetAddress.hostAddress,
                    serverSocket.localPort,
                    SshTunnelService.REMOTE_HOST,
                    remotePort
                ),
                serverSocket
            )

        val worker =
            Thread({
                try {
                    forwarder.listen()
                } catch (exception: Exception) {
                    /*
                     * closeでソケットを閉じた場合もここへ来る。
                     * 意図した終了と区別するため、closedのときは記録しない。
                     */
                    if (!closed) {
                        logger.warn("SSH Tunnelが終了しました。", exception)
                    }
                } finally {
                    closed = true
                }
            }, "ssh-tunnel-$localPort")

        worker.isDaemon = true
        worker.start()

        thread = worker
    }

    override fun close() {
        if (closed) {
            return
        }

        closed = true

        runCatching { serverSocket.close() }

        thread?.interrupt()
        thread = null
    }
}
