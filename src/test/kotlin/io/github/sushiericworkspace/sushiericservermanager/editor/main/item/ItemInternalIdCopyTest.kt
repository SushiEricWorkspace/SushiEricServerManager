package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ItemInternalIdCopyTest {
    @Test
    fun `キャッシュ済みItemの内部IDを読み込みなしで解決する`() {
        val cached = MutableItemBaseData(id = "cached")
        var loadCalled = false

        val internalId = resolveItemInternalId(cached) {
            loadCalled = true
            null
        }

        assertEquals(cached.internalId.value, internalId)
        assertEquals(false, loadCalled)
    }

    @Test
    fun `未選択Itemの内部IDを読み込み結果から解決する`() {
        val loaded = MutableItemBaseData(id = "loaded")

        assertEquals(loaded.internalId.value, resolveItemInternalId(null) { loaded })
    }

    @Test
    fun `Itemを読み込めない場合は内部IDを解決しない`() {
        assertNull(resolveItemInternalId(null) { null })
    }

    @Test
    fun `公開IDの変更では内部IDを変更しない`() {
        val item = MutableItemBaseData(id = "before")
        val internalId = item.internalId

        item.id = "after"

        assertEquals(internalId, item.internalId)
    }

    @Test
    fun `複製Itemは新しい内部IDを持つ`() {
        val source = MutableItemBaseData(id = "source")

        val duplicate = source.duplicateAsNew().apply { id = "duplicate" }

        assertNotEquals(source.internalId, duplicate.internalId)
    }
}
