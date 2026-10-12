package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import kotlinx.serialization.json.*
import kotlin.test.*

class ManagedLeaseTest {
    @Test fun `Manager取得分だけ更新し元のlease世代とoperationをprivate記録へ送る`() {
        val fixture = Fixture()
        val lease = fixture.lease(ManagedLease.Source.MANAGER)
        try {
            lease.tick()
            assertEquals(listOf("renew"), fixture.commands.map { it.first })
            assertEquals("lease-secret", fixture.commands.single().second["leaseId"]?.jsonPrimitive?.content)
            assertEquals("generation-1", fixture.commands.single().second["expectedGeneration"]?.jsonPrimitive?.content)
            assertEquals(listOf(false, true), fixture.journal.map { it.second })
            assertEquals(fixture.journal.first().first, fixture.journal.last().first)
        } finally { lease.abort() }
    }
    @Test fun `引継ぎでは貸出検査だけを行い発行元の更新返却責任を奪わない`() {
        val fixture = Fixture()
        val lease = fixture.lease(ManagedLease.Source.HANDOFF)
        try {
            lease.tick()
            assertEquals(listOf("lease-check"), fixture.commands.map { it.first })
            assertFailsWith<IllegalArgumentException> { lease.release() }
            assertTrue(fixture.journal.isEmpty())
            assertTrue(lease.responsibility.contains("発行元"))
        } finally { lease.abort() }
    }
    @Test fun `更新結果不明は受付を停止し別ID再送や返却を行わない`() {
        val fixture = Fixture()
        val lease = fixture.lease(ManagedLease.Source.MANAGER)
        var held = 0
        lease.watchFailure { held++ }
        fixture.fail = true
        assertFailsWith<SupervisorFailure> { lease.tick() }
        assertEquals(ManagedLease.State.UNKNOWN, lease.state)
        assertEquals(1, held)
        assertFailsWith<IllegalStateException> { lease.ensureActive() }
        assertFailsWith<IllegalStateException> { lease.tick() }
        assertFailsWith<IllegalStateException> { lease.release() }
        assertEquals(1, fixture.commands.size)
        assertEquals(listOf(false), fixture.journal.map { it.second })
    }
    @Test fun `期限切れと異常終了でwriter終了やreleaseを推測しない`() {
        for (expire in listOf(true, false)) {
            val fixture = Fixture()
            val lease = fixture.lease(ManagedLease.Source.MANAGER)
            if (expire) { fixture.now = 500.0; assertFailsWith<IllegalStateException> { lease.ensureActive() } }
            else lease.abort()
            assertEquals(ManagedLease.State.UNKNOWN, lease.state)
            assertTrue(fixture.commands.isEmpty())
            assertTrue(fixture.journal.isEmpty())
        }
    }
    @Test fun `旧epoch世代run nonceとUNKNOWN writerの引継ぎ監視を拒否する`() {
        for ((key, value) in listOf("epoch" to JsonPrimitive(2), "generation" to JsonPrimitive("old"),
            "runId" to JsonPrimitive("new-run"), "nonce" to JsonPrimitive("new-nonce"), "pending" to JsonPrimitive(1),
            "writerSessions" to buildJsonArray { add(buildJsonObject { put("status", "UNKNOWN") }) })) {
            val fixture = Fixture()
            fixture.checked = JsonObject(fixture.checked + (key to value))
            val lease = fixture.lease(ManagedLease.Source.HANDOFF)
            assertFailsWith<IllegalStateException> { lease.tick() }
            assertEquals(ManagedLease.State.UNKNOWN, lease.state)
            assertEquals(listOf("lease-check"), fixture.commands.map { it.first })
        }
    }
    @Test fun `正常切断と返却は別操作で停止拒否を無条件releaseへ変換しない`() {
        val fixture = Fixture()
        val lease = fixture.lease(ManagedLease.Source.MANAGER)
        lease.ensureActive()
        assertTrue(fixture.commands.isEmpty())
        fixture.fail = true
        assertFailsWith<SupervisorFailure> { lease.release() }
        assertEquals(ManagedLease.State.UNKNOWN, lease.state)
        assertEquals(listOf("release"), fixture.commands.map { it.first })
        assertEquals(listOf(false), fixture.journal.map { it.second })
    }
    @Test fun `明示返却の成功後は更新と旧token操作を拒否する`() {
        val fixture = Fixture()
        val lease = fixture.lease(ManagedLease.Source.MANAGER)
        lease.release()
        assertEquals(ManagedLease.State.RELEASED, lease.state)
        assertFailsWith<IllegalStateException> { lease.tick() }
        assertEquals(listOf("release"), fixture.commands.map { it.first })
    }
    @Test fun `世代準備と起動は明示操作だけで返された世代を固定しrenewも同じ世代を使う`() {
        val fixture = Fixture()
        val lease = fixture.lease(ManagedLease.Source.MANAGER)
        try {
            lease.prepareGeneration("reset", buildJsonObject { put("generationId", "generation-2"); put("artifactId", "a".repeat(64)); put("templateId", "smoke"); put("acceptEula", true) })
            assertEquals("generation-2", lease.token["expectedGeneration"]?.jsonPrimitive?.content)
            assertEquals(listOf("reset"), fixture.commands.map { it.first })
            lease.tick()
            assertEquals("generation-2", fixture.commands.last().second["expectedGeneration"]?.jsonPrimitive?.content)
            assertFalse(fixture.commands.any { it.first == "start" || it.first == "stop" })
        } finally { lease.abort() }
    }
    @Test fun `引継ぎの正常writer終了は貸出を残し監視を終え再使用を拒否する`() {
        val fixture = Fixture()
        val lease = fixture.lease(ManagedLease.Source.HANDOFF)
        lease.writerClosed()
        assertEquals(ManagedLease.State.DETACHED, lease.state)
        assertFailsWith<IllegalStateException> { lease.ensureActive() }
        assertTrue(fixture.commands.isEmpty())
    }

