package io.github.sushiericworkspace.sushiericservermanager.ui.dialog

import io.github.sushiericworkspace.sushiericservermanager.editor.merge.DataConflict
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.DataFieldPath
import kotlin.test.Test
import kotlin.test.assertEquals

class MergeConflictSelectionModelTest {
    @Test
    fun `初期状態ではすべてのローカル値を採用する`() {
        val conflicts = listOf(conflict("name"), conflict("material"))

        val model = MergeConflictSelectionModel(conflicts)

        assertEquals(conflicts.mapTo(mutableSetOf()) { it.path }, model.selectedLocalPaths())
    }

    @Test
    fun `サーバー値を選択したフィールドだけローカル適用対象から外す`() {
        val local = conflict("name")
        val remote = conflict("material")
        val model = MergeConflictSelectionModel(listOf(local, remote))

        model.select(remote.path, MergeConflictChoice.REMOTE)

        assertEquals(setOf(local.path), model.selectedLocalPaths())
    }

    @Test
    fun `一括選択ですべての採用先を切り替えられる`() {
        val conflicts = (1..20).map { conflict("field-$it") }
        val model = MergeConflictSelectionModel(conflicts)

        model.selectAll(MergeConflictChoice.REMOTE)
        assertEquals(emptySet(), model.selectedLocalPaths())

        model.selectAll(MergeConflictChoice.LOCAL)
        assertEquals(conflicts.mapTo(mutableSetOf()) { it.path }, model.selectedLocalPaths())
    }

    private fun conflict(name: String) = DataConflict(
        path = DataFieldPath.property(name, name),
        displayName = name,
        baseValue = null,
        localValue = "L".repeat(600),
        remoteValue = "server"
    )
}
