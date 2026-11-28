package io.github.sushiericworkspace.sushiericservermanager.communication.management

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericdatamodel.database.run.*
import io.github.sushiericworkspace.sushiericservermanager.communication.database.PostgresHostReference
import io.github.sushiericworkspace.sushiericservermanager.communication.management.codec.*
import io.github.sushiericworkspace.sushiericservermanager.editor.shop.ManagementShopTransport
import io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory.ManagementHistorySource
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfile
import io.github.sushiericworkspace.sushiericservermanager.config.DatabaseHistoryHost
import io.github.sushiericworkspace.sushiericservermanager.editor.store.RemoteEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory.historySource
import org.mockito.Mockito
import java.security.MessageDigest
import java.nio.ByteBuffer
import java.time.Duration
import java.util.UUID
import kotlin.test.*

class ManagementPostgresContractTest {
    private val json = Json { encodeDefaults = true }
    private fun vector(name: String) = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/management/$name")).readText()).jsonObject
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    @Test fun stopVectors() {
        val cases = vector("management-stop-vectors-pg.json").getValue("cases").jsonArray
        assertEquals(19, cases.size)
        for (entry in cases) {
            val case = entry.jsonObject
            val decoded = ServerManagementMessageCodec.decode(case.getValue("response").toString())
            if (case.text("expected") == "INVALID") {
                assertIs<ServerManagementDecodeResult.Failure>(decoded, case.text("name"))
                continue
            }
            val response = assertIs<ServerManagementDecodeResult.Success>(decoded, case.text("name")).message
            val (nonce, rejected) = when (response) {
                is ServerManagementResponse.DatabaseContext -> response.nonce to response.rejectedBeforeEffect
                is ServerManagementResponse.DatabaseHistory -> response.nonce to response.rejectedBeforeEffect
                is ServerManagementResponse.MoneyHistorySaveResult -> response.nonce to response.rejectedBeforeEffect
                is ServerManagementResponse.DatabaseProductResult -> response.nonce to response.response.rejectedBeforeEffect
                else -> error("未到達")
            }
            assertEquals(case.text("expected") == "REJECTED", nonce == case.text("expectedNonce") && rejected, case.text("name"))
        }
    }

