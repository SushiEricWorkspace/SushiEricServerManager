package io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe

import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.item.ItemManager
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.data.recipe.RecipeManager
import io.github.sushiericworkspace.common.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.common.data.recipe.model.RecipeSize
import io.github.sushiericworkspace.common.data.recipe.model.mutable.MutableRecipeData
import io.github.sushiericworkspace.common.stats.player.SkillType
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.ConflictValueFormatter
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.RecipeDataMerger
import io.github.sushiericworkspace.sushiericservermanager.editor.offline.OfflineWorkspaceMigrator
import io.github.sushiericworkspace.sushiericservermanager.editor.offline.WorkspaceMigrationResult
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.validateRecipeForEditor
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.LocalEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.OfflineUploadService
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.UploadDataCategory
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.UploadScanResult
import io.github.sushiericworkspace.sushiericservermanager.editor.view.saveChangeDetails
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipeEditorDataTest {
    private val ingredient = ItemInternalId("ingredient")
    private val result = ItemInternalId("result")
    private fun recipe(id: String = "weapons.test") = MutableRecipeData(id = id,
        ingredients = RecipeIngredients.Shaped(mapOf(0 to ingredient)), resultItemInternalId = result)

    @Test
    fun `未解決の参照は警告で保持し未選択と構造不正はエラー`() {
        val data = recipe()
        val warnings = validateRecipeForEditor(data, emptyList(), emptyList())
        assertEquals(2, warnings.size)
        assertTrue(warnings.all { it.isWarning })
        data.resultItemInternalId = null
        data.resultCount = 0
        data.ingredients = RecipeIngredients.Shaped(mapOf(9 to ingredient))
        data.skillLevelRequirements[SkillType.MINING] = 101
        val errors = validateRecipeForEditor(data, emptyList(), emptyList()).filter { it.isError }
        assertTrue(errors.any { it.property.name == "resultItemInternalId" })
        assertTrue(errors.any { it.property.name == "resultCount" })
        assertTrue(errors.any { it.property.name == "ingredients" })
        assertTrue(errors.any { it.property.name == "skillLevelRequirements" })
    }

    @Test
    fun `Commonの最大スタック数と全形状組み合わせの重複検証を使用する`() {
        val data = recipe()
        val output = MutableItemBaseData(id = "result", internalId = result).apply { itemDetail.maxStackSize = 2 }
        data.resultCount = 3
        assertTrue(validateRecipeForEditor(data, listOf(output), emptyList()).any { it.isError && it.property.name == "resultCount" })
        listOf(true, false).forEach { firstShaped ->
            listOf(true, false).forEach { secondShaped ->
                val candidate = recipe("other")
                changeRecipeShape(data, firstShaped)
                changeRecipeShape(candidate, secondShaped)
                assertTrue(validateRecipeForEditor(data, emptyList(), listOf(candidate)).any { it.isError && it.message.contains("other") })
                candidate.size = RecipeSize.FIVE_BY_FIVE
                assertFalse(validateRecipeForEditor(data, emptyList(), listOf(candidate)).any { it.message.contains("重複") })
            }
        }
    }

    @Test
    fun `サイズ縮小と形状切り替えで材料を失わない`() {
        val data = recipe().apply { size = RecipeSize.FIVE_BY_FIVE; ingredients = RecipeIngredients.Shaped(mapOf(0 to ingredient, 24 to ingredient)) }
        changeRecipeSize(data, RecipeSize.THREE_BY_THREE)
        assertEquals(mapOf(0 to ingredient, 24 to ingredient), recipeSlots(data.ingredients))
        assertTrue(validateRecipeForEditor(data, emptyList(), emptyList()).any { it.isError })
        changeRecipeShape(data, false)
        assertEquals(listOf(ingredient, ingredient), assertIs<RecipeIngredients.Shapeless>(data.ingredients).items)
        changeRecipeShape(data, true)
        assertEquals(mapOf(0 to ingredient, 1 to ingredient), recipeSlots(data.ingredients))
    }

    @Test
    fun `階層付きレシピを保存して参照不明の内部IDも再読込できる`() = withWorkspace { root ->
        val store = LocalEditorDataStore(root)
        val data = recipe()
        assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.recipe, data.id, data))
        val file = root.resolve("recipe_data/weapons/test.yml")
        assertTrue(file.isFile)
        assertFalse(file.readText().contains("completed:"))
        val loaded = assertIs<StoreResult.Success<MutableRecipeData>>(store.load(EditorDataDescriptors.recipe, data.id)).value
        assertEquals(data.freeze(), loaded.freeze())
        assertIs<StoreResult.Success<Unit>>(store.rename(EditorDataDescriptors.recipe, data.id, "renamed"))
        assertIs<StoreResult.Success<String>>(store.move(EditorDataDescriptors.recipe, "weapons.renamed", "nested.recipes"))
        assertIs<StoreResult.Success<Unit>>(store.delete(EditorDataDescriptors.recipe, "nested.recipes.renamed"))
    }

    @Test
    fun `保存先の最新レシピで重複を防ぎ既存YAMLを変更しない`() = withWorkspace { root ->
        val store = LocalEditorDataStore(root)
        val data = recipe()
        assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.recipe, data.id, data))
        val duplicate = data.copy(id = "duplicate")
        val failure = assertIs<StoreResult.Failure>(store.save(EditorDataDescriptors.recipe, duplicate.id, duplicate))
        assertEquals(StoreErrorCode.VALIDATION_FAILED, failure.error.code)
        assertFalse(root.resolve("recipe_data/duplicate.yml").exists())
        assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.recipe, data.id, data.copy(resultCount = 2)))
    }

    @Test
    fun `保存時の最大スタック数を最新アイテム定義から検証する`() = withWorkspace { root ->
        val file = root.resolve("item_data/stats/result.yml").apply { parentFile.mkdirs() }
        ItemManager.saveMutable(file,
            MutableItemBaseData(id = "result", internalId = result).apply { itemDetail.maxStackSize = 2 })
        val data = recipe().apply { resultCount = 3 }
        val failure = assertIs<StoreResult.Failure>(LocalEditorDataStore(root).save(EditorDataDescriptors.recipe, data.id, data))
        assertEquals(StoreErrorCode.VALIDATION_FAILED, failure.error.code)
        assertTrue(failure.error.detail.orEmpty().contains("2"))
    }

    @Test
    fun `カタログ読込に失敗した場合は未解決警告で保存を通さない`() = withWorkspace { root ->
        root.resolve("recipe_data/broken.yml").apply { parentFile.mkdirs(); writeText("size: invalid") }
        val data = recipe()
        val failure = assertIs<StoreResult.Failure>(LocalEditorDataStore(root).save(EditorDataDescriptors.recipe, data.id, data))
        assertEquals(StoreErrorCode.INVALID_YAML, failure.error.code)
        assertFalse(root.resolve("recipe_data/weapons/test.yml").exists())
    }

    @Test
    fun `未選択のレシピも編集と元データのバックアップから復元する`() = withWorkspace { root ->
        val access = EditorDataService(InMemoryEditorDataStore(), root).recipes
        val original = MutableRecipeData(id = "draft.test")
        val editing = original.deepCopy().apply { ingredients = RecipeIngredients.Shapeless(listOf(ingredient, ingredient)) }
        assertTrue(access.saveToLocalBackup(original.id, "original", original))
        assertTrue(access.saveToLocalBackup(editing.id, "editing", editing))
        val pair = assertNotNull(access.loadBackupPair(editing.id))
        assertNull(pair.first.resultItemInternalId)
        assertEquals(editing, pair.first)
        assertEquals(original, pair.second)
    }

    @Test
    fun `別項目は自動マージし盤サイズと材料を別々の変更として混ぜない`() {
        val base = recipe()
        val merged = RecipeDataMerger.merge(base, base.copy(resultCount = 2), base.deepCopy().apply { skillLevelRequirements[SkillType.MINING] = 5 })
        assertTrue(merged.conflicts.isEmpty())
        assertEquals(2, merged.merged.resultCount)
        assertEquals(5, merged.merged.skillLevelRequirements[SkillType.MINING])
        val local = base.copy(size = RecipeSize.FIVE_BY_FIVE)
        val remote = base.copy(ingredients = RecipeIngredients.Shapeless(listOf(ingredient)))
        val conflict = RecipeDataMerger.merge(base, local, remote)
        assertEquals("盤サイズ・材料配置", conflict.conflicts.single().displayName)
        assertEquals(local.size, conflict.resolveWithLocal(conflict.conflicts.map { it.path }.toSet()).size)
        assertTrue(ConflictValueFormatter.format(conflict.conflicts.single().localValue).contains("5 × 5"))
    }

    @Test
    fun `保存確認は材料と完成品と完成数を具体的に表示する`() {
        val base = recipe()
        val changed = base.copy(ingredients = RecipeIngredients.Shaped(mapOf(2 to ingredient)), resultCount = 2)
        val details = saveChangeDetails(base.id, null, base, changed,
            itemDisplayText = { "公開.${it.value}" }).joinToString("\n")
        assertTrue(details.contains("材料盤"))
        assertTrue(details.contains("形状あり（3 × 3）"))
        assertTrue(details.contains("│ 公開.ingredient │ ・ │ ・ │"))
        assertTrue(details.contains("│ ・ │ ・ │ 公開.ingredient │"))
        assertFalse(details.contains("│ ingredient │"))
        assertTrue(details.contains("完成数"))
        assertFalse(details.contains("MutableRecipeData("))
    }

    @Test
    fun `オフライン正規化とアップロード一覧にレシピを含める`() = withWorkspace { root ->
        val data = recipe()
        RecipeManager.saveRecipe(root.resolve("recipe_data/weapons/test.yml"), data.freeze())
        assertIs<WorkspaceMigrationResult.Success>(OfflineWorkspaceMigrator(root).migrateToCurrent())
        assertNotNull(RecipeManager.loadMutable(root.resolve("recipe_data/weapons/test.yml"), root.resolve("recipe_data")))
        val remote = InMemoryEditorDataStore()
        val service = OfflineUploadService(root, remote)
        val scan = assertIs<UploadScanResult.Success>(service.scan(UploadDataCategory.RECIPE))
        val candidate = scan.entries.single()
        assertEquals(UploadDataCategory.RECIPE, candidate.key.category)
        assertEquals(UploadDataCategory.RECIPE, UploadDataCategory.of(io.github.sushiericworkspace.common.data.core.SushiEricDataType.Recipe))
        val uploaded = service.upload(setOf(candidate.key), emptySet(), "destination")
        assertTrue(uploaded.failed.isEmpty())
        assertEquals(listOf(candidate.key), uploaded.succeeded)
        val saved = assertIs<StoreResult.Success<MutableRecipeData>>(remote.load(EditorDataDescriptors.recipe, "destination.test"))
        assertEquals("destination.test", saved.value.id)
        assertEquals(data.ingredients, saved.value.ingredients)
    }

    private fun withWorkspace(test: (File) -> Unit) {
        val root = createTempDirectory("recipe-editor-test").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
}
