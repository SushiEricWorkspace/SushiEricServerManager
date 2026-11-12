package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.VanillaBlockId
import io.github.sushiericworkspace.sushiericdatamodel.data.drop.model.mutable.MutableDropItemData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.ore.model.mutable.MutableOreBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.mutable.MutableRecipeData
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.sushiericservermanager.editor.service.*
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.*

class UploadPlanServiceTest {
    @TempDir lateinit var root: File

    private class Remote(val files: LocalEditorDataStore) : EditorDataStore by files {
        override val kind = EditorDataStoreKind.REMOTE
        override val documentSource = EditorDocumentSource.Server("profile", "host/root")
        var writes = 0
        override fun <T : ManagedData<T, *>> save(descriptor: EditorDataDescriptor<T>, id: String, data: T): StoreResult<Unit> {
            writes++
            return files.save(descriptor, id, data)
        }
    }

    private fun service(): Pair<Remote, EditorDataService> {
        val remote = Remote(LocalEditorDataStore(root.resolve("remote")))
        return remote to EditorDataService(remote, root.resolve("app"))
    }

    private fun <T : ManagedData<T, *>> saved(access: EditorDataService.DataAccess<T>, value: T): LocalDocumentRecord {
        val body = access.encodeDocument(value)
        return assertIs<StoreResult.Success<LocalDocumentRecord>>(access.updateLocalDocument(value.id) {
            it.copy(editing = body, original = body, saved = body)
        }).value
    }

    private fun planGroup(plans: UploadPlanService, vararg keys: EditorDocumentKey, destination: String = "event"): UploadPlanGroup =
        assertIs<StoreResult.Success<UploadPlanGroup>>(plans.createGroup(keys.map { UploadPlanRoot(it) }, destination)).value

    private fun operation(server: EditorDataService, group: UploadPlanGroup): SavedUploadOperation {
        val selections = assertIs<StoreResult.Success<List<SavedUploadSelection>>>(UploadPlanService(server).selections(group)).value
        val planner = SavedUploadPlanner(server.localService, server)
        val loadedPreview = planner.preview(selections)
        val preview = assertIs<StoreResult.Success<SavedUploadPreview>>(loadedPreview, loadedPreview.toString()).value
        return assertIs<StoreResult.Success<SavedUploadOperation>>(planner.build(preview,
            preview.candidates.associate { it.input.key to it.merge.conflicts.mapTo(mutableSetOf()) { conflict -> conflict.path } },
            confirmUnknownBaseline = true)).value
    }

    @Test fun `同じLocalを複数directoryへ予約しcopy内部IDを復元しても変えない`() {
        val (remote, server) = service()
        try {
            val local = server.localService
            val item = local.items.createDefault("tools.one")
            val record = saved(local.items, item)
            val before = assertIs<StoreResult.Success<LocalDocumentSnapshot>>(local.documents.read()).value.documents
            val plans = UploadPlanService(server)
            val first = planGroup(plans, record.key, destination = "event")
            val second = planGroup(plans, record.key, destination = "other")
            assertNotEquals(item.internalId.value, first.itemIds.getValue(item.internalId.value))
            assertNotEquals(first.itemIds, second.itemIds)
            val current = assertIs<StoreResult.Success<UploadPlan>>(plans.read()).value
            val persisted = assertIs<StoreResult.Success<UploadPlan>>(plans.save(current.add(first).add(second))).value
            assertEquals(persisted, assertIs<StoreResult.Success<UploadPlan>>(UploadPlanService(server).read()).value)
            assertEquals(before, assertIs<StoreResult.Success<LocalDocumentSnapshot>>(local.documents.read()).value.documents)
            assertEquals(0, remote.writes)
        } finally { server.closeIo() }
    }

