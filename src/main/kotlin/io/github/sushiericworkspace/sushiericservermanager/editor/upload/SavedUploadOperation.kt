package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentKey
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentSource
import io.github.sushiericworkspace.sushiericservermanager.editor.service.PendingStoreOperationRecord
import io.github.sushiericworkspace.sushiericservermanager.editor.service.documentHash
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable

/** 確認時の元savedです。コピーだけ変換契約のhashと、その要求で変換した比較元も固定します。編集中の値は含めません。 */
@Serializable
internal data class SavedUploadDocument(
    val key: EditorDocumentKey,
    val revision: Long,
    val saved: String?,
    val deleted: Boolean,
    val raw: Map<String, String>,
    val pending: PendingStoreOperationRecord? = null,
    val projectionHash: String? = null,
    val projectedSaved: String? = null
)

/** 一つの通信作用と読戻しを追跡します。WRITINGのまま再起動した工程は再送せず照合します。 */
@Serializable
internal data class SavedUploadStep(
    val key: EditorDocumentKey,
    val before: String?,
    val after: String?,
    val destination: EditorDocumentKey? = null,
    val state: SavedUploadState = SavedUploadState.READY,
    val detail: String? = null
)

@Serializable
internal enum class SavedUploadState {
    READY,
    WRITING,
    CONFIRMED,
    FAILED,
    UNKNOWN,
    CANCELLED;

    val displayName: String get() = when (this) {
        CONFIRMED -> "反映確認済み"
        UNKNOWN, WRITING -> "結果不明（読戻しが必要）"
        FAILED -> "未反映"
        CANCELLED -> "未実行・取消"
        READY -> "未実行"
    }
}

@Serializable
internal data class SavedUploadTargetVersion(val key: EditorDocumentKey, val revision: Long)

/**
 * ローカル保存済み世代から作った、宛先に束縛された操作台帳です。
 * revisionは台帳の観測版であり、サーバーの比較書込や実worldの同一性を証明しません。
 */
@Serializable
internal data class SavedUploadOperation(
    val id: String,
    val target: EditorDocumentSource.Server,
    val inputs: List<SavedUploadDocument>,
    val targetRevisions: List<SavedUploadTargetVersion>,
    val steps: List<SavedUploadStep>,
    val revision: Long = 0
) {
    /** 通信先と予約する文書が一意に対応しているかを確認します。 */
    val validKeys: Boolean get() =
        inputs.map { it.key }.toSet().size == inputs.size &&
            inputs.all { input ->
                if (input.projectionHash == null) input.projectedSaved == null
                else input.key.source == EditorDocumentSource.Local && !input.deleted && input.saved != null &&
                    input.raw.isEmpty() && input.projectedSaved != null && input.projectionHash.matches(Regex("[0-9a-f]{64}"))
            } &&
            targetRevisions.map { it.key }.toSet().size == targetRevisions.size &&
            targetRevisions.all { it.key.source == target } &&
            steps.map { it.key }.toSet().size == steps.size &&
            steps.all { step ->
                val keys = listOfNotNull(step.key, step.destination)
                keys.all { key -> key.source == target && targetRevisions.any { it.key == key } } &&
                    (step.destination == null || (step.destination != step.key &&
                        step.destination.category == step.key.category && step.key.category != "text" &&
                        step.before != null && step.after != null))
            }

    val needsReadback: Boolean get() = steps.any {
        it.state == SavedUploadState.WRITING || it.state == SavedUploadState.UNKNOWN
    }

    val complete: Boolean get() = steps.all { it.state == SavedUploadState.CONFIRMED }
}

/** pending解除や文書revisionの前進を反映済み判定へ混ぜず、削除は本文/rawを送らない状態として正規化します。 */
internal fun savedUploadFingerprint(
    key: EditorDocumentKey, saved: String?, deleted: Boolean, raw: Map<String, String>
): String = documentHash(Json.encodeToString(SavedUploadDocument(
    key, 0, saved.takeUnless { deleted }, deleted, if (deleted) emptyMap() else raw.toSortedMap())))
