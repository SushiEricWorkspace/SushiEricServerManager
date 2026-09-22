package io.github.sushiericworkspace.sushiericservermanager.editor.offline

import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.LocalEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OfflineWorkspaceMigratorTest {
    @Test
    fun `マニフェストなしを現在形式へ正規化する`() {
        val root = createTempDirectory("offline-migrate").toFile()
        try {
            val store = LocalEditorDataStore(root)
            assertIs<StoreResult.Success<Unit>>(
                store.save(EditorDataDescriptors.item, "sword", validItem("sword"))
            )

            val result = assertIs<WorkspaceMigrationResult.Success>(
                OfflineWorkspaceMigrator(root).migrateToCurrent()
            )

            assertEquals(1, result.normalizedFileCount)
            assertTrue(root.resolve(".editor/manifest.json").isFile)
            assertIs<ManifestReadResult.Success>(OfflineManifestRepository(root).read())
            assertIs<StoreResult.Success<MutableItemBaseData>>(
                LocalEditorDataStore(root).load(EditorDataDescriptors.item, "sword")
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `未来形式を拒否する`() {
        val root = createTempDirectory("offline-future").toFile()
        try {
            OfflineManifestRepository(root).write(
                OfflineWorkspaceManifest(
                    workspaceFormatVersion = 999,
                    dataFormatVersion = 999
                )
            )

            val result = assertIs<WorkspaceMigrationResult.Failure>(
                OfflineWorkspaceMigrator(root).migrateToCurrent()
            )
            assertEquals(StoreErrorCode.UNSUPPORTED_FORMAT, result.error.code)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `サブディレクトリ構造を維持して正規化する`() {
        val root = createTempDirectory("offline-migrate-directory").toFile()
        try {
            val id = "combat.sword.test_sword"
            val store = LocalEditorDataStore(root)
            assertIs<StoreResult.Success<Unit>>(
                store.save(EditorDataDescriptors.item, id, validItem(id))
            )

            val result = assertIs<WorkspaceMigrationResult.Success>(
                OfflineWorkspaceMigrator(root).migrateToCurrent()
            )

            assertEquals(1, result.normalizedFileCount)
            assertTrue(root.resolve("item_data/stats/combat/sword/test_sword.yml").isFile)
            assertTrue(result.manifest.files.any {
                it.relativePath == "item_data/stats/combat/sword/test_sword.yml"
            })
            assertEquals(id, assertIs<StoreResult.Success<MutableItemBaseData>>(
                LocalEditorDataStore(root).load(EditorDataDescriptors.item, id)
            ).value.id)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `変換失敗時に元ファイルを保持する`() {
        val root = createTempDirectory("offline-rollback").toFile()
        try {
            val store = LocalEditorDataStore(root)
            assertIs<StoreResult.Success<Unit>>(
                store.save(EditorDataDescriptors.item, "sword", validItem("sword"))
            )
            val original = root.resolve("item_data/stats/sword.yml").readBytes()
            OfflineManifestRepository(root).write(
                OfflineWorkspaceManifest(dataFormatVersion = 0)
            )
            val failingMigration = object : DataMigration {
                override val fromVersion: Int = 0
                override val toVersion: Int = 1
                override fun migrate(source: File, target: File) {
                    error("migration failed")
                }
            }

            assertIs<WorkspaceMigrationResult.Failure>(
                OfflineWorkspaceMigrator(
                    root,
                    DataMigrationRegistry(listOf(failingMigration))
                ).migrateToCurrent()
            )
            assertContentEquals(original, root.resolve("item_data/stats/sword.yml").readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun validItem(id: String): MutableItemBaseData = MutableItemBaseData(id = id).apply {
        display.displayName = "Sword"
    }
}