    @Test fun `ItemとOreとRecipeのcopy参照だけを付け替え元savedとdirtyrawを保持する`() {
        val (remote, server) = service()
        try {
            val local = server.localService
            val item = local.items.createDefault("item")
            val ore = local.ores.createDefault("ore").apply {
                blockId = VanillaBlockId("iron_ore"); mutableDropItems += MutableDropItemData(item.internalId)
            }
            val recipe = local.recipes.createDefault("recipe").apply {
                ingredients = RecipeIngredients.Shaped(mapOf(0 to item.internalId)); resultItemInternalId = item.internalId
            }
            val itemRecord = saved(local.items, item); val oreRecord = saved(local.ores, ore); val recipeRecord = saved(local.recipes, recipe)
            val group = planGroup(UploadPlanService(server), itemRecord.key, oreRecord.key, recipeRecord.key)
            val dirty = local.ores.encodeDocument(ore.deepCopy().apply { hardness = 9.0 })
            assertIs<StoreResult.Success<*>>(local.ores.updateLocalDocument(ore.id) {
                it.copy(editing = dirty, raw = mapOf("hardness" to "-"))
            })
            val before = assertIs<StoreResult.Success<LocalDocumentSnapshot>>(local.documents.read()).value.documents
            val request = operation(server, group)
            assertEquals(itemRecord.saved, request.inputs.single { it.key == itemRecord.key }.saved)
            assertEquals(oreRecord.saved, request.inputs.single { it.key == oreRecord.key }.saved)
            assertEquals(emptyMap(), request.inputs.single { it.key == oreRecord.key }.raw)
            assertEquals(local.items.dataType.categoryDirName, request.steps.first().key.category)
            assertTrue(assertIs<StoreResult.Success<SavedUploadOperation>>(SavedUploadExecutor(local, server).execute(request)).value.complete)
            val copied = assertIs<StoreResult.Success<MutableItemBaseData>>(remote.files.load(EditorDataDescriptors.item, "event.item")).value
            val copiedOre = assertIs<StoreResult.Success<MutableOreBaseData>>(
                remote.files.load(EditorDataDescriptors.ore, "event.ore")).value
            val copiedRecipe = assertIs<StoreResult.Success<MutableRecipeData>>(
                remote.files.load(EditorDataDescriptors.recipe, "event.recipe")).value
            assertNotEquals(item.internalId, copied.internalId)
            assertNotEquals(ore.internalId, copiedOre.internalId)
            assertEquals(copied.internalId, copiedOre.dropItems.single().itemId)
            assertEquals(copied.internalId, copiedRecipe.resultItemInternalId)
            assertEquals(mapOf(0 to copied.internalId), (copiedRecipe.ingredients as RecipeIngredients.Shaped).slots)
            assertEquals(before, assertIs<StoreResult.Success<LocalDocumentSnapshot>>(local.documents.read()).value.documents)
        } finally { server.closeIo() }
    }

    @Test fun `既存target更新はtargetの内部IDを維持する`() {
        val (remote, server) = service()
        try {
            val existing = EditorDataDescriptors.item.createDefault("event.item").apply { display.displayName = "old" }
            assertIs<StoreResult.Success<*>>(remote.files.save(EditorDataDescriptors.item, existing.id, existing))
            val local = server.localService
            val item = local.items.createDefault("item").apply { display.displayName = "new" }
            val record = saved(local.items, item)
            val group = planGroup(UploadPlanService(server), record.key)
            assertEquals(existing.internalId.value, group.itemIds[item.internalId.value])
            assertTrue(assertIs<StoreResult.Success<SavedUploadOperation>>(
                SavedUploadExecutor(local, server).execute(operation(server, group))).value.complete)
            val copied = assertIs<StoreResult.Success<MutableItemBaseData>>(
                remote.files.load(EditorDataDescriptors.item, "event.item")).value
            assertEquals(existing.internalId, copied.internalId); assertEquals("new", copied.display.displayName)
            assertEquals(record.saved, assertIs<StoreResult.Success<LocalDocumentSnapshot>>(local.documents.read()).value.documents.single().saved)
        } finally { server.closeIo() }
    }

