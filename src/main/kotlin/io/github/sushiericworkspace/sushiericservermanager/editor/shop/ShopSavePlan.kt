package io.github.sushiericworkspace.sushiericservermanager.editor.shop

import io.github.sushiericworkspace.sushiericdatamodel.data.shop.model.mutable.MutableShopProductData
import io.github.sushiericworkspace.sushiericdatamodel.database.host.*

/** D3へ渡す定義hashと在庫revisionを、保存確認で使用した読取結果に固定します。 */
object ShopSavePlan {
    fun definition(data: MutableShopProductData) = HostDefinition(
        requireNotNull(data.itemInternalId).value, data.purchasePrice, data.salePrice,
        data.increaseStockOnSale, data.displayOrder
    )

    /** 未変更在庫はSQL更新しません。未初期化の場合は登録値を明示します。 */
    fun save(baseline: HostProductRead, data: MutableShopProductData): HostProductChange.Save {
        require(baseline.id == data.id)
        val stock = baseline.stock
        return HostProductChange.Save(data.id, baseline.definitionSha256, definition(data),
            when {
                stock == null -> HostStockEdit.Register(data.stock, null)
                data.stock == stock.quantity -> HostStockEdit.Unchanged
                else -> HostStockEdit.Set(data.stock, stock.revision)
            })
    }

    fun register(data: MutableShopProductData, revision: Long?) =
        HostProductChange.Save(data.id, null, definition(data), HostStockEdit.Register(data.stock, revision))

    fun delete(baseline: HostProductRead) =
        HostProductChange.Delete(baseline.id, baseline.definitionSha256, baseline.stock?.revision)

    fun move(baseline: HostProductRead, targetId: String, targetRevision: Long?) =
        HostProductChange.Move(baseline.id, targetId, baseline.definitionSha256,
            requireNotNull(baseline.stock).revision, targetRevision)

    fun copy(baseline: HostProductRead, targetId: String, quantity: Int?, targetRevision: Long?) =
        HostProductChange.Copy(baseline.id, targetId, baseline.definitionSha256, quantity, targetRevision)

    /** IDと定義を同時変更する場合も親要求を分割せず、source revisionを削除で照合します。 */
    fun relocate(baseline: HostProductRead, data: MutableShopProductData, targetRevision: Long?) =
        HostAdapterAction.Edit(listOf(register(data, targetRevision), delete(baseline)))
}
