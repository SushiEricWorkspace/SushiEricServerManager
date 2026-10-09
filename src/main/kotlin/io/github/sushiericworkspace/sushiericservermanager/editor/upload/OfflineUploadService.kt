package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.common.data.core.SushiEricDataType
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

/** アップロードできるデータの種別です。エディターが扱う[SushiEricDataType]と対応します。 */
enum class UploadDataCategory(val displayName: String, val dataType: SushiEricDataType<*>) {
    ITEM("アイテム", SushiEricDataType.Item),
    ORE("鉱石", SushiEricDataType.Ore),
    RECIPE("レシピ", SushiEricDataType.Recipe);

    companion object {
        /** エディターのデータ種別に対応するアップロード種別を返します。対応しない種別はnullです。 */
        fun of(dataType: SushiEricDataType<*>): UploadDataCategory? = entries.firstOrNull { it.dataType == dataType }
    }
}

/** ローカルのデータを指します。IDはローカルでの完全IDです。 */
data class UploadKey(
    val category: UploadDataCategory,
    val id: String
)

enum class UploadCandidateState {
    NEW,
    OVERWRITE,
    UNAVAILABLE
}

/**
 * 宛先を反映したアップロード候補1件です。
 *
 * @property key ローカルのデータ。
 * @property targetId サーバー上に保存するときのID。
 * @property error 選択できない理由。選択できる場合はnullです。
 */
data class OfflineUploadCandidate(
    val key: UploadKey,
    val targetId: String,
    val state: UploadCandidateState,
    val requiresFormatUpdate: Boolean,
    val error: StoreError? = null
) {
    val selectable: Boolean
        get() = state != UploadCandidateState.UNAVAILABLE
}

/**
 * 宛先に依らない、ローカルデータ1件の走査結果です。
 *
 * @property loadError ローカルのデータを読めなかった場合の理由。
 */
data class LocalUploadEntry(
    val key: UploadKey,
    val requiresFormatUpdate: Boolean,
    val loadError: StoreError? = null
)

sealed interface UploadScanResult {
    /**
     * @property entries ローカルのデータ。
     * @property remoteIds 種別ごとの、サーバー上に存在するデータのID。
     */
    data class Success(
        val entries: List<LocalUploadEntry>,
        val remoteIds: Map<UploadDataCategory, Set<String>>
    ) : UploadScanResult

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
 *
 * サーバー上のIDは、宛先ディレクトリをつけて[uploadTargetId]で決めます。
 */
class OfflineUploadService(
    private val workspaceRoot: File,
    private val remoteStore: EditorDataStore,
    private val migrator: OfflineWorkspaceMigrator = OfflineWorkspaceMigrator(workspaceRoot)
) {
    private val localStore = LocalEditorDataStore(workspaceRoot)

    /** [category]のローカルデータとサーバー上のIDを調べます。宛先ごとの状態は[planUpload]で決めます。 */
    fun scan(category: UploadDataCategory): UploadScanResult {
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

        return when (category) {
            UploadDataCategory.ITEM -> scanDescriptor(category, EditorDataDescriptors.item, requiresUpdate)
            UploadDataCategory.ORE -> scanDescriptor(category, EditorDataDescriptors.ore, requiresUpdate)
            UploadDataCategory.RECIPE -> scanDescriptor(category, EditorDataDescriptors.recipe, requiresUpdate)
        }
    }

    /**
     * 選択したデータを、[destination]の下へアップロードします。
     *
     * @param selected アップロードするローカルのデータ。
     * @param overwriteApproved 上書きを承認したデータ。承認のない上書きは失敗として返します。
     * @param destination 宛先ディレクトリの完全ID。空の場合はルート。
     */
    fun upload(
        selected: Set<UploadKey>,
        overwriteApproved: Set<UploadKey>,
        destination: String
    ): OfflineUploadResult {
        if (!isValidUploadDestination(destination)) {
            val error = StoreError(StoreErrorCode.INVALID_ID, destination, "宛先ディレクトリの指定が不正です。")
            return OfflineUploadResult(emptyList(), selected.map { UploadItemFailure(it, error) })
        }
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
        val usedTargets = mutableSetOf<Pair<UploadDataCategory, String>>()
        selected.sortedWith(compareBy({ it.category.ordinal }, { it.id })).forEach { key ->
            val targetId = uploadTargetId(key.id, destination)
            if (!usedTargets.add(key.category to targetId)) {
                failed += UploadItemFailure(
                    key,
                    StoreError(StoreErrorCode.ALREADY_EXISTS, targetId, "同じ宛先IDになる別のデータがあります。")
                )
                return@forEach
            }
            when (key.category) {
                UploadDataCategory.ITEM -> uploadUnchecked(
                    key, targetId, EditorDataDescriptors.item, overwriteApproved, succeeded, failed
                )
                UploadDataCategory.ORE -> uploadUnchecked(
                    key, targetId, EditorDataDescriptors.ore, overwriteApproved, succeeded, failed
                )
                UploadDataCategory.RECIPE -> uploadUnchecked(
                    key, targetId, EditorDataDescriptors.recipe, overwriteApproved, succeeded, failed
                )
            }
        }
        return OfflineUploadResult(succeeded, failed)
    }

    private fun <T : ManagedData<T, *>> scanDescriptor(
        category: UploadDataCategory,
        descriptor: EditorDataDescriptor<T>,
        requiresUpdate: Boolean
    ): UploadScanResult {
        val localResources = when (val listed = localStore.list(descriptor)) {
            is StoreResult.Success -> listed.value
            is StoreResult.Failure -> emptyList()
        }
        val remoteIds = when (val listed = remoteStore.list(descriptor)) {
            is StoreResult.Success -> listed.value.mapTo(mutableSetOf()) { it.id }
            is StoreResult.Failure -> return UploadScanResult.Failure(listed.error)
        }

        val entries = localResources.map { resource ->
            LocalUploadEntry(
                key = UploadKey(category, resource.id),
                requiresFormatUpdate = requiresUpdate,
                loadError = (localStore.load(descriptor, resource.id) as? StoreResult.Failure)?.error
            )
        }
        return UploadScanResult.Success(
            entries = entries.sortedBy { it.key.id },
            remoteIds = mapOf(category to remoteIds)
        )
    }

    private fun <T : ManagedData<T, *>> uploadUnchecked(
        key: UploadKey,
        targetId: String,
        descriptor: EditorDataDescriptor<T>,
        overwriteApproved: Set<UploadKey>,
        succeeded: MutableList<UploadKey>,
        failed: MutableList<UploadItemFailure>
    ) {
        val remoteExists = when (val listed = remoteStore.list(descriptor)) {
            is StoreResult.Success -> listed.value.any { it.id == targetId }
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
                    targetId,
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

        // データ内のIDも保存先に合わせる。永続識別子は変えず、移動・名前変更と同じ扱いにする。
        val uploadData = descriptor.deepCopy(localData).apply { id = targetId }

        // Remote Storeが現在のManagerで一時ファイルへ再保存してからアップロードする。
        when (val saved = remoteStore.save(descriptor, targetId, uploadData)) {
            is StoreResult.Success -> succeeded += key
            is StoreResult.Failure -> failed += UploadItemFailure(key, saved.error)
        }
    }
}
