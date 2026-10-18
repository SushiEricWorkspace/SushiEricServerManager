package io.github.sushiericworkspace.sushiericservermanager.editor.service

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStoreKind
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assumptions.assumeTrue

class LocalDocumentServiceTest {
    @TempDir lateinit var root: File

    private fun <T : ManagedData<T, *>> observed(access: EditorDataService.DataAccess<T>, id: String): Map<String, LocalDocumentVersion> {
        val record = (access.localDocuments() as StoreResult.Success).value.single { it.key.id == id }
        return mapOf(id to LocalDocumentVersion(record.key, record.revision, record.unsaved))
    }

    @Test
    fun incompleteTypedDataAndRawInputCanBeSavedWithoutRemoteIo() {
        val remote = object : EditorDataStore by InMemoryEditorDataStore() {
            override val kind = EditorDataStoreKind.REMOTE
            override val documentSource = EditorDocumentSource.Server("profile", "host/root")
            override val isAvailable: Boolean get() = error("通信可否をローカル保存条件に使いません。")
        }
        val service = EditorDataService(remote, root.resolve("autosave"))
        val ore = service.ores.createDefault("same")
        val saved = service.ores.saveLocalDocument("same", ore, mapOf("hardness" to "-")) as StoreResult.Success
        assertEquals("-", saved.value.savedRaw["hardness"])
        assertFalse(saved.value.unsaved)
        assertFalse(saved.value.baselineKnown)
        val forImport = (service.ores.savedDocumentsForImport() as StoreResult.Success).value.getValue("same")
        assertEquals("-", forImport.second["hardness"])
        assertEquals(ore.hardness, forImport.first.hardness)
        val restored = service.ores.decodeDocument("same", requireNotNull(saved.value.saved))
        assertEquals("same", restored.id)
        assertEquals(ore.hardness, restored.hardness)
        service.closeIo()
    }

    @Test
    fun localSaveKeepsConfirmedBaselineAndOtherDocuments() {
        val service = EditorDataService(InMemoryEditorDataStore(), root.resolve("autosave"))
        val access = service.ores
        access.updateLocalDocument("same") { it.copy(baseline = "confirmed", baselineKnown = true) }
        access.saveLocalDocument("other", access.createDefault("other"), emptyMap())
        val saved = access.saveLocalDocument("same", access.createDefault("same"), emptyMap(), versions = observed(access, "same")) as StoreResult.Success
        assertEquals("confirmed", saved.value.baseline)
        assertTrue(saved.value.baselineKnown)
        assertEquals(2, (access.localDocuments() as StoreResult.Success).value.size)
        service.closeIo()
    }

    @Test
    fun serviceRecreationRestoresSavedRawAndTypedLayers() {
        val service = EditorDataService(InMemoryEditorDataStore(), root.resolve("autosave"))
        service.ores.saveLocalDocument("same", service.ores.createDefault("same"),
            mapOf("magicPowerAmount" to "-"))
        service.closeIo()
        val reopened = EditorDataService(InMemoryEditorDataStore(), root.resolve("autosave"))
        val restored = (reopened.ores.localDocuments() as StoreResult.Success).value.single()
        assertEquals("-", restored.savedRaw["magicPowerAmount"])
        assertEquals(restored.saved, restored.editing)
        assertFalse(restored.unsaved)
        reopened.closeIo()
    }
    @Test
    fun fetchedBaselineKeepsUnsentSavedCheckpointAndRawDraft() {
        val service = EditorDataService(InMemoryEditorDataStore(), root.resolve("autosave"))
        val access = service.ores
        val initial = access.createDefault("same")
        val base = access.encodeDocument(initial)
        access.updateLocalDocument("same") { it.copy(editing = base, original = base, saved = base, baseline = base, baselineKnown = true) }
        val local = initial.deepCopy().apply { hardness = 7.0 }
        access.saveLocalDocument("same", local, mapOf("magicPowerAmount" to "-"), versions = observed(access, "same"))
        val old = (service.documents.read() as StoreResult.Success).value
        val remote = initial.deepCopy().apply { hardness = 9.0 }
        val result = access.acceptFetched(old, mapOf("same" to remote), mapOf("same" to local), setOf("same")) as StoreResult.Success
        val record = result.value.single()
        assertEquals(access.encodeDocument(remote), record.baseline)
        assertEquals(access.encodeDocument(local), record.saved)
        assertEquals("-", record.savedRaw["magicPowerAmount"])
        assertFalse(record.unsaved)
        assertTrue(record.unreflected == true)
        service.closeIo()
    }

