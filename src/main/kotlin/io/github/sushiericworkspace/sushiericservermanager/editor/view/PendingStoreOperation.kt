package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.sushiericservermanager.editor.service.PendingStoreOperationKind
import io.github.sushiericworkspace.sushiericservermanager.editor.service.PendingStoreOperationRecord

internal sealed interface PendingStoreOperation {
    val currentId: String

    data class Create(
        override val currentId: String
    ) : PendingStoreOperation

    data class Delete(
        override val currentId: String,
        val sourceId: String
    ) : PendingStoreOperation

    data class Rename(
        val sourceId: String,
        override val currentId: String
    ) : PendingStoreOperation
}

internal fun saveChangeSummary(
    dataId: String,
    operation: PendingStoreOperation?,
    contentChanged: Boolean
): List<String> = buildList {
    when (operation) {
        is PendingStoreOperation.Create -> add("追加: ${operation.currentId}")
        is PendingStoreOperation.Delete -> add("削除: ${operation.sourceId}")
        is PendingStoreOperation.Rename -> add("ID変更: ${operation.sourceId} → ${operation.currentId}")
        null -> if (contentChanged) add("変更: $dataId")
    }
    if (contentChanged && operation != null && operation !is PendingStoreOperation.Delete) {
        add("内容変更: $dataId")
    }
}

internal fun PendingStoreOperation.toRecord(): PendingStoreOperationRecord = when (this) {
    is PendingStoreOperation.Create -> PendingStoreOperationRecord(
        kind = PendingStoreOperationKind.CREATE,
        currentId = currentId
    )
    is PendingStoreOperation.Delete -> PendingStoreOperationRecord(
        kind = PendingStoreOperationKind.DELETE,
        currentId = currentId,
        sourceId = sourceId
    )
    is PendingStoreOperation.Rename -> PendingStoreOperationRecord(
        kind = PendingStoreOperationKind.RENAME,
        currentId = currentId,
        sourceId = sourceId
    )
}

internal fun PendingStoreOperationRecord.toPendingOperation(): PendingStoreOperation? = when (kind) {
    PendingStoreOperationKind.CREATE -> PendingStoreOperation.Create(currentId)
    PendingStoreOperationKind.DELETE -> sourceId?.let { PendingStoreOperation.Delete(currentId, it) }
    PendingStoreOperationKind.RENAME -> sourceId?.let { PendingStoreOperation.Rename(it, currentId) }
}
