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

    @Test fun `引継ぎの形式不正は送信前に拒否し入力訂正まで貸出と結果不明を残さない`() = withFixture { profile, _ ->
        val service = ManagedConnectionService(profile, SupervisorClient { _, _, _ -> error("入力拒否で監督へ送信しません") })
        val invalid = listOf(
            Triple("invalid id", "test-owner", "manager"),
            Triple("test-handoff", "invalid owner", "manager"),
            Triple("test-handoff", "test-owner", "client manager")
        )
        invalid.forEach { (handoff, owner, client) ->
            assertFailsWith<IllegalArgumentException> { service.claim(handoff, owner, client) }
            assertFalse(service.uncertain)
            assertNull(service.lease)
            assertFalse(ConnectionProfile.load(profile.file).pendingManagerOperation)
        }
        // 形式を訂正した明示要求はbridgeへ進み、存在しない引継ぎの失敗を保全します。
        assertFailsWith<SupervisorFailure> { service.claim("test-handoff", "test-owner", "manager") }
        assertTrue(service.uncertain)
        assertNull(service.lease)
    }

    @Test fun `設定済みボタン起動は借用準備起動を一度ずつ行い停止後は同じ貸出で再起動する`() = withFixture { profile, _ ->
        val fixture = ButtonFixture(profile)
        val preset = ManagedServerPreset("test-owner", "a".repeat(64), 123L, "trial")
        try {
            val progress = mutableListOf<Int>()
            fixture.service.start("persistent-01", preset) { completed, total, _ ->
                assertEquals(4, total); progress += completed
            }
            assertEquals(listOf(0, 1, 2, 3, 4), progress)
            val first = fixture.generation
            assertEquals(listOf("acquire", "prepare", "start"), fixture.mutations())
            assertEquals(123L, fixture.commands.single { it.first == "prepare" }.second["seed"]?.jsonPrimitive?.long)
            assertEquals(first, fixture.service.lease!!.token["expectedGeneration"])
            fixture.service.lease!!.runtime("stop")
            fixture.service.start("persistent-01", preset)
            assertEquals(first, fixture.generation)
            assertEquals(listOf("acquire", "prepare", "start", "stop", "start"), fixture.mutations())
            assertSame(fixture.service.lease, fixture.service.connect("persistent-01", null))
            fixture.service.lease!!.runtime("stop")
            progress.clear()
            fixture.service.reset("persistent-01", preset) { completed, total, _ ->
                assertEquals(3, total); progress += completed
            }
            assertEquals(listOf(0, 1, 2, 3), progress)
            assertNotEquals(first, fixture.generation)
            assertEquals("trial", fixture.commands.single { it.first == "reset" }.second["templateId"]?.jsonPrimitive?.content)
            assertEquals("reset", fixture.mutations().last())
            assertEquals("READY", fixture.state)
            val resetGeneration = fixture.generation
            fixture.service.start("persistent-01", preset)
            assertEquals(resetGeneration, fixture.generation)
            assertEquals(listOf("reset", "start"), fixture.mutations().takeLast(2))
            assertEquals(1, fixture.mutations().count { it == "acquire" })
        } finally { fixture.service.lease?.abort() }
    }

    @Test fun `既存枠の起動はtemplate未設定なら借用前に拒否し設定済みtemplateだけを使う`() = withFixture { profile, _ ->
        val fixture = ButtonFixture(profile).apply { generation = JsonPrimitive("old-generation") }
        assertFailsWith<IllegalArgumentException> { fixture.service.start("persistent-01", ManagedServerPreset("test-owner", "a".repeat(64), 1, "")) }
        assertNull(fixture.service.lease)
        assertTrue(fixture.mutations().isEmpty())
        assertFalse(fixture.service.uncertain)
        try {
            fixture.service.start("persistent-01", ManagedServerPreset("test-owner", "a".repeat(64), 1, "trial"))
            assertEquals(listOf("acquire", "reset", "start"), fixture.mutations())
            assertEquals("old-generation", fixture.commands.single { it.first == "reset" }.second["expectedGeneration"]?.jsonPrimitive?.content)
        } finally { fixture.service.lease?.abort() }
    }

    @Test fun `他ownerと復旧待ちの起動は貸出を取得せず世代準備の応答喪失では取り直さない`() = withFixture { profile, _ ->
        val preset = ManagedServerPreset("test-owner", "a".repeat(64), 1, "trial")
        for (state in listOf("RUNNING", "RECOVERY_REQUIRED")) {
            val fixture = ButtonFixture(profile).apply { this.state = state }
            assertFailsWith<IllegalStateException> { fixture.service.start("persistent-01", preset) }
            assertTrue(fixture.mutations().isEmpty())
        }
        val fixture = ButtonFixture(profile).apply { failPrepare = true }
        val progress = mutableListOf<Int>()
        assertFailsWith<SupervisorFailure> { fixture.service.start("persistent-01", preset) { completed, _, _ -> progress += completed } }
        assertEquals(listOf(0, 1, 2), progress)
        assertEquals(ManagedLease.State.UNKNOWN, fixture.service.lease!!.state)
        assertTrue(ConnectionProfile.load(profile.file).pendingManagerOperation)
        assertFailsWith<IllegalStateException> { fixture.service.start("persistent-01", preset) }
        assertEquals(listOf("acquire", "prepare"), fixture.mutations())
    }

    private class ButtonFixture(profile: ConnectionProfile) {
        var state = "AVAILABLE"
        var generation: JsonElement = JsonNull
        var bound = false
        var failPrepare = false
        val commands = mutableListOf<Pair<String, JsonObject>>()
        fun mutations() = commands.map { it.first }.filter { it != "list" && it != "lease-check" }
        val service = ManagedConnectionService(profile, SupervisorClient { command, payload, _ ->
            commands += command to payload
            val expires = System.currentTimeMillis() / 1000.0 + 3600
            when (command) {
                "list" -> buildJsonObject { putJsonArray("instances") { add(buildJsonObject {
                    put("instanceId", "persistent-01"); put("state", state); put("generation", generation)
                    put("owner", if (state == "AVAILABLE") JsonNull else JsonPrimitive("test-owner")); put("leaseExpired", false)
                }) } }
                "acquire" -> { state = "LEASED_STOPPED"; buildJsonObject {
                    put("instanceId", "persistent-01"); put("leaseId", "test-private-lease"); put("epoch", 1)
                    put("generation", generation); put("expiresAt", expires); put("generationBound", false)
                } }
                "prepare", "reset" -> {
                    if (failPrepare) throw SupervisorFailure("RESULT_UNKNOWN")
                    generation = payload.getValue("generationId"); bound = true; state = "READY"
                    buildJsonObject { put("instanceId", "persistent-01"); put("generation", generation); put("prepared", true) }
                }
                "lease-check" -> buildJsonObject {
                    put("instanceId", "persistent-01"); put("owner", "test-owner"); put("epoch", 1)
                    put("generation", generation); put("generationBound", bound); put("expiresAt", expires); put("state", state)
                    put("runId", "test-run"); put("nonce", "test-nonce"); putJsonArray("writerSessions") {}
                }
                "start" -> { state = "RUNNING"; buildJsonObject {
                    put("instanceId", "persistent-01"); put("generation", generation); put("running", true)
                } }
                "stop" -> { state = "LEASED_STOPPED"; buildJsonObject {
                    put("instanceId", "persistent-01"); put("generation", generation); put("runtimeStopped", true)
                    put("modSaveCompleted", true); put("jvmAndChildrenExited", true); put("exitCode", 0)
                } }
                else -> error("暗黙操作：$command")
            }
        })
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
