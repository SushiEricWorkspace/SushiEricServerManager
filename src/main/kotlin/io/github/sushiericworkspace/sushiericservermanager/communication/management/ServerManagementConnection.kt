package io.github.sushiericworkspace.sushiericservermanager.communication.management

import io.github.sushiericworkspace.sushiericservermanager.communication.management.codec.ServerManagementDecodeResult
import io.github.sushiericworkspace.sushiericservermanager.communication.management.codec.ServerManagementMessageCodec
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Management APIとのWebSocket接続1本です。
 *
 * 接続はSSH Tunnelのローカル側へ張ります。転送先はサーバーのループバックへ
 * 固定されているため、この接続が外部ホストへ向くことはありません。
 *
 * 受信したメッセージは[listener]へ渡します。復号できないメッセージは
 * 記録するだけで接続を維持します。サーバー側が新しい種別を送っても
 * 接続が落ちないようにするためです。
 */
class ServerManagementConnection internal constructor(
    private val listener: ServerManagementListener
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val buffer = StringBuilder()
    private val closed = CompletableFuture<Unit>()
    private var connecting: CompletableFuture<WebSocket>? = null

    @Volatile
    private var webSocket: WebSocket? = null

    /**
     * 送信を1本ずつ順番に実行するスレッドです。
     *
     * JDKのWebSocketは、直前の送信が完了する前に次の送信を要求すると受け付けません。
     * コンソールの購読と監視の購読のように、別々のスレッドから続けて送る場面があるため、
     * 送信をこのスレッドへ集約して直列化します。
     */
    private val sendExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "management-api-send").apply {
                isDaemon = true
            }
        }

    /** 接続が生存しているかを返します。 */
    val isOpen: Boolean
        get() = webSocket?.isInputClosed == false

    /**
     * 指定したローカルポートのManagement APIへ接続します。
     *
     * @param localPort SSH Tunnelのローカル側ポート。
     * @param timeout 接続の待ち時間。
     * @throws java.io.IOException 接続に失敗した場合。
     */
    internal fun connect(
        localPort: Int,
        timeout: Duration
    ) {
        val uri =
            URI.create("ws://$LOOPBACK:$localPort$PATH")

        val future = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .connectTimeout(timeout)
                .buildAsync(uri, Adapter())
        connecting = future
        try {
            webSocket = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (error: Exception) {
            // 期限後にhandshakeが成立してもhandleを保持し、Closeを要求する。
            future.thenAccept { socket -> socket.sendClose(WebSocket.NORMAL_CLOSURE, "late managed connection") }
            throw error
        }
    }

    /**
     * メッセージを送信します。
     *
     * 実際の送信は専用スレッドで順番に行います。
     * 戻り値は送信を受け付けたかどうかを表し、相手へ届いたことまでは保証しません。
     * 送信自体に失敗した場合は記録します。
     *
     * @param request 送信するメッセージ。
     * @return 送信を受け付けた場合は`true`。接続していない場合は`false`。
     */
    fun send(
        request: ServerManagementRequest
    ): Boolean {
        val socket =
            webSocket
                ?: return false

        val text =
            ServerManagementMessageCodec.encode(request)

        return try {
            sendExecutor.execute {
                runCatching {
                    socket.sendText(text, true)
                        .get(SEND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                }.onFailure { error ->
                    logger.warn("Management APIへの送信に失敗しました。", error)
                }
            }

            true
        } catch (exception: Exception) {
            logger.warn("Management APIへの送信を受け付けられませんでした。", exception)
            false
        }
    }

    /**
     * 接続を閉じます。
     *
     * 閉じる直前に要求された購読解除などを送りきってから終了します。
     * 送信が終わらない場合は待ち時間で打ち切り、接続の解放を優先します。
     */
    fun close() {
        val socket =
            webSocket

        webSocket = null

        sendExecutor.shutdown()

        runCatching {
            if (!sendExecutor.awaitTermination(CLOSE_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                sendExecutor.shutdownNow()
            }
        }.onFailure {
            sendExecutor.shutdownNow()
        }

        if (socket == null) {
            return
        }

        runCatching {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "closed by manager")
        }

        runCatching { socket.abort() }
    }

    /** 管理sessionでは送信キューと相手のCloseを確認し、timeoutを終了証明にしません。 */
    internal fun closeVerified() {
        check(connecting?.isDone == true && connecting?.isCompletedExceptionally == false) { "監視接続の確立結果が未確認です。" }
        sendExecutor.shutdown()
        check(sendExecutor.awaitTermination(SEND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) { "監視送信キューが終了していません。" }
        val socket = webSocket
        if (socket != null && !closed.isDone) {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "managed session closed")
                .get(SEND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            closed.get(SEND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }
        check(closed.isDone && !closed.isCompletedExceptionally) { "監視socketの終了が未確認です。" }
        webSocket = null
    }

    /**
     * JDKのWebSocketコールバックを[ServerManagementListener]へ橋渡しします。
     */
    private inner class Adapter : WebSocket.Listener {

        override fun onOpen(webSocket: WebSocket) {
            this@ServerManagementConnection.webSocket = webSocket
            webSocket.request(Long.MAX_VALUE)
        }

        override fun onText(
            webSocket: WebSocket,
            data: CharSequence,
            last: Boolean
        ): CompletionStage<*>? {
            buffer.append(data)

            if (!last) {
                return null
            }

            val text = buffer.toString()
            buffer.setLength(0)

            when (val result = ServerManagementMessageCodec.decode(text)) {
                is ServerManagementDecodeResult.Success ->
                    listener.onMessage(result.message)

                is ServerManagementDecodeResult.Failure ->
                    logger.warn(
                        "Management APIからのメッセージを解釈できません: {} {}",
                        result.reason,
                        result.detail
                    )
            }

            return null
        }

        override fun onClose(
            webSocket: WebSocket,
            statusCode: Int,
            reason: String
        ): CompletionStage<*>? {
            closed.complete(Unit)
            listener.onClosed(statusCode, reason)
            return null
        }

        override fun onError(
            webSocket: WebSocket,
            error: Throwable
        ) {
            closed.completeExceptionally(error)
            listener.onError(error)
        }
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val PATH = "/management"

        /** 1件の送信完了を待つ上限です。応答が無いまま送信スレッドを塞がないために使用します。 */
        const val SEND_TIMEOUT_MILLIS = 5_000L

        /** 接続を閉じる前に、残っている送信の完了を待つ上限です。 */
        const val CLOSE_WAIT_MILLIS = 500L
    }
}

/**
 * Management API接続の受信通知先です。
 *
 * 通知はWebSocketのスレッドから呼ばれます。
 * UIを更新する場合は呼び出し側でJavaFXスレッドへ移してください。
 */
interface ServerManagementListener {

    /** メッセージを受信したときに呼ばれます。 */
    fun onMessage(message: ServerManagementResponse)

    /** 接続が閉じたときに呼ばれます。 */
    fun onClosed(statusCode: Int, reason: String)

    /** 通信エラーが発生したときに呼ばれます。 */
    fun onError(error: Throwable)
}
