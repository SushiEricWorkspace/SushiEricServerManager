package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.common.data.core.ManagedData

/** 保存前の取り込みデータと、競合確認の基準になるサーバーの読み込み時点のデータです。 */
data class LocalEditImport<T : ManagedData<T, *>>(
    val key: UploadKey,
    val data: T,
    val original: T?
)

/** サーバーへ書き込まず、選択したローカルデータを読み込んだ結果です。 */
data class LocalEditImportResult<T : ManagedData<T, *>>(
    val entries: List<LocalEditImport<T>>,
    val failed: List<UploadItemFailure>
)
