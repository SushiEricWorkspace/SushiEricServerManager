package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** 正本のprivateストアと合成監督を接続し、画面の借用経路を検査します。 */
class ManagedConnectionServiceTest {
    @Test fun `候補だけ表示し他ownerと復旧待ちは借りず停止枠を暗黙起動しない`() = withFixture { profile, root ->
        var state = "RUNNING"
        var owner: JsonElement = JsonPrimitive("other-owner")
        val commands = mutableListOf<String>()
        val service = ManagedConnectionService(profile, SupervisorClient { command, _, _ ->
            commands += command
            when (command) {
                "list" -> buildJsonObject { putJsonArray("instances") {
                    add(buildJsonObject { put("instanceId", "persistent-01"); put("state", state); put("owner", owner); put("leaseExpired", false) })
                    add(buildJsonObject { put("instanceId", "not-approved"); put("state", "AVAILABLE") })
                } }
                "acquire" -> buildJsonObject {
                    put("instanceId", "persistent-01"); put("leaseId", "secret-test"); put("epoch", 1); put("generation", JsonNull)
                    put("expiresAt", System.currentTimeMillis() / 1000.0 + 3600); put("generationBound", false)
                }
                else -> error("暗黙操作：$command")
            }
        })
        assertEquals(1, service.instances().size)
        assertFailsWith<IllegalStateException> { service.borrow("persistent-01", "test-owner") }
        state = "RECOVERY_REQUIRED"; owner = JsonNull
        assertFailsWith<IllegalStateException> { service.borrow("persistent-01", "test-owner") }
        state = "AVAILABLE"
        val lease = service.borrow("persistent-01", "test-owner")
        try {
            assertEquals(ManagedLease.Source.MANAGER, lease.source)
            assertEquals(listOf("acquire"), commands.filter { it != "list" })
            assertEquals(JsonNull, lease.token["expectedGeneration"])
            assertFailsWith<IllegalStateException> { service.borrow("persistent-01", "test-owner") }
            assertTrue(ConnectionProfile.load(profile.file).pendingManagerOperation)
            assertFalse(root.resolve("fake-root").toFile().exists())
        } finally { lease.abort() }
    }
    @Test fun `acquire応答喪失はprivate UNKNOWNを保全して同じアプリも再起動も二重取得しない`() = withFixture { profile, _ ->
        val commands = mutableListOf<String>()
        val service = ManagedConnectionService(profile, SupervisorClient { command, _, _ ->
            commands += command
            if (command == "acquire") throw SupervisorFailure("RESULT_UNKNOWN")
            buildJsonObject { putJsonArray("instances") { add(buildJsonObject { put("instanceId", "persistent-01"); put("state", "AVAILABLE"); put("owner", JsonNull) }) } }
        })
        assertFailsWith<SupervisorFailure> { service.borrow("persistent-01", "test-owner") }
        assertFailsWith<IllegalStateException> { service.borrow("persistent-01", "test-owner") }
        assertEquals(1, commands.count { it == "acquire" })
        val reloaded = ConnectionProfile.load(profile.file)
        assertTrue(reloaded.pendingManagerOperation)
        val restarted = ManagedConnectionService(reloaded, SupervisorClient { _, _, _ -> error("再送禁止") })
        assertFailsWith<IllegalStateException> { restarted.borrow("persistent-01", "test-owner") }
    }
    @Test fun `キャンセル後のacquire成功応答を保持貸出へ変換せずprivate結果不明を残す`() = withFixture { profile, _ ->
        lateinit var service: ManagedConnectionService
        var acquired = 0
        service = ManagedConnectionService(profile, SupervisorClient { command, _, _ ->
            when (command) {
                "list" -> buildJsonObject { putJsonArray("instances") { add(buildJsonObject {
                    put("instanceId", "persistent-01"); put("state", "AVAILABLE"); put("owner", JsonNull)
                }) } }
                "acquire" -> {
                    acquired++
                    service.abort()
                    buildJsonObject {
                        put("instanceId", "persistent-01"); put("leaseId", "late-lease"); put("epoch", 1)
                        put("generation", JsonNull); put("expiresAt", System.currentTimeMillis() / 1000.0 + 3600)
                    }
                }
                else -> error("キャンセル後に自動操作しません")
            }
        })
        assertFailsWith<IllegalStateException> { service.borrow("persistent-01", "test-owner") }
        assertTrue(service.uncertain)
        assertNull(service.lease)
        assertTrue(ConnectionProfile.load(profile.file).pendingManagerOperation)
        assertFailsWith<IllegalStateException> { service.borrow("persistent-01", "test-owner") }
        assertEquals(1, acquired)
    }

    private fun withFixture(test: (ConnectionProfile, Path) -> Unit) {
        val python = System.getenv("MANAGED_PYTHON")
        val contract = System.getenv("MANAGED_CONTRACT_DIRECTORY")
        assumeTrue(python != null && contract != null, "正本Python 3.12の検証環境を指定してください")
        val root = createTempDirectory("manager-profile-").toRealPath()
        try {
            val script = """
                import json, socket, sys
                from pathlib import Path
                sys.path.insert(0, str(Path(sys.argv[1]) / "scripts"))
                from dev_server.connection_profiles import ConnectionStore
                root = Path(sys.argv[2])
                profile = {"version":1,"profileId":"test","displayName":"試験","transport":"local",
                  "targetOs":"windows" if sys.platform == "win32" else "macos","host":socket.gethostname(),
                  "managementRoot":str(root / "fake-root"),"python":sys.executable,"cli":str(Path(sys.argv[1]) / "scripts/dev-server.py"),
                  "instances":[{"instanceId":"persistent-01","displayName":"試験1"}],"approval":{"operator":"test-owner","reference":"合成監督のみ"}}
                ConnectionStore(root / "private").save_profile(profile)
            """.trimIndent()
            val scriptFile = root.resolve("fixture.py").toFile().apply { writeText(script, Charsets.UTF_8) }
            val process = ProcessBuilder(python!!, "-B", scriptFile.absolutePath, contract!!, root.toString()).inheritIO().start()
            assertEquals(0, process.waitFor())
            test(ConnectionProfile.load(root.resolve("private/profiles/test.json")), root)
        } finally { root.toFile().deleteRecursively() }
    }
}
