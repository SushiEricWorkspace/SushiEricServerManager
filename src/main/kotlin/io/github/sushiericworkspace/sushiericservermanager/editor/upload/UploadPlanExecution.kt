package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult

/** 最終確認後、接続のI/Oキュー内だけで可変予約を固定台帳へ結び付けます。 */
internal class UploadPlanExecution(private val target: EditorDataService) {
    private val plans = UploadPlanService(target)
    private val origin = target.localService

    fun execute(
        plan: UploadPlan, groupId: String, operation: SavedUploadOperation,
        cancelled: () -> Boolean, progress: (SavedUploadOperation) -> Unit
    ): StoreResult<SavedUploadOperation> {
        if (cancelled()) return failure("実行前に取り消しました。予約は変更していません。")
        if (!target.sourceStore.isAvailable || !target.sourceStore.isWritable)
            return failure("未接続または読取専用です。予約を保持しています。")
        val group = plan.groups.single { it.id == groupId }
        if (group.operationId != null || operation.revision != 0L || operation.target != plan.target || !operation.validKeys ||
            operation.inputs.any { input -> input.deleted || input.raw.isNotEmpty() || group.documents.none { document ->
                document.key == input.key && document.savedHash == savedUploadFingerprint(input.key, input.saved, input.deleted, input.raw)
            } } ||
            operation.steps.any { it.destination != null || group.documents.none { document ->
                it.key == document.key.copy(source = plan.target, id = document.targetId)
            } } ||
            operation.inputs.map { it.key }.toSet() != group.documents.map { it.key }.toSet() ||
            operation.steps.any { it.state != SavedUploadState.READY })
            return failure("確認した予約と実行要求が一致しません。")
        when (val current = plans.read()) {
            is StoreResult.Failure -> return current
            is StoreResult.Success -> if (current.value != plan) return failure("送信予約が更新されています。")
        }
        when (val selections = plans.selections(group)) {
            is StoreResult.Failure -> return selections
            is StoreResult.Success -> Unit
        }
        val journal = target.documents.publish(emptyMap(), emptyList(), upload = operation)
        if (journal is StoreResult.Failure) return journal
        val fixed = (journal as StoreResult.Success).value.uploads.single { it.id == operation.id }
        val bound = plans.save(plan.copy(groups = plan.groups.map {
            if (it.id == groupId) it.copy(operationId = fixed.id) else it
        }))
        if (bound is StoreResult.Failure) {
            stopUnstarted(fixed.id)
            return bound
        }
        val result = SavedUploadExecutor(origin, target).execute(fixed, cancelled, progress)
        // Executorが戻り、同じキューで未作用のREADYだけが残る場合は停止を台帳へ確定します。
        if (result is StoreResult.Failure) stopUnstarted(fixed.id)
        return result
    }

    /** 予約に結び付いた保存済み要求だけを読み、別要求の作成や再送は行いません。 */
    fun readback(group: UploadPlanGroup): StoreResult<SavedUploadOperation> {
        val loaded = target.documents.read()
        if (loaded is StoreResult.Failure) return loaded
        val operation = (loaded as StoreResult.Success).value.uploads.singleOrNull { it.id == group.operationId }
            ?: return failure("予約の固定要求を読み込めません。予約を保持しています。")
        return SavedUploadExecutor(origin, target).readback(operation)
    }

    private fun stopUnstarted(id: String) {
        val loaded = target.documents.read()
        if (loaded !is StoreResult.Success) return
        val operation = loaded.value.uploads.singleOrNull { it.id == id } ?: return
        if (operation.steps.all { it.state == SavedUploadState.READY }) {
            target.documents.publish(emptyMap(), emptyList(), upload = operation.copy(steps = operation.steps.map {
                it.copy(state = SavedUploadState.CANCELLED, detail = "通信作用前に実行が終了しました。")
            }), expectedUploadRevision = operation.revision)
        }
    }

    private fun failure(detail: String): StoreResult.Failure =
        StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, detail = detail))
}
