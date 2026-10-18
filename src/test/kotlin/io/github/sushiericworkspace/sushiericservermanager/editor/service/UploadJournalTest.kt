package io.github.sushiericworkspace.sushiericservermanager.editor.service

import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class UploadJournalTest {
    @TempDir lateinit var root: File
    private val source = EditorDocumentSource.Server("profile", "host/root")
    private val key = EditorDocumentKey(source, "item", "same")

    private fun operation() = SavedUploadOperation("op", source,
        listOf(SavedUploadDocument(key, 1, "saved", false, emptyMap())),
        listOf(SavedUploadTargetVersion(key, 1)),
        listOf(SavedUploadStep(key, "old", "saved")))

    @Test
    fun writingJournalSurvivesRestartWithoutAutomaticReplay() {
        val repository = LocalDocumentRepository(root, source)
        val op = operation().let { it.copy(steps = it.steps.map { step -> step.copy(state = SavedUploadState.WRITING) }) }
        assertTrue(repository.publish(emptyMap(), emptyList(), upload = op) is StoreResult.Success)
        val restored = (LocalDocumentRepository(root, source).read() as StoreResult.Success).value.uploads.single()
        assertTrue(restored.needsReadback)
        assertFalse(restored.complete)
        assertEquals(1, restored.revision)
        assertEquals(op.inputs, restored.inputs)
        assertEquals(op.targetRevisions, restored.targetRevisions)
    }

    @Test
    fun journalCasRejectsStaleResultAndDifferentTarget() {
        val repository = LocalDocumentRepository(root, source)
        val op = operation()
        assertTrue(repository.publish(emptyMap(), emptyList(), upload = op) is StoreResult.Success)
        assertTrue(repository.publish(emptyMap(), emptyList(), upload = op) is StoreResult.Failure)
        assertTrue(repository.publish(emptyMap(), emptyList(),
            upload = op.copy(target = EditorDocumentSource.Server("other", "host/root"))) is StoreResult.Failure)
        assertEquals(1, (repository.read() as StoreResult.Success).value.uploads.single().revision)
    }

    @Test
    fun reservationStopsOnlyTargetDocumentsAcrossRepositories() {
        val first = LocalDocumentRepository(root, source)
        val second = LocalDocumentRepository(root, source)
        assertTrue(first.reserve("op", mapOf(key to 0L)) is StoreResult.Success)
        try {
            assertTrue(second.publish(mapOf(key to 0L), listOf(LocalDocumentRecord(key, saved = "wrong"))) is StoreResult.Failure)
            val other = key.copy(id = "other")
            assertTrue(second.publish(mapOf(other to 0L), listOf(LocalDocumentRecord(other, saved = "other"))) is StoreResult.Success)
            assertTrue(second.publish(mapOf(key to 0L), listOf(LocalDocumentRecord(key, baseline = "confirmed")),
                uploadOwner = "op") is StoreResult.Success)
        } finally { first.release("op") }
        assertTrue(second.publish(mapOf(key to 1L), listOf(LocalDocumentRecord(key, saved = "next"))) is StoreResult.Success)
    }

    @Test
    fun staleReservationDoesNotFreezeAnyPartOfTheRequest() {
        val repository = LocalDocumentRepository(root, source)
        val record = LocalDocumentRecord(key, saved = "new")
        repository.publish(mapOf(key to 0L), listOf(record))
        val other = key.copy(id = "other")
        assertTrue(repository.reserve("op", linkedMapOf(other to 0L, key to 0L)) is StoreResult.Failure)
        assertTrue(repository.publish(mapOf(other to 0L), listOf(LocalDocumentRecord(other, saved = "free"))) is StoreResult.Success)
    }

    @Test
    fun releasingAnotherOwnerCannotUnlockTheOperation() {
        val repository = LocalDocumentRepository(root, source)
        repository.reserve("owner", mapOf(key to 0L))
        try {
            repository.release("other")
            assertTrue(repository.publish(mapOf(key to 0L), listOf(LocalDocumentRecord(key))) is StoreResult.Failure)
        } finally { repository.release("owner") }
    }
    private fun assertBrokenJournalPreserved(name: String, uploads: List<SavedUploadOperation>) {
        val repositoryRoot = root.resolve(name)
        val repository = LocalDocumentRepository(repositoryRoot, source)
        repository.publish(emptyMap(), emptyList())
        val directory = repositoryRoot.resolve(source.directory)
        val pointer = directory.resolve("current")
        val beforePointer = pointer.readBytes()
        val generation = directory.resolve(pointer.readText())
        val broken = Json.encodeToString(LocalDocumentSnapshot(source, uploads = uploads))
        generation.writeText(broken)
        val beforeGeneration = generation.readBytes()
        assertTrue(repository.read() is StoreResult.Failure)
        assertTrue(repository.publish(mapOf(key to 0L), listOf(LocalDocumentRecord(key))) is StoreResult.Failure)
        assertArrayEquals(beforePointer, pointer.readBytes())
        assertArrayEquals(beforeGeneration, generation.readBytes())
    }

    @Test
    fun duplicateJournalIdsAreRejectedWithoutDroppingEitherOriginal() {
        val operation = operation()
        assertBrokenJournalPreserved("duplicate", listOf(operation, operation.copy(revision = 2)))
    }

    @Test
    fun wrongTargetOrUncoveredStepJournalIsRejectedWithoutPublishing() {
        val operation = operation()
        assertBrokenJournalPreserved("wrong-target",
            listOf(operation.copy(target = EditorDocumentSource.Server("another", "different"))))
        assertBrokenJournalPreserved("uncovered-step", listOf(operation.copy(targetRevisions = emptyList())))
    }

}
