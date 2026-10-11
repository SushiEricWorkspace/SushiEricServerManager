package io.github.sushiericworkspace.sushiericservermanager.communication.management

import io.github.sushiericworkspace.sushiericservermanager.communication.ssh.SshTunnel
import io.github.sushiericworkspace.sushiericservermanager.communication.ssh.SshTunnelService
import net.schmizz.sshj.SSHClient
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import io.github.sushiericworkspace.sushiericservermanager.communication.managed.ManagedSession
import io.github.sushiericworkspace.sushiericservermanager.communication.managed.SupervisorFailure
import io.github.sushiericworkspace.sushiericservermanager.communication.management.codec.ServerManagementMessageCodec
import io.github.sushiericworkspace.sushiericservermanager.communication.management.codec.ServerManagementDecodeResult
import kotlinx.serialization.json.*

/**
 * Management APIへの接続を管理します。
 *
 * SSH Tunnelの作成からWebSocket接続までをまとめて扱い、接続状態を公開します。
 *
 * Management APIへ接続できない場合も例外を投げません。
 * SSHとSFTPだけを利用する状態を維持するため、失敗は状態として表します。
 */
class ServerManagementClient(
    private val tunnelService: SshTunnelService = SshTunnelService()
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val stateListeners =
        CopyOnWriteArrayList<(ServerManagementState) -> Unit>()

    private val messageListeners =
        CopyOnWriteArrayList<(ServerManagementResponse) -> Unit>()

    private var tunnel: SshTunnel? = null
    private var connection: ServerManagementConnection? = null
    private var managed: ManagedSession? = null
    private var managedSender: ExecutorService? = null
    @Volatile private var connectionId = UUID.randomUUID()

    /** 現在の接続状態です。 */
    @Volatile
    var state: ServerManagementState = ServerManagementState.Disconnected
        private set

    /** 接続済みかを返します。 */
    val isConnected: Boolean
        get() = state is ServerManagementState.Connected

    /**
     * 接続状態の変化を受け取ります。
     *
     * 通知は接続処理を行ったスレッドから呼ばれます。
     */
    fun addStateListener(
        listener: (ServerManagementState) -> Unit
    ) {
        stateListeners.add(listener)
    }

    /** 接続状態のリスナーを解除します。 */
    fun removeStateListener(
        listener: (ServerManagementState) -> Unit
    ) {
        stateListeners.remove(listener)
    }

    /**
     * 受信メッセージを受け取ります。
     *
     * 通知はWebSocketのスレッドから呼ばれます。
     */
    fun addMessageListener(
        listener: (ServerManagementResponse) -> Unit
    ) {
        messageListeners.add(listener)
    }

    /** 受信メッセージのリスナーを解除します。 */
    fun removeMessageListener(
        listener: (ServerManagementResponse) -> Unit
    ) {
        messageListeners.remove(listener)
    }

    /**
     * SSH Tunnelを作成し、Management APIへ接続します。
     *
     * 既に接続している場合は一度切断してから接続し直します。
     *
     * @param client 認証済みのSSHクライアント。
     * @param remotePort サーバー側のManagement APIポート。
     * @param timeout 接続の待ち時間。
     * @return 接続できた場合は`true`。
     */
    fun connect(
        client: SSHClient,
        remotePort: Int = DEFAULT_REMOTE_PORT,
        timeout: Duration = DEFAULT_TIMEOUT
    ): Boolean {
        disconnect()
        val id = UUID.randomUUID().also { connectionId = it }

        updateState(ServerManagementState.Connecting)

        return try {
            val openedTunnel =
                tunnelService.open(client, remotePort)

            tunnel = openedTunnel

            val opened =
                ServerManagementConnection(Listener(id))

            opened.connect(openedTunnel.localPort, timeout)
            connection = opened

            updateState(
                ServerManagementState.Connected(openedTunnel.localPort)
            )

            logger.info(
                "Management APIへ接続しました: localPort={}",
                openedTunnel.localPort
            )

            true
        } catch (exception: Exception) {
            /*
             * Management APIが起動していない場合もここへ来る。
             * SSHとSFTPは利用できる状態を保つため、例外を投げずに状態で表す。
             */
            logger.warn(
                "Management APIへ接続できません。SSHとSFTPのみ利用します。",
                exception
            )

            releaseResources()

            updateState(
                ServerManagementState.Failed(
                    reason = exception.message ?: exception::class.simpleName.orEmpty()
                )
            )

            false
        }
    }

    /**
     * 接続を閉じ、Tunnelを解放します。
     *
     * 接続していない場合も安全に呼び出せます。
     */
    fun disconnect() {
        if (managed != null) {
            managedSender?.shutdown()
            check(managedSender?.awaitTermination(20, TimeUnit.SECONDS) != false) { "管理APIのキューが終了していません。" }
            connection?.closeVerified()
            connectionId = UUID.randomUUID()
            connection = null
            managedSender = null
            managed = null
            updateState(ServerManagementState.Disconnected)
            return
        }
        connectionId = UUID.randomUUID()
        if (state is ServerManagementState.Disconnected) {
            releaseResources()
            return
        }

        releaseResources()
        updateState(ServerManagementState.Disconnected)
    }

    /**
     * 接続確認を送ります。
     *
     * @return 送信できた場合は送信したnonce。接続していない場合は`null`。
     */
    fun ping(): String? {
        val nonce =
            UUID.randomUUID().toString()
        return if (send(ServerManagementRequest.Ping(nonce))) {
            nonce
        } else {
            null
        }
    }

    /**
     * メッセージを送信します。
     *
     * @param request 送信するメッセージ。
     * @return 送信できた場合は`true`。
     */
    fun send(
        request: ServerManagementRequest
    ): Boolean {
        val session = managed ?: return connection?.send(request) == true
        if (session.state != ManagedSession.State.OPEN) return false
        val id = connectionId
        return when (request) {
            is ServerManagementRequest.CommandExecute,
            is ServerManagementRequest.MoneyHistorySave,
            is ServerManagementRequest.AchievementSave -> session.enqueueIo(buildJsonObject {
                put("action", "api"); put("message", Json.parseToJsonElement(ServerManagementMessageCodec.encode(request)))
                put("timeoutSeconds", 5)
            }, managedSender!!, result = { result ->
                    try {
                        check(result["completed"]?.jsonPrimitive?.booleanOrNull == true)
                        val response = result["apiResponse"] ?: error("管理API応答がありません。")
                        check(id == connectionId) { "管理APIの接続識別が変わりました。" }
                        val decoded = ServerManagementMessageCodec.decode(response.toString())
                        check(decoded is ServerManagementDecodeResult.Success) { "管理API応答を解釈できません。" }
                        Listener(id).onMessage(decoded.message)
                    } catch (_: Exception) {
                        session.holdUnknown()
                        if (id == connectionId) updateState(ServerManagementState.Failed("管理APIの完了が不明です。writer登録は保留されています。"))
                    }
            }, failure = { error ->
                if (error !is SupervisorFailure || !error.rejectedBeforeEffect) session.holdUnknown()
                if (id == connectionId) updateState(ServerManagementState.Failed("管理API要求を完了できませんでした。監督statusを確認してください。"))
            })
            // 監視socketでは状態を変更する要求を送信しません。
            is ServerManagementRequest.Ping,
            is ServerManagementRequest.CommandComplete,
            is ServerManagementRequest.MonitorSubscribe,
            is ServerManagementRequest.MonitorUnsubscribe,
            ServerManagementRequest.ConsoleSubscribe,
            ServerManagementRequest.ConsoleUnsubscribe -> connection?.send(request) == true
        }
    }

    /** statusから得た固定endpointへ監視専用socketを張ります。SSH・自動再接続は利用しません。 */
    fun connectManaged(session: ManagedSession, timeout: Duration = DEFAULT_TIMEOUT): Boolean {
        disconnect()
        val id = UUID.randomUUID().also { connectionId = it }
        managed = session
        managedSender = Executors.newSingleThreadExecutor { Thread(it, "managed-api-write").apply { isDaemon = true } }
        updateState(ServerManagementState.Connecting)
        val opened = ServerManagementConnection(Listener(id))
        connection = opened
        return try {
            opened.connect(session.endpointPort, timeout)
            updateState(ServerManagementState.Connected(session.endpointPort))
            true
        } catch (_: Exception) {
            session.holdUnknown()
            updateState(ServerManagementState.Failed("管理監視socketへ接続できません。writer登録は保留されています。"))
            false
        }
    }

    private fun releaseResources() {
        runCatching { connection?.close() }
        runCatching { tunnel?.close() }

        connection = null
        tunnel = null
    }

    private fun updateState(
        next: ServerManagementState
    ) {
        state = next

        stateListeners.forEach { listener ->
            runCatching { listener(next) }
                .onFailure {
                    logger.warn("接続状態の通知に失敗しました。", it)
                }
        }
    }

    /**
     * 接続からの通知を受け取ります。
     */
    private inner class Listener(private val id: UUID) : ServerManagementListener {

        override fun onMessage(message: ServerManagementResponse) {
            if (id != connectionId) return
            messageListeners.forEach { listener ->
                runCatching { listener(message) }
                    .onFailure {
                        logger.warn("受信メッセージの通知に失敗しました。", it)
                    }
            }
        }

        override fun onClosed(statusCode: Int, reason: String) {
            if (id != connectionId) return
            if (managed != null) {
                updateState(ServerManagementState.Disconnected)
                return
            }
            logger.info(
                "Management APIの接続が閉じました: code={} reason={}",
                statusCode,
                reason
            )

            releaseResources()
            updateState(ServerManagementState.Disconnected)
        }

        override fun onError(error: Throwable) {
            if (id != connectionId) return
            if (managed != null) {
                updateState(ServerManagementState.Failed("管理監視socketの終了が未確認です。"))
                return
            }
            logger.warn("Management APIの接続でエラーが発生しました。", error)

            releaseResources()
            updateState(
                ServerManagementState.Failed(
                    reason = error.message ?: error::class.simpleName.orEmpty()
                )
            )
        }
    }

    companion object {
        /** サーバー側のManagement APIの既定ポートです。 */
        const val DEFAULT_REMOTE_PORT: Int = 25580

        /** 接続の既定待ち時間です。 */
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

/**
 * Management APIの接続状態です。
 *
 * 利用者へ提示するため、未接続と接続失敗を区別します。
 */
sealed interface ServerManagementState {

    /** 接続していません。 */
    data object Disconnected : ServerManagementState

    /** 接続処理中です。 */
    data object Connecting : ServerManagementState

    /**
     * 接続済みです。
     *
     * @property localPort SSH Tunnelのローカル側ポート。
     */
    data class Connected(
        val localPort: Int
    ) : ServerManagementState

    /**
     * 接続に失敗しました。SSHとSFTPは利用できます。
     *
     * @property reason 失敗した理由。
     */
    data class Failed(
        val reason: String
    ) : ServerManagementState
}
