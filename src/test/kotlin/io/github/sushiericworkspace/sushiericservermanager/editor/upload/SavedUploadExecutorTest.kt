package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.editor.service.*
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SavedUploadExecutorTest {
    @TempDir lateinit var root: File

    private class Remote(private val memory: InMemoryEditorDataStore = InMemoryEditorDataStore()) : EditorDataStore by memory {
        override val kind = EditorDataStoreKind.REMOTE
        override val documentSource = EditorDocumentSource.Server("profile", "host/root")
        var writable = true
        var readsFail = false
        var failReadAfterWrite = false
        var writes = 0
        var beforeWrite: (() -> Unit)? = null
        var afterRelocate: (() -> Unit)? = null
        var leaveOldOnRename = false
        var enforceRecipeValidation = false
        override val isWritable get() = writable
        override fun <T : ManagedData<T, *>> load(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<T> =
            if (readsFail) StoreResult.Failure(StoreError(StoreErrorCode.STORE_UNAVAILABLE)) else memory.load(descriptor, id)
        override fun <T : ManagedData<T, *>> save(descriptor: EditorDataDescriptor<T>, id: String, data: T): StoreResult<Unit> {
            writes++
            beforeWrite?.invoke()
            if (enforceRecipeValidation && descriptor == EditorDataDescriptors.recipe) {
                when (val validation = descriptor.validateInStore?.invoke(data, this)) {
                    is StoreResult.Failure -> return validation
                    is StoreResult.Success -> if (validation.value.any { it.isError })
                        return StoreResult.Failure(StoreError(StoreErrorCode.VALIDATION_FAILED))
                    null -> Unit
                }
            }
            val result = memory.save(descriptor, id, data)
            if (failReadAfterWrite) readsFail = true
            return result
        }
        override fun <T : ManagedData<T, *>> rename(
            descriptor: EditorDataDescriptor<T>, oldId: String, newName: String
        ): StoreResult<Unit> {
            writes++
            val result = if (leaveOldOnRename) {
                val value = (memory.load(descriptor, oldId) as StoreResult.Success).value
                val targetId = io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.PublicId.join(
                    io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.PublicId.directoryOf(oldId), newName)
                memory.save(descriptor, targetId, value.apply { id = targetId })
            } else memory.rename(descriptor, oldId, newName)
            afterRelocate?.invoke()
            return result
        }
        override fun <T : ManagedData<T, *>> move(
            descriptor: EditorDataDescriptor<T>, id: String, directory: String
        ): StoreResult<String> {
            writes++
            val result = memory.move(descriptor, id, directory)
            afterRelocate?.invoke()
            return result
        }
        override fun <T : ManagedData<T, *>> delete(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<Unit> {
            writes++
            val result = memory.delete(descriptor, id)
            if (failReadAfterWrite) readsFail = true
            return result
        }
        fun <T : ManagedData<T, *>> seed(descriptor: EditorDataDescriptor<T>, data: T) {
            memory.save(descriptor, data.id, data)
        }
        fun seed() {
            val data = EditorDataDescriptors.ore.createDefault("same").apply { hardness = 2.0 }
            memory.save(EditorDataDescriptors.ore, "same", data)
        }
    }

    private fun prepare(service: EditorDataService): SavedUploadOperation {
        val access = service.ores
        val before = (service.sourceStore.load(access.descriptor, "same") as StoreResult.Success).value
        val after = before.deepCopy().apply { hardness = 3.0 }
        access.updateLocalDocument("same") {
            it.copy(editing = access.encodeDocument(after), saved = access.encodeDocument(after),
                original = access.encodeDocument(after), baseline = access.encodeDocument(before), baselineKnown = true)
        }
        val record = (access.localDocuments() as StoreResult.Success).value.single()
        return SavedUploadOperation("operation", service.documentSource as EditorDocumentSource.Server,
            listOf(SavedUploadDocument(record.key, record.revision, record.saved, record.savedDeleted, record.savedRaw)),
            listOf(SavedUploadTargetVersion(record.key, record.revision)),
            listOf(SavedUploadStep(record.key, access.encodeDocument(before), record.saved)))
    }

    @Test
    fun changedObservedRevisionRejectsBeforeAnyWrite() {
        val remote = Remote().apply { seed() }
        val service = EditorDataService(remote, root.resolve("autosave"))
        try {
            val operation = prepare(service)
            service.ores.updateLocalDocument("same") { it.copy(editing = "unsaved-draft", raw = mapOf("hardness" to "-")) }
            // 確認時点より後の別編集は予約で拒否します。
            assertTrue(SavedUploadExecutor(service, service).execute(operation) is StoreResult.Failure)
            assertEquals(0, remote.writes)
        } finally { service.closeIo() }
    }

    @Test
    fun ownConfirmedRevisionCanBeReopenedWithoutDuplicateWrite() {
        val remote = Remote().apply { seed() }
        val service = EditorDataService(remote, root.resolve("autosave"))
        try {
            val operation = prepare(service)
            val executor = SavedUploadExecutor(service, service)
            val confirmed = (executor.execute(operation) as StoreResult.Success).value
            assertTrue(confirmed.complete)
            assertEquals(1, remote.writes)
            assertTrue((executor.execute(confirmed) as StoreResult.Success).value.complete)
            assertEquals(1, remote.writes)
            val record = (service.ores.localDocuments() as StoreResult.Success).value.single()
            assertEquals(operation.inputs.single().saved, record.saved)
            assertEquals(operation.steps.single().after, record.baseline)
        } finally { service.closeIo() }
    }

    @Test
    fun unknownSurvivesRecreationAndReadbackDoesNotWrite() {
        val remote = Remote().apply { seed(); failReadAfterWrite = true }
        var service = EditorDataService(remote, root.resolve("autosave"))
        val operation = prepare(service)
        val uncertain = (SavedUploadExecutor(service, service).execute(operation) as StoreResult.Success).value
        assertTrue(uncertain.needsReadback)
        assertEquals(1, remote.writes)
        service.closeIo()
        service = EditorDataService(remote, root.resolve("autosave"))
        try {
            val restored = (service.documents.read() as StoreResult.Success).value.uploads.single()
            assertTrue(SavedUploadExecutor(service, service).execute(restored) is StoreResult.Failure)
            val fresh = restored.copy(id = "new-operation", revision = 0,
                steps = operation.steps)
            assertTrue(SavedUploadExecutor(service, service).execute(fresh) is StoreResult.Failure)
            assertEquals(1, remote.writes)
            remote.readsFail = false
            val readback = (SavedUploadExecutor(service, service).readback(restored) as StoreResult.Success).value
            assertTrue(readback.complete)
            assertEquals(1, remote.writes)
        } finally { service.closeIo() }
    }

    @Test
    fun journalFailurePreventsAnyRemoteWrite() {
        val remote = Remote().apply { seed() }
        val service = EditorDataService(remote, root.resolve("autosave"))
        try {
            val operation = prepare(service)
            val directory = root.resolve("autosave/documents").resolve(service.documentSource.directory)
            FileChannel.open(directory.resolve("publisher.lock").toPath(), StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    assertTrue(SavedUploadExecutor(service, service).execute(operation) is StoreResult.Failure)
                }
            }
            assertEquals(0, remote.writes)
            assertTrue((service.documents.read() as StoreResult.Success).value.uploads.isEmpty())
        } finally { service.closeIo() }
    }

    @Test
    fun readonlyStopsBeforeJournalOrWriteAndKeepsLocalData() {
        val remote = Remote().apply { seed(); writable = false }
        val service = EditorDataService(remote, root.resolve("autosave"))
        try {
            val operation = prepare(service)
            assertTrue(SavedUploadExecutor(service, service).execute(operation) is StoreResult.Failure)
            assertEquals(0, remote.writes)
            assertEquals(operation.inputs.single().saved, (service.ores.localDocuments() as StoreResult.Success).value.single().saved)
            assertTrue((service.documents.read() as StoreResult.Success).value.uploads.isEmpty())
        } finally { service.closeIo() }
    }
    @Test
    fun readbackRejectsNewTamperedAndStaleRequestsBeforeBaselineChanges() {
        val remote = Remote().apply { seed() }
        val service = EditorDataService(remote, root.resolve("autosave"))
        try {
            val initial = prepare(service)
            val unknown = initial.copy(steps = initial.steps.map { it.copy(state = SavedUploadState.UNKNOWN) })
            val stored = (service.documents.publish(emptyMap(), emptyList(), upload = unknown) as StoreResult.Success)
                .value.uploads.single()
            val prior = (service.documents.read() as StoreResult.Success).value
            val invalid = listOf(
                stored.copy(id = "unrecorded", revision = 0),
                stored.copy(steps = stored.steps.map { it.copy(after = it.before) }),
                stored.copy(steps = stored.steps.map { it.copy(state = SavedUploadState.WRITING) }),
                stored.copy(revision = 0)
            )
            invalid.forEach {
                assertTrue(SavedUploadExecutor(service, service).readback(it) is StoreResult.Failure)
                assertEquals(prior, (service.documents.read() as StoreResult.Success).value)
            }
            assertEquals(0, remote.writes)
        } finally { service.closeIo() }
    }

    @Test
    fun sameIdSecondExecutionAndReadbackCannotReleaseTheFirstReservation() {
        val remote = Remote().apply { seed() }
        val first = EditorDataService(remote, root.resolve("autosave"))
        val second = EditorDataService(remote, root.resolve("autosave"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val operation = prepare(first)
            remote.beforeWrite = {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS)) { "試験の作用待機が解除されませんでした。" }
            }
            val running = first.submitIo { SavedUploadExecutor(first, first).execute(operation) }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            try {
                val writing = (second.documents.read() as StoreResult.Success).value.uploads.single()
                assertTrue(SavedUploadExecutor(second, second).execute(operation) is StoreResult.Failure)
                assertTrue(SavedUploadExecutor(second, second).readback(writing) is StoreResult.Failure)
                assertTrue(second.ores.updateLocalDocument("same") { it.copy(saved = "forbidden") } is StoreResult.Failure)
                assertTrue(second.ores.updateLocalDocument("other") { it.copy(saved = "free") } is StoreResult.Success)
                assertEquals(writing, (second.documents.read() as StoreResult.Success).value.uploads.single())
            } finally { release.countDown() }
            assertTrue((running.get(10, TimeUnit.SECONDS) as StoreResult.Success).value.complete)
            assertEquals(1, remote.writes)
        } finally {
            release.countDown()
            first.awaitIo()
            first.closeIo()
            second.closeIo()
        }
    }

    @Test
    fun savedRawCannotSendThePreviousTypedValue() {
        val remote = Remote().apply { seed() }
        val service = EditorDataService(remote, root.resolve("autosave"))
        try {
            val initial = prepare(service)
            val record = (service.ores.updateLocalDocument("same") {
                it.copy(savedRaw = mapOf("hardness" to "-"), raw = mapOf("hardness" to "-"))
            } as StoreResult.Success).value
            val operation = initial.copy(
                inputs = listOf(SavedUploadDocument(record.key, record.revision, record.saved, false, record.savedRaw)),
                targetRevisions = listOf(SavedUploadTargetVersion(record.key, record.revision)))
            assertTrue(SavedUploadExecutor(service, service).execute(operation) is StoreResult.Failure)
            assertEquals(0, remote.writes)
            assertEquals(record, (service.ores.localDocuments() as StoreResult.Success).value.single())
        } finally { service.closeIo() }
    }

    @Test
    fun unreservedDuplicateOrDifferentSourceStepHasNoRemoteEffect() {
        val remote = Remote().apply { seed() }
        val service = EditorDataService(remote, root.resolve("autosave"))
        try {
            val initial = prepare(service)
            val invalid = listOf(
                initial.copy(targetRevisions = emptyList()),
                initial.copy(targetRevisions = initial.targetRevisions + initial.targetRevisions),
                initial.copy(steps = initial.steps + initial.steps),
                initial.copy(steps = initial.steps.map { it.copy(key = it.key.copy(source = EditorDocumentSource.Local)) })
            )
            val prior = (service.documents.read() as StoreResult.Success).value
            invalid.forEach {
                assertTrue(SavedUploadExecutor(service, service).execute(it) is StoreResult.Failure)
                assertEquals(prior, (service.documents.read() as StoreResult.Success).value)
            }
            assertEquals(0, remote.writes)
        } finally { service.closeIo() }
    }

    private fun <T : ManagedData<T, *>> renamePlan(
        service: EditorDataService, access: EditorDataService.DataAccess<T>
    ): SavedUploadOperation {
        val before = (service.sourceStore.load(access.descriptor, "same") as StoreResult.Success).value
        assertTrue(access.observeStore("same") is StoreResult.Success)
        assertTrue(service.store.rename(access.descriptor, "same", "renamed") is StoreResult.Success)
        val records = (access.localDocuments() as StoreResult.Success).value
        val old = records.single { it.key.id == "same" }
        val next = records.single { it.key.id == "renamed" }
        val intermediate = access.encodeDocument(before.deepCopy().apply { id = "renamed" })
        return SavedUploadOperation("rename", service.documentSource as EditorDocumentSource.Server,
            records.map { SavedUploadDocument(it.key, it.revision, it.saved, it.savedDeleted, it.savedRaw, it.savedPending) },
            records.map { SavedUploadTargetVersion(it.key, it.revision) },
            listOf(SavedUploadStep(old.key, access.encodeDocument(before), intermediate, destination = next.key),
                SavedUploadStep(next.key, intermediate, next.saved)))
    }

    @Test
    fun recipeRenameUsesExistingRelocateBeforeSaveInsteadOfSelfCollision() {
        val remote = Remote().apply { enforceRecipeValidation = true }
        val item = EditorDataDescriptors.item.createDefault("item")
        remote.seed(EditorDataDescriptors.item, item)
        val recipe = EditorDataDescriptors.recipe.createDefault("same").apply {
            resultItemInternalId = item.internalId
            ingredients = io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeIngredients.Shaped(
                mapOf(0 to item.internalId))
        }
        remote.seed(EditorDataDescriptors.recipe, recipe)
        val service = EditorDataService(remote, root.resolve("autosave"))
        try {
            val naive = recipe.deepCopy().apply { id = "renamed" }
            assertTrue(remote.save(EditorDataDescriptors.recipe, "renamed", naive) is StoreResult.Failure)
            remote.writes = 0
            val operation = renamePlan(service, service.recipes)
            val complete = (SavedUploadExecutor(service, service).execute(operation) as StoreResult.Success).value
            assertTrue(complete.complete)
            assertEquals(1, remote.writes)
            assertTrue(complete.steps.all { it.state == SavedUploadState.CONFIRMED })
            assertTrue(remote.load(EditorDataDescriptors.recipe, "same") is StoreResult.Failure)
            assertEquals("renamed", (remote.load(EditorDataDescriptors.recipe, "renamed") as StoreResult.Success).value.id)
        } finally { service.closeIo() }
    }

    @Test
    fun existingRelocationDestinationStopsBeforeAnyEffect() {
        val remote = Remote().apply { seed() }
        val service = EditorDataService(remote, root.resolve("autosave"))
        try {
            val operation = renamePlan(service, service.ores)
            val existing = service.ores.createDefault("renamed").apply { hardness = 99.0 }
            remote.seed(EditorDataDescriptors.ore, existing)
            val result = (SavedUploadExecutor(service, service).execute(operation) as StoreResult.Success).value
            assertEquals(SavedUploadState.FAILED, result.steps.first().state)
            assertEquals(SavedUploadState.READY, result.steps.last().state)
            assertEquals(0, remote.writes)
            assertEquals(99.0, (remote.load(EditorDataDescriptors.ore, "renamed") as StoreResult.Success).value.hardness)
        } finally { service.closeIo() }
    }

    @Test
    fun uncertainRelocationStopsNextStepAndReadbackDoesNotRepeatIt() {
        val remote = Remote().apply { seed() }
        val service = EditorDataService(remote, root.resolve("autosave"))
        try {
            val operation = renamePlan(service, service.ores)
            remote.afterRelocate = { remote.readsFail = true }
            val unknown = (SavedUploadExecutor(service, service).execute(operation) as StoreResult.Success).value
            assertEquals(SavedUploadState.UNKNOWN, unknown.steps.first().state)
            assertEquals(SavedUploadState.READY, unknown.steps.last().state)
            assertEquals(1, remote.writes)
            remote.readsFail = false
            val confirmed = (SavedUploadExecutor(service, service).readback(unknown) as StoreResult.Success).value
            assertEquals(SavedUploadState.CONFIRMED, confirmed.steps.first().state)
            assertEquals(SavedUploadState.READY, confirmed.steps.last().state)
            assertEquals(1, remote.writes)
            val completed = (SavedUploadExecutor(service, service).execute(confirmed) as StoreResult.Success).value
            assertTrue(completed.complete)
            assertEquals(SavedUploadState.CONFIRMED, completed.steps.last().state)
            assertEquals(1, remote.writes)
        } finally { service.closeIo() }
    }

    @Test
    fun matchingDestinationWithOldSourceStillPresentRemainsUnknown() {
        val remote = Remote().apply { seed(); leaveOldOnRename = true }
        val service = EditorDataService(remote, root.resolve("autosave"))
        try {
            val operation = renamePlan(service, service.ores)
            val unknown = (SavedUploadExecutor(service, service).execute(operation) as StoreResult.Success).value
            assertTrue(unknown.needsReadback)
            assertEquals(SavedUploadState.READY, unknown.steps.last().state)
            assertTrue((SavedUploadExecutor(service, service).readback(unknown) as StoreResult.Success).value.needsReadback)
            assertEquals(1, remote.writes)
            assertTrue(remote.load(EditorDataDescriptors.ore, "same") is StoreResult.Success)
            assertTrue(remote.load(EditorDataDescriptors.ore, "renamed") is StoreResult.Success)
        } finally { service.closeIo() }
    }

    @Test
    fun changedOldOrNewLocalRevisionCannotTurnRemoteRelocationIntoUnexecuted() {
        for (changedId in listOf("same", "renamed")) {
            val remote = Remote().apply { seed() }
            val home = root.resolve(changedId)
            val service = EditorDataService(remote, home)
            val peer = EditorDataService(remote, home)
            try {
                val operation = renamePlan(service, service.ores)
                remote.afterRelocate = { remote.readsFail = true }
                val unknown = (SavedUploadExecutor(service, service).execute(operation) as StoreResult.Success).value
                val changed = (peer.ores.updateLocalDocument(changedId) {
                    it.copy(editing = "peer-draft", raw = mapOf("hardness" to "-"))
                } as StoreResult.Success).value
                remote.readsFail = false
                assertTrue(SavedUploadExecutor(service, service).readback(unknown) is StoreResult.Failure)
                assertEquals(1, remote.writes)
                assertTrue(remote.load(EditorDataDescriptors.ore, "same") is StoreResult.Failure)
                assertTrue(remote.load(EditorDataDescriptors.ore, "renamed") is StoreResult.Success)
                assertEquals(changed, (peer.ores.localDocuments() as StoreResult.Success).value.single { it.key.id == changedId })
                assertTrue((peer.documents.read() as StoreResult.Success).value.uploads.single().needsReadback)
            } finally { service.closeIo(); peer.closeIo() }
        }
    }

    @Test
    fun relocationDestinationMustAlsoBeReservedBeforeEffect() {
        val remote = Remote().apply { seed() }
        val service = EditorDataService(remote, root.resolve("autosave"))
        try {
            val operation = renamePlan(service, service.ores)
            val invalid = operation.copy(targetRevisions = operation.targetRevisions.filter { it.key.id == "same" })
            assertTrue(SavedUploadExecutor(service, service).execute(invalid) is StoreResult.Failure)
            assertEquals(0, remote.writes)
            assertTrue((service.documents.read() as StoreResult.Success).value.uploads.isEmpty())
        } finally { service.closeIo() }
    }

    @Test
    fun confirmedMergedSavedIsAcknowledgedWithoutReplacingTheSavedOrDraft() {
        val remote = Remote().apply { seed() }
        val home = root.resolve("autosave")
        var service = EditorDataService(remote, home)
        try {
            val initial = prepare(service)
            val saved = initial.inputs.single().saved
            val latest = service.ores.decodeDocument("same", requireNotNull(saved)).apply { requiredTier = 8 }
            val draft = (service.ores.updateLocalDocument("same") {
                it.copy(editing = "editing-raw", raw = mapOf("hardness" to "-"))
            } as StoreResult.Success).value
            val operation = initial.copy(inputs = listOf(SavedUploadDocument(draft.key, draft.revision,
                saved, false, emptyMap())), targetRevisions = listOf(SavedUploadTargetVersion(draft.key, draft.revision)),
                steps = initial.steps.map { it.copy(after = service.ores.encodeDocument(latest)) })
            assertTrue((SavedUploadExecutor(service, service).execute(operation) as StoreResult.Success).value.complete)
            val record = (service.ores.localDocuments() as StoreResult.Success).value.single()
            assertEquals(saved, record.saved)
            assertEquals("editing-raw", record.editing)
            assertEquals(mapOf("hardness" to "-"), record.raw)
            assertNotEquals(record.saved, record.baseline)
            assertEquals(false, record.unreflected)
            assertTrue(record.unsaved)
            service.closeIo()
            service = EditorDataService(remote, home)
            val restored = (service.ores.localDocuments() as StoreResult.Success).value.single()
            assertEquals(record, restored)
            val snapshot = (service.documents.read() as StoreResult.Success).value
            assertTrue(service.ores.acceptFetched(snapshot, mapOf("same" to latest),
                mapOf("same" to latest), setOf("same")) is StoreResult.Success)
            val fetched = (service.ores.localDocuments() as StoreResult.Success).value.single()
            assertEquals(fetched.baseline, fetched.saved)
            assertNull(fetched.reflectedSavedHash)
            assertEquals(false, fetched.unreflected)
        } finally { service.closeIo() }
    }

    @Test
    fun confirmedDeleteNormalizesRetainedBodyAndRawWhileKeepingDifferentEditingPending() {
        val remote = Remote().apply { seed() }
        val service = EditorDataService(remote, root.resolve("autosave"))
        try {
            val initial = prepare(service)
            val deletion = PendingStoreOperationRecord(PendingStoreOperationKind.DELETE, "same")
            val editing = PendingStoreOperationRecord(PendingStoreOperationKind.RENAME, "other", "same")
            val record = (service.ores.updateLocalDocument("same") {
                it.copy(savedDeleted = true, savedPending = deletion, savedRaw = mapOf("hardness" to "-"),
                    pending = editing, editing = "different-editing", deleted = false)
            } as StoreResult.Success).value
            val operation = initial.copy(inputs = listOf(SavedUploadDocument(record.key, record.revision,
                record.saved, true, record.savedRaw, deletion)),
                targetRevisions = listOf(SavedUploadTargetVersion(record.key, record.revision)),
                steps = initial.steps.map { it.copy(after = null) })
            assertTrue((SavedUploadExecutor(service, service).execute(operation) as StoreResult.Success).value.complete)
            val confirmed = (service.ores.localDocuments() as StoreResult.Success).value.single()
            assertEquals(false, confirmed.unreflected)
            assertNull(confirmed.savedPending)
            assertEquals(editing, confirmed.pending)
            assertEquals(record.savedRaw, confirmed.savedRaw)
            assertEquals(record.saved, confirmed.saved)
            assertTrue(confirmed.unsaved)
            assertNull(confirmed.baseline)
            assertEquals(savedUploadFingerprint(record.key, null, true, emptyMap()), confirmed.reflectedSavedHash)
            assertTrue(remote.load(EditorDataDescriptors.ore, "same") is StoreResult.Failure)
        } finally { service.closeIo() }
    }

    @Test
    fun renameAcknowledgesOnlyFinalSavedStateAndClearsMatchingPending() {
        val remote = Remote().apply { seed() }
        val service = EditorDataService(remote, root.resolve("autosave"))
        try {
            val operation = renamePlan(service, service.ores)
            var intermediateChecked = false
            val result = SavedUploadExecutor(service, service).execute(operation, onProgress = { progress ->
                if (progress.steps.first().state == SavedUploadState.CONFIRMED &&
                    progress.steps.last().state == SavedUploadState.READY) {
                    val next = (service.ores.localDocuments() as StoreResult.Success).value.single { it.key.id == "renamed" }
                    assertNull(next.reflectedSavedHash)
                    assertNotNull(next.savedPending)
                    intermediateChecked = true
                }
            })
            assertTrue((result as StoreResult.Success).value.complete)
            assertTrue(intermediateChecked)
            val records = (service.ores.localDocuments() as StoreResult.Success).value
            records.forEach {
                assertEquals(false, it.unreflected)
                assertNull(it.savedPending)
                assertNull(it.pending)
            }
            val next = records.single { it.key.id == "renamed" }
            val changed = (service.ores.updateLocalDocument("renamed") {
                it.copy(savedPending = PendingStoreOperationRecord(PendingStoreOperationKind.DELETE, "renamed"))
            } as StoreResult.Success).value
            assertEquals(next.saved, changed.saved)
            assertEquals(true, changed.unreflected)
        } finally { service.closeIo() }
    }

    @Test
    fun cancelledAndUnknownDoNotAcknowledgeSavedBeforeReadback() {
        for (cancel in listOf(true, false)) {
            val remote = Remote().apply { seed(); failReadAfterWrite = !cancel }
            val service = EditorDataService(remote, root.resolve(cancel.toString()))
            try {
                val operation = prepare(service)
                val result = (SavedUploadExecutor(service, service).execute(operation, cancelled = { cancel }) as StoreResult.Success).value
                assertEquals(if (cancel) SavedUploadState.CANCELLED else SavedUploadState.UNKNOWN, result.steps.single().state)
                val record = (service.ores.localDocuments() as StoreResult.Success).value.single()
                assertNull(record.reflectedSavedHash)
                assertEquals(true, record.unreflected)
                assertEquals(if (cancel) 0 else 1, remote.writes)
            } finally { service.closeIo() }
        }
    }

    @Test
    fun localOriginDoesNotAcknowledgeSharedLocalOrReplaceServerSavedAndDraft() {
        val remote = Remote().apply { seed() }
        val server = EditorDataService(remote, root.resolve("server"))
        val local = EditorDataService(InMemoryEditorDataStore(), root.resolve("local"))
        try {
            val initial = prepare(server)
            assertTrue((SavedUploadExecutor(server, server).execute(initial) as StoreResult.Success).value.complete)
            val serverBefore = (server.ores.updateLocalDocument("same") {
                it.copy(editing = "server-draft", raw = mapOf("hardness" to "-"))
            } as StoreResult.Success).value
            val body = server.ores.encodeDocument(server.ores.decodeDocument("same", requireNotNull(serverBefore.saved))
                .apply { hardness = 6.0 })
            val origin = (local.ores.updateLocalDocument("same") {
                it.copy(saved = body, editing = body, original = body)
            } as StoreResult.Success).value
            val operation = SavedUploadOperation("local-to-server", server.documentSource as EditorDocumentSource.Server,
                listOf(SavedUploadDocument(origin.key, origin.revision, origin.saved, false, emptyMap())),
                listOf(SavedUploadTargetVersion(serverBefore.key, serverBefore.revision)),
                listOf(SavedUploadStep(serverBefore.key, serverBefore.baseline, body)))
            assertTrue((SavedUploadExecutor(local, server).execute(operation) as StoreResult.Success).value.complete)
            assertEquals(origin, (local.ores.localDocuments() as StoreResult.Success).value.single())
            val result = (server.ores.localDocuments() as StoreResult.Success).value.single()
            assertEquals(serverBefore.saved, result.saved)
            assertEquals(serverBefore.editing, result.editing)
            assertEquals(serverBefore.raw, result.raw)
            assertNull(result.reflectedSavedHash)
            assertEquals(true, result.unreflected)
            assertEquals(body, result.baseline)
        } finally { server.closeIo(); local.closeIo() }
    }

    @Test
    fun directoryMoveAndRenameThenMoveUseEveryReservedIntermediateKey() {
        for (rename in listOf(false, true)) {
            val remote = Remote().apply { seed() }
            val service = EditorDataService(remote, root.resolve("move-" + rename))
            try {
                val access = service.ores
                val before = (remote.load(access.descriptor, "same") as StoreResult.Success).value
                assertTrue(access.observeStore("same") is StoreResult.Success)
                val intermediateId = if (rename) "renamed" else "same"
                if (rename) assertTrue(service.store.rename(access.descriptor, "same", intermediateId) is StoreResult.Success)
                assertTrue(service.store.move(access.descriptor, intermediateId, "group") is StoreResult.Success)
                val records = (access.localDocuments() as StoreResult.Success).value
                val finalId = "group." + intermediateId
                val final = records.single { it.key.id == finalId }
                fun key(id: String) = final.key.copy(id = id)
                fun encoded(id: String) = access.encodeDocument(before.deepCopy().apply { this.id = id })
                val steps = mutableListOf<SavedUploadStep>()
                if (rename) steps += SavedUploadStep(key("same"), encoded("same"), encoded(intermediateId),
                    destination = key(intermediateId))
                steps += SavedUploadStep(key(intermediateId), encoded(intermediateId), encoded(finalId),
                    destination = key(finalId))
                steps += SavedUploadStep(key(finalId), encoded(finalId), final.saved)
                val operation = SavedUploadOperation("move", service.documentSource as EditorDocumentSource.Server,
                    records.map { SavedUploadDocument(it.key, it.revision, it.saved, it.savedDeleted, it.savedRaw, it.savedPending) },
                    records.map { SavedUploadTargetVersion(it.key, it.revision) }, steps)
                assertTrue(operation.validKeys)
                assertTrue((SavedUploadExecutor(service, service).execute(operation) as StoreResult.Success).value.complete)
                assertEquals(if (rename) 2 else 1, remote.writes)
                assertTrue(remote.load(access.descriptor, "same") is StoreResult.Failure)
                if (rename) assertTrue(remote.load(access.descriptor, intermediateId) is StoreResult.Failure)
                assertEquals(finalId, (remote.load(access.descriptor, finalId) as StoreResult.Success).value.id)
                (access.localDocuments() as StoreResult.Success).value.forEach {
                    assertEquals(false, it.unreflected)
                    assertNull(it.savedPending)
                }
            } finally { service.closeIo() }
        }
    }

    @Test
    fun fetchAfterConfirmedDeleteAcceptsRecreationButKeepsUnsentAndNewEditingDeletes() {
        for (scenario in listOf("confirmed", "editing-delete", "unsent", "unknown")) {
            val remote = Remote().apply { seed() }
            val service = EditorDataService(remote, root.resolve(scenario))
            try {
                prepare(service)
                val deletion = PendingStoreOperationRecord(PendingStoreOperationKind.DELETE, "same")
                val confirmed = scenario == "confirmed" || scenario == "editing-delete"
                val record = (service.ores.updateLocalDocument("same") {
                    it.copy(savedDeleted = true, deleted = true, baseline = null, baselineKnown = scenario != "unknown",
                        savedPending = if (confirmed) null else deletion,
                        pending = if (scenario == "editing-delete" || !confirmed) deletion else null,
                        reflectedSavedHash = if (confirmed) savedUploadFingerprint(it.key, it.saved, true, it.savedRaw) else null)
                } as StoreResult.Success).value
                val latest = service.ores.decodeDocument("same", requireNotNull(record.saved)).apply { hardness = 9.0 }
                val previous = (service.documents.read() as StoreResult.Success).value
                val editing = if (scenario == "editing-delete") emptyMap() else mapOf("same" to latest)
                assertTrue(service.ores.acceptFetched(previous, mapOf("same" to latest), editing, setOf("same")) is StoreResult.Success)
                val next = (service.ores.localDocuments() as StoreResult.Success).value.single()
                assertEquals(!confirmed, next.savedDeleted)
                assertEquals(if (confirmed) service.ores.encodeDocument(latest) else record.saved, next.saved)
                assertEquals(record.pending, next.pending)
                assertEquals(record.savedPending, next.savedPending)
                assertEquals(scenario == "editing-delete", confirmed && next.unsaved)
                assertEquals(!confirmed, next.unreflected)
                assertEquals(0, remote.writes)
            } finally { service.closeIo() }
        }
    }

    @Test
    fun matchingExistingOrAbsentRemoteStateConfirmsWithoutAnyWrite() {
        for (absent in listOf(false, true)) {
            val remote = Remote().apply { if (!absent) seed() }
            val service = EditorDataService(remote, root.resolve(absent.toString()))
            try {
                val access = service.items
                val value = access.createDefault("item")
                val body = access.encodeDocument(value)
                if (!absent) remote.seed(access.descriptor, value)
                val pending = PendingStoreOperationRecord(if (absent) PendingStoreOperationKind.DELETE else PendingStoreOperationKind.CREATE, "item")
                val record = (access.updateLocalDocument("item") {
                    it.copy(editing = body, saved = body, original = body, savedDeleted = absent, deleted = absent,
                        pending = pending, savedPending = pending)
                } as StoreResult.Success).value
                val expected = body.takeUnless { absent }
                val operation = SavedUploadOperation("no-op", service.documentSource as EditorDocumentSource.Server,
                    listOf(SavedUploadDocument(record.key, record.revision, record.saved, absent, emptyMap(), pending)),
                    listOf(SavedUploadTargetVersion(record.key, record.revision)),
                    listOf(SavedUploadStep(record.key, expected, expected)))
                assertTrue((SavedUploadExecutor(service, service).execute(operation) as StoreResult.Success).value.complete)
                assertEquals(0, remote.writes)
                val result = (access.localDocuments() as StoreResult.Success).value.single()
                assertEquals(expected, result.baseline)
                assertEquals(false, result.unreflected)
                assertNull(result.savedPending)
            } finally { service.closeIo() }
        }
    }

    @Test
    fun aReadFailureCannotUseTheNoOpPathToAcknowledgeAnAbsentRemoteDocument() {
        val remote = Remote().apply { seed() }
        val service = EditorDataService(remote, root.resolve("autosave"))
        try {
            val initial = prepare(service)
            val operation = initial.copy(steps = initial.steps.map { it.copy(before = it.after) })
            remote.readsFail = true
            val result = (SavedUploadExecutor(service, service).execute(operation) as StoreResult.Success).value
            assertEquals(SavedUploadState.FAILED, result.steps.single().state)
            assertEquals(0, remote.writes)
            val record = (service.ores.localDocuments() as StoreResult.Success).value.single()
            assertNull(record.reflectedSavedHash)
            assertEquals(true, record.unreflected)
        } finally { service.closeIo() }
    }

    @Test
    fun anExistingUnknownCannotBypassExplicitReadbackThroughNoOp() {
        val remote = Remote().apply { seed() }
        val service = EditorDataService(remote, root.resolve("autosave"))
        try {
            val initial = prepare(service)
            val unknown = initial.copy(steps = initial.steps.map {
                it.copy(after = it.before, state = SavedUploadState.UNKNOWN)
            })
            val stored = (service.documents.publish(emptyMap(), emptyList(), upload = unknown) as StoreResult.Success)
                .value.uploads.single()
            assertTrue(SavedUploadExecutor(service, service).execute(stored) is StoreResult.Failure)
            assertEquals(stored, (service.documents.read() as StoreResult.Success).value.uploads.single())
            assertEquals(0, remote.writes)
            assertTrue((SavedUploadExecutor(service, service).readback(stored) as StoreResult.Success).value.complete)
            assertEquals(0, remote.writes)
        } finally { service.closeIo() }
    }

}