    @Test
    fun fetchedConfirmationCannotOverwriteChangedRevision() {
        val service = EditorDataService(InMemoryEditorDataStore(), root.resolve("autosave"))
        val access = service.ores
        val data = access.createDefault("same")
        access.saveLocalDocument("same", data, emptyMap())
        val old = (service.documents.read() as StoreResult.Success).value
        access.saveLocalDocument("same", data, mapOf("hardness" to "-"), versions = observed(access, "same"))
        assertTrue(access.acceptFetched(old, mapOf("same" to data), mapOf("same" to data), setOf("same")) is StoreResult.Failure)
        assertEquals("-", (access.localDocuments() as StoreResult.Success).value.single().savedRaw["hardness"])
        service.closeIo()
    }

    @Test
    fun cleanFetchedCopyAndConfirmedDeletionUpdateSavedTogether() {
        val service = EditorDataService(InMemoryEditorDataStore(), root.resolve("autosave"))
        val access = service.ores
        val data = access.createDefault("same")
        val first = access.acceptFetched(LocalDocumentSnapshot(service.documentSource), mapOf("same" to data), mapOf("same" to data), setOf("same")) as StoreResult.Success
        assertFalse(first.value.single().unsaved)
        assertTrue(first.value.single().unreflected == false)
        val deleted = access.acceptFetched((service.documents.read() as StoreResult.Success).value, emptyMap(), emptyMap(), setOf("same")) as StoreResult.Success
        assertNull(deleted.value.single().saved)
        assertTrue(deleted.value.single().baselineKnown)
        assertFalse(deleted.value.single().unsaved)
        assertEquals(false, deleted.value.single().unreflected)
        service.closeIo()
    }

    @Test
    fun explicitLegacyRestoreKeepsSavedBaselineAndOriginalBackupBytes() {
        val service = EditorDataService(InMemoryEditorDataStore(), root.resolve("autosave"))
        val access = service.ores
        val saved = access.createDefault("same").apply { hardness = 3.0 }
        access.saveLocalDocument("same", saved, mapOf("hardness" to "-"))
        access.updateLocalDocument("same") { it.copy(baseline = "confirmed", baselineKnown = true) }
        val category = root.resolve("autosave/renamed-profile").resolve(access.dataType.categoryDirName)
        val editing = category.resolve("editing/same.yml")
        val original = category.resolve("original/same.yml")
        editing.parentFile.mkdirs(); original.parentFile.mkdirs()
        access.descriptor.saveBackup(editing, saved.deepCopy().apply { hardness = 9.0 })
        access.descriptor.saveBackup(original, saved.deepCopy().apply { hardness = 1.0 })
        val originals = listOf(editing, original).associateWith { it.readBytes().toList() to it.lastModified() }
        assertEquals("renamed-profile", access.legacyBackupCandidates().single().name)
        repeat(2) {
            val record = (access.restoreLegacyBackup(access.legacyBackupCandidates().single().location) as StoreResult.Success).value.single()
            assertEquals(9.0, access.decodeDocument("same", requireNotNull(record.editing)).hardness)
            assertEquals(1.0, access.decodeDocument("same", requireNotNull(record.original)).hardness)
            assertEquals(3.0, access.decodeDocument("same", requireNotNull(record.saved)).hardness)
            assertEquals("confirmed", record.baseline)
            assertEquals("-", record.savedRaw["hardness"])
            assertTrue(record.unsaved)
        }
        originals.forEach { (file, expected) -> assertEquals(expected, file.readBytes().toList() to file.lastModified()) }
        service.closeIo()
    }

