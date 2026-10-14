package io.github.sushiericworkspace.sushiericservermanager.editor.service

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResource

/** 全同期で取得した編集データと、サイドバー表示に使うストア一覧です。 */
data class EditorSyncSnapshot<T>(
    val resources: List<StoreResource>,
    val directories: List<String>,
    val data: Map<String, T>
)

class EditorSyncService<T : ManagedData<T, *>>(
    private val dataAccess: EditorDataService.DataAccess<T>
) {
    fun fetchOne(id: String): StoreResult<T> = dataAccess.loadStore(id)

    /**
     * データ一覧、ディレクトリ、各データを順に取得します。
     * [onProgress]はI/O実行スレッドから、一覧確定時と各データ取得成功後に呼び出します。
     */
    fun fetchAll(onProgress: (completed: Int, total: Int, id: String?) -> Unit = { _, _, _ -> }): StoreResult<EditorSyncSnapshot<T>> {
        val resources = when (val listed = dataAccess.listStoreResources()) {
            is StoreResult.Success -> listed.value
            is StoreResult.Failure -> return listed
        }
        val directories = when (val listed = dataAccess.listDirectories()) {
            is StoreResult.Success -> listed.value
            is StoreResult.Failure -> return listed
        }
        onProgress(0, resources.size, null)
        val loaded = linkedMapOf<String, T>()
        resources.forEach { resource ->
            when (val result = dataAccess.loadStore(resource.id)) {
                is StoreResult.Success -> {
                    loaded[resource.id] = result.value
                    onProgress(loaded.size, resources.size, resource.id)
                }
                is StoreResult.Failure -> return result
            }
        }
        return StoreResult.Success(EditorSyncSnapshot(resources, directories, loaded))
    }
}
