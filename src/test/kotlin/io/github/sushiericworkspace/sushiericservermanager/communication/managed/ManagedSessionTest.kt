package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** GUI・Minecraftを起動せず、実行中キューと世代固定要求を検査します。 */
class ManagedSessionTest {
    @Test fun `既知の作用前拒否だけは受付と終了証明を維持する`() {
        withFixture { fixture ->
            val session = fixture.open()
            fixture.failure = SupervisorFailure("ALREADY_EXISTS", rejectedBeforeEffect = true)
            assertFailsWith<SupervisorFailure> { session.io(fixture.write) }
            assertEquals(ManagedSession.State.OPEN, session.state)
            fixture.failure = SupervisorFailure("PERMISSION_DENIED", rejectedBeforeEffect = true)
            assertFailsWith<SupervisorFailure> { session.io(fixture.write) }
            assertEquals(ManagedSession.State.OPEN, session.state)
            fixture.failure = null
            session.io(fixture.write)
            session.close {}
            assertEquals(ManagedSession.State.CLOSED, session.state)
            assertFailsWith<IllegalStateException> { session.io(fixture.write) }
        }
    }

    @Test fun `同じエラーcodeでも作用前情報のない失敗は保留する`() {
        for (code in listOf("FILE_NOT_FOUND", "ALREADY_EXISTS", "PERMISSION_DENIED", "RESULT_UNKNOWN")) {
            withFixture { fixture ->
                val session = fixture.open()
                fixture.failure = SupervisorFailure(code)
                assertFailsWith<SupervisorFailure> { session.io(fixture.write) }
                assertEquals(ManagedSession.State.UNKNOWN, session.state)
                assertFailsWith<IllegalStateException> { session.close {} }
                assertFalse(fixture.commands.any { it.first == "writer-close" })
            }
        }
    }

