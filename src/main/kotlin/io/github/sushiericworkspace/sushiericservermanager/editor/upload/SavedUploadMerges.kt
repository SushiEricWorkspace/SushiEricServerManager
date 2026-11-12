package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.ore.model.mutable.MutableOreBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.DataConflict
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.DataFieldPath
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService

/**
 * 確認画面の選択から送信候補を作る。通信先へは作用しない。
 * previewWithLocalはdecode済みmergeのコピーだけを返し、encodeやファイルI/Oを行わない。
 */
internal class SavedUploadMerge(
    val conflicts: List<DataConflict>,
    val previewWithLocal: ((Set<DataFieldPath>) -> ManagedData<*, *>?)? = null,
    private val resolve: (Set<DataFieldPath>) -> String?
) {
    fun resolveWithLocal(paths: Set<DataFieldPath>): String? = resolve(paths)
}

internal object SavedUploadMerges {
    private val document = DataFieldPath.property("document", "文書全体")

    /**
     * 比較元は取得基準、または宛先別の前回成功時に固定したorigin savedです。
     * 後者により、変更していない遠隔由来フィールドを次回アップロードで差し戻しません。
     * encode/decodeはファイルI/Oを含むため、接続のI/Oキューから呼び出してください。
     */
    fun <T : ManagedData<T, *>> typed(
        access: EditorDataService.DataAccess<T>, id: String,
        base: String?, local: String?, latest: String?, baselineKnown: Boolean
    ): SavedUploadMerge {
        if (base == null || local == null || latest == null || !baselineKnown)
            return text(base, local, latest, baselineKnown)
        val before = access.decodeDocument(id, base)
        val saved = access.decodeDocument(id, local)
        val remote = access.decodeDocument(id, latest)
        if (identity(before) != identity(saved) || identity(before) != identity(remote))
            return whole(base, local, latest)
        val merged = access.merge(before, saved, remote)
        return SavedUploadMerge(merged.conflicts, { paths -> merged.resolveWithLocal(paths) }) { paths ->
            access.encodeDocument(merged.resolveWithLocal(paths))
        }
    }

    /** 設定の両側変更や存在/削除の競合は、本文全体を明示選択します。 */
    fun text(base: String?, local: String?, latest: String?, baselineKnown: Boolean): SavedUploadMerge {
        if (local == latest) return SavedUploadMerge(emptyList()) { local }
        if (baselineKnown && local == base) return SavedUploadMerge(emptyList()) { latest }
        if (baselineKnown && latest == base) return SavedUploadMerge(emptyList()) { local }
        if (!baselineKnown && latest == null && local != null)
            return SavedUploadMerge(emptyList()) { local }
        return whole(base, local, latest)
    }

    private fun whole(base: String?, local: String?, latest: String?) = SavedUploadMerge(
        listOf(DataConflict(document, "文書全体", base, local, latest))
    ) { paths -> if (document in paths) local else latest }

    private fun identity(data: ManagedData<*, *>): String? = when (data) {
        is MutableItemBaseData -> data.internalId.value
        is MutableOreBaseData -> data.internalId.value
        else -> null
    }
}