    @Test
    fun brokenLegacyPairDoesNotPublishOrReplaceCurrentGeneration() {
        val service = EditorDataService(InMemoryEditorDataStore(), root.resolve("autosave"))
        val access = service.ores
        access.saveLocalDocument("same", access.createDefault("same"), emptyMap())
        val before = (service.documents.read() as StoreResult.Success).value
        val editing = root.resolve("autosave/old").resolve(access.dataType.categoryDirName).resolve("editing/same.yml")
        editing.parentFile.mkdirs()
        access.descriptor.saveBackup(editing, access.createDefault("same"))
        assertTrue(access.restoreLegacyBackup(access.legacyBackupCandidates().single().location) is StoreResult.Failure)
        assertEquals(before, (service.documents.read() as StoreResult.Success).value)
        assertTrue(editing.isFile)
        service.closeIo()
    }

    @Test
    fun codecFailurePreservesThePublishedCheckpoint() {
        val service = EditorDataService(InMemoryEditorDataStore(), root.resolve("autosave"))
        val access = service.ores
        assertTrue(access.saveLocalDocument("same", access.createDefault("same"), emptyMap()) is StoreResult.Success)
        val before = (service.documents.read() as StoreResult.Success).value
        val codec = root.resolve("autosave/document-codec")
        assertTrue(codec.delete())
        codec.writeText("書込先を作成できないfixture")
        assertTrue(access.saveLocalDocument("same", access.createDefault("same"), mapOf("hardness" to "-"), versions = observed(access, "same")) is StoreResult.Failure)
        assertEquals(before, (service.documents.read() as StoreResult.Success).value)
        service.closeIo()
    }

    @Test
    fun legacyCandidatesKeepSameNamesFromExplicitRootsSeparate() {
        val first = root.resolve("managed/first/autosave")
        val second = root.resolve("managed/second/autosave")
        val outside = root.resolve("not-selected/autosave")
        val descriptor = io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors.ore
        listOf(first, second, outside).forEachIndexed { index, backup ->
            listOf("editing", "original").forEach { layer ->
                val file = backup.resolve("same-name").resolve(descriptor.dataType.categoryDirName).resolve(layer).resolve("one.yml")
                file.parentFile.mkdirs()
                descriptor.saveBackup(file, descriptor.createDefault("one").apply { hardness = index.toDouble() })
            }
        }
        val service = EditorDataService(InMemoryEditorDataStore(), root.resolve("current"),
            legacyAutoSaveRoots = listOf(first, second))
        val candidates = service.ores.legacyBackupCandidates()
        assertEquals(2, candidates.size)
        assertEquals(2, candidates.map { it.location }.toSet().size)
        assertEquals(setOf("same-name"), candidates.map { it.name }.toSet())
        val selected = candidates.single { it.location.startsWith(second.absolutePath) }
        val result = service.ores.restoreLegacyBackup(selected.location) as StoreResult.Success
        assertEquals(1.0, service.ores.decodeDocument("one", requireNotNull(result.value.single().editing)).hardness)
        val snapshot = (service.documents.read() as StoreResult.Success).value
        assertTrue(service.ores.restoreLegacyBackup(outside.resolve("same-name").absolutePath) is StoreResult.Failure)
        assertEquals(snapshot, (service.documents.read() as StoreResult.Success).value)
        service.closeIo()
    }

    @Test
    fun legacyCandidateLinksCannotReadOrRestoreOutsideTheSelectedRoot() {
        val autosave = root.resolve("autosave").apply { mkdirs() }
        val outside = root.resolve("outside").apply { mkdirs() }
        val descriptor = io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors.ore
        listOf("editing", "original").forEach { layer ->
            val file = outside.resolve(descriptor.dataType.categoryDirName).resolve(layer).resolve("one.yml")
            file.parentFile.mkdirs()
            descriptor.saveBackup(file, descriptor.createDefault("one"))
        }
        val link = autosave.resolve("external")
        val linked = runCatching { Files.createSymbolicLink(link.toPath(), outside.toPath()) }.isSuccess
        assumeTrue(linked, "この環境ではシンボリックリンクを作成できません。")
        val service = EditorDataService(InMemoryEditorDataStore(), autosave)
        val access = service.ores
        access.saveLocalDocument("kept", access.createDefault("kept"), emptyMap())
        val snapshot = (service.documents.read() as StoreResult.Success).value
        val originals = outside.walkTopDown().filter(File::isFile).associateWith { it.readBytes().toList() to it.lastModified() }
        assertTrue(access.legacyBackupCandidates().isEmpty())
        assertTrue(access.restoreLegacyBackup(link.absolutePath) is StoreResult.Failure)
        assertEquals(snapshot, (service.documents.read() as StoreResult.Success).value)
        val candidate = autosave.resolve("linked-category").apply { mkdirs() }
        Files.createSymbolicLink(candidate.resolve(descriptor.dataType.categoryDirName).toPath(),
            outside.resolve(descriptor.dataType.categoryDirName).toPath())
        assertTrue(access.legacyBackupCandidates().isEmpty())
        originals.forEach { (file, bytes) -> assertEquals(bytes, file.readBytes().toList() to file.lastModified()) }
        service.closeIo()
    }

