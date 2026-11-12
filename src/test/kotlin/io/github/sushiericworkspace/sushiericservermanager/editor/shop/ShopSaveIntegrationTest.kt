package io.github.sushiericworkspace.sushiericservermanager.editor.shop

import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericdatamodel.database.*
import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericdatamodel.database.run.WorldRunLifecycle
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.ShopDataMerger
import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.editor.service.*
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.*
import io.github.sushiericworkspace.sushiericdatamodel.data.shop.model.mutable.MutableShopProductData
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.*

class ShopSaveIntegrationTest {
    @TempDir lateinit var root: Path
    private val worldId = UUID.randomUUID()
    private lateinit var world: Path
    private lateinit var shop: Path
    private lateinit var lifecycle: WorldRunLifecycle
    private lateinit var transport: LocalShopTransport
    private lateinit var session: ShopSaveSession
    private var dropEditResponse = false
    private val id = "tab.apple"

    private fun setup() {
        root = root.toRealPath()
        world = Files.createDirectories(root.resolve("日本語 world"))
        shop = Files.createDirectories(root.resolve("config/shop_data"))
        lifecycle = WorldRunLifecycle.adoptInitial(world, shop, worldId, "a".repeat(64))
        database(true) { }
        transport = LocalShopTransport(world, shop, worldId.toString())
        session = ShopSaveSession(object : ShopTransport {
            override fun worldUuid() = worldId.toString()
            override fun execute(request: HostAdapterRequest): HostAdapterResponse {
                val response = transport.execute(request)
                if (request.action is HostAdapterAction.Edit && dropEditResponse) {
                    dropEditResponse = false
                    throw java.io.IOException("応答喪失")
                }
                return response
            }
        }, root.resolve("pending/request.json"))
        saved(HostAdapterAction.Edit(listOf(HostProductChange.Save(
            id, null, HostDefinition("missing-item", 100, 50), HostStockEdit.Register(12, null)
        ))))
    }

    private fun <T> database(create: Boolean = false, block: (SushiEricDatabase) -> T): T {
        lifecycle.openOfflineWriter(lifecycle.readStatus()).use { writer ->
            val operationId = UUID.randomUUID()
            val operation = writer.beginOperation(operationId, "b".repeat(64))
            val value = operation.openDatabase(create).use(block)
            val response = HostAdapterResponse(requestId = operationId.toString(),
                status = HostAdapterStatus.SUCCESS, result = HostAdapterResult.Edited(emptyList()))
            writer.complete(operation.finish(response))
            return value
        }
    }

    private fun baseline(productId: String = id): HostProductRead {
        val response = session.read(listOf(productId))
        assertEquals(HostAdapterStatus.SUCCESS, response.status, response.message)
        return assertIs<HostAdapterResult.Products>(response.result).products.single()
    }

    private fun stock(productId: String = id) = SushiEricDatabase.openReadOnly(
        world.resolve("sushieric/server.sqlite"), worldId
    ).use { it.findStock(productId) }

    private fun saved(edit: HostAdapterAction.Edit): HostAdapterResponse =
        session.execute(edit).also { assertEquals(HostAdapterStatus.SUCCESS, it.status, it.message) }

    @Test fun `編集中の在庫更新後も価格だけの保存は在庫とrevisionを戻さない`() {
        setup()
        val before = baseline()
        val edit = ShopEditorStore.mutable(before).apply { purchasePrice = 200 }
        database { it.setStock(UUID.randomUUID(), Instant.now(), id, 9, 1) }
        saved(HostAdapterAction.Edit(listOf(ShopSavePlan.save(before, edit))))
        assertEquals(ShopStock(9, 2), stock())
        assertEquals(200L, baseline().definition.purchasePrice)
        assertFalse(Files.readString(shop.resolve("tab/apple.yml")).contains("stock:"))
    }

