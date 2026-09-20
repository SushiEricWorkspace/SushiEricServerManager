package io.github.sushiericworkspace.sushiericservermanager.editor.component

import io.github.sushiericworkspace.common.data.drop.model.mutable.MutableDropItemData
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DropItemEditorDialogTest {

    @Test
    fun `人間用IDの選択結果を内部IDへ変換する`() {
        val item = item("iron_sword", "internal-sword")
        val catalog = DropItemCatalog(listOf(item))
        val dropItem = MutableDropItemData()

        assertTrue(selectDropItemByPublicId(dropItem, "iron_sword", catalog))
        assertEquals(item.internalId, dropItem.itemId)
        assertFalse(selectDropItemByPublicId(dropItem, "unknown", catalog))
        assertEquals(item.internalId, dropItem.itemId)
    }

    @Test
    fun `同じ内部IDなら人間用ID変更後の表示へ解決する`() {
        val internalId = ItemInternalId("stable-internal-id")
        val oldCatalog = DropItemCatalog(listOf(item("old_name", internalId.value)))
        val newCatalog = DropItemCatalog(listOf(item("new_name", internalId.value)))

        assertEquals("old_name", oldCatalog.resolve(internalId)?.publicId)
        assertEquals("new_name", newCatalog.resolve(internalId)?.publicId)
    }

    @Test
    fun `解決できない内部IDは保持してCommonの警告を返す`() {
        val unresolvedId = ItemInternalId("missing-internal-id")
        val dropItem = MutableDropItemData(itemId = unresolvedId)
        val catalog = DropItemCatalog(emptyList())

        assertNull(catalog.resolve(unresolvedId))
        assertEquals(unresolvedId, dropItem.itemId)
        val errors = dropItemValidationErrors(dropItem, catalog)
        assertEquals(1, errors.size)
        assertTrue(errors.single().isWarning)
        assertTrue(errors.single().message.contains(unresolvedId.value))
    }

    @Test
    fun `期待値を百分率で表示する`() {
        val dropItem = MutableDropItemData(n = 3, p = 0.125)

        assertEquals("期待値: 37.5%", formatDropItemExpectedValue(dropItem))

        dropItem.n = 2
        dropItem.p = 0.75
        assertEquals("期待値: 150%", formatDropItemExpectedValue(dropItem))
    }

    @Test
    fun `ドロップアイテムを指定位置へ移動する`() {
        val first = MutableDropItemData(n = 1)
        val second = MutableDropItemData(n = 2)
        val third = MutableDropItemData(n = 3)
        val items = mutableListOf(first, second, third)

        assertTrue(moveDropItem(items, 2, 0))
        assertEquals(listOf(third, first, second), items)
        assertFalse(moveDropItem(items, 0, -1))
        assertEquals(listOf(third, first, second), items)
    }

    private fun item(publicId: String, internalId: String): MutableItemBaseData =
        MutableItemBaseData(
            id = publicId,
            internalId = ItemInternalId(internalId)
        )
}
