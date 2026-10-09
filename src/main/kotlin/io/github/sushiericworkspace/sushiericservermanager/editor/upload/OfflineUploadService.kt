package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.editor.offline.ManifestReadResult
import io.github.sushiericworkspace.sushiericservermanager.editor.offline.OfflineFormatVersion
import io.github.sushiericworkspace.sushiericservermanager.editor.offline.OfflineManifestRepository
import io.github.sushiericworkspace.sushiericservermanager.editor.offline.OfflineWorkspaceMigrator
import io.github.sushiericworkspace.sushiericservermanager.editor.offline.WorkspaceMigrationResult
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptor
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.LocalEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import java.io.File

enum class UploadDataCategory(val displayName: String) {
    ITEM("アイテム"),
    ORE("鉱石"),
    RECIPE("レシピ")
}

data class UploadKey(
    val category: UploadDataCategory,
    val id: String
)

enum class UploadCandidateState {
    NEW,
    OVERWRITE,
    UNAVAILABLE
}

data class OfflineUploadCandidate(
    val key: UploadKey,
    val state: UploadCandidateState,
    val requiresFormatUpdate: Boolean,
    val error: StoreError? = null
) {
    val selectable: Boolean
        get() = state != UploadCandidateState.UNAVAILABLE
}

sealed interface UploadScanResult {
    data class Success(val candidates: List<OfflineUploadCandidate>) : UploadScanResult
    data class Failure(val error: StoreError) : UploadScanResult
}

data class UploadItemFailure(
    val key: UploadKey,
    val error: StoreError
)

data class OfflineUploadResult(
    val succeeded: List<UploadKey>,
    val failed: List<UploadItemFailure>
)

/**
 * オフラインデータを現在形式へ正規化したうえでRemote Storeへ保存します。
 * マニフェストや.editor配下のファイルは列挙対象になりません。
 */
