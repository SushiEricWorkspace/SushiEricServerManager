package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SavedUploadMergesTest {
    @TempDir lateinit var root: File

    @Test
    fun previousAcknowledgedSavedKeepsRemoteOnlyChangesAfterNextLocalEdit() {
        val service = EditorDataService(InMemoryEditorDataStore(), root)
        try {
            val access = service.ores
            val acknowledged = access.createDefault("same").apply { hardness = 2.0; requiredTier = 1 }
            val local = acknowledged.deepCopy().apply { hardness = 3.0 }
            val latest = acknowledged.deepCopy().apply { requiredTier = 8 }
            val merge = SavedUploadMerges.typed(access, "same", access.encodeDocument(acknowledged),
                access.encodeDocument(local), access.encodeDocument(latest), true)
            assertTrue(merge.conflicts.isEmpty())
            val result = access.decodeDocument("same", requireNotNull(merge.resolveWithLocal(emptySet())))
            assertEquals(3.0, result.hardness)
            assertEquals(8, result.requiredTier)
        } finally { service.closeIo() }
    }

    @Test
    fun typedConflictNeedsExplicitLocalOrRemoteChoice() {
        val service = EditorDataService(InMemoryEditorDataStore(), root)
        try {
            val access = service.ores
            val base = access.createDefault("same").apply { hardness = 2.0 }
            val merge = SavedUploadMerges.typed(access, "same", access.encodeDocument(base),
                access.encodeDocument(base.deepCopy().apply { hardness = 3.0 }),
                access.encodeDocument(base.deepCopy().apply { hardness = 7.0 }), true)
            assertEquals(1, merge.conflicts.size)
            val chosen = merge.conflicts.mapTo(mutableSetOf()) { it.path }
            assertEquals(3.0, access.decodeDocument("same", requireNotNull(merge.resolveWithLocal(chosen))).hardness)
            assertEquals(7.0, access.decodeDocument("same", requireNotNull(merge.resolveWithLocal(emptySet()))).hardness)
        } finally { service.closeIo() }
    }

    @Test
    fun unknownBaselineAndConcurrentDeleteDoNotGuessTheWinner() {
        val unknown = SavedUploadMerges.text(null, "saved", "server", false)
        assertEquals(1, unknown.conflicts.size)
        assertEquals("saved", unknown.resolveWithLocal(unknown.conflicts.mapTo(mutableSetOf()) { it.path }))
        assertEquals("server", unknown.resolveWithLocal(emptySet()))
        val deletion = SavedUploadMerges.text("before", null, "changed", true)
        assertEquals(1, deletion.conflicts.size)
        assertNull(deletion.resolveWithLocal(deletion.conflicts.mapTo(mutableSetOf()) { it.path }))
        assertEquals("changed", deletion.resolveWithLocal(emptySet()))
    }

    @Test
    fun invalidModTextIsKeptVerbatimAndBothSideChangesRequireSelection() {
        val local = "value: [\n"
        val remote = "value: 2\n"
        val merge = SavedUploadMerges.text("value: 1\n", local, remote, true)
        assertEquals(1, merge.conflicts.size)
        assertEquals(local, merge.resolveWithLocal(merge.conflicts.mapTo(mutableSetOf()) { it.path }))
        assertEquals(remote, merge.resolveWithLocal(emptySet()))
    }
}
