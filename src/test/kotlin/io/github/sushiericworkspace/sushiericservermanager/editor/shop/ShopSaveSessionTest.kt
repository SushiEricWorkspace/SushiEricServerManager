package io.github.sushiericworkspace.sushiericservermanager.editor.shop

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.channels.OverlappingFileLockException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class ShopSaveSessionTest {
    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `応答後の記録削除失敗は元要求を保全し同じIDの照合で整理できる`() {
        for (status in listOf(HostAdapterStatus.SUCCESS, HostAdapterStatus.REJECTED)) {
            val path = root.resolve("cleanup-$status.json")
            val requests = mutableListOf<HostAdapterRequest>()
            var original: ByteArray? = null
            val transport = object : ShopTransport {
                override fun worldUuid() = world
                override fun execute(request: HostAdapterRequest): HostAdapterResponse {
                    requests += request
                    if (requests.size == 1) {
                        original = Files.readAllBytes(path)
                        Files.setAttribute(path, "dos:readonly", true)
                    }
                    return HostAdapterResponse(requestId = request.requestId, status = status,
                        rejectedBeforeEffect = status == HostAdapterStatus.REJECTED,
                        result = if (status == HostAdapterStatus.SUCCESS) HostAdapterResult.Edited(emptyList()) else null)
                }
            }
            val session = ShopSaveSession(transport, path)
            try {
                val response = session.execute(action)
                assertEquals(HostAdapterStatus.UNKNOWN, response.status)
                assertEquals("PENDING_REVIEW", response.code)
                assertContentEquals(assertNotNull(original), Files.readAllBytes(path))
                assertEquals(requests.single(), session.pending())
                assertFails { session.execute(action) }
                assertEquals(1, requests.size)
            } finally { Files.setAttribute(path, "dos:readonly", false) }
            assertEquals(status, ShopSaveSession(transport, path).reconcile().status)
            assertEquals(requests[0], requests[1])
            assertFalse(Files.exists(path))
        }
    }

    @Test
    fun `別sessionの排他競合は送信と記録変更をせず解放後だけ照合できる`() {
        val path = root.resolve("locked.json")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<HostAdapterRequest>()
        val transport = object : ShopTransport {
            override fun worldUuid() = world
            override fun execute(request: HostAdapterRequest): HostAdapterResponse {
                requests += request
                if (requests.size == 1) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                return HostAdapterResponse(requestId = request.requestId, status = HostAdapterStatus.UNKNOWN)
            }
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val first = worker.submit<HostAdapterResponse> { ShopSaveSession(transport, path).execute(action) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val original = Files.readAllBytes(path)
            val other = ShopSaveSession(transport, path)
            try {
                assertFailsWith<OverlappingFileLockException> { other.execute(action) }
                assertFailsWith<OverlappingFileLockException> { other.reconcile() }
                assertEquals(1, requests.size)
                assertContentEquals(original, Files.readAllBytes(path))
            } finally { release.countDown() }
            assertEquals(HostAdapterStatus.UNKNOWN, first.get(10, TimeUnit.SECONDS).status)
            assertEquals(HostAdapterStatus.UNKNOWN, other.reconcile().status)
            assertEquals(requests[0], requests[1])
            assertContentEquals(original, Files.readAllBytes(path))
        } finally {
            release.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `破損した保存待ち記録は再送も新規操作もせず保全する`() {
        val path = Files.writeString(root.resolve("corrupt.json"), "{broken")
        var calls = 0
        val transport = object : ShopTransport {
            override fun worldUuid() = world
            override fun execute(request: HostAdapterRequest): HostAdapterResponse { calls++; error("未到達") }
        }
        val session = ShopSaveSession(transport, path)
        assertFails { session.reconcile() }
        assertFails { session.execute(action) }
        assertEquals(0, calls)
        assertEquals("{broken", Files.readString(path))
    }

    @TempDir lateinit var root: Path
    private val world = UUID.randomUUID().toString()
    private val action = HostAdapterAction.Edit(listOf(
        HostProductChange.Save("tab.apple", null, HostDefinition("item", 10), HostStockEdit.Register(null, null))
    ))

    @Test fun `応答ID不一致と作用前確証のない拒否は結果不明として元本文を残す`() {
        for (mismatched in listOf(false, true)) {
            val path = root.resolve("pending-$mismatched.json")
            val transport = object : ShopTransport {
                override fun worldUuid() = world
                override fun execute(request: HostAdapterRequest) = HostAdapterResponse(
                    requestId = if (mismatched) UUID.randomUUID().toString() else request.requestId,
                    status = if (mismatched) HostAdapterStatus.SUCCESS else HostAdapterStatus.REJECTED,
                    result = if (mismatched) HostAdapterResult.Edited(emptyList()) else null)
            }
            val session = ShopSaveSession(transport, path)
            val response = session.execute(action)
            if (mismatched) assertEquals(HostAdapterStatus.UNKNOWN, response.status)
            assertNotNull(session.pending())
            assertEquals(action, session.pending()?.action)
        }
    }

    @Test fun `送信前の永続化失敗ではtransportを呼ばない`() {
        val blocked = Files.writeString(root.resolve("file"), "保全")
        var calls = 0
        val transport = object : ShopTransport {
            override fun worldUuid() = world
            override fun execute(request: HostAdapterRequest): HostAdapterResponse { calls++; error("未到達") }
        }
        assertFails { ShopSaveSession(transport, blocked.resolve("pending.json")).execute(action) }
        assertEquals(0, calls)
        assertEquals("保全", Files.readString(blocked))
    }

    @Test fun `別worldへの再接続では元要求を再送しない`() {
        var calls = 0
        val transport = object : ShopTransport {
            override fun worldUuid() = world
            override fun execute(request: HostAdapterRequest): HostAdapterResponse { calls++; throw java.io.IOException() }
        }
        val path = root.resolve("pending.json")
        ShopSaveSession(transport, path).execute(action)
        val original = Files.readAllBytes(path)
        val other = object : ShopTransport {
            override fun worldUuid() = UUID.randomUUID().toString()
            override fun execute(request: HostAdapterRequest): HostAdapterResponse { calls++; error("未到達") }
        }
        assertFails { ShopSaveSession(other, path).reconcile() }
        assertEquals(1, calls)
        assertContentEquals(original, Files.readAllBytes(path))
    }
    @Test fun `読込と保存の間にworldが変わると保存要求を送らない`() {
        var current = world
        var calls = 0
        val transport = object : ShopTransport {
            override fun worldUuid() = current
            override fun execute(request: HostAdapterRequest): HostAdapterResponse {
                calls++
                return HostAdapterResponse(requestId = request.requestId, status = HostAdapterStatus.SUCCESS,
                    result = HostAdapterResult.Products(emptyList()))
            }
        }
        val session = ShopSaveSession(transport, root.resolve("pending.json"))
        session.read(listOf("tab.apple"))
        current = UUID.randomUUID().toString()
        assertFails { session.execute(action) }
        assertEquals(1, calls)
        assertNull(session.pending())
    }

}