    @Test fun `Oreだけの選択でも不足Itemを予約へ明示追加して依存順に反映する`() {
        val (remote, server) = service()
        try {
            val local = server.localService
            val item = local.items.createDefault("tools.item")
            val ore = local.ores.createDefault("ore").apply {
                blockId = VanillaBlockId("iron_ore"); mutableDropItems += MutableDropItemData(item.internalId)
            }
            saved(local.items, item); val record = saved(local.ores, ore)
            val group = planGroup(UploadPlanService(server), record.key)
            assertEquals(setOf("event.ore", "event.tools.item"), group.documents.map { it.targetId }.toSet())
            val request = operation(server, group)
            assertEquals(local.items.dataType.categoryDirName, request.steps.first().key.category)
            assertTrue(assertIs<StoreResult.Success<SavedUploadOperation>>(SavedUploadExecutor(local, server).execute(request)).value.complete)
            assertEquals(2, remote.writes)
        } finally { server.closeIo() }
    }

    @Test fun `再割当ては相対階層を保ち同じsourceとsavedを使う`() {
        val (remote, server) = service()
        try {
            val local = server.localService
            val item = local.items.createDefault("tools.deep.item")
            val record = saved(local.items, item)
            val plans = UploadPlanService(server)
            val first = assertIs<StoreResult.Success<UploadPlanGroup>>(
                plans.createGroup(listOf(UploadPlanRoot(record.key.copy(id = "tools"), true)), "event")).value
            val plan = UploadPlan(server.documentSource as EditorDocumentSource.Server).add(first)
            val moved = assertIs<StoreResult.Success<UploadPlan>>(plans.reassign(plan, first.id, "other")).value.groups.single()
            assertEquals("other.deep.item", moved.documents.single().targetId)
            assertEquals(record.key, moved.documents.single().key)
            assertEquals(first.documents.single().savedHash, moved.documents.single().savedHash)
            assertEquals(0, remote.writes)
        } finally { server.closeIo() }
    }

    @Test fun `予約後のsaved変更は拒否しdirtyだけの変化は送信本文に混ぜない`() {
        val (remote, server) = service()
        try {
            val local = server.localService
            val item = local.items.createDefault("one")
            val record = saved(local.items, item)
            val plans = UploadPlanService(server); val group = planGroup(plans, record.key)
            assertIs<StoreResult.Success<*>>(local.items.updateLocalDocument(item.id) { it.copy(editing = "draft", raw = mapOf("field" to "-")) })
            assertIs<StoreResult.Success<*>>(plans.selections(group))
            val changed = local.items.encodeDocument(item.deepCopy().apply { display.displayName = "other saved" })
            assertIs<StoreResult.Success<*>>(local.items.updateLocalDocument(item.id) { it.copy(saved = changed) })
            assertIs<StoreResult.Failure>(plans.selections(group))
            assertEquals(0, remote.writes)
        } finally { server.closeIo() }
    }

    @Test fun `Localのrename保留をServer旧位置の移動へ流用しない`() {
        val (remote, server) = service()
        try {
            val local = server.localService
            val item = local.items.createDefault("one")
            saved(local.items, item)
            assertIs<StoreResult.Success<*>>(local.store.rename(EditorDataDescriptors.item, "one", "renamed"))
            val renamed = local.items.documentKey("renamed")
            assertIs<StoreResult.Success<*>>(local.items.updateLocalDocument("renamed") {
                it.copy(saved = it.editing, savedPending = it.pending, savedDeleted = it.deleted)
            })
            val group = planGroup(UploadPlanService(server), renamed)
            val request = operation(server, group)
            assertEquals(listOf("event.renamed"), request.steps.map { it.key.id })
            assertTrue(request.steps.all { it.destination == null })
            assertTrue(assertIs<StoreResult.Success<SavedUploadOperation>>(SavedUploadExecutor(local, server).execute(request)).value.complete)
            assertIs<StoreResult.Failure>(remote.files.load(EditorDataDescriptors.item, "one"))
            assertIs<StoreResult.Success<*>>(remote.files.load(EditorDataDescriptors.item, "event.renamed"))
        } finally { server.closeIo() }
    }

