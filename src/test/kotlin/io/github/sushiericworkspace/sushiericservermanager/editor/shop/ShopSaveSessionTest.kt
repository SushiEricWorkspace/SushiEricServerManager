package io.github.sushiericworkspace.sushiericservermanager.editor.shop

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class ShopSaveSessionTest {
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
