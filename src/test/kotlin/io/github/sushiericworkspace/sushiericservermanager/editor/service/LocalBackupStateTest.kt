package io.github.sushiericworkspace.sushiericservermanager.editor.service

import kotlin.test.*

class LocalBackupStateTest {
    @Test
    fun `同じ編集世代はペアと保留操作を重複して書かない`() {
        val calls = mutableListOf<String>()
        val state = LocalBackupState<String>(
            { id, kind, _ -> calls += "$id:$kind"; true },
            { id -> calls += "delete:$id"; true },
            { calls += "pending"; true }
        )
        val snapshot = LocalBackupSnapshot(1, mapOf("a" to ("edit" to "base")), emptySet(), emptyList())
        assertTrue(state.persist(snapshot))
        assertTrue(state.persist(snapshot.copy(generation = 2)))
        assertEquals(listOf("a:editing", "a:original", "pending"), calls)
    }

    @Test
    fun `片側失敗後は同じ内容でも両側を再試行する`() {
        var originalFails = true
        val writes = mutableListOf<String>()
        val state = LocalBackupState<String>(
            { _, kind, value -> writes += "$kind:$value"; kind != "original" || !originalFails },
            { true }, { true }
        )
        val old = LocalBackupSnapshot(1, mapOf("a" to ("old" to "base")), emptySet(), emptyList())
        originalFails = false
        assertTrue(state.persist(old))
        originalFails = true
        assertFalse(state.persist(old.copy(generation = 2, entries = mapOf("a" to ("new" to "base")))))
        originalFails = false
        assertTrue(state.persist(old.copy(generation = 3)))
        assertEquals(6, writes.size)
        assertEquals(listOf("editing:old", "original:base"), writes.takeLast(2))
    }

    @Test
    fun `保留操作の記録失敗では削除を行わず次回に再試行する`() {
        var pendingFails = false
        val deleted = mutableListOf<String>()
        val state = LocalBackupState<String>({ _, _, _ -> true }, { deleted += it; true }, { !pendingFails })
        val pending = listOf(PendingStoreOperationRecord(PendingStoreOperationKind.CREATE, "a"))
        assertTrue(state.persist(LocalBackupSnapshot(1, mapOf("a" to ("edit" to "base")), emptySet(), pending)))
        val removed = LocalBackupSnapshot<String>(2, emptyMap(), setOf("a"), emptyList())
        pendingFails = true
        assertFalse(state.persist(removed))
        assertTrue(deleted.isEmpty())
        pendingFails = false
        assertTrue(state.persist(removed))
        assertEquals(listOf("a"), deleted)
    }

    @Test
    fun `削除失敗は成功扱いにせず再試行後だけ削除済みを記録する`() {
        var attempts = 0
        val state = LocalBackupState<String>({ _, _, _ -> true }, { ++attempts > 1 }, { true })
        val removed = LocalBackupSnapshot<String>(1, emptyMap(), setOf("a"), emptyList())
        assertFalse(state.persist(removed))
        assertTrue(state.persist(removed.copy(generation = 2)))
        assertTrue(state.persist(removed.copy(generation = 3)))
        assertEquals(2, attempts)
    }
}
