package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.shop.ShopManager
import java.io.File
import io.github.sushiericworkspace.common.data.shop.model.mutable.MutableShopProductData
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.ShopDataMerger
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ShopEditorDataTest {
    private val item = ItemInternalId("missing-item")
    private fun product() = MutableShopProductData("weapons.swords.test", item, 100, 50, 12, true, 3)

    @Test
    fun `Commonの価格警告を表示しても正常な商品の保存と完成を妨げない`() {
        val data = product().apply { salePrice = 101 }
        val commonWarnings = data.validator(setOf(item)).priceWarnings()
        assertTrue(commonWarnings.isNotEmpty())
        assertEquals(commonWarnings, validateShopDataForEditor(data, setOf(item)))
        assertTrue(commonWarnings.all { it.isWarning })
        data.refreshCompleted(data.validate(setOf(item)))
        assertTrue(data.completed)
        val directory = createTempDirectory("shop-price-warning").toFile()
        val file = File(directory, "weapons/swords/test.yml")
        ShopManager.saveMutableProduct(file, data, setOf(item))
        assertEquals(101L, ShopManager.loadMutableProduct(file, directory, setOf(item))?.salePrice)

        data.purchasePrice = null
        val saleOnlyWarnings = data.validator(setOf(item)).priceWarnings()
        assertTrue(saleOnlyWarnings.isNotEmpty())
        assertEquals(saleOnlyWarnings, validateShopDataForEditor(data, setOf(item)))
        data.purchasePrice = 101
        assertTrue(validateShopDataForEditor(data, setOf(item)).isEmpty())
        data.salePrice = null
        assertTrue(validateShopDataForEditor(data, setOf(item)).isEmpty())
    }

    @Test
    fun `存在しない対象は保存可能な警告で未選択と不正価格はエラー`() {
        val data = product()
        val warnings = validateShopDataForEditor(data, emptySet())
        assertTrue(warnings.any { it.property.name == "itemInternalId" && it.isWarning })
        assertFalse(warnings.any { it.isError })
        data.itemInternalId = null
        data.purchasePrice = -1
        data.stock = -1
        val errors = validateShopDataForEditor(data, emptySet())
        assertTrue(errors.any { it.property.name == "itemInternalId" && it.isError })
        assertTrue(errors.any { it.property.name == "purchasePrice" && it.isError })
        assertTrue(errors.any { it.property.name == "stock" && it.isError })
        data.purchasePrice = null
        data.salePrice = null
        assertTrue(validateShopDataForEditor(data, emptySet()).any { it.property.name == "purchasePrice" && it.isError })
    }

    @Test
    fun `ショップを階層付きで保存と再読込しディレクトリ定義を除外する`() {
        val root = createTempDirectory("shop-store").toFile()
        try {
            val store = LocalEditorDataStore(root)
            val data = product()
            assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.shop, data.id, data))
            assertTrue(root.resolve("shop_data/weapons/swords/test.yml").isFile)
            root.resolve("shop_data/weapons/directory-data.yml").writeText("icon: minecraft:chest")
            val listed = assertIs<StoreResult.Success<List<StoreResource>>>(store.list(EditorDataDescriptors.shop)).value
            assertEquals(listOf(data.id), listed.map { it.id })
            assertEquals(data.freeze(), assertIs<StoreResult.Success<MutableShopProductData>>(store.load(EditorDataDescriptors.shop, data.id)).value.freeze())
            assertIs<StoreResult.Failure>(store.move(EditorDataDescriptors.shop, data.id, ""))
            assertIs<StoreResult.Success<Unit>>(store.rename(EditorDataDescriptors.shop, data.id, "renamed"))
            assertIs<StoreResult.Success<Unit>>(store.delete(EditorDataDescriptors.shop, "weapons.swords.renamed"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `未選択商品も自動保存ペアからIDと編集内容を復元できる`() {
        val root = createTempDirectory("shop-backup").toFile()
        try {
            val access = EditorDataService(InMemoryEditorDataStore(), root).shops
            val original = MutableShopProductData(id = "weapons.sword")
            val editing = original.copy(purchasePrice = 50)
            assertTrue(access.saveToLocalBackup(original.id, "original", original))
            assertTrue(access.saveToLocalBackup(editing.id, "editing", editing))
            val pair = assertNotNull(access.loadBackupPair(editing.id))
            assertEquals(editing, pair.first)
            assertEquals(original, pair.second)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `別項目の変更は自動マージし同じ項目の競合だけを表示する`() {
        val base = product()
        val merged = ShopDataMerger.merge(base, base.copy(purchasePrice = 200), base.copy(stock = 20))
        assertTrue(merged.conflicts.isEmpty())
        assertEquals(200L, merged.merged.purchasePrice)
        assertEquals(20, merged.merged.stock)
        val conflict = ShopDataMerger.merge(base, base.copy(purchasePrice = 200), base.copy(purchasePrice = 300))
        assertEquals("購入価格", conflict.conflicts.single().displayName)
        assertEquals(200L, conflict.resolveWithLocal(conflict.conflicts.map { it.path }.toSet()).purchasePrice)
    }
}