    @Test fun `明示在庫の古いrevisionは定義も書かず拒否し再読込の競合へ戻せる`() {
        setup()
        val before = baseline()
        val original = ShopEditorStore.mutable(before)
        val edit = original.copy(stock = 8, purchasePrice = 200)
        database { it.setStock(UUID.randomUUID(), Instant.now(), id, 9, 1) }
        val response = session.execute(HostAdapterAction.Edit(listOf(ShopSavePlan.save(before, edit))))
        assertEquals(HostAdapterStatus.REJECTED, response.status)
        assertTrue(response.rejectedBeforeEffect)
        assertNull(session.pending())
        assertEquals(100L, baseline().definition.purchasePrice)
        val remote = ShopEditorStore.mutable(baseline())
        val merge = ShopDataMerger.merge(original, edit, remote)
        assertEquals("在庫", merge.conflicts.single().displayName)
        val resolved = merge.resolveWithLocal(merge.conflicts.mapTo(linkedSetOf()) { it.path })
        assertEquals(remote.stockBaseline, resolved.stockBaseline)
        saved(HostAdapterAction.Edit(listOf(ShopSavePlan.save(baseline(), resolved))))
        assertEquals(ShopStock(8, 3), stock())
    }

    @Test fun `有限無限と未初期化は別状態として編集する`() {
        setup()
        val first = baseline()
        saved(HostAdapterAction.Edit(listOf(ShopSavePlan.save(first, ShopEditorStore.mutable(first).copy(stock = null)))))
        assertEquals(ShopStock(null, 2), stock())
        val infinite = baseline()
        saved(HostAdapterAction.Edit(listOf(ShopSavePlan.save(infinite, ShopEditorStore.mutable(infinite).copy(stock = 4)))))
        assertEquals(ShopStock(4, 3), stock())
        val uninitialized = first.copy(stock = null)
        assertIs<HostStockEdit.Register>(ShopSavePlan.save(uninitialized, ShopEditorStore.mutable(uninitialized)).stock)
        assertIs<HostStockEdit.Unchanged>(ShopSavePlan.save(infinite, ShopEditorStore.mutable(infinite)).stock)
    }

    @Test fun `改名と価格変更は一要求で確定し複製削除再登録も履歴を保持する`() {
        setup()
        val before = baseline()
        val moved = ShopEditorStore.mutable(before).copy(id = "other.pear", purchasePrice = 200)
        saved(ShopSavePlan.relocate(before, moved, null))
        assertFalse(Files.exists(shop.resolve("tab/apple.yml")))
        assertEquals(ShopStock(12, 1), stock(moved.id))
        val target = baseline(moved.id)
        saved(HostAdapterAction.Edit(listOf(ShopSavePlan.copy(target, "other.copy", 3, null))))
        assertEquals(ShopStock(3, 1), stock("other.copy"))
        saved(HostAdapterAction.Edit(listOf(ShopSavePlan.delete(target))))
        val revision = assertNotNull(stock(moved.id)).revision
        saved(HostAdapterAction.Edit(listOf(ShopSavePlan.register(moved.copy(stock = null, purchasePrice = 300), revision))))
        assertEquals(ShopStock(null, revision + 1), stock(moved.id))
        val stale = session.execute(HostAdapterAction.Edit(listOf(ShopSavePlan.save(target, moved))))
        assertEquals(HostAdapterStatus.REJECTED, stale.status)
        assertEquals(300L, baseline(moved.id).definition.purchasePrice)
        SushiEricDatabase.openReadOnly(world.resolve("sushieric/server.sqlite"), worldId).use { db ->
            assertTrue(db.history(db.historySnapshot()).any { it.stock?.productId == id })
        }
    }

    @Test fun `SaveとDeleteのsource revision不一致では移動先も作らない`() {
        setup()
        val before = baseline()
        database { it.setStock(UUID.randomUUID(), Instant.now(), id, 7, 1) }
        val response = session.execute(ShopSavePlan.relocate(before, ShopEditorStore.mutable(before).copy(id = "tab.target"), null))
        assertEquals(HostAdapterStatus.REJECTED, response.status)
        assertTrue(Files.exists(shop.resolve("tab/apple.yml")))
        assertFalse(Files.exists(shop.resolve("tab/target.yml")))
        assertNull(stock("tab.target"))
    }

