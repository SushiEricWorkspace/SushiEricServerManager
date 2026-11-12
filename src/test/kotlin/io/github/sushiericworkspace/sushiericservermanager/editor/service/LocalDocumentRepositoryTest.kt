package io.github.sushiericworkspace.sushiericservermanager.editor.service

import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

class LocalDocumentRepositoryTest {
    @TempDir lateinit var root: File
    private val local = EditorDocumentSource.Local
    private fun key(id: String = "same") = EditorDocumentKey(local, "item", id)
    private fun read(repository: LocalDocumentRepository) =
        (repository.read() as StoreResult.Success).value

    @Test
    fun savedRawInputDoesNotBecomeTypedDataOrServerBaseline() {
        val repository = LocalDocumentRepository(root, local)
        val typed = "hardness: 1.0"
        val draft = LocalDocumentRecord(key(), editing = typed, saved = typed,
            raw = mapOf("hardness" to "-"), savedRaw = mapOf("hardness" to "-"))
        assertFalse(draft.unsaved)
        assertNull(draft.unreflected)
        assertTrue(repository.publish(mapOf(key() to 0L), listOf(draft)) is StoreResult.Success)
        val restored = read(LocalDocumentRepository(root, local)).documents.single()
        assertEquals("-", restored.savedRaw["hardness"])
        assertEquals(typed, restored.saved)
        assertFalse(restored.baselineKnown)
    }

    @Test
    fun staleDocumentCannotReplaceSavedGeneration() {
        val repository = LocalDocumentRepository(root, local)
        val first = LocalDocumentRecord(key(), editing = "first")
        assertTrue(repository.publish(mapOf(key() to 0L), listOf(first)) is StoreResult.Success)
        val expected = read(repository)
        assertTrue(repository.publish(mapOf(key() to 0L), listOf(first.copy(editing = "stale"))) is StoreResult.Failure)
        assertEquals(expected, read(repository))
    }

    @Test
    fun updatingAnotherDocumentPreservesCurrentSavedAndBaseline() {
        val repository = LocalDocumentRepository(root, local)
        val first = LocalDocumentRecord(key(), editing = "draft", saved = "local", baseline = "server", baselineKnown = true)
        repository.publish(mapOf(first.key to 0L), listOf(first))
        val another = LocalDocumentRecord(key("other"), editing = "other")
        assertTrue(repository.publish(mapOf(another.key to 0L), listOf(another)) is StoreResult.Success)
        val restored = read(repository).documents.associateBy { it.key }
        assertEquals("local", restored.getValue(first.key).saved)
        assertEquals("server", restored.getValue(first.key).baseline)
        assertEquals(2, restored.size)
    }

    @Test
    fun sourceAndCategoryKeepSamePublicIdSeparate() {
        val a = EditorDocumentSource.Server("profile", "host/root-a")
        val b = EditorDocumentSource.Server("profile", "host/root-b")
        val repoA = LocalDocumentRepository(root, a)
        val repoB = LocalDocumentRepository(root, b)
        val item = LocalDocumentRecord(EditorDocumentKey(a, "item", "same"), editing = "item")
        val ore = LocalDocumentRecord(EditorDocumentKey(a, "ore", "same"), editing = "ore")
        repoA.publish(mapOf(item.key to 0L, ore.key to 0L), listOf(item, ore))
        assertEquals(2, read(repoA).documents.size)
        assertTrue(read(repoB).documents.isEmpty())
        assertTrue(repoA.publish(mapOf(EditorDocumentKey(b, "item", "same") to 0L),
            listOf(item.copy(key = EditorDocumentKey(b, "item", "same")))) is StoreResult.Failure)
    }

    @Test
    fun publisherLockFailureKeepsCurrentAndOlderGenerations() {
        val repository = LocalDocumentRepository(root, local)
        repository.publish(mapOf(key() to 0L), listOf(LocalDocumentRecord(key(), editing = "old")))
        val folder = root.resolve(local.directory)
        val pointer = folder.resolve("current").readBytes()
        val previous = read(repository)
        FileChannel.open(folder.resolve("publisher.lock").toPath(), StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                assertTrue(repository.publish(mapOf(key() to 1L),
                    listOf(LocalDocumentRecord(key(), editing = "new"))) is StoreResult.Failure)
            }
        }
        assertArrayEquals(pointer, folder.resolve("current").readBytes())
        assertEquals(previous, read(repository))
    }

    @Test
    fun corruptCurrentIsPreservedInsteadOfLoadingAnOlderGeneration() {
        val repository = LocalDocumentRepository(root, local)
        repository.publish(mapOf(key() to 0L), listOf(LocalDocumentRecord(key(), editing = "old")))
        val pointer = root.resolve(local.directory).resolve("current")
        pointer.writeText("../another.json")
        assertTrue(repository.read() is StoreResult.Failure)
        assertEquals("../another.json", pointer.readText())
    }

    @Test
    fun staleDirectoryRevisionCannotEraseAnotherDirectory() {
        val repository = LocalDocumentRepository(root, local)
        assertTrue(repository.publish(emptyMap(), emptyList(), mapOf("item" to setOf("one")),
            mapOf("item" to 0L)) is StoreResult.Success)
        assertTrue(repository.publish(emptyMap(), emptyList(), mapOf("item" to setOf("stale")),
            mapOf("item" to 0L)) is StoreResult.Failure)
        assertEquals(setOf("one"), read(repository).directories["item"])
    }

    @Test
    fun confirmedMissingBaselineIsDifferentFromAnUnknownBaseline() {
        val unknown = LocalDocumentRecord(key(), saved = "new")
        assertNull(unknown.unreflected)
        assertEquals(true, unknown.copy(baselineKnown = true).unreflected)
        assertEquals(false, unknown.copy(baselineKnown = true, baseline = "new").unreflected)
    }
    @Test
    fun repositoriesWithTheSameCanonicalRootSerializeConcurrentPublishers() {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(6)
        val ready = java.util.concurrent.CountDownLatch(6)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val futures = (0 until 6).map { number ->
                pool.submit<StoreResult<LocalDocumentSnapshot>> {
                    val repository = LocalDocumentRepository(if (number % 2 == 0) root else root.resolve("."), local)
                    val key = key("item$number")
                    ready.countDown()
                    check(start.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    repository.publish(mapOf(key to 0L), listOf(LocalDocumentRecord(key, saved = "value$number")))
                }
            }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS))
            start.countDown()
            futures.forEach { assertTrue(it.get(10, java.util.concurrent.TimeUnit.SECONDS) is StoreResult.Success) }
            val snapshot = read(LocalDocumentRepository(root, local))
            assertEquals(6, snapshot.documents.size)
            assertEquals(6L, snapshot.generation)
        } finally { start.countDown(); pool.shutdownNow() }
    }

}
