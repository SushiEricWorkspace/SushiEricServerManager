package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.VanillaBlockId
import io.github.sushiericworkspace.sushiericdatamodel.data.item.ItemManager
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.ore.model.mutable.MutableOreBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.drop.model.mutable.MutableDropItemData
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.mutable.MutableRecipeData
import io.github.sushiericworkspace.sushiericdatamodel.data.shop.model.mutable.MutableShopProductData
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfile
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class StoreSaveReferencesTest {
    @Test
    fun `ローカルのアイテム保存は一覧取得や他アイテム読込を行わず検証とYAMLを維持する`() {
        val root = createTempDirectory("item-save-local-").toFile()
        try {
            val store = spy(LocalEditorDataStore(root))
            val descriptor = EditorDataDescriptors.item
            val existing = descriptor.createDefault("existing")
            ItemManager.saveMutable(root.resolve("item_data/stats/existing.yml"), existing)
            val item = descriptor.createDefault("new_item")
            assertIs<StoreResult.Success<Unit>>(store.save(descriptor, item.id, item))
            assertTrue(item.completed)
            verify(store, never()).list(descriptor)
            verify(store, never()).load(descriptor, existing.id)
            assertEquals(item.freeze(), assertIs<StoreResult.Success<MutableItemBaseData>>(store.load(descriptor, item.id)).value.freeze())
            item.display.displayName = " "
            assertEquals(StoreErrorCode.VALIDATION_FAILED, assertIs<StoreResult.Failure>(store.save(descriptor, item.id, item)).error.code)
            assertFalse(item.completed)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `通常リモートのアイテム保存は全件取得せず検証と出力を維持する`() {
        val ssh = remote()
        var uploaded: String? = null
        doAnswer { invocation -> uploaded = File(invocation.getArgument<String>(0)).readText(); null }
            .`when`(ssh).upload(anyString(), anyString())
        val store = RemoteEditorDataStore(ssh)
        val descriptor = EditorDataDescriptors.item
        val item = descriptor.createDefault("new_item")
        assertIs<StoreResult.Success<Unit>>(store.save(descriptor, item.id, item))
        assertTrue(item.completed)
        verify(ssh, never()).listFilesOrThrow(anyString())
        verify(ssh, never()).download(anyString(), anyString())
        val root = createTempDirectory("item-save-remote-").toFile()
        try {
            val file = root.resolve("new_item.yml").apply { writeText(assertNotNull(uploaded)) }
            assertEquals(item.freeze(), assertNotNull(ItemManager.loadMutable(file, root)).freeze())
        } finally { root.deleteRecursively() }
        clearInvocations(ssh)
        item.display.displayName = " "
        assertEquals(StoreErrorCode.VALIDATION_FAILED, assertIs<StoreResult.Failure>(store.save(descriptor, item.id, item)).error.code)
        assertFalse(item.completed)
        verify(ssh, never()).upload(anyString(), anyString())
        verify(ssh, never()).listFilesOrThrow(anyString())
        verify(ssh, never()).download(anyString(), anyString())
    }

    @Test
    fun `通常リモートで鉱石とショップは毎回最新参照を取得して削除を検出する`() {
        val store = spy(RemoteEditorDataStore(remote()))
        val item = EditorDataDescriptors.item.createDefault("reference")
        val resource = StoreResource(item.id, "reference.yml", "item_data/stats/reference.yml")
        doReturn(StoreResult.Success(listOf(resource))).`when`(store).list(EditorDataDescriptors.item)
        doReturn(StoreResult.Success(item)).`when`(store).load(EditorDataDescriptors.item, item.id)
        val ore = MutableOreBaseData(
            id = "ore", blockId = VanillaBlockId("iron_ore"),
            mutableDropItems = mutableListOf(MutableDropItemData(itemId = item.internalId))
        )
        val shop = MutableShopProductData("weapons.product", item.internalId, 100, 50, 12, true, 3)
        assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.ore, ore.id, ore))
        assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.shop, shop.id, shop))
        assertTrue(ore.completed)
        assertTrue(shop.completed)
        verify(store, times(2)).list(EditorDataDescriptors.item)
        verify(store, times(2)).load(EditorDataDescriptors.item, item.id)
        doReturn(StoreResult.Success(emptyList<StoreResource>())).`when`(store).list(EditorDataDescriptors.item)
        clearInvocations(store)
        assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.ore, ore.id, ore))
        assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.shop, shop.id, shop))
        assertFalse(ore.completed)
        assertFalse(shop.completed)
        verify(store, times(2)).list(EditorDataDescriptors.item)
        verify(store, never()).load(EditorDataDescriptors.item, item.id)
    }

    @Test
    fun `ローカルで参照先を削除すると鉱石とショップの完成状態を再検証する`() {
        val root = createTempDirectory("references-local-").toFile()
        try {
            val store = LocalEditorDataStore(root)
            val item = EditorDataDescriptors.item.createDefault("reference")
            assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.item, item.id, item))
            val ore = MutableOreBaseData(
                id = "ore", blockId = VanillaBlockId("iron_ore"),
                mutableDropItems = mutableListOf(MutableDropItemData(itemId = item.internalId))
            )
            val shop = MutableShopProductData("weapons.product", item.internalId, 100, 50, 12, true, 3)
            assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.ore, ore.id, ore))
            assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.shop, shop.id, shop))
            assertTrue(ore.completed)
            assertTrue(shop.completed)
            assertIs<StoreResult.Success<Unit>>(store.delete(EditorDataDescriptors.item, item.id))
            assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.ore, ore.id, ore))
            assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.shop, shop.id, shop))
            assertFalse(ore.completed)
            assertFalse(shop.completed)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `通常リモートのレシピ保存はストア検証で最新定義を取得し重複を拒否する`() {
        val ssh = remote()
        val store = spy(RemoteEditorDataStore(ssh))
        val item = EditorDataDescriptors.item.createDefault("reference")
        doReturn(StoreResult.Success(listOf(StoreResource(item.id, "reference.yml", "reference.yml"))))
            .`when`(store).list(EditorDataDescriptors.item)
        doReturn(StoreResult.Success(item)).`when`(store).load(EditorDataDescriptors.item, item.id)
        doReturn(StoreResult.Success(emptyList<StoreResource>())).`when`(store).list(EditorDataDescriptors.recipe)
        val recipe = MutableRecipeData(id = "recipe",
            ingredients = RecipeIngredients.Shaped(mapOf(0 to item.internalId)), resultItemInternalId = item.internalId)
        assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.recipe, recipe.id, recipe))
        assertTrue(recipe.completed)
        verify(store, times(1)).list(EditorDataDescriptors.item)
        verify(store, times(1)).load(EditorDataDescriptors.item, item.id)
        doReturn(StoreResult.Success(listOf(StoreResource(recipe.id, "recipe.yml", "recipe.yml"))))
            .`when`(store).list(EditorDataDescriptors.recipe)
        doReturn(StoreResult.Success(recipe)).`when`(store).load(EditorDataDescriptors.recipe, recipe.id)
        clearInvocations(ssh)
        val duplicate = recipe.copy(id = "duplicate")
        assertEquals(StoreErrorCode.VALIDATION_FAILED,
            assertIs<StoreResult.Failure>(store.save(EditorDataDescriptors.recipe, duplicate.id, duplicate)).error.code)
        verify(ssh, never()).upload(anyString(), anyString())
    }

    private fun remote(): SshManager = mock(SshManager::class.java).also { ssh ->
        doReturn(ServerProfile("test", "example.invalid", 22, "test", "/server", ""))
            .`when`(ssh).currentProfile
        doReturn(true).`when`(ssh).isSftpActive
        doReturn(ManagerCompatibility.Compatible("0.1.0")).`when`(ssh).compatibility
    }
}
