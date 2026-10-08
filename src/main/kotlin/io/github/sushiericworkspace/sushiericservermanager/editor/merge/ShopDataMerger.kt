package io.github.sushiericworkspace.sushiericservermanager.editor.merge

import io.github.sushiericworkspace.common.data.shop.model.mutable.MutableShopProductData

/** 商品の各項目を独立に三方向マージします。 */
object ShopDataMerger : DataMerger<MutableShopProductData> {
    override fun merge(base: MutableShopProductData, local: MutableShopProductData, remote: MutableShopProductData): ThreeWayMergeResult<MutableShopProductData> {
        val accumulator = MergeAccumulator(remote.deepCopy(), MutableShopProductData::deepCopy)
        accumulator.mergeValue(DataFieldPath.property("item-internal-id", "対象アイテム"), base.itemInternalId, local.itemInternalId, remote.itemInternalId) { data, value -> data.itemInternalId = value }
        accumulator.mergeValue(DataFieldPath.property("purchase-price", "購入価格"), base.purchasePrice, local.purchasePrice, remote.purchasePrice) { data, value -> data.purchasePrice = value }
        accumulator.mergeValue(DataFieldPath.property("sale-price", "販売価格"), base.salePrice, local.salePrice, remote.salePrice) { data, value -> data.salePrice = value }
        accumulator.mergeValue(DataFieldPath.property("stock", "在庫"), base.stock, local.stock, remote.stock) { data, value -> data.stock = value }
        accumulator.mergeValue(DataFieldPath.property("increase-stock-on-sale", "販売時の在庫追加"), base.increaseStockOnSale, local.increaseStockOnSale, remote.increaseStockOnSale) { data, value -> data.increaseStockOnSale = value }
        accumulator.mergeValue(DataFieldPath.property("display-order", "表示順"), base.displayOrder, local.displayOrder, remote.displayOrder) { data, value -> data.displayOrder = value }
        return accumulator.result()
    }
}
