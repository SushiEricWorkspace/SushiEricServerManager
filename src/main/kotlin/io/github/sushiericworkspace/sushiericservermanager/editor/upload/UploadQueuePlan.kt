package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.PublicId
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentKey
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentSource
import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentRecord
import kotlinx.serialization.Serializable

/** 選択したフォルダーはその直下を相対rootとし、単一文書は親を相対rootにします。 */
internal data class UploadPlanRoot(val key: EditorDocumentKey, val directory: Boolean = false)

/** 予約時に確認したsavedの版です。本文や編集中データは予約へ複製しません。 */
@Serializable
internal data class UploadPlanDocument(
    val key: EditorDocumentKey,
    val targetId: String,
    val savedRevision: Long,
    val savedHash: String
)

/** 一度のdropを単位とする割当てです。内部ID対応は確認や復元のたびに再生成しません。 */
@Serializable
internal data class UploadPlanGroup(
    val id: String,
    val targetDirectory: String,
    val documents: List<UploadPlanDocument>,
    val itemIds: Map<String, String> = emptyMap(),
    val oreIds: Map<String, String> = emptyMap(),
    val operationId: String? = null
)

/** 可変の送信予約です。実行要求の台帳とは分離し、復元しても作用を開始しません。 */
@Serializable
internal data class UploadPlan(
    val target: EditorDocumentSource.Server,
    val revision: Long = 0,
    val groups: List<UploadPlanGroup> = emptyList(),
    val version: Int = 1
) {
    fun add(group: UploadPlanGroup): UploadPlan {
        require(groups.none { it.id == group.id }) { "同じ割当てが既にあります。" }
        val existing = groups.flatMap { it.documents }.map { it.key.category to it.targetId }.toSet()
        require(group.documents.none { (it.key.category to it.targetId) in existing }) {
            "別の割当てと宛先の種別・IDが重複しています。"
        }
        return copy(groups = (groups + group).map { it.snapshot() })
    }

    fun remove(id: String): UploadPlan {
        require(groups.first { it.id == id }.operationId == null) { "実行要求に結び付いた割当ては結果を確認してください。" }
        return copy(groups = groups.filterNot { it.id == id }.map { it.snapshot() })
    }

    fun move(id: String, destination: Int): UploadPlan {
        val current = groups.first { it.id == id }
        return copy(groups = groups.toMutableList().apply {
            remove(current)
            add(destination.coerceIn(0, size), current)
        }.map { it.snapshot() })
    }
}

/** 末尾名へ平坦化せず、選択rootからの相対構造を宛先以下へ保ちます。 */
internal fun relativeUploadTargetId(id: String, root: String, destination: String): String {
    require(root.isEmpty() || id.startsWith(root + ".")) { "文書が選択rootの下にありません。" }
    require(isValidUploadDestination(destination)) { "宛先ディレクトリが不正です。" }
    val relative = if (root.isEmpty()) id else id.removePrefix(root + ".")
    require(PublicId.isValidFull(relative)) { "相対IDが不正です。" }
    return listOf(destination, relative).filter(String::isNotEmpty).joinToString(".")
}

/** sourceを含む重複選択を正規化し、dirtyを黙ってsavedとして予約しません。 */
internal fun uploadPlanDocuments(
    roots: List<UploadPlanRoot>,
    records: List<LocalDocumentRecord>,
    destination: String
): List<UploadPlanDocument> {
    require(roots.isNotEmpty()) { "文書またはフォルダーを選択してください。" }
    require(roots.all { it.key.source == EditorDocumentSource.Local }) { "ローカル文書から予約してください。" }
    val normalized = roots.distinct().filter { root ->
        roots.none { parent -> parent.directory && parent.key.category == root.key.category &&
            parent.key.source == root.key.source && parent.key.id != root.key.id &&
            (parent.key.id.isEmpty() || root.key.id.startsWith(parent.key.id + ".")) }
    }
    val selected = linkedMapOf<EditorDocumentKey, UploadPlanDocument>()
    for (root in normalized) {
        val found = records.filter { record ->
            record.key.source == root.key.source && record.key.category == root.key.category &&
                if (root.directory) root.key.id.isEmpty() || record.key.id.startsWith(root.key.id + ".")
                else record.key == root.key
        }.filterNot { it.deleted || it.movedTo != null }
        require(root.directory || found.size == 1) { "選択した文書が見つかりません。" }
        for (record in found) {
            require(!record.unsaved) { "先にローカル保存が必要です: " + record.key.id }
            require(record.saved != null && !record.savedDeleted) { "保存済み本文がありません: " + record.key.id }
            require(record.savedRaw.isEmpty()) { "未確定入力を含むため予約できません: " + record.key.id }
            val relativeRoot = if (root.directory) root.key.id else PublicId.directoryOf(record.key.id).joinToString(".")
            selected[record.key] = UploadPlanDocument(record.key,
                relativeUploadTargetId(record.key.id, relativeRoot, destination), record.revision,
                savedUploadFingerprint(record.key, record.saved, false, record.savedRaw))
        }
    }
    require(selected.isNotEmpty()) { "選択したフォルダーに保存済み文書がありません。" }
    require(selected.values.map { it.key.category to it.targetId }.toSet().size == selected.size) {
        "選択した文書が同じ宛先IDになります。"
    }
    return selected.values.toList()
}

internal fun UploadPlanGroup.snapshot(): UploadPlanGroup =
    copy(documents = documents.toList(), itemIds = itemIds.toMap(), oreIds = oreIds.toMap())

internal fun UploadPlan.snapshot(): UploadPlan = copy(groups = groups.map { it.snapshot() })
