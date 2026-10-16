package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementListener
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementResponse
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.ManagedEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.test.*

/** 承認済みの実Mod二枠へ一回限りの引継ぎで接続します。起動・停止・復元と貸出更新は発行元の責任です。 */
class ManagedModIntegrationTest {
    @Test fun `実Modの二枠を型付き保存と監視Closeで照合する`() {
        val location = System.getenv("MANAGED_REAL_MOD_TEST_CONFIG")
        assumeTrue(location != null, "実Mod試験は承認済みprivate設定を明示した場合だけ実行します")
        val config = Json.parseToJsonElement(Files.readString(Path.of(location))).jsonObject
        val edit = config.getValue("mode").jsonPrimitive.content.let {
            require(it in setOf("edit", "verify")); it == "edit"
        }
        val entries = config.getValue("connections").jsonArray
        require(entries.size == 2)
        val names = entries.map { it.jsonObject.getValue("displayName").jsonPrimitive.content }
        require(names.distinct().size == 2)
        val sessions = mutableListOf<ManagedSession>()
        val stores = mutableListOf<ManagedEditorDataStore>()
        val leases = mutableListOf<ManagedLease>()
        val profiles = mutableListOf<ConnectionProfile>()
        val transports = mutableListOf<ManagedManagementTransport>()
        try {
            for (entry in entries.map { it.jsonObject }) {
                val tools = entry["tools"]?.jsonObject?.let {
                    ManagedClientTools(Path.of(it.getValue("python").jsonPrimitive.content),
                        Path.of(it.getValue("cli").jsonPrimitive.content), Path.of(it.getValue("knownHosts").jsonPrimitive.content))
                }
                val profile = ConnectionProfile.load(Path.of(entry.getValue("profileFile").jsonPrimitive.content), tools)
                profiles += profile
                assertEquals(entry.getValue("approvedRoot"), profile.json.getValue("managementRoot"))
                assertEquals(entry.getValue("approvedHost"), profile.json.getValue("host"))
                val lease = ManagedConnectionService(profile).claim(entry.getValue("handoffId").jsonPrimitive.content,
                    entry.getValue("owner").jsonPrimitive.content, entry.getValue("targetClient").jsonPrimitive.content)
                leases += lease
                val session = ManagedSession.open(lease.connectionProfile(profile),
                    Path.of(config.getValue("storage").jsonPrimitive.content), admissionGuard = lease::ensureActive, onUnknown = lease::holdUnknown)
                sessions += session
                lease.watchFailure(session::holdUnknown)
                assertEquals(ManagedSession.State.OPEN, session.state)
                val transport = ManagedManagementTransport(session)
                transports += transport
                transport.connect(object : ServerManagementListener {
                    override fun onMessage(message: ServerManagementResponse) = Unit
                    override fun onClosed(statusCode: Int, reason: String) = Unit
                    override fun onError(error: Throwable) { session.holdUnknown() }
                }, Duration.ofSeconds(15))
                stores += ManagedEditorDataStore(session, ManagerCompatibility.Compatible("0.1.0"))
                val checked = lease.checkLease()
                assertTrue(checked.getValue("writers").jsonPrimitive.int > 0)
                assertFalse(checked.getValue("cleanEligible").jsonPrimitive.boolean)
                val commands = mapOf("snapshot" to buildJsonObject {
                    put("action", "create"); put("snapshotId", "blocked-547")
                }, "restore" to buildJsonObject {
                    put("artifactId", "0".repeat(64)); put("generationId", "blocked-547")
                    put("snapshotId", "absent-547"); put("acceptEula", true)
                })
                for ((command, payload) in commands) {
                    val control = if (profile.ssh) ConnectionProfile.load(profile.file, tools) else profile
                    try {
                        val failure = assertFailsWith<SupervisorFailure> {
                            control.client().request(command, JsonObject(lease.token + payload), operationId())
                        }
                        assertEquals("PROOF_REQUIRED", failure.code)
                    } finally {
                        if (profile.ssh) control.closeUnused()
                    }
                }
                assertEquals(ManagedSession.State.OPEN, session.state)
            }
            assertNotEquals(stores[0].identity, stores[1].identity)
            assertNotEquals(sessions[0].cacheDirectory, sessions[1].cacheDirectory)
            val descriptor = EditorDataDescriptors.item
            val before = stores.map { assertIs<StoreResult.Success<MutableItemBaseData>>(it.load(descriptor, "fixture.token")).value }
            before.forEach { assertEquals("00000000-0000-4000-8000-000000000526", it.internalId.value) }
            if (edit) {
                for ((index, store) in stores.withIndex()) {
                    val data = before[index]
                    data.display.displayName = names[index]
                    assertIs<StoreResult.Success<Unit>>(store.save(descriptor, "fixture.token", data))
                    if (index == 0) {
                        val other = assertIs<StoreResult.Success<MutableItemBaseData>>(stores[1].load(descriptor, "fixture.token")).value
                        assertEquals(before[1].display.displayName, other.display.displayName)
                    }
                    val response = sessions[index].io(buildJsonObject {
                        put("action", "api"); put("timeoutSeconds", 5)
                        putJsonObject("message") { put("type", "command_execute"); put("command", "se reload item") }
                    })
                    assertTrue(response.getValue("completed").jsonPrimitive.boolean)
                    assertTrue(response.getValue("apiResponse").jsonObject.getValue("success").jsonPrimitive.boolean)
                }
            }
            stores.forEachIndexed { index, store ->
                val result = store.load(descriptor, "fixture.token")
                val message = if (result is StoreResult.Failure) {
                    val error = result.error
                    "読込失敗: code=${error.code}, cause=${error.cause?.javaClass?.name}, " +
                        "supervisorCode=${(error.cause as? SupervisorFailure)?.code}"
                } else null
                val loaded = assertIs<StoreResult.Success<MutableItemBaseData>>(result, message).value
                assertEquals(names[index], loaded.display.displayName)
                assertEquals(before[index].internalId, loaded.internalId)
            }
            for (index in sessions.indices) {
                sessions[index].close { transports[index].disconnect() }
                assertEquals(ManagedSession.State.CLOSED, sessions[index].state)
                assertFailsWith<IllegalStateException> { sessions[index].io(buildJsonObject { put("action", "write") }) }
                leases[index].writerClosed()
                val row = profiles[index].client().request("status", buildJsonObject { put("instanceId", leases[index].instanceId) }, operationId())
                    .getValue("instances").jsonArray.single().jsonObject
                assertEquals(0, row.getValue("writers").jsonPrimitive.int)
                assertEquals("RUNNING", row.getValue("state").jsonPrimitive.content)
                profiles[index].closeUnused()
            }
        } catch (error: Throwable) {
            sessions.forEach { it.holdUnknown() }
            leases.forEach { it.abort() }
            throw error
        }
    }
}
