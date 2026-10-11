package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.communication.managed.*
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.PendingStoreOperationKind
import io.github.sushiericworkspace.sushiericservermanager.editor.service.PendingStoreOperationRecord
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import kotlinx.serialization.json.*
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class ManagedEditorDataStoreTest {
    @Test fun `同じpublicIDの異なる管理rootをCRUDとautoSave pending cache offlineで隔離する`() {
        val root = createTempDirectory("managed-store-profiles-")
        try {
            val first = StorePeer(root.resolve("first"))
            val second = StorePeer(root.resolve("second"))
            val a = first.store()
            val b = second.store()
            val descriptor = EditorDataDescriptors.item
            val sword = MutableItemBaseData(id = "shared").apply { display.displayName = "first" }
            val axe = MutableItemBaseData(id = "shared").apply { display.displayName = "second" }
            assertIs<StoreResult.Success<Unit>>(a.save(descriptor, "shared", sword))
            assertIs<StoreResult.Success<Unit>>(b.save(descriptor, "shared", axe))
            assertEquals("first", assertIs<StoreResult.Success<MutableItemBaseData>>(a.load(descriptor, "shared")).value.display.displayName)
            assertEquals("second", assertIs<StoreResult.Success<MutableItemBaseData>>(b.load(descriptor, "shared")).value.display.displayName)
            assertNotEquals(a.identity, b.identity)
            assertNotEquals(a.session.cacheDirectory, b.session.cacheDirectory)
            val aService = EditorDataService(a, a.session.autoSaveDirectory)
            val bService = EditorDataService(b, b.session.autoSaveDirectory)
            for ((service, data) in listOf(aService to sword, bService to axe)) {
                assertTrue(service.items.saveToLocalBackup("editing", data))
                assertTrue(service.items.saveToLocalBackup("original", data))
                assertTrue(service.items.savePendingStoreOperations(listOf(PendingStoreOperationRecord(PendingStoreOperationKind.CREATE, "shared"))))
            }
            aService.items.deleteLocalBackup("shared")
            assertTrue(aService.items.savePendingStoreOperations(emptyList()))
            assertNull(aService.items.loadBackupPair("shared"))
            assertEquals("second", bService.items.loadBackupPair("shared")!!.first.display.displayName)
            assertEquals(1, bService.items.loadPendingStoreOperations().size)
            a.session.offlineDirectory.mkdirs()
            a.session.offlineDirectory.resolve("only-first.yml").writeText("first")
            assertFalse(b.session.offlineDirectory.resolve("only-first.yml").exists())
            assertIs<StoreResult.Success<Unit>>(a.rename(descriptor, "shared", "renamed"))
            val renamed = assertIs<StoreResult.Success<MutableItemBaseData>>(a.load(descriptor, "renamed")).value
            assertEquals("renamed", renamed.id)
            assertEquals(sword.internalId, renamed.internalId)
            assertIs<StoreResult.Success<MutableItemBaseData>>(b.load(descriptor, "shared"))
            assertIs<StoreResult.Success<Unit>>(a.delete(descriptor, "renamed"))
            assertFalse(first.files.containsKey("item_data/stats/renamed.yml"))
            assertTrue(second.files.containsKey("item_data/stats/shared.yml"))
            assertFalse(first.fixture.profile.root.toFile().exists(), "Storeは管理rootを直接作成しない")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `rename衝突の作用前拒否は再保存可能で結果不明は成功やqueue消去にならない`() {
        val root = createTempDirectory("managed-store-failure-")
        try {
            val peer = StorePeer(root)
            val store = peer.store()
            val descriptor = EditorDataDescriptors.item
            val item = MutableItemBaseData(id = "first").apply { display.displayName = "Sword" }
            assertIs<StoreResult.Success<Unit>>(store.save(descriptor, "first", item))
            peer.files["item_data/stats/taken.yml"] = peer.files.getValue("item_data/stats/first.yml")
            assertEquals(StoreErrorCode.ALREADY_EXISTS, assertIs<StoreResult.Failure>(store.rename(descriptor, "first", "taken")).error.code)
            assertEquals(ManagedSession.State.OPEN, store.session.state)
            assertIs<StoreResult.Success<Unit>>(store.save(descriptor, "first", item))
            val service = EditorDataService(store, store.session.autoSaveDirectory)
            val pending = PendingStoreOperationRecord(PendingStoreOperationKind.CREATE, "first")
            assertTrue(service.items.savePendingStoreOperations(listOf(pending)))
            peer.failure = SupervisorFailure("RESULT_UNKNOWN")
            assertIs<StoreResult.Failure>(service.items.saveStore("first", item))
            assertEquals(listOf(pending), service.items.loadPendingStoreOperations())
            assertEquals(ManagedSession.State.UNKNOWN, store.session.state)
            assertFailsWith<IllegalStateException> { store.session.close {} }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun `互換性readOnlyと不正IDではsupervisor変更要求を送らない`() {
        val root = createTempDirectory("managed-store-permission-")
        try {
            val peer = StorePeer(root)
            val session = peer.session()
            val store = ManagedEditorDataStore(session, ManagerCompatibility.UpdateRequired("99.0.0"))
            val before = peer.fixture.commands.size
            assertEquals(StoreErrorCode.PERMISSION_DENIED, assertIs<StoreResult.Failure>(store.writeText("config.yml", "secret")).error.code)
            assertEquals(StoreErrorCode.INVALID_ID, assertIs<StoreResult.Failure>(store.delete(EditorDataDescriptors.item, "../escape")).error.code)
            assertEquals(before, peer.fixture.commands.size)
            assertEquals(ManagedSession.State.OPEN, session.state)
        } finally { root.toFile().deleteRecursively() }
    }

    private class StorePeer(private val storage: Path) : SupervisorClient {
        val fixture = ManagedSessionTest.Fixture(storage)
        val files = mutableMapOf<String, String>()
        var failure: SupervisorFailure? = null
        fun session() = ManagedSession.open(fixture.profile, storage, this)
        fun store() = ManagedEditorDataStore(session(), ManagerCompatibility.Compatible("0.1.0"))
        override fun request(command: String, payload: JsonObject, operationId: String): JsonObject {
            if (command != "writer-io") return fixture.request(command, payload, operationId)
            fixture.commands += command to payload
            failure?.let { throw it }
            val request = payload.getValue("request").jsonObject
            val path = request.getValue("path").jsonPrimitive.content
            return when (request.getValue("action").jsonPrimitive.content) {
                "read" -> buildJsonObject { put("text", files[path] ?: throw SupervisorFailure("FILE_NOT_FOUND", rejectedBeforeEffect = true)) }
                "list" -> buildJsonObject {
                    putJsonArray("entries") {
                        files.keys.filter { it.startsWith("$path/") }.map { it.removePrefix("$path/").substringBefore('/') }.distinct().forEach { name ->
                            add(buildJsonObject { put("name", name); put("isDirectory", files.keys.any { it.startsWith("$path/$name/") }) })
                        }
                    }
                }
                "write" -> { files[path] = request.getValue("text").jsonPrimitive.content; buildJsonObject { put("completed", true) } }
                "move" -> {
                    val destination = request.getValue("destination").jsonPrimitive.content
                    if (destination in files) throw SupervisorFailure("ALREADY_EXISTS", rejectedBeforeEffect = true)
                    files[destination] = request["text"]?.jsonPrimitive?.content ?: files.getValue(path)
                    files.remove(path); buildJsonObject { put("completed", true) }
                }
                "delete" -> { files.remove(path); buildJsonObject { put("completed", true) } }
                else -> error("試験対象外")
            }
        }
    }
}