    @Test fun `同じcopyを再確認しても元fingerprintだけでskipせず実read一致でwriteを省く`() {
        val (remote, server) = service()
        try {
            val local = server.localService
            val record = saved(local.items, local.items.createDefault("one"))
            val group = planGroup(UploadPlanService(server), record.key)
            assertTrue(assertIs<StoreResult.Success<SavedUploadOperation>>(
                SavedUploadExecutor(local, server).execute(operation(server, group))).value.complete)
            assertEquals(1, remote.writes)
            val next = operation(server, group)
            assertEquals(1, next.steps.size)
            assertEquals(next.steps.single().before, next.steps.single().after)
            assertTrue(assertIs<StoreResult.Success<SavedUploadOperation>>(SavedUploadExecutor(local, server).execute(next)).value.complete)
            assertEquals(1, remote.writes)
        } finally { server.closeIo() }
    }

    @Test fun `予約後の宛先新規作成は内部ID上書きせず再割当てを求める`() {
        val (remote, server) = service()
        try {
            val local = server.localService; val item = local.items.createDefault("one")
            val record = saved(local.items, item); val plans = UploadPlanService(server)
            val group = planGroup(plans, record.key)
            val other = server.items.createDefault("event.one")
            assertIs<StoreResult.Success<*>>(remote.files.save(server.items.descriptor, other.id, other))
            val selections = assertIs<StoreResult.Success<List<SavedUploadSelection>>>(plans.selections(group)).value
            assertIs<StoreResult.Failure>(SavedUploadPlanner(local, server).preview(selections))
            assertEquals(other.internalId, assertIs<StoreResult.Success<MutableItemBaseData>>(remote.files.load(server.items.descriptor, other.id)).value.internalId)
            val plan = UploadPlan(server.documentSource as EditorDocumentSource.Server).add(group)
            val reassigned = assertIs<StoreResult.Success<UploadPlan>>(plans.reassign(plan, group.id, "event")).value.groups.single()
            assertEquals(other.internalId.value, reassigned.itemIds.getValue(item.internalId.value))
            assertTrue(assertIs<StoreResult.Success<SavedUploadOperation>>(SavedUploadExecutor(local, server).execute(operation(server, reassigned))).value.complete)
            assertEquals(other.internalId, assertIs<StoreResult.Success<MutableItemBaseData>>(remote.files.load(server.items.descriptor, other.id)).value.internalId)
        } finally { server.closeIo() }
    }

    @Test fun `既存Oreの内部ID差替えも確認前に拒否する`() {
        val (remote, server) = service()
        try {
            val local = server.localService
            val ore = local.ores.createDefault("one").apply { blockId = VanillaBlockId("iron_ore") }
            val record = saved(local.ores, ore)
            val target = server.ores.createDefault("event.one").apply { blockId = VanillaBlockId("iron_ore") }
            assertIs<StoreResult.Success<*>>(remote.files.save(server.ores.descriptor, target.id, target))
            val plans = UploadPlanService(server); val group = planGroup(plans, record.key)
            val replacement = target.duplicateAsNew()
            assertIs<StoreResult.Success<*>>(remote.files.save(server.ores.descriptor, target.id, replacement))
            assertIs<StoreResult.Failure>(SavedUploadPlanner(local, server).preview(
                assertIs<StoreResult.Success<List<SavedUploadSelection>>>(plans.selections(group)).value))
            assertEquals(0, remote.writes)
        } finally { server.closeIo() }
    }