    @Test
    fun staleSaveAndBackupCannotReplaceAnotherEditorsTypedOrRawCheckpoint() {
        val path = root.resolve("autosave")
        val first = EditorDataService(InMemoryEditorDataStore(), path)
        val second = EditorDataService(InMemoryEditorDataStore(), path)
        val access = first.ores
        val initial = access.createDefault("same")
        access.saveLocalDocument("same", initial, emptyMap())
        val a = observed(first.ores, "same")
        val b = observed(second.ores, "same")
        val latest = initial.deepCopy().apply { hardness = 9.0 }
        assertTrue(access.saveLocalDocument("same", latest, mapOf("hardness" to "-"), versions = a) is StoreResult.Success)
        val published = (first.documents.read() as StoreResult.Success).value
        assertTrue(second.ores.saveLocalDocument("same", initial, emptyMap(), versions = b) is StoreResult.Failure)
        val backup = LocalBackupSnapshot(1, mapOf("same" to (initial to initial)), emptySet(), emptyList(), versions = b)
        assertFalse(second.ores.persistBackup(backup).get(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(published, (first.documents.read() as StoreResult.Success).value)
        first.closeIo(); second.closeIo()
    }

    @Test
    fun oneEditorsQueuedBackupThenSaveAdvancesOnlyItsOwnObservedVersion() {
        val service = EditorDataService(InMemoryEditorDataStore(), root.resolve("autosave"))
        val access = service.ores
        val initial = access.createDefault("same")
        access.saveLocalDocument("same", initial, emptyMap())
        val versions = observed(access, "same")
        val edit = initial.deepCopy().apply { hardness = 9.0 }
        val backup = access.persistBackup(LocalBackupSnapshot(1, mapOf("same" to (edit to initial)), emptySet(), emptyList(),
            mapOf("same" to mapOf("hardness" to "-")), versions))
        val saved = service.submitIo { access.saveLocalDocument("same", edit, mapOf("hardness" to "-"), versions = versions) }
        assertTrue(backup.get(10, java.util.concurrent.TimeUnit.SECONDS))
        val record = (saved.get(10, java.util.concurrent.TimeUnit.SECONDS) as StoreResult.Success).value
        assertFalse(record.unsaved)
        assertEquals(record.revision, versions.getValue("same").revision)
        assertEquals("-", record.savedRaw["hardness"])
        service.closeIo()
    }

    @Test
    fun fetchedDirectoriesPreserveLocalAdditionsAndRemovalsAgainstTheirBaseline() {
        val memory = InMemoryEditorDataStore()
        val descriptor = io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors.ore
        memory.createDirectory(descriptor, "server_old")
        val remote = object : EditorDataStore by memory {
            override val kind = EditorDataStoreKind.REMOTE
            override val documentSource = EditorDocumentSource.Server("dirs", "declared/root")
        }
        val service = EditorDataService(remote, root.resolve("autosave"))
        service.ores.listStoreResources()
        service.store.createDirectory(descriptor, "local_only")
        service.store.deleteDirectory(descriptor, "server_old")
        val before = (service.documents.read() as StoreResult.Success).value
        assertTrue(service.ores.acceptFetched(before, emptyMap(), emptyMap(), emptySet(),
            listOf("server_old", "server_new")) is StoreResult.Success)
        val result = (service.documents.read() as StoreResult.Success).value
        assertEquals(setOf("local_only", "server_new"), result.directories[descriptor.dataType.categoryDirName])
        assertEquals(setOf("server_old", "server_new"), result.directoryBaselines[descriptor.dataType.categoryDirName])
        service.closeIo()
    }

    @Test
    fun fetchedDirectoriesCannotOverwriteAChangeAfterFetchStarted() {
        val path = root.resolve("autosave")
        val service = EditorDataService(InMemoryEditorDataStore(), path)
        val other = EditorDataService(InMemoryEditorDataStore(), path)
        val descriptor = io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors.ore
        service.ores.listStoreResources()
        val started = (service.documents.read() as StoreResult.Success).value
        other.store.createDirectory(descriptor, "newer")
        val latest = (other.documents.read() as StoreResult.Success).value
        assertTrue(service.ores.acceptFetched(started, emptyMap(), emptyMap(), emptySet(), emptyList()) is StoreResult.Failure)
        assertEquals(latest, (service.documents.read() as StoreResult.Success).value)
        service.closeIo(); other.closeIo()
    }

    @Test
    fun itemSaveAndRenameNotifyTheReadyLocalReferenceCache() {
        val service = EditorDataService(InMemoryEditorDataStore(), root.resolve("autosave"))
        val access = service.items
        val item = access.createDefault("old")
        access.saveLocalDocument("old", item, emptyMap())
        service.itemReferences.load().get(10, java.util.concurrent.TimeUnit.SECONDS)
        val edit = item.deepCopy().apply { display.displayName = "更新した名称" }
        assertTrue(access.saveLocalDocument("old", edit, emptyMap(), versions = observed(access, "old")) is StoreResult.Success)
        assertEquals("更新した名称", service.itemReferences.snapshot.items.single().display.displayName)
        val renamed = edit.deepCopy().apply { id = "new" }
        val versions = observed(access, "old") + ("new" to LocalDocumentVersion(access.documentKey("new"), 0))
        assertTrue(access.saveLocalDocument("new", renamed, emptyMap(),
            PendingStoreOperationRecord(PendingStoreOperationKind.RENAME, "new", "old"), versions = versions) is StoreResult.Success)
        assertEquals(listOf("new"), service.itemReferences.snapshot.items.map { it.id })
        assertEquals("更新した名称", service.itemReferences.snapshot.items.single().display.displayName)
        service.closeIo()
    }

    @Test
    fun fetchedSavedItemsRefreshReferencesButUnsentSavedItemsAreNotReplaced() {
        val service = EditorDataService(InMemoryEditorDataStore(), root.resolve("autosave"))
        val access = service.items
        val base = access.createDefault("same")
        access.acceptFetched(LocalDocumentSnapshot(service.documentSource), mapOf("same" to base), mapOf("same" to base), setOf("same"))
        service.itemReferences.load().get(10, java.util.concurrent.TimeUnit.SECONDS)
        val latest = base.deepCopy().apply { display.displayName = "取得した名称" }
        val previous = (service.documents.read() as StoreResult.Success).value
        assertTrue(access.acceptFetched(previous, mapOf("same" to latest), mapOf("same" to latest), setOf("same")) is StoreResult.Success)
        assertEquals("取得した名称", service.itemReferences.snapshot.items.single().display.displayName)
        val local = latest.deepCopy().apply { display.displayName = "未送信の名称" }
        access.saveLocalDocument("same", local, emptyMap(), versions = observed(access, "same"))
        val beforeFetch = (service.documents.read() as StoreResult.Success).value
        access.acceptFetched(beforeFetch, mapOf("same" to base), mapOf("same" to local), setOf("same"))
        assertEquals("未送信の名称", service.itemReferences.snapshot.items.single().display.displayName)
        service.closeIo()
    }

    @Test
    fun deletingAnUnsavedRenamePublishesTheOldAndNewTombstonesTogether() {
        val service = EditorDataService(InMemoryEditorDataStore(), root.resolve("autosave"))
        val access = service.ores
        val original = access.createDefault("old")
        access.saveLocalDocument("old", original, emptyMap())
        val renamed = original.deepCopy().apply { id = "renamed" }
        val versions = observed(access, "old") + ("renamed" to LocalDocumentVersion(access.documentKey("renamed"), 0))
        val pending = PendingStoreOperationRecord(PendingStoreOperationKind.RENAME, "renamed", "old")
        assertTrue(access.persistBackup(LocalBackupSnapshot(1, mapOf("renamed" to (renamed to renamed)), setOf("old"),
            listOf(pending), versions = versions)).get(10, java.util.concurrent.TimeUnit.SECONDS))
        val before = (service.documents.read() as StoreResult.Success).value
        assertTrue(access.saveLocalDocument("renamed", renamed, emptyMap(),
            PendingStoreOperationRecord(PendingStoreOperationKind.DELETE, "renamed", "old"), deleted = true,
            versions = versions) is StoreResult.Success)
        val published = (service.documents.read() as StoreResult.Success).value
        assertEquals(before.generation + 1, published.generation)
        assertTrue(published.documents.all { it.savedDeleted })
        assertTrue((access.listStoreResources() as StoreResult.Success).value.isEmpty())
        service.closeIo()
    }

    @Test
    fun nonOverlappingSessionsBackUpAndCloseWithoutChangingTheOtherDocument() {
        val path = root.resolve("shared")
        val a = EditorDataService(InMemoryEditorDataStore(), path)
        val b = EditorDataService(InMemoryEditorDataStore(), path)
        val first = a.ores.createDefault("first")
        val second = a.ores.createDefault("second")
        a.ores.saveLocalDocument("first", first, emptyMap())
        a.ores.saveLocalDocument("second", second, emptyMap())
        val av = observed(a.ores, "first") + observed(a.ores, "second")
        val bv = observed(b.ores, "first") + observed(b.ores, "second")
        val left = LocalBackupSnapshot(1, mapOf("first" to (first.deepCopy().apply { hardness = 11.0 } to first)),
            setOf("second"), emptyList(), versions = av)
        val right = LocalBackupSnapshot(1, mapOf("second" to (second.deepCopy().apply { hardness = 22.0 } to second)),
            setOf("first"), emptyList(), versions = bv)
        try {
            assertTrue(a.ores.persistBackup(left).get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(b.ores.persistBackup(right).get(10, java.util.concurrent.TimeUnit.SECONDS))
            val before = (a.documents.read() as StoreResult.Success).value
            assertTrue(a.ores.persistBackup(left.copy(generation = 2)).get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(b.ores.persistBackup(right.copy(generation = 2)).get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(before, (a.documents.read() as StoreResult.Success).value)
            assertEquals(11.0, requireNotNull(a.ores.loadBackupPair("first")).first.hardness)
            assertEquals(22.0, requireNotNull(b.ores.loadBackupPair("second")).first.hardness)
            assertFalse(av.getValue("second").backupOwned)
            assertFalse(bv.getValue("first").backupOwned)
        } finally { a.closeIo(); b.closeIo() }
    }

    @Test
    fun queuedUndoClearsOnlyTheSuccessfullyPublishedPrecedingBackup() {
        val service = EditorDataService(InMemoryEditorDataStore(), root.resolve("queue"))
        val access = service.ores
        val base = access.createDefault("same")
        access.saveLocalDocument("same", base, emptyMap())
        val versions = observed(access, "same")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        service.submitIo { entered.countDown(); assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
        assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        val backup = access.persistBackup(LocalBackupSnapshot(1,
            mapOf("same" to (base.deepCopy().apply { hardness = 12.0 } to base)),
            emptySet(), emptyList(), versions = versions))
        val undo = access.persistBackup(LocalBackupSnapshot(2, emptyMap(), setOf("same"), emptyList(), versions = versions))
        try {
            assertFalse(versions.getValue("same").backupOwned)
            release.countDown()
            assertTrue(backup.get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(undo.get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(versions.getValue("same").backupOwned)
            assertNull(access.loadBackupPair("same"))
            assertFalse((access.localDocuments() as StoreResult.Success).value.single().unsaved)
        } finally { release.countDown(); service.closeIo() }
    }

    @Test
    fun failedBackupDoesNotAcquireOwnershipOfAnotherWritersDraft() {
        val path = root.resolve("failed-owner")
        val a = EditorDataService(InMemoryEditorDataStore(), path)
        val b = EditorDataService(InMemoryEditorDataStore(), path)
        val base = a.ores.createDefault("same")
        a.ores.saveLocalDocument("same", base, emptyMap())
        val old = observed(a.ores, "same")
        val newer = observed(b.ores, "same")
        try {
            assertTrue(b.ores.persistBackup(LocalBackupSnapshot(1,
                mapOf("same" to (base.deepCopy().apply { hardness = 20.0 } to base)),
                emptySet(), emptyList(), versions = newer)).get(10, java.util.concurrent.TimeUnit.SECONDS))
            val before = (b.documents.read() as StoreResult.Success).value
            assertFalse(a.ores.persistBackup(LocalBackupSnapshot(1,
                mapOf("same" to (base.deepCopy().apply { hardness = 30.0 } to base)),
                emptySet(), emptyList(), versions = old)).get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(old.getValue("same").backupOwned)
            assertTrue(a.ores.persistBackup(LocalBackupSnapshot(2, emptyMap(), setOf("same"), emptyList(),
                versions = old)).get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(before, (b.documents.read() as StoreResult.Success).value)
        } finally { a.closeIo(); b.closeIo() }
    }

    @Test
    fun aNoOpBackupStillRejectsAnOlderObservedRevision() {
        val path = root.resolve("noop")
        val a = EditorDataService(InMemoryEditorDataStore(), path)
        val b = EditorDataService(InMemoryEditorDataStore(), path)
        val base = a.ores.createDefault("same")
        a.ores.saveLocalDocument("same", base, emptyMap())
        val old = observed(a.ores, "same")
        try {
            assertTrue(b.ores.saveLocalDocument("same", base, emptyMap(), versions = observed(b.ores, "same")) is StoreResult.Success)
            val before = (b.documents.read() as StoreResult.Success).value
            assertFalse(a.ores.persistBackup(LocalBackupSnapshot(1, mapOf("same" to (base to base)),
                emptySet(), emptyList(), versions = old)).get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(before, (b.documents.read() as StoreResult.Success).value)
            assertFalse(old.getValue("same").backupOwned)
        } finally { a.closeIo(); b.closeIo() }
    }

    @Test
    fun backupOwnershipIsSeparateForTheSameIdInLocalAndServerSources() {
        val store = object : EditorDataStore by InMemoryEditorDataStore() {
            override val kind = EditorDataStoreKind.REMOTE
            override val documentSource = EditorDocumentSource.Server("backup-owner", "declared/root")
        }
        val server = EditorDataService(store, root.resolve("sources"))
        val local = server.localService
        val base = server.ores.createDefault("same")
        server.ores.saveLocalDocument("same", base, emptyMap())
        local.ores.saveLocalDocument("same", base, emptyMap())
        val localVersion = observed(local.ores, "same")
        val serverVersion = observed(server.ores, "same")
        try {
            assertTrue(local.ores.persistBackup(LocalBackupSnapshot(1,
                mapOf("same" to (base.deepCopy().apply { hardness = 15.0 } to base)),
                emptySet(), emptyList(), versions = localVersion)).get(10, java.util.concurrent.TimeUnit.SECONDS))
            val before = (local.documents.read() as StoreResult.Success).value
            assertTrue(server.ores.persistBackup(LocalBackupSnapshot(1, emptyMap(), setOf("same"), emptyList(),
                versions = serverVersion)).get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(before, (local.documents.read() as StoreResult.Success).value)
            assertTrue(localVersion.getValue("same").backupOwned)
            assertFalse(serverVersion.getValue("same").backupOwned)
            assertTrue(local.ores.persistBackup(LocalBackupSnapshot(2, emptyMap(), setOf("same"), emptyList(),
                versions = localVersion)).get(10, java.util.concurrent.TimeUnit.SECONDS))
            assertNull(local.ores.loadBackupPair("same"))
        } finally { server.closeIo() }
    }

}