    @Test fun `閉鎖は実行中IOfinishとsocket終了の後だけ申告し遅延queueを拒否する`() {
        withFixture { fixture ->
            val session = fixture.open()
            val entered = CountDownLatch(1)
            val finish = CountDownLatch(1)
            val disconnected = CountDownLatch(1)
            fixture.onIo = { entered.countDown(); check(finish.await(3, TimeUnit.SECONDS)) }
            val workers = Executors.newFixedThreadPool(2)
            try {
                val io = workers.submit { session.io(fixture.write) }
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                val close = workers.submit { session.close { disconnected.countDown() } }
                val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                while (session.state != ManagedSession.State.CLOSING && System.nanoTime() < limit) Thread.yield()
                assertEquals(ManagedSession.State.CLOSING, session.state)
                assertTrue(disconnected.await(3, TimeUnit.SECONDS))
                assertFalse(fixture.commands.any { it.first == "writer-close" })
                finish.countDown()
                io.get(3, TimeUnit.SECONDS)
                close.get(3, TimeUnit.SECONDS)
                assertEquals(0, disconnected.count)
                val payload = fixture.commands.single { it.first == "writer-close" }.second
                assertTrue(payload.getValue("drained").jsonPrimitive.boolean)
                assertTrue(payload.getValue("disconnected").jsonPrimitive.boolean)
                assertFailsWith<IllegalStateException> { session.io(fixture.write) }
            } finally { finish.countDown(); workers.shutdown(); assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS)) }
        }
    }

    @Test fun `drain中の応答喪失とdisconnect失敗をclose成功へ変換しない`() {
        withFixture { fixture ->
            val session = fixture.open()
            assertFailsWith<IllegalStateException> { session.close { error("未確認のsocket") } }
            assertEquals(ManagedSession.State.UNKNOWN, session.state)
            assertFalse(fixture.commands.any { it.first == "writer-close" })
        }
    }

    @Test fun `受理済みqueueはClosingでも実行しexecutor待機とIOlockが相互待ちしない`() {
        withFixture { fixture ->
            val session = fixture.open()
            val executor = Executors.newSingleThreadExecutor()
            val blocker = CountDownLatch(1)
            val blocked = CountDownLatch(1)
            val delivered = CountDownLatch(2)
            val failures = CopyOnWriteArrayList<Exception>()
            executor.execute { blocked.countDown(); check(blocker.await(3, TimeUnit.SECONDS)) }
            assertTrue(blocked.await(3, TimeUnit.SECONDS))
            repeat(2) { assertTrue(session.enqueueIo(fixture.write, executor, { delivered.countDown() }, failures::add)) }
            val closeWorker = Executors.newSingleThreadExecutor()
            try {
                val close = closeWorker.submit { session.close {
                    executor.shutdown()
                    blocker.countDown()
                    check(executor.awaitTermination(3, TimeUnit.SECONDS))
                } }
                close.get(3, TimeUnit.SECONDS)
                assertEquals(0, delivered.count)
                assertTrue(failures.isEmpty())
                assertEquals(2, fixture.commands.count { it.first == "writer-io" })
                assertEquals("writer-close", fixture.commands.last().first)
                assertFalse(session.enqueueIo(fixture.write, executor, {}, {}))
                assertEquals(ManagedSession.State.CLOSED, session.state)
            } finally {
                blocker.countDown(); executor.shutdown(); closeWorker.shutdown()
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS)); assertTrue(closeWorker.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun `statusで得たrunを固定し同じinstanceでもsession領域を再利用しない`() {
        withFixture { fixture ->
            val first = fixture.open()
            val second = fixture.open()
            assertNotEquals(first.identity, second.identity)
            assertNotEquals(first.autoSaveDirectory, second.autoSaveDirectory)
            assertNotEquals(first.offlineDirectory, second.offlineDirectory)
            assertNotEquals(first.cacheDirectory, second.cacheDirectory)
            first.io(fixture.write)
            val payload = fixture.commands.last().second
            assertEquals("generation-01", payload.getValue("expectedGeneration").jsonPrimitive.content)
            assertEquals("run-01", payload.getValue("runId").jsonPrimitive.content)
            assertEquals("nonce-01", payload.getValue("nonce").jsonPrimitive.content)
            val registration = fixture.commands.first { it.first == "writer-open" }.second
            assertEquals(ProcessHandle.current().pid(), registration.getValue("pid").jsonPrimitive.long)
            assertEquals(Path.of("").toRealPath().toString(), registration.getValue("cwd").jsonPrimitive.content)
            assertFalse(first.workspace.name.contains(fixture.profile.leaseId))
        }
    }

    @Test fun `disconnect申告だけでは未実行の受理済みqueueを終了扱いしない`() {
        withFixture { fixture ->
            val session = fixture.open()
            val executor = Executors.newSingleThreadExecutor()
            val blocker = CountDownLatch(1)
            executor.execute { check(blocker.await(3, TimeUnit.SECONDS)) }
            try {
                assertTrue(session.enqueueIo(fixture.write, executor, {}, {}))
                assertFailsWith<IllegalStateException> { session.close {} }
                assertEquals(ManagedSession.State.UNKNOWN, session.state)
                assertFalse(fixture.commands.any { it.first == "writer-close" })
            } finally {
                blocker.countDown(); executor.shutdown(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun `writer登録の応答喪失も接続識別とworkspaceを保持して保留する`() {
        withFixture { fixture ->
            val client = SupervisorClient { command, payload, operation ->
                if (command == "writer-open") throw SupervisorFailure("RESULT_UNKNOWN")
                fixture.request(command, payload, operation)
            }
            val session = ManagedSession.open(fixture.profile, fixture.storage, client)
            assertEquals(ManagedSession.State.UNKNOWN, session.state)
            assertTrue(session.workspace.isDirectory)
            assertFailsWith<IllegalStateException> { session.io(fixture.write) }
            assertFailsWith<IllegalStateException> { session.close {} }
            assertFalse(fixture.commands.any { it.first == "writer-close" })
        }
    }

    @Test fun `ローカルIPC上限拒否はCLIを起動せず作用前情報を返す`() {
        withFixture { fixture ->
            val payload = buildJsonObject { put("text", "x".repeat(CliSupervisorClient.IPC_LIMIT)) }
            val error = assertFailsWith<SupervisorFailure> { CliSupervisorClient(fixture.profile).request("writer-io", payload, "large-body") }
            assertEquals("MESSAGE_TOO_LARGE", error.code)
            assertTrue(error.rejectedBeforeEffect)
        }
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val root = createTempDirectory("managed-session-test-")
        try { test(Fixture(root)) } finally { root.toFile().deleteRecursively() }
    }

    internal class Fixture(val storage: Path, endpointPort: Int = 25680) : SupervisorClient {
        val profile = ManagedProfile(storage.resolve("supervisor"), storage.resolve("python"), storage.resolve("cli"), "instance-01", "test-lease", 1)
        val commands = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        var failure: SupervisorFailure? = null
        var onIo: (() -> Unit)? = null
        val write = buildJsonObject { put("action", "write"); put("path", "config.yml"); put("text", "value: 1") }
        val status = buildJsonObject {
            putJsonArray("instances") { add(buildJsonObject {
                put("state", "RUNNING"); put("epoch", 1); put("leaseExpired", false); put("generationBound", true)
                put("generation", "generation-01"); put("runId", "run-01"); put("nonce", "nonce-01")
                putJsonObject("managementEndpoint") { put("address", "127.0.0.1"); put("protocol", "tcp"); put("port", endpointPort) }
            }) }
        }
        fun open() = ManagedSession.open(profile, storage, this)
        override fun request(command: String, payload: JsonObject, operationId: String): JsonObject {
            commands += command to payload
            return when (command) {
                "status" -> status
                "writer-open" -> buildJsonObject { put("closed", false) }
                "writer-close" -> buildJsonObject { put("closed", true) }
                else -> { onIo?.invoke(); failure?.let { throw it }; buildJsonObject { put("completed", true) } }
            }
        }
    }
}
