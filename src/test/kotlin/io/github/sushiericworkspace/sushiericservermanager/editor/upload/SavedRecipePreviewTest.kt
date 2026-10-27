package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeDataView
import io.github.sushiericworkspace.sushiericservermanager.editor.store.LocalEditorDataStore
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SavedRecipePreviewTest {
    @TempDir lateinit var root: File

    @Test
    fun `保存済みが比較元と同じなら反映予定は最新サーバーの値になる`() {
        val service = EditorDataService(LocalEditorDataStore(root.resolve("files")), root.resolve("documents"))
        try {
            val access = service.recipes
            val base = access.createDefault("recipe")
            val remote = base.deepCopy().apply { resultCount = 3 }
            val merge = SavedUploadMerges.typed(access, base.id, access.encodeDocument(base), access.encodeDocument(base), access.encodeDocument(remote), true)
            assertTrue(merge.conflicts.isEmpty())
            assertEquals(3, (merge.previewWithLocal!!.invoke(emptySet()) as RecipeDataView).resultCount)
            assertEquals(3, access.decodeDocument(base.id, merge.resolveWithLocal(emptySet())!!).resultCount)
            assertEquals(1, base.resultCount)
        } finally { service.closeIo() }
    }

    @Test
    fun `競合選択の読取投影は正式反映内容と一致し確認取消でも元を変更しない`() {
        val service = EditorDataService(LocalEditorDataStore(root.resolve("files")), root.resolve("documents"))
        try {
            val access = service.recipes
            val base = access.createDefault("recipe")
            val local = base.deepCopy().apply { resultCount = 2 }
            val remote = base.deepCopy().apply { resultCount = 3 }
            val merge = SavedUploadMerges.typed(access, base.id, access.encodeDocument(base), access.encodeDocument(local), access.encodeDocument(remote), true)
            val path = merge.conflicts.single().path
            for (paths in listOf(emptySet(), setOf(path))) {
                assertEquals(access.decodeDocument(base.id, merge.resolveWithLocal(paths)!!).resultCount,
                    (merge.previewWithLocal!!.invoke(paths) as RecipeDataView).resultCount)
            }
            assertEquals(2, (merge.previewWithLocal!!.invoke(setOf(path)) as RecipeDataView).resultCount)
            assertEquals(3, (merge.previewWithLocal.invoke(emptySet()) as RecipeDataView).resultCount)
            assertEquals(1, base.resultCount)
            assertEquals(2, local.resultCount)
            assertEquals(3, remote.resultCount)
            assertTrue(root.resolve("files").walkTopDown().none { it.isFile })
        } finally { service.closeIo() }
    }
}