    @Test fun `確定後の切断は元要求を永続保持し再接続しても二重適用しない`() {
        setup()
        val before = baseline()
        var sent: HostAdapterRequest? = null
        val dropped = object : ShopTransport {
            override fun worldUuid() = worldId.toString()
            override fun execute(request: HostAdapterRequest): HostAdapterResponse {
                sent = request
                val response = transport.execute(request)
                assertEquals(HostAdapterStatus.SUCCESS, response.status)
                throw java.io.IOException("応答喪失")
            }
        }
        val pending = root.resolve("reconnect/request.json")
        val first = ShopSaveSession(dropped, pending)
        val action = HostAdapterAction.Edit(listOf(ShopSavePlan.save(before, ShopEditorStore.mutable(before).copy(stock = 6))))
        assertEquals(HostAdapterStatus.UNKNOWN, first.execute(action).status)
        assertEquals(sent, first.pending())
        assertFailsWith<IllegalStateException> { first.execute(action) }
        database { it.setStock(UUID.randomUUID(), Instant.now(), id, 5, 2) }
        val reopened = ShopSaveSession(transport, pending)
        assertEquals(sent, reopened.pending())
        assertEquals(HostAdapterStatus.SUCCESS, reopened.reconcile().status)
        assertEquals(ShopStock(5, 3), stock())
        assertNull(reopened.pending())
    }
    @Test fun `確認後の再読込でcacheが変わっても確認済みhashを置換しない`() {
        setup()
        val store = ShopEditorStore(session)
        val before = assertIs<StoreResult.Success<HostProductRead>>(store.load(id)).value
        val edit = ShopEditorStore.mutable(before).copy(purchasePrice = 200)
        saved(HostAdapterAction.Edit(listOf(ShopSavePlan.save(before, ShopEditorStore.mutable(before).copy(purchasePrice = 300)))))
        assertIs<StoreResult.Success<HostProductRead>>(store.load(id))
        val result = assertIs<StoreResult.Failure>(store.save(edit, before))
        assertEquals(StoreErrorCode.CONFLICT, result.error.code)
        assertEquals(300L, baseline().definition.purchasePrice)
        assertNull(session.pending())
    }

    @Test fun `在庫数量が元に戻っていても編集中のrevision更新は明示在庫の競合になる`() {
        setup()
        val before = ShopEditorStore.mutable(baseline())
        database { it.setStock(UUID.randomUUID(), Instant.now(), id, 9, 1) }
        database { it.setStock(UUID.randomUUID(), Instant.now(), id, 12, 2) }
        val remote = ShopEditorStore.mutable(baseline())
        val merge = ShopDataMerger.merge(before, before.copy(stock = 8), remote)
        assertEquals("在庫", merge.conflicts.single().displayName)
        assertEquals(remote.stockBaseline, merge.merged.stockBaseline)
        val price = ShopDataMerger.merge(before, before.copy(purchasePrice = 200), remote)
        assertTrue(price.conflicts.isEmpty())
        assertIs<HostStockEdit.Unchanged>(ShopSavePlan.save(baseline(), price.merged).stock)
    }

