package io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe

import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentRecord
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.export
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.fx
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.startFx
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorView
import javafx.application.Platform
import javafx.scene.control.ButtonBar
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** 不正resourceをclasspath先頭へ置いた専用workerだけで実行する。製品resourceは変更しない。 */
@EnabledOnOs(OS.WINDOWS, OS.MAC)
class RecipeAssetFailureFxTest {
    @TempDir lateinit var directory: File

    @Test
    @EnabledIfEnvironmentVariable(named = "MANAGER_RECIPE_ASSET_FAILURE_TEST", matches = "true")
    fun 素材hash不一致でも本番盤面のIDを保持し確認後のローカル保存を拒否しない() {
        val bytes = javaClass.classLoader.getResourceAsStream("images/preview/item.png")!!.use { it.readNBytes(65) }
        assertTrue(bytes.size <= 64)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals(System.getProperty("manager.recipeBadAssetSha256"), hash)
        startFx()
        RecipeFxFixture.create(directory).use { f ->
            fx { assertTrue(f.slot(0).text.contains("item.0000")); f.count().requestFocus(); f.count().text = "2"; f.slot(0).requestFocus() }
            val outcome = CompletableFuture<EditorView.SaveOutcome>()
            fx { Platform.runLater { f.logic.onSave { outcome.complete(it) } } }
            val window = dialog("変更内容を保存")
            fx {
                assertEquals(2, descendants(window.scene.root).filterIsInstance<RecipeBoardView>().count())
                export(window.scene.snapshot(null), "recipe-asset-failure-save")
                fireDialog(window, ButtonBar.ButtonData.OK_DONE)
            }
            assertEquals(EditorView.SaveOutcome.SAVED, outcome.get(15, TimeUnit.SECONDS))
            val records = assertIs<StoreResult.Success<List<LocalDocumentRecord>>>(f.service.recipes.localDocuments()).value
            val saved = f.service.recipes.decodeDocument(f.original.id, assertNotNull(records.single { it.key.id == f.original.id }.saved))
            assertEquals(2, saved.resultCount)
            assertEquals(f.original.ingredients, saved.ingredients)
            assertEquals(f.original.resultItemInternalId, saved.resultItemInternalId)
        }
    }
}