    @Test fun `削除された宛先への新コピーは旧projectionの成功を流用しない`() {
        val (remote, server) = service()
        try {
            val local = server.localService; val item = local.items.createDefault("one")
            val record = saved(local.items, item); val plans = UploadPlanService(server)
            val first = planGroup(plans, record.key)
            assertTrue(assertIs<StoreResult.Success<SavedUploadOperation>>(SavedUploadExecutor(local, server).execute(operation(server, first))).value.complete)
            assertIs<StoreResult.Success<*>>(remote.files.delete(server.items.descriptor, "event.one"))
            val second = planGroup(plans, record.key)
            assertNotEquals(first.itemIds, second.itemIds)
            val planner = SavedUploadPlanner(local, server)
            val preview = assertIs<StoreResult.Success<SavedUploadPreview>>(planner.preview(
                assertIs<StoreResult.Success<List<SavedUploadSelection>>>(plans.selections(second)).value)).value
            assertFalse(preview.candidates.single().baselineKnown)
            val request = operation(server, second)
            assertNotNull(request.steps.single().after)
            assertTrue(assertIs<StoreResult.Success<SavedUploadOperation>>(SavedUploadExecutor(local, server).execute(request)).value.complete)
            val copied = assertIs<StoreResult.Success<MutableItemBaseData>>(remote.files.load(server.items.descriptor, "event.one")).value
            assertEquals(second.itemIds.getValue(item.internalId.value), copied.internalId.value)
            assertEquals(2, remote.writes)
        } finally { server.closeIo() }
    }

    @Test fun `同projectionの固定比較元はremoteだけの差を次回差し戻さず台帳から復元する`() {
        val (remote, server) = service()
        try {
            val local = server.localService; val record = saved(local.items, local.items.createDefault("one"))
            val group = planGroup(UploadPlanService(server), record.key)
            val first = assertIs<StoreResult.Success<SavedUploadOperation>>(SavedUploadExecutor(local, server).execute(operation(server, group))).value
            val input = first.inputs.single(); assertNotNull(input.projectionHash); assertNotNull(input.projectedSaved)
            val changed = assertIs<StoreResult.Success<MutableItemBaseData>>(remote.files.load(server.items.descriptor, "event.one")).value
            changed.display.displayName = "remote only"
            assertIs<StoreResult.Success<*>>(remote.files.save(server.items.descriptor, changed.id, changed))
            val next = operation(server, group)
            assertEquals(next.steps.single().before, next.steps.single().after)
            assertTrue(assertIs<StoreResult.Success<SavedUploadOperation>>(SavedUploadExecutor(local, server).execute(next)).value.complete)
            assertEquals(1, remote.writes)
            val restored = assertIs<StoreResult.Success<LocalDocumentSnapshot>>(server.documents.read()).value.uploads.first { it.id == first.id }
            assertEquals(input.projectionHash, restored.inputs.single().projectionHash)
            assertEquals(input.projectedSaved, restored.inputs.single().projectedSaved)
            assertEquals(record.saved, assertIs<StoreResult.Success<LocalDocumentSnapshot>>(local.documents.read()).value.documents.single().saved)
        } finally { server.closeIo() }
    }

    @Test fun `コピー成功を通常250の送信済み基準として流用しない`() {
        val (remote, server) = service()
        try {
            val local = server.localService; val item = local.items.createDefault("one")
            val record = saved(local.items, item)
            val group = planGroup(UploadPlanService(server), record.key, destination = "")
            assertTrue(assertIs<StoreResult.Success<SavedUploadOperation>>(SavedUploadExecutor(local, server).execute(operation(server, group))).value.complete)
            val copied = assertIs<StoreResult.Success<MutableItemBaseData>>(remote.files.load(server.items.descriptor, "one")).value
            assertNotEquals(item.internalId, copied.internalId)
            val preview = assertIs<StoreResult.Success<SavedUploadPreview>>(SavedUploadPlanner(local, server).preview(
                listOf(SavedUploadSelection(record.key)))).value
            assertEquals(1, preview.candidates.size)
            assertFalse(preview.candidates.single().baselineKnown)
            assertEquals(record.saved, preview.inputs.single().saved)
            assertNull(preview.inputs.single().projectionHash)
            assertEquals(1, remote.writes)
        } finally { server.closeIo() }
    }
}
