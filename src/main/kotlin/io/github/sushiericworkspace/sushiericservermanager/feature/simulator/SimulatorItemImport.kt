package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javafx.application.Platform
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.LocalEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import java.io.File
import java.util.UUID

/** 明示取得した一版です。revisionは取得単位の識別子で、保存先の更新番号ではありません。 */
internal class SimulatorImportedItem(item: ItemBaseData, val source: String, val revision: String = UUID.randomUUID().toString()) {
    private val fixed = SimulatorEquippedItem(item)
    val item: ItemBaseData get() = fixed.baseData

    companion object {
        fun fromEdited(item: MutableItemBaseData): SimulatorImportedItem =
            SimulatorImportedItem(item.deepCopy().freeze(), "編集中（未保存）: ${item.id}")
    }
}

/** 指定ローカルrootのtyped読込だけを行います。接続中のRemote/storeには触れません。 */
internal fun loadSimulatorItem(root: File, id: String): StoreResult<SimulatorImportedItem> =
    when (val loaded = LocalEditorDataStore(root).load(EditorDataDescriptors.item, id)) {
        is StoreResult.Failure -> loaded
        is StoreResult.Success -> {
            val fixed = loaded.value.deepCopy().freeze()
            val errors = fixed.validate().filter { it.isError }
            if (errors.isNotEmpty()) StoreResult.Failure(StoreError(StoreErrorCode.VALIDATION_FAILED, id, errors.joinToString { it.message }))
            else StoreResult.Success(SimulatorImportedItem(fixed, "保存済み: ${root.absolutePath} / $id"))
        }
    }

/** 読み込み世代と計算世代が一致した通知だけをFXへ反映します。取得失敗は入力を置換しません。 */
internal class SimulatorItemLoader(
    private val isCurrent: () -> Boolean,
    private val calculationGeneration: () -> Long,
    private val onLoaded: (StoreResult<SimulatorImportedItem>) -> Unit,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { task -> Thread(task, "simulator-item-read").apply { isDaemon = true } },
    private val dispatch: (() -> Unit) -> Unit = { Platform.runLater(it) },
    private val load: (File, String) -> StoreResult<SimulatorImportedItem> = ::loadSimulatorItem
) {
    private var generation = 0L
    private var disposed = false
    fun read(root: File, id: String) {
        check(!disposed && isCurrent()) { "終了済みの画面・接続では読み込めません。" }
        val current = ++generation
        val calculation = calculationGeneration()
        executor.execute {
            val result = try { load(root, id) } catch (error: Exception) {
                StoreResult.Failure(StoreError(
                    StoreErrorCode.IO_ERROR, id, error.message, error))
            }
            dispatch { if (!disposed && isCurrent() && generation == current && calculationGeneration() == calculation) onLoaded(result) }
        }
    }
    fun invalidate() { generation++ }
    fun dispose() { disposed = true; generation++; executor.shutdownNow() }
}
