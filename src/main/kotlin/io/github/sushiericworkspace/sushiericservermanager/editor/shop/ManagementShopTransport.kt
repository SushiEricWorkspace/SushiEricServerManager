package io.github.sushiericworkspace.sushiericservermanager.editor.shop

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericservermanager.communication.management.*
import javafx.application.Platform
import io.github.sushiericworkspace.sushiericservermanager.communication.database.PostgresHostReference
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** 正規Management接続だけを使用し、拒否・切断時にHost/SFTPへ切り替えません。 */
class ManagementShopTransport(
    private val client: ServerManagementClient,
    private val binding: () -> ManagementDatabaseBinding? = { null },
    private val timeoutMillis: Long = 30_000,
    private val reference: PostgresHostReference? = null
) : ShopTransport {
    private val generation = client.connectionGeneration
    private var context: ManagementDatabaseBinding? = null

    override fun worldUuid(): String {
        check(!Platform.isFxApplicationThread()) { "商品DB操作はI/O workerで実行してください。" }
        val nonce = UUID.randomUUID().toString()
        val response = await(ServerManagementRequest.DatabaseContextRequest(nonce)) {
            (it as? ServerManagementResponse.DatabaseContext)?.takeIf { value -> value.nonce == nonce }
        }
        val current = requireNotNull(response.binding).also { require(response.success) }
        current.validate(reference)
        require(context == null || context == current) { "Managementのrunが変更されました。再接続してください。" }
        context = binding()?.also {
            it.validate(reference)
            require(it.worldUuid == current.worldUuid && it.runId == current.runId &&
                it.instanceId == current.instanceId && it.generationId == current.generationId && it.database == current.database)
        } ?: current.also {
            require(it.instanceId == null && it.generationId == null) { "管理worldの所有bindingが必要です。" }
        }
        return current.worldUuid
    }

    override fun databaseBinding() = context?.database ?: run { worldUuid(); requireNotNull(context).database }

    override fun execute(request: HostAdapterRequest): HostAdapterResponse {
        val fixed = requireNotNull(context) { "商品DBの接続先を確認してください。" }
        require(fixed.worldUuid == request.worldUuid)
        val nonce = UUID.randomUUID().toString()
        return await(ServerManagementRequest.DatabaseProductRequest(fixed, request, nonce)) {
            (it as? ServerManagementResponse.DatabaseProductResult)?.takeIf { value -> value.nonce == nonce }
        }.response.also { require(it.requestId == request.requestId && it.version == 1) }
    }

    private fun <T> await(request: ServerManagementRequest, select: (ServerManagementResponse) -> T?): T {
        check(!Platform.isFxApplicationThread()) { "商品DB操作はI/O workerで実行してください。" }
        val result = CompletableFuture<T>()
        val messageListener: (ServerManagementResponse) -> Unit = { message -> select(message)?.let(result::complete); Unit }
        val stateListener: (ServerManagementState) -> Unit = { state ->
            if (state !is ServerManagementState.Connected)
                result.completeExceptionally(IllegalStateException("Management接続が切断されました。"))
        }
        client.addMessageListener(messageListener)
        client.addStateListener(stateListener)
        try {
            check(client.connectionGeneration == generation && client.isConnected && client.send(request)) { "Management接続を利用できません。" }
            return result.get(timeoutMillis, TimeUnit.MILLISECONDS).also {
                check(client.isConnected && client.connectionGeneration == generation) { "商品DBの接続が終了しました。" }
            }
        } finally {
            client.removeMessageListener(messageListener)
            client.removeStateListener(stateListener)
        }
    }
}
