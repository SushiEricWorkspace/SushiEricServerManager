package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import io.github.sushiericworkspace.sushiericservermanager.communication.management.*
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class SshManagedSessionTest {
    @Test fun `二ホスト二枠の同一公開IDとcache領域を分離し外部ManagerのPIDを送らない`() = withProfiles { one, two, storage ->
        val fixture = ManagedSessionTest.Fixture(storage)
        val targets = listOf(ManagedTarget.Ssh(one, "test-owner", fixture), ManagedTarget.Ssh(two, "test-owner", fixture))
        val profiles = targets.flatMap { target -> listOf("instance-01", "instance-02").map { fixture.profile.copy(target=target, instanceId=it, fixedBinding=buildJsonObject {
            put("expectedGeneration", "generation-01"); put("runId", "run-01"); put("nonce", "nonce-01"); put("sessionId", "same-session")
        }) } }
        val sessions = profiles.map { ManagedSession.open(it, storage, fixture) }
        assertEquals(4, sessions.map { it.cacheDirectory }.toSet().size)
        val opens = fixture.commands.filter { it.first == "writer-open" }.map { it.second }
        assertTrue(opens.all { "pid" !in it && "cwd" !in it && "startMillis" !in it && it["owner"] == JsonPrimitive("test-owner") })
        sessions.forEach { it.io(fixture.write); it.close {} }
        assertEquals(4, fixture.commands.count { it.first == "writer-close" })
    }

    @Test fun `SSH監視の停止後に届いたcallbackを次の画面へ渡さない`() = withProfiles { one, _, storage ->
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val notifications = mutableListOf<ServerManagementResponse>()
        val fixture = ManagedSessionTest.Fixture(storage)
        val client = SupervisorClient { command, payload, operation ->
            when (command) {
                "monitor-open" -> subscription(true)
                "monitor-read" -> {
                    entered.countDown(); check(finish.await(5, TimeUnit.SECONDS))
                    buildJsonObject { putJsonArray("events") { add(subscription(true)) } }
                }
                "monitor-close" -> buildJsonObject { put("disconnected", true) }
                else -> fixture.request(command, payload, operation)
            }
        }
        val session = ManagedSession.open(fixture.profile.copy(target=ManagedTarget.Ssh(one, "test-owner", client)), storage, client)
        val listener = object : ServerManagementListener {
            override fun onMessage(message: ServerManagementResponse) { synchronized(notifications) { notifications += message } }
            override fun onClosed(statusCode: Int, reason: String) = Unit
            override fun onError(error: Throwable) { throw AssertionError(error) }
        }
        val monitor = SshManagedMonitor(session, listener)
        monitor.connect()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val closer = Thread { session.close { monitor.disconnect() } }.apply { start() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (session.state == ManagedSession.State.OPEN && System.nanoTime() < deadline) Thread.yield()
        finish.countDown(); closer.join(5000)
        assertFalse(closer.isAlive)
        assertEquals(ManagedSession.State.CLOSED, session.state)
        assertEquals(1, synchronized(notifications) { notifications.size })
        assertFalse(monitor.send(ServerManagementRequest.MonitorSubscribe()))
    }

    @Test fun `監視peer Close未確認ではwriter Closeも保存の再送も禁止する`() = withProfiles { one, _, storage ->
        val fixture = ManagedSessionTest.Fixture(storage)
        val client = SupervisorClient { command, payload, operation ->
            when (command) {
                "monitor-open" -> subscription(true)
                "monitor-read" -> buildJsonObject { putJsonArray("events") {} }
                "monitor-close" -> throw SupervisorFailure("RESULT_UNKNOWN")
                else -> fixture.request(command, payload, operation)
            }
        }
        val session = ManagedSession.open(fixture.profile.copy(target=ManagedTarget.Ssh(one, "test-owner", client)), storage, client)
        val transport = ManagedManagementTransport(session)
        transport.connect(object : ServerManagementListener {
            override fun onMessage(message: ServerManagementResponse) = Unit
            override fun onClosed(statusCode: Int, reason: String) = Unit
            override fun onError(error: Throwable) = Unit
        }, Duration.ofSeconds(5))
        assertFailsWith<SupervisorFailure> { session.close { transport.disconnect() } }
        assertEquals(ManagedSession.State.UNKNOWN, session.state)
        assertFalse(fixture.commands.any { it.first == "writer-close" })
        assertFailsWith<IllegalStateException> { session.io(fixture.write) }
    }

    private fun subscription(subscribed: Boolean) = buildJsonObject {
        put("type", "monitor_subscription"); put("subscribed", subscribed); put("intervalTicks", 20)
    }

    private fun withProfiles(action: (ConnectionProfile, ConnectionProfile, Path) -> Unit) {
        val python = System.getenv("MANAGED_PYTHON")
        val contracts = System.getenv("MANAGED_CONTRACT_DIRECTORY")
        assumeTrue(python != null && contracts != null)
        val directory = createTempDirectory("manager-197-profile-").toRealPath()
        val tools = ManagedClientTools(Path.of(python), Path.of(contracts, "scripts/dev-server.py"), directory.resolve("known_hosts"))
        val script = """
            import sys
            from pathlib import Path
            sys.path.insert(0,str(Path(sys.argv[2])/'scripts'))
            from dev_server.connection_profiles import ConnectionStore
            store=ConnectionStore(Path(sys.argv[1])/'private')
            for host in ('one','two'):
                store.save_profile({'version':1,'profileId':host,'displayName':host,'transport':'ssh','targetOs':'macos','host':host,'sshUser':'test-user',
                    'managementRoot':'/別 PC/root','python':'/別 PC/python','cli':'/別 PC/scripts/dev-server.py',
                    'instances':[{'instanceId':i,'displayName':i} for i in ('instance-01','instance-02')],
                    'approval':{'operator':'test-owner','reference':'合成試験のみ'}})
        """.trimIndent()
        val process = ProcessBuilder(python, "-B", "-c", script, directory.toString(), contracts).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        check(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0)
        val one = ConnectionProfile.load(directory.resolve("private/profiles/one.json"), tools)
        val two = ConnectionProfile.load(directory.resolve("private/profiles/two.json"), tools)
        try { action(one, two, directory.resolve("workspace").apply { toFile().mkdirs() }) }
        finally { one.closeUnused(); two.closeUnused(); directory.toFile().deleteRecursively() }
    }
}