    @Test fun `更新喪失は既存writerの保存を止め終了証明と貸出を残す`() {
        val fixture = Fixture()
        val lease = fixture.lease(ManagedLease.Source.MANAGER)
        val storage = kotlin.io.path.createTempDirectory("lease-writer-")
        try {
            val writer = ManagedSessionTest.Fixture(storage)
            val session = ManagedSession.open(writer.profile, storage, writer, admissionGuard = { lease.ensureActive() }, onUnknown = { lease.holdUnknown() })
            lease.watchFailure { session.holdUnknown() }
            session.io(writer.write)
            fixture.fail = true
            assertFailsWith<SupervisorFailure> { lease.tick() }
            assertEquals(ManagedSession.State.UNKNOWN, session.state)
            assertFailsWith<IllegalStateException> { session.io(writer.write) }
            assertFailsWith<IllegalStateException> { session.close {} }
            assertEquals(1, writer.commands.count { it.first == "writer-io" })
            assertFalse(writer.commands.any { it.first == "writer-close" })
        } finally { lease.abort(); storage.toFile().deleteRecursively() }
    }

    private class Fixture {
        var now = 100.0
        var fail = false
        val commands = mutableListOf<Pair<String, JsonObject>>()
        val journal = mutableListOf<Pair<String, Boolean>>()
        val token = buildJsonObject { put("instanceId", "persistent-01"); put("leaseId", "lease-secret"); put("epoch", 1); put("expectedGeneration", "generation-1") }
        val binding = buildJsonObject { put("runId", "run-1"); put("nonce", "nonce-1"); put("sessionId", "handoff-session") }
        var checked = buildJsonObject {
            put("instanceId", "persistent-01"); put("owner", "test-owner"); put("epoch", 1); put("generation", "generation-1")
            put("runId", "run-1"); put("nonce", "nonce-1"); put("expiresAt", 300.0); put("state", "RUNNING")
            put("generationBound", true); put("pending", 0); putJsonArray("writerSessions") {}
        }
        fun lease(source: ManagedLease.Source) = ManagedLease(source, "test-owner", SupervisorClient { command, payload, _ ->
            commands += command to payload
            if (fail) throw SupervisorFailure("RESULT_UNKNOWN")
            if (command == "reset") buildJsonObject { put("instanceId", "persistent-01"); put("generation", payload.getValue("generationId")); put("prepared", true) }
            else if (command == "lease-check") checked else buildJsonObject { put("instanceId", "persistent-01"); put("expiresAt", 400.0); put("released", true) }
        }, token, 300.0, if (source == ManagedLease.Source.HANDOFF) binding else null,
            { _, _, operation, result -> journal += operation to (result != null) }, { now })
    }
}
