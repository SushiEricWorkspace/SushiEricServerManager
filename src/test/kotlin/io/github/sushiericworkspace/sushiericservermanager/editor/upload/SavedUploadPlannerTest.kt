package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.VanillaBlockId
import io.github.sushiericworkspace.sushiericdatamodel.data.drop.model.mutable.MutableDropItemData
import io.github.sushiericworkspace.sushiericservermanager.editor.service.*
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SavedUploadPlannerTest {
    @TempDir lateinit var root: File

    private class Remote(val files: LocalEditorDataStore) : EditorDataStore by files {
        override val kind = EditorDataStoreKind.REMOTE
        override val documentSource = EditorDocumentSource.Server("profile", "host/root")
        var writes = 0
        var failSave = false
        override fun <T : ManagedData<T, *>> save(descriptor: EditorDataDescriptor<T>, id: String, data: T): StoreResult<Unit> {
            writes++
            if (failSave) return StoreResult.Failure(StoreError(StoreErrorCode.VALIDATION_FAILED))
            return files.save(descriptor, id, data)
        }
        override fun <T : ManagedData<T, *>> rename(descriptor: EditorDataDescriptor<T>, oldId: String, newName: String): StoreResult<Unit> {
            writes++
            return files.rename(descriptor, oldId, newName)
        }
        override fun <T : ManagedData<T, *>> move(descriptor: EditorDataDescriptor<T>, id: String, targetDirectory: String): StoreResult<String> {
            writes++
            return files.move(descriptor, id, targetDirectory)
        }
    }

    private fun remote() = Remote(LocalEditorDataStore(root.resolve("remote-files")))

    private fun <T : ManagedData<T, *>> saved(access: EditorDataService.DataAccess<T>, value: T): LocalDocumentRecord {
        val body = access.encodeDocument(value)
        return (access.updateLocalDocument(value.id) { it.copy(editing = body, saved = body, original = body) }
            as StoreResult.Success).value
    }

    @Test
    fun requiredItemIsPresentInPlannedRecipeCatalogAndMatchesBuiltPayloadWithoutWrites() {
        val remote = remote()
        val service = EditorDataService(remote, root.resolve("server"))
        val local = service.localService
        try {
            val item = local.items.createDefault("new_item")
            val recipe = local.recipes.createDefault("recipe").apply {
                ingredients = RecipeIngredients.Shaped(mapOf(0 to item.internalId))
                resultItemInternalId = item.internalId
            }
            saved(local.items, item)
            val input = saved(local.recipes, recipe)
            val planner = SavedUploadPlanner(local, service)
            val preview = (planner.preview(listOf(SavedUploadSelection(input.key))) as StoreResult.Success).value
            assertEquals(setOf("new_item", "recipe"), preview.candidates.map { it.targetKey.id }.toSet())
            val projected = plannedUploadItems(emptyMap(), preview.candidates, emptyMap())!!
            assertEquals(item.internalId, projected.values.single().internalId)
            val recipePreview = preview.candidates.single { it.recipePreview != null }.recipePreview!!
            assertEquals(recipe.ingredients, recipePreview.planned(emptySet())!!.ingredients)
            val operation = (planner.build(preview, emptyMap(), confirmUnknownBaseline = true) as StoreResult.Success).value
            val writtenItem = service.items.decodeDocument(item.id, operation.steps.single { it.key.id == item.id }.after!!)
            assertEquals(writtenItem.freeze(), (projected.getValue(item.internalId) as MutableItemBaseData).freeze())
            assertEquals(0, remote.writes)
        } finally { service.closeIo(); local.closeIo() }
    }

    @Test
    fun aBaselineOnlyTargetFromLocalUploadCannotBecomeAnImplicitDeletion() {
        val remote = remote()
        val service = EditorDataService(remote, root.resolve("server"))
        val local = service.localService
        try {
            assertTrue(service.items.localDocuments() is StoreResult.Success)
            val input = saved(local.items, local.items.createDefault("new_local"))
            val planner = SavedUploadPlanner(local, service)
            val preview = (planner.preview(listOf(SavedUploadSelection(input.key))) as StoreResult.Success).value
            val operation = (planner.build(preview, emptyMap(), confirmUnknownBaseline = true) as StoreResult.Success).value
            assertTrue((SavedUploadExecutor(local, service).execute(operation) as StoreResult.Success).value.complete)
            val snapshot = (service.documents.read() as StoreResult.Success).value
            val target = snapshot.documents.single { it.key.id == input.key.id }
            assertNull(target.saved)
            assertFalse(target.savedDeleted)
            assertTrue(target.baselineKnown)
            assertNotNull(target.baseline)
            val writes = remote.writes
            val rejected = SavedUploadPlanner(service, service).preview(listOf(SavedUploadSelection(target.key)))
            assertTrue(rejected is StoreResult.Failure)
            assertEquals(snapshot, (service.documents.read() as StoreResult.Success).value)
            assertEquals(writes, remote.writes)
            assertTrue(remote.load(EditorDataDescriptors.item, target.key.id) is StoreResult.Success)
        } finally { service.closeIo(); local.closeIo() }
    }

    @Test
    fun requiredSavedItemPrecedesOreWithoutSendingUnsavedInput() {
        val remote = remote()
        val service = EditorDataService(remote, root.resolve("server"))
        val local = service.localService
        try {
            val item = local.items.createDefault("item")
            val ore = local.ores.createDefault("ore").apply {
                blockId = VanillaBlockId("iron_ore")
                mutableDropItems += MutableDropItemData(item.internalId)
            }
            saved(local.items, item)
            val input = saved(local.ores, ore)
            assertTrue(local.ores.updateLocalDocument("ore") {
                it.copy(editing = "unsaved", raw = mapOf("hardness" to "-"))
            } is StoreResult.Success)
            val planner = SavedUploadPlanner(local, service)
            val preview = (planner.preview(listOf(SavedUploadSelection(input.key))) as StoreResult.Success).value
            assertEquals(setOf("item", "ore"), preview.inputs.map { it.key.id }.toSet())
            assertEquals(0, remote.writes)
            assertTrue(planner.build(preview, emptyMap()) is StoreResult.Failure)
            val operation = (planner.build(preview, emptyMap(), confirmUnknownBaseline = true) as StoreResult.Success).value
            assertEquals(listOf(service.items.dataType.categoryDirName, service.ores.dataType.categoryDirName),
                operation.steps.map { it.key.category })
            assertTrue((SavedUploadExecutor(local, service).execute(operation) as StoreResult.Success).value.complete)
            assertEquals(2, remote.writes)
            assertEquals(ore.hardness, (remote.load(EditorDataDescriptors.ore, "ore") as StoreResult.Success).value.hardness)
            val retained = (local.ores.localDocuments() as StoreResult.Success).value.single()
            assertEquals("unsaved", retained.editing)
            assertEquals(mapOf("hardness" to "-"), retained.raw)
            assertFalse(retained.baselineKnown)
            assertTrue((planner.preview(listOf(SavedUploadSelection(input.key))) as StoreResult.Success).value.candidates.isEmpty())
        } finally { service.closeIo(); local.closeIo() }
    }

    @Test
    fun renameAndDirectoryChangeAreBuiltAsThreeExistingStoreOperations() {
        val remote = remote()
        val ore = EditorDataDescriptors.ore.createDefault("same").apply { blockId = VanillaBlockId("iron_ore") }
        assertTrue(remote.files.save(EditorDataDescriptors.ore, ore.id, ore) is StoreResult.Success)
        val service = EditorDataService(remote, root.resolve("server"))
        try {
            assertTrue(service.ores.observeStore("same") is StoreResult.Success)
            assertTrue(service.store.rename(EditorDataDescriptors.ore, "same", "renamed") is StoreResult.Success)
            assertTrue(service.store.move(EditorDataDescriptors.ore, "renamed", "group") is StoreResult.Success)
            val planner = SavedUploadPlanner(service, service)
            val key = service.ores.documentKey("group.renamed")
            val preview = (planner.preview(listOf(SavedUploadSelection(key))) as StoreResult.Success).value
            val operation = (planner.build(preview, emptyMap()) as StoreResult.Success).value
            assertEquals(listOf("same", "renamed", "group.renamed"), operation.steps.map { it.key.id })
            assertEquals(setOf("same", "renamed", "group.renamed"), operation.targetRevisions.map { it.key.id }.toSet())
            assertTrue((SavedUploadExecutor(service, service).execute(operation) as StoreResult.Success).value.complete)
            assertEquals(2, remote.writes)
            assertTrue((service.documents.read() as StoreResult.Success).value.uploads.single().complete)
            assertTrue(remote.load(EditorDataDescriptors.ore, "same") is StoreResult.Failure)
            assertTrue(remote.load(EditorDataDescriptors.ore, "renamed") is StoreResult.Failure)
            assertTrue(remote.load(EditorDataDescriptors.ore, "group.renamed") is StoreResult.Success)
        } finally { service.closeIo() }
    }

    @Test
    fun intermediateDestinationCollisionAndSavedRawStopBeforeAnyEffect() {
        val remote = remote()
        val ore = EditorDataDescriptors.ore.createDefault("same").apply { blockId = VanillaBlockId("iron_ore") }
        remote.files.save(EditorDataDescriptors.ore, ore.id, ore)
        val service = EditorDataService(remote, root.resolve("server"))
        try {
            service.ores.observeStore("same")
            service.store.rename(EditorDataDescriptors.ore, "same", "renamed")
            service.store.move(EditorDataDescriptors.ore, "renamed", "group")
            remote.files.save(EditorDataDescriptors.ore, "renamed", ore.deepCopy().apply { id = "renamed" })
            val planner = SavedUploadPlanner(service, service)
            val key = service.ores.documentKey("group.renamed")
            assertTrue(planner.preview(listOf(SavedUploadSelection(key))) is StoreResult.Failure)
            service.ores.updateLocalDocument(key.id) { it.copy(savedRaw = mapOf("hardness" to "-")) }
            assertTrue(planner.preview(listOf(SavedUploadSelection(key))) is StoreResult.Failure)
            assertEquals(0, remote.writes)
            assertTrue((service.documents.read() as StoreResult.Success).value.uploads.isEmpty())
        } finally { service.closeIo() }
    }

    @Test
    fun observedTargetRevisionRejectsThePlanWithoutOverwritingPeerDraft() {
        val remote = remote()
        val server = EditorDataService(remote, root.resolve("server"))
        val local = server.localService
        val peer = EditorDataService(remote, root.resolve("server"))
        try {
            val item = local.items.createDefault("item")
            val input = saved(local.items, item)
            val planner = SavedUploadPlanner(local, server)
            val preview = (planner.preview(listOf(SavedUploadSelection(input.key))) as StoreResult.Success).value
            val operation = (planner.build(preview, emptyMap(), true) as StoreResult.Success).value
            val changed = (peer.items.updateLocalDocument("item") {
                it.copy(editing = "peer", raw = mapOf("value" to "-"))
            } as StoreResult.Success).value
            assertTrue(SavedUploadExecutor(local, server).execute(operation) is StoreResult.Failure)
            assertEquals(changed, (peer.items.localDocuments() as StoreResult.Success).value.single())
            assertEquals(0, remote.writes)
        } finally { server.closeIo(); local.closeIo(); peer.closeIo() }
    }

    @Test
    fun modConflictNeedsExplicitChoiceAndShopCapabilityIsNotAssumed() {
        val remote = remote()
        remote.files.writeText("config.yml", "server: 2\n")
        val service = EditorDataService(remote, root.resolve("server"))
        val local = service.localService
        try {
            assertTrue(local.store.writeText("config.yml", "value: [\n") is StoreResult.Success)
            val key = EditorDocumentKey(local.documentSource, "text", "config.yml")
            val planner = SavedUploadPlanner(local, service)
            val preview = (planner.preview(listOf(SavedUploadSelection(key))) as StoreResult.Success).value
            assertEquals(1, preview.candidates.single().merge.conflicts.size)
            assertTrue(planner.build(preview, emptyMap(), true) is StoreResult.Failure)
            val choices = preview.candidates.single().merge.conflicts.mapTo(mutableSetOf()) { it.path }
            val operation = (planner.build(preview, mapOf(key to choices), true) as StoreResult.Success).value
            assertEquals("value: [\n", operation.steps.single().after)
            val shop = saved(local.shops, local.shops.createDefault("group.shop"))
            assertTrue(planner.preview(listOf(SavedUploadSelection(shop.key))) is StoreResult.Failure)
            assertEquals(0, remote.writes)
            assertTrue((service.documents.read() as StoreResult.Success).value.uploads.isEmpty())
        } finally { service.closeIo(); local.closeIo() }
    }
    @Test
    fun anotherTargetIdNeverReusesTheOriginalDocumentsBaseline() {
        for (existing in listOf(false, true)) {
            val remote = Remote(LocalEditorDataStore(root.resolve("remote-" + existing)))
            val item = EditorDataDescriptors.item.createDefault("a")
            remote.files.save(EditorDataDescriptors.item, "a", item)
            if (existing) remote.files.save(EditorDataDescriptors.item, "b", item.deepCopy().apply { id = "b" })
            val service = EditorDataService(remote, root.resolve("server-" + existing))
            try {
                assertTrue(service.items.observeStore("a") is StoreResult.Success)
                val planner = SavedUploadPlanner(service, service)
                val preview = (planner.preview(listOf(SavedUploadSelection(service.items.documentKey("a"), "b"))) as StoreResult.Success).value
                assertFalse(preview.candidates.single().baselineKnown)
                assertTrue(planner.build(preview, emptyMap()) is StoreResult.Failure)
                val candidate = preview.candidates.single()
                val choices = candidate.merge.conflicts.mapTo(mutableSetOf()) { it.path }
                val operation = (planner.build(preview, mapOf(candidate.input.key to choices), true) as StoreResult.Success).value
                assertNotNull(operation.steps.single().after)
                assertEquals("b", operation.steps.single().key.id)
                assertEquals(setOf("b"), operation.targetRevisions.map { it.key.id }.toSet())
                assertEquals(0, remote.writes)
            } finally { service.closeIo() }
        }
    }

    @Test
    fun renamedThenDeletedItemPreviewUsesTheOldPublicIdWhenServerContentIsSelected() {
        val remote = remote()
        val item = EditorDataDescriptors.item.createDefault("a")
        remote.files.save(EditorDataDescriptors.item, item.id, item)
        val service = EditorDataService(remote, root.resolve("server"))
        try {
            assertTrue(service.items.observeStore("a") is StoreResult.Success)
            assertTrue(service.store.rename(EditorDataDescriptors.item, "a", "b") is StoreResult.Success)
            assertTrue(service.store.delete(EditorDataDescriptors.item, "b") is StoreResult.Success)
            val latest = item.deepCopy().apply { itemDetail.maxStackSize = 32 }
            remote.files.save(EditorDataDescriptors.item, item.id, latest)
            val planner = SavedUploadPlanner(service, service)
            val preview = (planner.preview(listOf(SavedUploadSelection(service.items.documentKey("a")))) as StoreResult.Success).value
            val candidate = preview.candidates.single()
            val conflicts = candidate.merge.conflicts.mapTo(mutableSetOf()) { it.path }
            assertFalse(conflicts.isEmpty())
            for (choices in listOf(emptySet(), conflicts)) {
                val paths = mapOf(candidate.input.key to choices)
                val catalog = plannedUploadItems(mapOf(latest.internalId to latest), preview.candidates, paths)!!
                val operation = (planner.build(preview, paths) as StoreResult.Success).value
                val old = operation.steps.single { it.key.id == "a" }
                assertNull(operation.steps.single { it.key.id == "b" }.after)
                if (old.after == null) assertTrue(catalog.isEmpty())
                else {
                    val written = service.items.decodeDocument("a", old.after)
                    assertEquals(written.id, catalog.values.single().id)
                    assertEquals(written.itemDetail.maxStackSize, catalog.values.single().itemDetail.maxStackSize)
                }
            }
            assertEquals(0, remote.writes)
        } finally { service.closeIo() }
    }

    @Test
    fun deletingUnsentRenameOrMoveDeletesTheOriginalAndConfirmsTheAbsentDestination() {
        for (move in listOf(false, true)) {
            val remote = Remote(LocalEditorDataStore(root.resolve("remote-" + move)))
            val item = EditorDataDescriptors.item.createDefault("a")
            remote.files.save(EditorDataDescriptors.item, item.id, item)
            val service = EditorDataService(remote, root.resolve("server-" + move))
            try {
                assertTrue(service.items.observeStore("a") is StoreResult.Success)
                val id = if (move) "group.a" else "b"
                assertTrue((if (move) service.store.move(EditorDataDescriptors.item, "a", "group")
                    else service.store.rename(EditorDataDescriptors.item, "a", "b")) is StoreResult.Success)
                assertTrue(service.store.delete(EditorDataDescriptors.item, id) is StoreResult.Success)
                val planner = SavedUploadPlanner(service, service)
                val preview = (planner.preview(listOf(SavedUploadSelection(service.items.documentKey("a")))) as StoreResult.Success).value
                val operation = (planner.build(preview, emptyMap()) as StoreResult.Success).value
                assertEquals(setOf("a", id), operation.inputs.map { it.key.id }.toSet())
                assertEquals(setOf("a", id), operation.targetRevisions.map { it.key.id }.toSet())
                assertEquals(listOf("a", id), operation.steps.map { it.key.id })
                assertTrue(operation.steps.all { it.after == null && it.destination == null })
                assertTrue((SavedUploadExecutor(service, service).execute(operation) as StoreResult.Success).value.complete)
                assertTrue(remote.load(EditorDataDescriptors.item, "a") is StoreResult.Failure)
                assertTrue(remote.load(EditorDataDescriptors.item, id) is StoreResult.Failure)
                (service.items.localDocuments() as StoreResult.Success).value.forEach {
                    assertEquals(false, it.unreflected)
                    assertNull(it.savedPending)
                }
            } finally { service.closeIo() }
        }
    }

    @Test
    fun deletingAnUnsentRenameNeverDeletesAnUnrelatedRemoteDestination() {
        val remote = remote()
        val item = EditorDataDescriptors.item.createDefault("a")
        remote.files.save(EditorDataDescriptors.item, "a", item)
        val service = EditorDataService(remote, root.resolve("server"))
        try {
            service.items.observeStore("a")
            service.store.rename(EditorDataDescriptors.item, "a", "b")
            service.store.delete(EditorDataDescriptors.item, "b")
            val unrelated = EditorDataDescriptors.item.createDefault("b")
            remote.files.save(EditorDataDescriptors.item, "b", unrelated)
            val planner = SavedUploadPlanner(service, service)
            assertTrue(planner.preview(listOf(SavedUploadSelection(service.items.documentKey("b")))) is StoreResult.Failure)
            assertEquals(unrelated.internalId, (remote.load(EditorDataDescriptors.item, "b") as StoreResult.Success).value.internalId)
            assertTrue(remote.load(EditorDataDescriptors.item, "a") is StoreResult.Success)
            assertTrue((service.documents.read() as StoreResult.Success).value.uploads.isEmpty())
            assertEquals(0, remote.writes)
        } finally { service.closeIo() }
    }

    @Test
    fun aConfirmedRelocationWithFailedContentUpdateDeletesTheConfirmedNewLocation() {
        val remote = remote()
        val item = EditorDataDescriptors.item.createDefault("a")
        remote.files.save(EditorDataDescriptors.item, "a", item)
        val service = EditorDataService(remote, root.resolve("server"))
        try {
            service.items.observeStore("a")
            service.store.rename(EditorDataDescriptors.item, "a", "b")
            val changed = (service.store.load(EditorDataDescriptors.item, "b") as StoreResult.Success).value
                .apply { display.displayName = "changed" }
            assertTrue(service.store.save(EditorDataDescriptors.item, "b", changed) is StoreResult.Success)
            val planner = SavedUploadPlanner(service, service)
            val key = service.items.documentKey("b")
            val preview = (planner.preview(listOf(SavedUploadSelection(key))) as StoreResult.Success).value
            val operation = (planner.build(preview, emptyMap()) as StoreResult.Success).value
            remote.failSave = true
            val partial = (SavedUploadExecutor(service, service).execute(operation) as StoreResult.Success).value
            assertEquals(SavedUploadState.CONFIRMED, partial.steps.first().state)
            assertEquals(SavedUploadState.FAILED, partial.steps.last().state)
            assertTrue(service.store.delete(EditorDataDescriptors.item, "b") is StoreResult.Success)
            val deletion = (planner.preview(listOf(SavedUploadSelection(key))) as StoreResult.Success).value
            val next = (planner.build(deletion, emptyMap()) as StoreResult.Success).value
            assertEquals(listOf("b"), next.steps.map { it.key.id })
            assertTrue((SavedUploadExecutor(service, service).execute(next) as StoreResult.Success).value.complete)
            assertTrue(remote.load(EditorDataDescriptors.item, "a") is StoreResult.Failure)
            assertTrue(remote.load(EditorDataDescriptors.item, "b") is StoreResult.Failure)
        } finally { service.closeIo() }
    }

    @Test
    fun aPreviousSourceTombstoneCannotReplaceTheNextReverseRelocation() {
        val remote = remote()
        val item = EditorDataDescriptors.item.createDefault("a")
        remote.files.save(EditorDataDescriptors.item, "a", item)
        val service = EditorDataService(remote, root.resolve("server"))
        try {
            service.items.observeStore("a")
            val planner = SavedUploadPlanner(service, service)
            fun upload(id: String): SavedUploadOperation {
                val preview = (planner.preview(listOf(SavedUploadSelection(service.items.documentKey(id)))) as StoreResult.Success).value
                val operation = (planner.build(preview, emptyMap()) as StoreResult.Success).value
                return (SavedUploadExecutor(service, service).execute(operation) as StoreResult.Success).value
            }
            service.store.rename(EditorDataDescriptors.item, "a", "b")
            assertTrue(upload("b").complete)
            assertTrue(remote.load(EditorDataDescriptors.item, "a") is StoreResult.Failure)
            service.store.rename(EditorDataDescriptors.item, "b", "a")
            val preview = (planner.preview(listOf(SavedUploadSelection(service.items.documentKey("a")))) as StoreResult.Success).value
            assertEquals("b", preview.candidates.single().relocationKey?.id)
            val reverse = (planner.build(preview, emptyMap()) as StoreResult.Success).value
            assertEquals("b", reverse.steps.first().key.id)
            assertEquals("a", reverse.steps.first().destination?.id)
            assertTrue((SavedUploadExecutor(service, service).execute(reverse) as StoreResult.Success).value.complete)
            assertTrue(remote.load(EditorDataDescriptors.item, "b") is StoreResult.Failure)
            assertEquals(item.internalId, (remote.load(EditorDataDescriptors.item, "a") as StoreResult.Success).value.internalId)
        } finally { service.closeIo() }
    }

    @Test
    fun anOlderForwardReceiptDoesNotOwnARepeatedRenameThenDeleteAfterAReverseUpload() {
        val remote = remote()
        val item = EditorDataDescriptors.item.createDefault("a")
        remote.files.save(EditorDataDescriptors.item, "a", item)
        val service = EditorDataService(remote, root.resolve("server"))
        try {
            service.items.observeStore("a")
            val planner = SavedUploadPlanner(service, service)
            for ((old, next) in listOf("a" to "b", "b" to "a")) {
                assertTrue(service.store.rename(EditorDataDescriptors.item, old, next) is StoreResult.Success)
                val preview = (planner.preview(listOf(SavedUploadSelection(service.items.documentKey(next)))) as StoreResult.Success).value
                val operation = (planner.build(preview, emptyMap()) as StoreResult.Success).value
                assertTrue((SavedUploadExecutor(service, service).execute(operation) as StoreResult.Success).value.complete)
            }
            assertTrue(service.store.rename(EditorDataDescriptors.item, "a", "b") is StoreResult.Success)
            assertTrue(service.store.delete(EditorDataDescriptors.item, "b") is StoreResult.Success)
            val preview = (planner.preview(listOf(SavedUploadSelection(service.items.documentKey("b")))) as StoreResult.Success).value
            assertEquals("a", preview.candidates.single().relocationKey?.id)
            val deletion = (planner.build(preview, emptyMap()) as StoreResult.Success).value
            assertEquals(listOf("a", "b"), deletion.steps.map { it.key.id })
            assertTrue((SavedUploadExecutor(service, service).execute(deletion) as StoreResult.Success).value.complete)
            assertTrue(remote.load(EditorDataDescriptors.item, "a") is StoreResult.Failure)
            assertTrue(remote.load(EditorDataDescriptors.item, "b") is StoreResult.Failure)
        } finally { service.closeIo() }
    }

    @Test
    fun ambiguousConfirmedRevisionsAndUnknownOperationsAreHeldInsteadOfUsingOlderReceipts() {
        val remote = remote()
        val item = EditorDataDescriptors.item.createDefault("a")
        remote.files.save(EditorDataDescriptors.item, "a", item)
        val service = EditorDataService(remote, root.resolve("server"))
        try {
            val record = (service.items.observeStore("a") as StoreResult.Success).value.second
            val input = SavedUploadDocument(record.key, record.revision, record.saved, false, emptyMap())
            val operation = SavedUploadOperation("first", service.documentSource as EditorDocumentSource.Server,
                listOf(input), listOf(SavedUploadTargetVersion(record.key, record.revision)),
                listOf(SavedUploadStep(record.key, record.saved, record.saved, state = SavedUploadState.CONFIRMED)))
            assertTrue(service.documents.publish(emptyMap(), emptyList(), upload = operation) is StoreResult.Success)
            assertTrue(service.documents.publish(emptyMap(), emptyList(), upload = operation.copy(id = "same-revision")) is StoreResult.Success)
            val before = (service.documents.read() as StoreResult.Success).value
            val planner = SavedUploadPlanner(service, service)
            assertTrue(planner.preview(listOf(SavedUploadSelection(record.key))) is StoreResult.Failure)
            assertEquals(before, (service.documents.read() as StoreResult.Success).value)
            val unknown = operation.copy(id = "unknown", steps = operation.steps.map { it.copy(state = SavedUploadState.UNKNOWN) })
            assertTrue(service.documents.publish(emptyMap(), emptyList(), upload = unknown) is StoreResult.Success)
            assertTrue(planner.preview(listOf(SavedUploadSelection(record.key))) is StoreResult.Failure)
            assertEquals(0, remote.writes)
        } finally { service.closeIo() }
    }

    @Test
    fun reverseRenameAfterPartialRelocationIsHeldWithoutCopyingOrDeletingEitherPosition() {
        val remote = remote()
        val item = EditorDataDescriptors.item.createDefault("a")
        remote.files.save(EditorDataDescriptors.item, "a", item)
        val service = EditorDataService(remote, root.resolve("server"))
        try {
            service.items.observeStore("a")
            service.store.rename(EditorDataDescriptors.item, "a", "b")
            val changed = (service.store.load(EditorDataDescriptors.item, "b") as StoreResult.Success).value
                .apply { display.displayName = "not-yet-applied" }
            service.store.save(EditorDataDescriptors.item, "b", changed)
            val planner = SavedUploadPlanner(service, service)
            val preview = (planner.preview(listOf(SavedUploadSelection(service.items.documentKey("b")))) as StoreResult.Success).value
            val operation = (planner.build(preview, emptyMap()) as StoreResult.Success).value
            remote.failSave = true
            val partial = (SavedUploadExecutor(service, service).execute(operation) as StoreResult.Success).value
            assertEquals(SavedUploadState.CONFIRMED, partial.steps.first().state)
            assertEquals(SavedUploadState.FAILED, partial.steps.last().state)
            assertTrue(service.store.rename(EditorDataDescriptors.item, "b", "a") is StoreResult.Success)
            val before = (service.documents.read() as StoreResult.Success).value
            val writes = remote.writes
            val result = planner.preview(listOf(SavedUploadSelection(service.items.documentKey("a"))))
            assertTrue(result is StoreResult.Failure)
            assertEquals(before, (service.documents.read() as StoreResult.Success).value)
            assertEquals(writes, remote.writes)
            assertTrue(remote.load(EditorDataDescriptors.item, "a") is StoreResult.Failure)
            assertEquals(item.internalId, (remote.load(EditorDataDescriptors.item, "b") as StoreResult.Success).value.internalId)
        } finally { service.closeIo() }
    }

}
