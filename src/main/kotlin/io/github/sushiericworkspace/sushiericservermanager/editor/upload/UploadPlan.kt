package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.common.data.core.identity.PublicId
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode

/**
 * ローカルのIDを、宛先ディレクトリの下に置いたときのサーバー上のIDへ変換します。
 *
 * ローカルのディレクトリは使わず、IDの末尾の名前だけを宛先の直下へ置きます。
 * 宛先が空の場合は、ルート直下の名前だけのIDになります。
 *
 * 例: ローカル`weapons.sword`、宛先`event.weapons`の場合は`event.weapons.sword`。
 *
 * @param localId ローカルのデータの完全ID。
 * @param destination 宛先ディレクトリの完全ID。空の場合はルート。
 */
fun uploadTargetId(localId: String, destination: String): String =
    PublicId.join(destination.split('.').filter(String::isNotEmpty), PublicId.nameOf(localId))

/** 宛先ディレクトリとして使える指定かどうかを返します。空はルートを表し、有効です。 */
fun isValidUploadDestination(destination: String): Boolean =
    destination.isEmpty() || PublicId.isValidFull(destination)

/**
 * 走査結果と宛先から、アップロード候補の状態を決めます。
 *
 * サーバーへの通信は行わないため、宛先を変えるたびに呼び出せます。
 * 候補は種別、ローカルIDの順に並びます。次の場合は選択できない候補になります。
 * - 宛先の指定が不正
 * - ローカルのデータを読めない
 * - サーバー上のIDとして不正になる
 * - 別のローカルデータと同じサーバー上のIDになる（いずれも選択不可にします）
 */
fun planUpload(scan: UploadScanResult.Success, destination: String): List<OfflineUploadCandidate> {
    val destinationError = if (isValidUploadDestination(destination)) {
        null
    } else {
        StoreError(StoreErrorCode.INVALID_ID, destination, PublicId.DESCRIPTION)
    }
    val targetCounts = scan.entries
        .groupingBy { it.key.category to uploadTargetId(it.key.id, destination) }
        .eachCount()

    return scan.entries.map { entry ->
        val targetId = uploadTargetId(entry.key.id, destination)
        val descriptor = EditorDataDescriptors.all.first { it.dataType == entry.key.category.dataType }
        val error = destinationError
            ?: entry.loadError
            ?: if (!descriptor.isValidId(targetId)) {
                StoreError(StoreErrorCode.INVALID_ID, targetId, "宛先を含めたIDが使えない形式です。")
            } else if ((targetCounts[entry.key.category to targetId] ?: 0) > 1) {
                StoreError(StoreErrorCode.ALREADY_EXISTS, targetId, "同じ宛先IDになる別のデータがあります。")
            } else {
                null
            }
        OfflineUploadCandidate(
            key = entry.key,
            targetId = targetId,
            state = when {
                error != null -> UploadCandidateState.UNAVAILABLE
                targetId in scan.remoteIds.getValue(entry.key.category) -> UploadCandidateState.OVERWRITE
                else -> UploadCandidateState.NEW
            },
            requiresFormatUpdate = entry.requiresFormatUpdate,
            error = error
        )
    }.sortedWith(compareBy({ it.key.category.ordinal }, { it.key.id }))
}
