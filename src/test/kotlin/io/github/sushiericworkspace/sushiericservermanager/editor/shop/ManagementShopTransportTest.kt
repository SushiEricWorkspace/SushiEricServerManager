package io.github.sushiericworkspace.sushiericservermanager.editor.shop

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericservermanager.communication.management.*
import io.github.sushiericworkspace.sushiericservermanager.communication.management.codec.ServerManagementDecodeResult
import io.github.sushiericworkspace.sushiericservermanager.communication.management.codec.ServerManagementMessageCodec
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.test.*

class ManagementShopTransportTest {
    @TempDir lateinit var root: Path
    private val world = UUID.randomUUID().toString()

    private class Transport(val binding: ManagementDatabaseBinding) : ManagementTransport {
        lateinit var listener: ServerManagementListener
        var drop = false
        var wrongNonce = false
        val products = mutableListOf<ServerManagementRequest.DatabaseProductRequest>()
        override fun connect(listener: ServerManagementListener, timeout: Duration): Int {
            this.listener = listener
            return 25580
        }
        override fun disconnect() = Unit
        override fun send(request: ServerManagementRequest): Boolean {
            when (request) {
                is ServerManagementRequest.DatabaseContextRequest ->
                    listener.onMessage(ServerManagementResponse.DatabaseContext(true, binding, nonce = request.nonce))
                is ServerManagementRequest.DatabaseProductRequest -> {
                    products += request
                    if (drop) listener.onClosed(1006, "応答喪失")
                    else listener.onMessage(ServerManagementResponse.DatabaseProductResult(
                        HostAdapterResponse(requestId = request.request.requestId, status = HostAdapterStatus.SUCCESS,
                            result = HostAdapterResult.Edited(emptyList())),
                        if (wrongNonce) "other" else request.nonce))
                }
                else -> error("未到達")
            }
            return true
        }
    }

    @Test fun `Modと同じwireを使い要求worldとrunを所有接続へ固定する`() {
        val client = ServerManagementClient()
        val binding = ManagementDatabaseBinding(world, UUID.randomUUID().toString())
        val wire = Transport(binding)
        assertTrue(client.connect(wire))
        try {
            val transport = ManagementShopTransport(client)
            assertEquals(world, transport.worldUuid())
            val request = HostAdapterRequest(requestId = UUID.randomUUID().toString(), worldUuid = world,
                action = HostAdapterAction.Edit(listOf(HostProductChange.Save(
                    "tab.apple", null, HostDefinition("item", 10), HostStockEdit.Register(null, null)))))
            assertEquals(HostAdapterStatus.SUCCESS, transport.execute(request).status)
            assertEquals(binding, wire.products.single().binding)
            val json = ServerManagementMessageCodec.encode(wire.products.single())
            assertTrue(json.contains("database_product_request"))
            assertTrue(json.contains("worldUuid"))
            val response = """{"type":"database_product_result","response":{"requestId":"${request.requestId}","status":"SUCCESS","result":{"type":"io.github.sushiericworkspace.sushiericdatamodel.database.host.HostAdapterResult.Edited","transactionIds":[]}}}"""
            assertIs<ServerManagementDecodeResult.Success>(ServerManagementMessageCodec.decode(response))
        } finally { client.disconnect() }
    }

    @Test fun `送信後切断と異なるnonceの応答は保存待ちを残す`() {
        for (drop in listOf(false, true)) {
            val client = ServerManagementClient()
            val wire = Transport(ManagementDatabaseBinding(world, UUID.randomUUID().toString())).apply {
                this.drop = drop; wrongNonce = !drop
            }
            assertTrue(client.connect(wire))
            try {
                val session = ShopSaveSession(ManagementShopTransport(client, timeoutMillis = 20),
                    root.resolve("pending-$drop.json"))
                val result = session.execute(HostAdapterAction.Edit(listOf(HostProductChange.Save(
                    "tab.apple", null, HostDefinition("item", 10), HostStockEdit.Register(1, null)))))
                assertEquals(HostAdapterStatus.UNKNOWN, result.status)
                assertNotNull(session.pending())
            } finally { client.disconnect() }
        }
    }

    @Test fun `管理binding不在では所有情報を推測せず送信しない`() {
        val client = ServerManagementClient()
        val wire = Transport(ManagementDatabaseBinding(world, UUID.randomUUID().toString(),
            UUID.randomUUID().toString(), UUID.randomUUID().toString()))
        assertTrue(client.connect(wire))
        try {
            assertFails { ManagementShopTransport(client).worldUuid() }
            assertTrue(wire.products.isEmpty())
        } finally { client.disconnect() }
    }
}
