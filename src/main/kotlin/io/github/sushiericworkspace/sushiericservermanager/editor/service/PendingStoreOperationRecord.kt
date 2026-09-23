package io.github.sushiericworkspace.sushiericservermanager.editor.service

import kotlinx.serialization.Serializable

/** 自動保存領域へ永続化する、保存待ちのストア操作です。 */
@Serializable
internal data class PendingStoreOperationRecord(
    val kind: PendingStoreOperationKind,
    val currentId: String,
    val sourceId: String? = null
)

/** 保存待ち操作の種類です。 */
@Serializable
internal enum class PendingStoreOperationKind {
    CREATE,
    DELETE,
    RENAME
}