    private fun withUpload(action: (EditorDataService, ShopEditorStore) -> Unit) {
        setup()
        val memory = InMemoryEditorDataStore()
        val item = EditorDataDescriptors.item.createDefault("apple")
        memory.save(EditorDataDescriptors.item, item.id, item)
        val first = baseline()
        saved(HostAdapterAction.Edit(listOf(ShopSavePlan.save(first,
            ShopEditorStore.mutable(first).apply { itemInternalId = item.internalId }))))
        val products = ShopEditorStore(session)
        val files = LocalEditorDataStore(root.resolve("config").toFile())
        val remote = object : EditorDataStore by memory {
            override val kind = EditorDataStoreKind.REMOTE
            override val documentSource = EditorDocumentSource.Server("upload", "fixed/root")
            override val shopProducts = products
            override fun <T : ManagedData<T, *>> list(descriptor: EditorDataDescriptor<T>): StoreResult<List<StoreResource>> =
                if (descriptor == EditorDataDescriptors.shop) files.list(descriptor) else memory.list(descriptor)
            override fun <T : ManagedData<T, *>> load(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<T> {
                val decode = descriptor.readProduct ?: return memory.load(descriptor, id)
                val file = files.load(descriptor, id)
                if (file is StoreResult.Failure) return file
                return when (val read = products.load(id)) {
                    is StoreResult.Failure -> read
                    is StoreResult.Success -> StoreResult.Success(decode(read.value))
                }
            }
        }
        val service = EditorDataService(remote, root.resolve("editor").toFile())
        try { action(service, products) } finally { service.closeIo() }
    }

    private fun plan(service: EditorDataService, id: String = this.id): SavedUploadOperation {
        val planner = SavedUploadPlanner(service, service)
        val preview = assertIs<StoreResult.Success<SavedUploadPreview>>(
            planner.preview(listOf(SavedUploadSelection(service.shops.documentKey(id))))).value
        val paths = preview.candidates.associate { it.input.key to it.merge.conflicts.mapTo(mutableSetOf()) { conflict -> conflict.path } }
        return assertIs<StoreResult.Success<SavedUploadOperation>>(planner.build(preview, paths, true)).value
    }

    @Test fun `商品のローカル保存はDBを変えず明示アップロードだけがD3へ反映する`() = withUpload { service, _ ->
        val data = assertIs<StoreResult.Success<Pair<MutableShopProductData, LocalDocumentRecord>>>(
            service.shops.observeStore(id)).value.first
        data.purchasePrice = 250
        assertIs<StoreResult.Success<Unit>>(service.store.save(EditorDataDescriptors.shop, id, data))
        assertEquals(100L, baseline().definition.purchasePrice)
        val operation = plan(service)
        assertNotNull(operation.steps.single().productRequest)
        val completed = assertIs<StoreResult.Success<SavedUploadOperation>>(
            SavedUploadExecutor(service, service).execute(operation)).value
        assertTrue(completed.complete)
        assertEquals(250L, baseline().definition.purchasePrice)
        assertEquals(false, assertIs<StoreResult.Success<List<LocalDocumentRecord>>>(service.shops.localDocuments()).value.single().unreflected)
    }

    @Test fun `価格だけのアップロードは確認後の在庫更新を巻き戻さない`() = withUpload { service, _ ->
        assertIs<StoreResult.Success<*>>(service.shops.observeStore(id))
        val edit = assertIs<StoreResult.Success<MutableShopProductData>>(
            service.store.load(EditorDataDescriptors.shop, id)).value.apply { purchasePrice = 200 }
        assertIs<StoreResult.Success<Unit>>(service.store.save(EditorDataDescriptors.shop, id, edit))
        val operation = plan(service)
        database { it.setStock(UUID.randomUUID(), Instant.now(), id, 9, 1) }
        val completed = assertIs<StoreResult.Success<SavedUploadOperation>>(SavedUploadExecutor(service, service).execute(operation)).value
        assertTrue(completed.complete)
        assertEquals(ShopStock(9, 2), stock())
        assertEquals(200L, baseline().definition.purchasePrice)
    }

    @Test fun `アップロードの古い在庫revisionは未反映として台帳と保存済み内容を残す`() = withUpload { service, _ ->
        service.shops.observeStore(id)
        val edit = assertIs<StoreResult.Success<MutableShopProductData>>(
            service.store.load(EditorDataDescriptors.shop, id)).value.apply { stock = 6; purchasePrice = 200 }
        service.store.save(EditorDataDescriptors.shop, id, edit)
        val operation = plan(service)
        database { it.setStock(UUID.randomUUID(), Instant.now(), id, 9, 1) }
        val failed = assertIs<StoreResult.Success<SavedUploadOperation>>(SavedUploadExecutor(service, service).execute(operation)).value
        assertEquals(SavedUploadState.FAILED, failed.steps.single().state)
        assertEquals(100L, baseline().definition.purchasePrice)
        assertEquals(ShopStock(9, 2), stock())
        assertNull(session.pending())
        assertNotNull(assertIs<StoreResult.Success<List<LocalDocumentRecord>>>(service.shops.localDocuments()).value.single().saved)
    }

    @Test fun `商品削除は読取baselineが取得できなければアップロード要求を作らない`() = withUpload { service, _ ->
        service.shops.observeStore(id)
        service.store.delete(EditorDataDescriptors.shop, id)
        saved(HostAdapterAction.Edit(listOf(ShopSavePlan.delete(baseline()))))
        val planner = SavedUploadPlanner(service, service)
        val preview = assertIs<StoreResult.Success<SavedUploadPreview>>(
            planner.preview(listOf(SavedUploadSelection(service.shops.documentKey(id))))).value
        val paths = preview.candidates.associate { it.input.key to it.merge.conflicts.mapTo(mutableSetOf()) { c -> c.path } }
        assertIs<StoreResult.Failure>(planner.build(preview, paths, true))
        assertTrue(assertIs<StoreResult.Success<LocalDocumentSnapshot>>(service.documents.read()).value.uploads.isEmpty())
    }

    @Test fun `商品の改名と価格変更はアップロードの一要求で保存削除する`() = withUpload { service, _ ->
        service.shops.observeStore(id)
        assertIs<StoreResult.Success<Unit>>(service.store.rename(EditorDataDescriptors.shop, id, "pear"))
        val next = "tab.pear"
        val data = assertIs<StoreResult.Success<MutableShopProductData>>(
            service.store.load(EditorDataDescriptors.shop, next)).value.apply { purchasePrice = 300 }
        service.store.save(EditorDataDescriptors.shop, next, data)
        val operation = plan(service, next)
        val request = assertNotNull(operation.steps.single().productRequest)
        assertEquals(2, assertIs<HostAdapterAction.Edit>(request.action).products.size)
        assertTrue(assertIs<StoreResult.Success<SavedUploadOperation>>(SavedUploadExecutor(service, service).execute(operation)).value.complete)
        assertFalse(Files.exists(shop.resolve("tab/apple.yml")))
        assertEquals(300L, baseline(next).definition.purchasePrice)
    }

    @Test fun `商品アップロードの応答喪失は同一要求を保持し明示照合だけで解決する`() = withUpload { service, _ ->
        service.shops.observeStore(id)
        val data = assertIs<StoreResult.Success<MutableShopProductData>>(
            service.store.load(EditorDataDescriptors.shop, id)).value.apply { stock = 6 }
        service.store.save(EditorDataDescriptors.shop, id, data)
        val operation = plan(service)
        val fixed = assertNotNull(operation.steps.single().productRequest)
        dropEditResponse = true
        val stored = assertIs<StoreResult.Success<SavedUploadOperation>>(
            SavedUploadExecutor(service, service).execute(operation)).value
        assertEquals(SavedUploadState.UNKNOWN, stored.steps.single().state)
        assertIs<StoreResult.Failure>(SavedUploadExecutor(service, service).execute(stored))
        assertEquals(fixed, session.pending())
        database { it.setStock(UUID.randomUUID(), Instant.now(), id, 5, 2) }
        val confirmed = assertIs<StoreResult.Success<SavedUploadOperation>>(SavedUploadExecutor(service, service).readback(stored)).value
        assertTrue(confirmed.complete)
        assertEquals(fixed, confirmed.steps.single().productRequest)
        assertNull(session.pending())
        assertEquals(ShopStock(5, 3), stock())
    }

    @Test fun `商品の送信予約はローカル原文書を変えずD3要求へ結合する`() = withUpload { service, _ ->
        val local = service.localService
        val data = ShopEditorStore.mutable(baseline()).apply { id = "local.copy"; stock = 4; purchasePrice = 333 }
        val record = assertIs<StoreResult.Success<LocalDocumentRecord>>(
            local.shops.saveLocalDocument(data.id, data, emptyMap())).value
        val plans = UploadPlanService(service)
        val group = assertIs<StoreResult.Success<UploadPlanGroup>>(
            plans.createGroup(listOf(UploadPlanRoot(record.key)), "event")).value
        val savedPlan = assertIs<StoreResult.Success<UploadPlan>>(
            plans.save(assertIs<StoreResult.Success<UploadPlan>>(plans.read()).value.add(group))).value
        val planner = SavedUploadPlanner(local, service)
        val preview = assertIs<StoreResult.Success<SavedUploadPreview>>(
            planner.preview(assertIs<StoreResult.Success<List<SavedUploadSelection>>>(plans.selections(group)).value)).value
        val request = assertIs<StoreResult.Success<SavedUploadOperation>>(planner.build(preview, emptyMap(), true)).value
        val before = assertIs<StoreResult.Success<LocalDocumentSnapshot>>(local.documents.read()).value.documents
        assertNotNull(request.steps.single().productRequest)
        assertTrue(assertIs<StoreResult.Success<SavedUploadOperation>>(
            UploadPlanExecution(service).execute(savedPlan, group.id, request, { false }, {})).value.complete)
        assertEquals(333L, baseline("event.copy").definition.purchasePrice)
        assertEquals(ShopStock(4, 1), stock("event.copy"))
        assertEquals(before, assertIs<StoreResult.Success<LocalDocumentSnapshot>>(local.documents.read()).value.documents)
    }

}