    private fun wireReference(): Pair<PostgresHostReference, ManagementDatabaseBinding> {
        val vector = vector("mod-control-vectors-v3-pg.json")
        val context = vector.getValue("contexts").jsonObject.getValue("Windows").jsonObject
        val descriptor = Json.parseToJsonElement(context.text("descriptorJson")).jsonObject
        val session = vector.getValue("sessions").jsonArray.first { it.jsonObject.text("contextId") == "Windows" }.jsonObject
        val pair = session.getValue("pairs").jsonArray.first { it.jsonObject.text("name") == "BOUND" }.jsonObject
        val request = pair.getValue("request").jsonObject
        val frame = request.text("frameHex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(frame.size - 4, ByteBuffer.wrap(frame).int)
        val body = frame.copyOfRange(4, frame.size)
        assertEquals(request.text("bodySha256"), hash(body))
        val message = Json.parseToJsonElement(body.decodeToString()).jsonObject
        assertEquals(3, message.getValue("version").jsonPrimitive.int)
        assertEquals(hash(context.text("descriptorJson").encodeToByteArray()), message.text("descriptorSha256"))
        val response = pair.getValue("response").jsonObject.text("frameHex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val reply = Json.parseToJsonElement(response.copyOfRange(4, response.size).decodeToString()).jsonObject
        assertEquals(message.getValue("requestNonce"), reply.getValue("requestNonce"))
        assertEquals(request.text("bodySha256"), reply.getValue("payload").jsonObject.text("requestBodySha256"))
        val payload = message.getValue("payload").jsonObject
        val status = Json.parseToJsonElement(payload.text("statusJson")).jsonObject
        assertEquals(payload.text("statusJsonSha256"), hash(payload.text("statusJson").encodeToByteArray()))
        val state = PostgresRunCodec.decode(status.getValue("status").jsonObject.getValue("state").toString().encodeToByteArray())
        val reference = PostgresHostReference(runState = Json.encodeToString(state),
            shop = json.decodeFromJsonElement(PostgresRunShopBinding.serializer(), status.getValue("shop")),
            javaExecutable = "/fixed/java", runtimeDirectory = "/fixed/runtime", settingsFile = "/protected/settings.json")
        val binding = ManagementDatabaseBinding(state.binding.worldUuid, (state.phase as PostgresRunPhase.GameDirty).runUuid,
            database = state.binding)
        assertEquals(descriptor.getValue("database"), json.encodeToJsonElement(PostgresRunBinding.serializer(), binding.database))
        return reference to binding
    }

    @Test fun wireBindingRejectsMismatch() {
        val (reference, binding) = wireReference()
        binding.validate(reference)
        assertFails { binding.copy(database = binding.database.copy(connectionReference = "other")).validate(reference) }
        assertFails { binding.copy(database = binding.database.copy(generation = UUID.randomUUID().toString())).validate(reference) }
        assertFails { binding.copy(runId = UUID.randomUUID().toString()).validate(reference) }
        assertFails { binding.copy(instanceId = "slot-a", generationId = binding.database.generation).validate(reference) }
        val encoded = Json.parseToJsonElement(ServerManagementMessageCodec.encode(
            ServerManagementRequest.DatabaseHistoryStart(binding, nonce = "current"))).jsonObject
        assertEquals(2, encoded.getValue("storageContractVersion").jsonPrimitive.int)
        assertEquals(json.encodeToJsonElement(PostgresRunBinding.serializer(), binding.database),
            encoded.getValue("binding").jsonObject.getValue("database"))
    }

    @Test fun rejectLegacyAndInjectedFields() {
        val (_, binding) = wireReference()
        val valid = json.encodeToString<ServerManagementResponse>(ServerManagementResponse.DatabaseContext(true, binding, nonce = "n"))
        assertIs<ServerManagementDecodeResult.Success>(ServerManagementMessageCodec.decode(valid))
        val invalid = listOf(
            valid.replace(",\"storageContractVersion\":2", ""),
            valid.replace("\"storageContractVersion\":2", "\"storageContractVersion\":1"),
            valid.replace("\"storageContractVersion\":2", "\"storageContractVersion\":\"2\""),
            valid.replace("\"connectionReference\":\"fixture\"", "\"connectionReference\":\"fixture\",\"jdbcUrl\":\"jdbc:postgresql:other\""),
            valid.replace("\"nonce\":\"n\"", "\"nonce\":\"n\",\"nonce\":\"n\""),
            valid.replace("\"nonce\":\"n\"", "\"nonce\":\"\\uD800\"")
        )
        val extra = listOf("role", "passfile", "connection", "properties", "password").map { field ->
            valid.replace("\"connectionReference\":", "\"$field\":\"injected\",\"connectionReference\":")
        }
        for (text in invalid + extra) assertIs<ServerManagementDecodeResult.Failure>(ServerManagementMessageCodec.decode(text))
    }

    private class Wire(var binding: ManagementDatabaseBinding) : ManagementTransport {
        lateinit var listener: ServerManagementListener
        val sent = mutableListOf<ServerManagementRequest>()
        var answer = true
        override fun connect(listener: ServerManagementListener, timeout: Duration): Int {
            this.listener = listener
            return 25580
        }
        override fun disconnect() = Unit
        override fun send(request: ServerManagementRequest): Boolean {
            sent += request
            if (answer && request is ServerManagementRequest.DatabaseContextRequest)
                listener.onMessage(ServerManagementResponse.DatabaseContext(true, binding, nonce = request.nonce))
            if (answer && request is ServerManagementRequest.DatabaseProductRequest)
                listener.onMessage(ServerManagementResponse.DatabaseProductResult(HostAdapterResponse(
                    requestId = request.request.requestId, status = HostAdapterStatus.SUCCESS,
                    result = HostAdapterResult.Products(emptyList())), request.nonce))
            return true
        }
    }

    @Test fun rejectNonceReuseAndOldConnectionResponse() {
        val (_, binding) = wireReference()
        val wire = Wire(binding).apply { answer = false }
        val client = ServerManagementClient()
        val seen = mutableListOf<ServerManagementResponse>()
        client.addMessageListener { seen += it }
        assertTrue(client.connect(wire))
        try {
            assertTrue(client.send(ServerManagementRequest.DatabaseContextRequest("n")))
            val old = wire.listener
            old.onMessage(ServerManagementResponse.DatabaseContext(true, binding, nonce = "other"))
            assertTrue(seen.isEmpty())
            val response = ServerManagementResponse.DatabaseContext(true, binding, nonce = "n")
            old.onMessage(response)
            old.onMessage(response)
            assertEquals(1, seen.size)
            assertFalse(client.send(ServerManagementRequest.DatabaseContextRequest("n")))
            assertTrue(client.connect(wire))
            assertTrue(client.send(ServerManagementRequest.DatabaseContextRequest("n")))
            old.onMessage(response)
            assertEquals(1, seen.size)
            wire.listener.onMessage(response)
            assertEquals(2, seen.size)
        } finally { client.disconnect() }
    }

    @Test fun productPinsDatabaseAndConnection() {
        val (reference, binding) = wireReference()
        val client = ServerManagementClient()
        val wire = Wire(binding)
        assertTrue(client.connect(wire))
        try {
            val transport = ManagementShopTransport(client, reference = reference)
            assertEquals(binding.worldUuid, transport.worldUuid())
            assertEquals(binding.database, transport.databaseBinding())
            val request = HostAdapterRequest(requestId = UUID.randomUUID().toString(), worldUuid = binding.worldUuid,
                action = HostAdapterAction.Products(emptyList()))
            assertEquals(HostAdapterStatus.SUCCESS, transport.execute(request).status)
            wire.binding = binding.copy(database = binding.database.copy(databaseUuid = UUID.randomUUID().toString()))
            assertFails { transport.worldUuid() }
            assertFails { ManagementHistorySource(client, reference = reference).open() }
            assertTrue(client.connect(wire))
            assertFails { transport.execute(request) }
            assertEquals(1, wire.sent.filterIsInstance<ServerManagementRequest.DatabaseProductRequest>().size)
        } finally { client.disconnect() }
    }

    @Test fun legacyProfileCannotEnterManagementHistory() {
        val (reference, binding) = wireReference()
        val wire = Wire(binding)
        val client = ServerManagementClient()
        assertTrue(client.connect(wire))
        try {
            val ssh = Mockito.mock(SshManager::class.java)
            val profile = ServerProfile(
                "fixture", "localhost", 22, "fixture", "/world", "")
            val store = RemoteEditorDataStore(ssh)
            for (host in listOf(null,
                DatabaseHistoryHost(binding.worldUuid, "old-command"),
                DatabaseHistoryHost(binding.worldUuid, "old-command", reference),
                DatabaseHistoryHost(UUID.randomUUID().toString(), postgres = reference))) {
                Mockito.`when`(ssh.currentProfile).thenReturn(profile.copy(databaseHistory = host))
                assertFails { historySource(store, client) }
                assertTrue(wire.sent.isEmpty())
            }
            Mockito.`when`(ssh.currentProfile).thenReturn(profile.copy(databaseHistory =
                DatabaseHistoryHost(binding.worldUuid, postgres = reference)))
            assertNotNull(historySource(store, client))
        } finally { client.disconnect() }
    }

    @Test fun requestsCarryRequiredStorageVersion() {
        val (_, binding) = wireReference()
        val product = HostAdapterRequest(requestId = UUID.randomUUID().toString(), worldUuid = binding.worldUuid,
            action = HostAdapterAction.Products(emptyList()))
        val requests = listOf(
            ServerManagementRequest.DatabaseContextRequest("n"),
            ServerManagementRequest.DatabaseHistoryStart(binding, nonce = "n"),
            ServerManagementRequest.DatabaseHistoryPage("h", nonce = "n"),
            ServerManagementRequest.DatabaseHistoryEnd("h", "n"),
            ServerManagementRequest.DatabaseProductRequest(binding, product, "n"),
            ServerManagementRequest.MoneyHistorySave(nonce = "n"))
        for (request in requests) {
            val encoded = Json.parseToJsonElement(ServerManagementMessageCodec.encode(request)).jsonObject
            assertEquals(JsonPrimitive(2), encoded.getValue("storageContractVersion"))
        }
        assertFails { ServerManagementMessageCodec.encode(ServerManagementRequest.DatabaseContextRequest("n", 1)) }
    }
}
