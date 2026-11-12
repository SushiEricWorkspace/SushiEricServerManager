package io.github.sushiericworkspace.sushiericservermanager.editor.shop

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.shop.model.ShopProductStock
import io.github.sushiericworkspace.sushiericdatamodel.data.shop.model.mutable.MutableShopProductData
import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*

/** 接続のI/Oキューで使用し、画面確認に使った最新の定義hashを保存要求まで保持します。 */
class ShopEditorStore(private val session: ShopSaveSession, private val writable: () -> Boolean = { true }) {
    private val snapshots = mutableMapOf<String, HostProductRead>()

    internal fun prepareUpload(edit: HostAdapterAction.Edit): HostAdapterRequest {
        check(writable()) { "この接続は読取専用です。" }
        return session.prepare(edit)
    }

    internal fun upload(request: HostAdapterRequest, reconcile: Boolean = false): StoreResult<Unit> = try {
        check(writable()) { "この接続は読取専用です。" }
        val response = if (reconcile) session.reconcile(request) else session.execute(request)
        if (response.status == HostAdapterStatus.SUCCESS && response.result is HostAdapterResult.Edited)
            StoreResult.Success(Unit)
        else failure(request.requestId, response)
    } catch (error: Exception) {
        StoreResult.Failure(StoreError(StoreErrorCode.PENDING_REVIEW, request.requestId, error.message, error))
    }

    fun load(id: String): StoreResult<HostProductRead> = try {
        val response = session.read(listOf(id))
        val products = response.result as? HostAdapterResult.Products
        if (response.status != HostAdapterStatus.SUCCESS || products == null) failure(id, response)
        else {
            val product = products.products.single { it.id == id }
            snapshots[id] = product
            StoreResult.Success(product)
        }
    } catch (failure: Exception) {
        StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, id, cause = failure))
    }

    /** 同じI/O工程で読んだ不変snapshotを保存確認へ渡します。 */
    fun snapshot(id: String): HostProductRead? = snapshots[id]

    /** 新規登録と複製は編集値を初期在庫として明示します。既存商品には読取baselineが必須です。 */
    fun save(data: MutableShopProductData, baseline: HostProductRead?): StoreResult<Unit> {
        return edit(data.id) {
            requireValid(data)
            val change = if (baseline == null) ShopSavePlan.register(data, null)
            else {
                requireBaseline(data, baseline)
                ShopSavePlan.save(baseline, data)
            }
            HostAdapterAction.Edit(listOf(change))
        }
    }

    fun delete(baseline: HostProductRead): StoreResult<Unit> = edit(baseline.id) {
        HostAdapterAction.Edit(listOf(ShopSavePlan.delete(baseline)))
    }

    /** 改名後の保存失敗をSFTPの逆改名で補償せず、Save+DeleteをD3の一つの親要求にします。 */
    fun relocate(baseline: HostProductRead, data: MutableShopProductData): StoreResult<Unit> = edit(baseline.id) {
        requireValid(data)
        requireBaseline(data, baseline)
        ShopSavePlan.relocate(baseline, data, null)
    }

    fun move(baseline: HostProductRead, targetId: String): StoreResult<Unit> = edit(baseline.id) {
        HostAdapterAction.Edit(listOf(ShopSavePlan.move(baseline, targetId, null)))
    }

    /** UIで元要求を確認してから呼びます。新IDの再試行には変換しません。 */
    fun reconcile(): HostAdapterResponse {
        check(writable()) { "この接続は読取専用です。" }
        return session.reconcile()
    }

    private fun requireValid(data: MutableShopProductData) {
        require(EditorDataDescriptors.shop.isValidId(data.id)) { "商品IDが不正です。" }
        require(EditorDataDescriptors.shop.validate(data, emptySet()).none { it.isError }) { "商品入力を確認してください。" }
    }

    private fun requireBaseline(data: MutableShopProductData, baseline: HostProductRead) {
        val expected = baseline.stock?.let { ShopProductStock.Current(it.quantity, it.revision) }
            ?: ShopProductStock.Uninitialized
        require(data.stockBaseline == expected) { "在庫の読取基準が変わりました。再読込して競合を確認してください。" }
    }

    private fun edit(id: String, plan: () -> HostAdapterAction.Edit): StoreResult<Unit> = try {
        check(writable()) { "この接続は読取専用です。" }
        val edit = plan()
        val response = session.execute(edit)
        if (response.status == HostAdapterStatus.SUCCESS && response.result is HostAdapterResult.Edited) {
            edit.products.forEach { change ->
                when (change) {
                    is HostProductChange.Save -> snapshots.remove(change.id)
                    is HostProductChange.Delete -> snapshots.remove(change.id)
                    is HostProductChange.Move -> { snapshots.remove(change.sourceId); snapshots.remove(change.targetId) }
                    is HostProductChange.Copy -> snapshots.remove(change.targetId)
                }
            }
            StoreResult.Success(Unit)
        } else failure(id, response)
    } catch (failure: Exception) {
        StoreResult.Failure(StoreError(
            if (session.pending() != null) StoreErrorCode.PENDING_REVIEW else StoreErrorCode.CONFLICT,
            id, failure.message, failure))
    }

    private fun failure(id: String, response: HostAdapterResponse): StoreResult.Failure =
        StoreResult.Failure(StoreError(
            if (response.status == HostAdapterStatus.REJECTED && response.rejectedBeforeEffect)
                StoreErrorCode.CONFLICT else StoreErrorCode.PENDING_REVIEW,
            id, response.message ?: response.code))

    companion object {
        fun mutable(product: HostProductRead): MutableShopProductData = MutableShopProductData(
            product.id, ItemInternalId(product.definition.itemInternalId), product.definition.purchasePrice,
            product.definition.salePrice, product.stock?.quantity, product.definition.increaseStockOnSale,
            product.definition.displayOrder,
            stockBaseline = product.stock?.let { ShopProductStock.Current(it.quantity, it.revision) }
                ?: ShopProductStock.Uninitialized
        )
    }
}