class OfflineUploadService(
    private val workspaceRoot: File,
    private val remoteStore: EditorDataStore,
    private val migrator: OfflineWorkspaceMigrator = OfflineWorkspaceMigrator(workspaceRoot)
) {
    private val localStore = LocalEditorDataStore(workspaceRoot)

    fun scan(): UploadScanResult {
        val requiresUpdate = when (val manifest = OfflineManifestRepository(workspaceRoot).read()) {
            ManifestReadResult.Missing -> true
            is ManifestReadResult.Success ->
                manifest.manifest.dataFormatVersion < OfflineFormatVersion.CURRENT_DATA_FORMAT_VERSION
            is ManifestReadResult.FutureVersion -> {
                return UploadScanResult.Failure(
                    StoreError(
                        StoreErrorCode.UNSUPPORTED_FORMAT,
                        detail = "未来の形式バージョンです: ${manifest.version}"
                    )
                )
            }
            is ManifestReadResult.Invalid -> {
                return UploadScanResult.Failure(
                    StoreError(StoreErrorCode.MANIFEST_INVALID, cause = manifest.cause)
                )
            }
        }

        when (val migrated = migrator.migrateToCurrent()) {
            is WorkspaceMigrationResult.Failure -> return UploadScanResult.Failure(migrated.error)
            is WorkspaceMigrationResult.Success -> Unit
        }

        val candidates = mutableListOf<OfflineUploadCandidate>()
        scanDescriptor(UploadDataCategory.ITEM, EditorDataDescriptors.item, requiresUpdate, candidates)
        scanDescriptor(UploadDataCategory.ORE, EditorDataDescriptors.ore, requiresUpdate, candidates)
        scanDescriptor(UploadDataCategory.RECIPE, EditorDataDescriptors.recipe, requiresUpdate, candidates)
        return UploadScanResult.Success(
            candidates.sortedWith(compareBy({ it.key.category.ordinal }, { it.key.id }))
        )
    }

    fun upload(
        selected: Set<UploadKey>,
        overwriteApproved: Set<UploadKey>
    ): OfflineUploadResult {
        when (val migrated = migrator.migrateToCurrent()) {
            is WorkspaceMigrationResult.Failure -> {
                return OfflineUploadResult(
                    succeeded = emptyList(),
                    failed = selected.map { UploadItemFailure(it, migrated.error) }
                )
            }
            is WorkspaceMigrationResult.Success -> Unit
        }

        val succeeded = mutableListOf<UploadKey>()
        val failed = mutableListOf<UploadItemFailure>()
        selected.sortedWith(compareBy({ it.category.ordinal }, { it.id })).forEach { key ->
            when (key.category) {
                UploadDataCategory.ITEM -> uploadUnchecked(
                    key,
                    EditorDataDescriptors.item,
                    overwriteApproved,
                    succeeded,
                    failed
                )
                UploadDataCategory.ORE -> uploadUnchecked(
                    key,
                    EditorDataDescriptors.ore,
                    overwriteApproved,
                    succeeded,
                    failed
                )
                UploadDataCategory.RECIPE -> uploadUnchecked(key, EditorDataDescriptors.recipe, overwriteApproved, succeeded, failed)
            }
        }
        return OfflineUploadResult(succeeded, failed)
    }

    private fun <T : ManagedData<T, *>> scanDescriptor(
        category: UploadDataCategory,
        descriptor: EditorDataDescriptor<T>,
        requiresUpdate: Boolean,
        output: MutableList<OfflineUploadCandidate>
    ) {
        val localResources = when (val listed = localStore.list(descriptor)) {
            is StoreResult.Success -> listed.value
            is StoreResult.Failure -> return
        }
        val remoteListing = remoteStore.list(descriptor)
        val remoteIds = when (remoteListing) {
            is StoreResult.Success -> remoteListing.value.mapTo(mutableSetOf()) { it.id }
            is StoreResult.Failure -> emptySet()
        }

        localResources.forEach { resource ->
            val loaded = localStore.load(descriptor, resource.id)
            val key = UploadKey(category, resource.id)
            output += when {
                remoteListing is StoreResult.Failure -> OfflineUploadCandidate(
                    key = key,
                    state = UploadCandidateState.UNAVAILABLE,
                    requiresFormatUpdate = requiresUpdate,
                    error = remoteListing.error
                )
                loaded is StoreResult.Failure -> OfflineUploadCandidate(
                    key = key,
                    state = UploadCandidateState.UNAVAILABLE,
                    requiresFormatUpdate = requiresUpdate,
                    error = loaded.error
                )
                loaded is StoreResult.Success -> OfflineUploadCandidate(
                    key = key,
                    state = if (resource.id in remoteIds) {
                        UploadCandidateState.OVERWRITE
                    } else {
                        UploadCandidateState.NEW
                    },
                    requiresFormatUpdate = requiresUpdate
                )
                else -> error("未処理のStoreResultです")
            }
        }
    }

    private fun <T : ManagedData<T, *>> uploadUnchecked(
        key: UploadKey,
        descriptor: EditorDataDescriptor<T>,
        overwriteApproved: Set<UploadKey>,
        succeeded: MutableList<UploadKey>,
        failed: MutableList<UploadItemFailure>
    ) {
        val remoteExists = when (val listed = remoteStore.list(descriptor)) {
            is StoreResult.Success -> listed.value.any { it.id == key.id }
            is StoreResult.Failure -> {
                failed += UploadItemFailure(key, listed.error)
                return
            }
        }
        if (remoteExists && key !in overwriteApproved) {
            failed += UploadItemFailure(
                key,
                StoreError(
                    StoreErrorCode.ALREADY_EXISTS,
                    key.id,
                    "上書きが承認されていません。"
                )
            )
            return
        }

        val localData = when (val loaded = localStore.load(descriptor, key.id)) {
            is StoreResult.Success -> loaded.value
            is StoreResult.Failure -> {
                failed += UploadItemFailure(key, loaded.error)
                return
            }
        }

        // Remote Storeが現在のManagerで一時ファイルへ再保存してからアップロードする。
        when (val saved = remoteStore.save(descriptor, key.id, localData)) {
            is StoreResult.Success -> succeeded += key
            is StoreResult.Failure -> failed += UploadItemFailure(key, saved.error)
        }
    }
}
