package io.github.sushiericworkspace.sushiericservermanager.editor.offline

import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.common.path.SushiEricDataDirectory
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptor
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.LocalEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.update.AppVersion
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

sealed interface WorkspaceMigrationResult {
    data class Success(
        val manifest: OfflineWorkspaceManifest,
        val normalizedFileCount: Int
    ) : WorkspaceMigrationResult

    data class Failure(val error: StoreError) : WorkspaceMigrationResult
}

/**
 * オフライン領域を一時ワークスペースへ完全に再出力してから置換します。
 * 置換途中で失敗した場合はバックアップから元ファイルを復元します。
 */
class OfflineWorkspaceMigrator(
    private val workspaceRoot: File,
    private val migrations: DataMigrationRegistry = DataMigrationRegistry()
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun migrateToCurrent(): WorkspaceMigrationResult {
        val sourceStore = LocalEditorDataStore(workspaceRoot)
        when (val prepared = sourceStore.ensureDirectories()) {
            is StoreResult.Failure -> return WorkspaceMigrationResult.Failure(prepared.error)
            is StoreResult.Success -> Unit
        }

        val repository = OfflineManifestRepository(workspaceRoot)
        val sourceManifest = when (val read = repository.read()) {
            ManifestReadResult.Missing -> null
            is ManifestReadResult.Success -> read.manifest
            is ManifestReadResult.FutureVersion -> {
                return failure(
                    StoreErrorCode.UNSUPPORTED_FORMAT,
                    detail = "未来の形式バージョンです: ${read.version}"
                )
            }
            is ManifestReadResult.Invalid -> {
                return failure(StoreErrorCode.MANIFEST_INVALID, cause = read.cause)
            }
        }

        val sourceVersion = sourceManifest?.dataFormatVersion
            ?: OfflineFormatVersion.CURRENT_DATA_FORMAT_VERSION
        val migrationPath = migrations.path(
            sourceVersion,
            OfflineFormatVersion.CURRENT_DATA_FORMAT_VERSION
        ) ?: return failure(
            StoreErrorCode.UNSUPPORTED_FORMAT,
            detail = "$sourceVersion から現在形式への移行経路がありません。"
        )

        val editorDirectory = workspaceRoot.resolve(".editor").apply { mkdirs() }
        val operationId = UUID.randomUUID().toString()
        val stagingRoot = editorDirectory.resolve("migration-staging-$operationId")
        val backupRoot = editorDirectory.resolve("migration-backup-$operationId")
        val stagingStore = LocalEditorDataStore(stagingRoot)

        return try {
            when (val prepared = stagingStore.ensureDirectories()) {
                is StoreResult.Failure -> throw MigrationFailure(prepared.error)
                is StoreResult.Success -> Unit
            }
            val normalizationSource = prepareMigrationSource(sourceStore, stagingRoot, migrationPath)
            val normalizedEntries = mutableListOf<OfflineManifestFile>()
            normalizeDescriptor(normalizationSource, stagingStore, EditorDataDescriptors.item, normalizedEntries)
            normalizeDescriptor(normalizationSource, stagingStore, EditorDataDescriptors.ore, normalizedEntries)

            val manifest = OfflineWorkspaceManifest(
                appVersion = AppVersion.CURRENT,
                files = normalizedEntries.sortedBy { it.relativePath }
            )
            if (!OfflineManifestRepository(stagingRoot).write(manifest)) {
                return failure(StoreErrorCode.IO_ERROR, detail = "一時マニフェストを保存できませんでした。")
            }

            replaceWorkspace(stagingRoot, backupRoot)
            WorkspaceMigrationResult.Success(manifest, normalizedEntries.size)
        } catch (e: MigrationFailure) {
            logger.error("オフラインデータの形式更新に失敗しました", e)
            failure(e.error.code, e.error.dataId, e.error.detail, e.error.cause ?: e)
        } catch (e: Exception) {
            logger.error("オフラインデータの形式更新に失敗しました", e)
            failure(StoreErrorCode.IO_ERROR, cause = e)
        } finally {
            stagingRoot.deleteRecursively()
            backupRoot.deleteRecursively()
        }
    }

    private fun prepareMigrationSource(
        sourceStore: LocalEditorDataStore,
        stagingRoot: File,
        migrationPath: List<DataMigration>
    ): LocalEditorDataStore {
        if (migrationPath.isEmpty()) return sourceStore
        val migratedRoot = stagingRoot.resolve(".raw")
        EditorDataDescriptors.all.forEach { descriptor ->
            val sourceDirectory = sourceStore.root().resolve(descriptor.relativeDirectory)
            SushiEricDataDirectory.walkYmlFiles(sourceDirectory)
                .forEach { original ->
                    val relativePath = original.relativeTo(sourceDirectory).invariantSeparatorsPath
                    var input = original
                    migrationPath.forEachIndexed { index, migration ->
                        val target = migratedRoot.resolve(
                            ".steps/$index/${descriptor.relativeDirectory}/$relativePath"
                        )
                        target.parentFile.mkdirs()
                        migration.migrate(input, target)
                        input = target
                    }
                    val finalTarget = migratedRoot.resolve(descriptor.relativeDirectory).resolve(relativePath)
                    finalTarget.parentFile.mkdirs()
                    input.copyTo(finalTarget, overwrite = true)
                }
        }
        return LocalEditorDataStore(migratedRoot)
    }

    private fun <T : ManagedData<T, *>> normalizeDescriptor(
        source: LocalEditorDataStore,
        target: LocalEditorDataStore,
        descriptor: EditorDataDescriptor<T>,
        entries: MutableList<OfflineManifestFile>
    ) {
        val resources = when (val listed = source.list(descriptor)) {
            is StoreResult.Success -> listed.value
            is StoreResult.Failure -> throw MigrationFailure(listed.error)
        }
        resources.forEach { resource ->
            val data = when (val loaded = source.load(descriptor, resource.id)) {
                is StoreResult.Success -> loaded.value
                is StoreResult.Failure -> throw MigrationFailure(loaded.error)
            }
            when (val saved = target.save(descriptor, resource.id, data)) {
                is StoreResult.Success -> Unit
                is StoreResult.Failure -> throw MigrationFailure(saved.error)
            }
            entries += OfflineManifestFile(
                relativePath = descriptor.dataType.pathOf(resource.id).getRawPath(),
                dataFormatVersion = OfflineFormatVersion.CURRENT_DATA_FORMAT_VERSION
            )
        }
    }

    private fun replaceWorkspace(stagingRoot: File, backupRoot: File) {
        val relativeFiles = EditorDataDescriptors.all.map { it.relativeDirectory } + ".editor/manifest.json"
        val replaced = mutableListOf<String>()
        try {
            relativeFiles.forEach { relative ->
                val current = workspaceRoot.resolve(relative)
                val replacement = stagingRoot.resolve(relative)
                val backup = backupRoot.resolve(relative)

                if (current.exists()) {
                    backup.parentFile?.mkdirs()
                    if (current.isDirectory) {
                        current.copyRecursively(backup, overwrite = true)
                    } else {
                        current.copyTo(backup, overwrite = true)
                    }
                }

                // この時点以降に失敗した場合、削除済みの現行データも復元対象に含める。
                replaced += relative
                if (replacement.exists()) {
                    current.deleteRecursively()
                    current.parentFile?.mkdirs()
                    Files.move(
                        replacement.toPath(),
                        current.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                    )
                }
            }
        } catch (e: Exception) {
            replaced.asReversed().forEach { relative ->
                val current = workspaceRoot.resolve(relative)
                val backup = backupRoot.resolve(relative)
                current.deleteRecursively()
                if (backup.exists()) {
                    current.parentFile?.mkdirs()
                    if (backup.isDirectory) {
                        backup.copyRecursively(current, overwrite = true)
                    } else {
                        backup.copyTo(current, overwrite = true)
                    }
                }
            }
            throw e
        }
    }

    private fun failure(
        code: StoreErrorCode,
        dataId: String? = null,
        detail: String? = null,
        cause: Throwable? = null
    ): WorkspaceMigrationResult.Failure =
        WorkspaceMigrationResult.Failure(StoreError(code, dataId, detail, cause))

    private class MigrationFailure(val error: StoreError) : RuntimeException(error.detail, error.cause)
}
