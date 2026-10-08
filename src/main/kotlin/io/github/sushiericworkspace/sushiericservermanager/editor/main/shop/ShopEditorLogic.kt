package io.github.sushiericworkspace.sushiericservermanager.editor.main.shop

import io.github.sushiericworkspace.common.data.core.validation.SushiEricValidationError
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.shop.model.mutable.MutableShopProductData
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.view.ManagedDataEditorView
import io.github.sushiericworkspace.sushiericservermanager.editor.view.createPublicIdDisplay
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.MergeConflictDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import javafx.concurrent.Task
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.geometry.VPos
import javafx.scene.Node
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.TextFormatter
import javafx.scene.layout.GridPane
import javafx.scene.layout.VBox
import javafx.scene.layout.HBox
import javafx.scene.control.Button
import javafx.scene.control.Dialog
import javafx.scene.control.ButtonType
import io.github.sushiericworkspace.sushiericservermanager.editor.component.SearchableComboBox
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen

/** 商品入力と参照アイテムの検証を提供し、階層・保存・履歴は共通基盤へ委譲します。 */
internal class ShopEditorLogic(main: MainController, dataService: EditorDataService) :
    ManagedDataEditorView<MutableShopProductData>(main, dataService, dataService.shops) {
    private var itemIds = emptySet<ItemInternalId>()
    private var itemPublicIds = emptyMap<ItemInternalId, String>()
    private var catalogLoaded = false
    private var catalogRequested = false
    private var catalogFailed = false
    private var catalogLoading = false
    private var itemSelectionButton: Button? = null
    private var refreshValidation: (() -> Unit)? = null
    private val focusTargets = mutableMapOf<String, Node>()

    override fun setupSidebar(container: VBox, selectId: String?) {
        super.setupSidebar(container, selectId)
        if (!catalogRequested || catalogFailed) loadItemCatalog()
    }

    override fun setupMainContent(selectData: MutableShopProductData) {
        val warnings = VBox(5.0)
        fun refresh() {
            val messages = mutableListOf<Node>()
            if (!catalogLoaded) messages += Label(
                if (catalogFailed) "アイテム一覧を取得できませんでした。対象アイテムの存在は確認できません。"
                else "対象アイテム一覧を読み込み中..."
            ).apply { styleClass.add("warning-label"); isWrapText = true }
            validationErrors(selectData).forEach { error ->
                messages += Label(error.message).apply {
                    styleClass.add(if (error.isWarning) "warning-label" else "error-label")
                    isWrapText = true
                }
            }
            warnings.children.setAll(messages)
            refreshButtonVisual(selectData.id)
        }
        refreshValidation = ::refresh
        val itemLabel = Label().apply { style = "-fx-font-size: 16px;" }
        fun refreshItem() {
            itemLabel.text = selectData.itemInternalId?.let(::itemDisplayText) ?: "未選択"
        }
        refreshItem()
        val item = HBox(10.0, itemLabel, Button("アイテムを選択...").apply {
            itemSelectionButton = this
            isDisable = catalogLoading
            setOnAction {
                loadItemCatalog {
                    val choices = itemPublicIds.keys.sortedBy { itemPublicIds[it] }
                    val selector = SearchableComboBox(choices, selectData.itemInternalId, "公開IDで検索",
                        ::itemDisplayText, onSelected = {})
                    val selected = Dialog<ItemInternalId>().apply {
                        title = "対象アイテムを選択"
                        initOwner(main.currentStage)
                        dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)
                        dialogPane.stylesheets.add(ShopEditorLogic::class.java.getResource(AppScreen.WIDGETS_ONLY.css)!!.toExternalForm())
                        dialogPane.content = selector.apply { prefWidth = 420.0 }
                        setResultConverter { if (it == ButtonType.OK) selector.comboBox.value else null }
                    }.showAndWait().orElse(null)
                    if (selected != null) {
                        selectData.itemInternalId = selected
                        refreshItem()
                        refresh()
                    }
                }
            }
        }).apply { alignment = Pos.CENTER_LEFT }
        val refreshMessages = refreshValidation
        refreshValidation = { refreshItem(); refreshMessages?.invoke() }
        val purchase = priceField(selectData.purchasePrice) { selectData.purchasePrice = it; refresh() }
        val sale = priceField(selectData.salePrice) { selectData.salePrice = it; refresh() }
        val stock = integerField(selectData.stock, "空欄: 無限在庫") { selectData.stock = it; refresh() }
        val order = integerField(selectData.displayOrder, "表示順（空欄: 0）") { selectData.displayOrder = it ?: 0; refresh() }
        val increaseStock = CheckBox("プレイヤーの販売時に有限在庫を増やす").apply {
            isSelected = selectData.increaseStockOnSale
            selectedProperty().addListener { _, _, value -> selectData.increaseStockOnSale = value; refresh() }
        }
        focusTargets.clear()
        focusTargets.putAll(mapOf("itemInternalId" to item, "purchasePrice" to purchase, "salePrice" to sale, "stock" to stock, "displayOrder" to order))
        val grid = GridPane().apply {
            hgap = 12.0
            vgap = 12.0
            add(Label("商品ID:"), 0, 0)
            add(createPublicIdDisplay(selectData.id).apply {
                alignment = Pos.CENTER_LEFT
                children.forEach { it.style = "-fx-font-size: 16px;" }
            }, 1, 0)
            listOf("対象アイテム:" to item, "購入価格:" to purchase, "販売価格:" to sale, "在庫:" to stock, "表示順:" to order)
                .forEachIndexed { index, (label, field) ->
                    (field as? javafx.scene.layout.Region)?.prefWidth = 380.0
                    add(Label(label), 0, index + 1)
                    add(field, 1, index + 1)
                }
            add(increaseStock, 1, 6)
            children.forEach { GridPane.setValignment(it, VPos.CENTER) }
        }
        main.mainContentContainer.children.setAll(VBox(16.0, grid, warnings).apply { padding = Insets(20.0) })
        refresh()
    }

    private fun priceField(initial: Long?, changed: (Long?) -> Unit): TextField = TextField(initial?.toString().orEmpty()).apply {
        promptText = "空欄: この売買を許可しない"
        textFormatter = TextFormatter<String> { change ->
            change.takeIf { it.controlNewText.isEmpty() || it.controlNewText.toLongOrNull() != null }
        }
        textProperty().addListener { _, _, value -> changed(value.toLongOrNull()) }
    }

    private fun integerField(initial: Int?, prompt: String, changed: (Int?) -> Unit): TextField = TextField(initial?.toString().orEmpty()).apply {
        promptText = prompt
        textFormatter = TextFormatter<String> { change ->
            change.takeIf { it.controlNewText.isEmpty() || it.controlNewText.toIntOrNull() != null }
        }
        textProperty().addListener { _, _, value -> changed(value.toIntOrNull()) }
    }

    override fun availableItemInternalIds(): Set<ItemInternalId> = itemIds

    override fun itemDisplayText(id: ItemInternalId): String = itemPublicIds[id] ?: "参照先不明"

    override fun isHiddenValidationError(data: MutableShopProductData, error: SushiEricValidationError): Boolean =
        !catalogLoaded && error.property.name == "itemInternalId" && data.itemInternalId != null

    override fun focusValidationError(error: SushiEricValidationError): Boolean =
        focusTargets[error.property.name]?.let { it.requestFocus(); true } ?: false

    override fun resolveSaveConflict(dataId: String, originalData: MutableShopProductData, currentData: MutableShopProductData, serverData: MutableShopProductData): MutableShopProductData? {
        val merge = dataAccess.merge(originalData, currentData, serverData)
        if (merge.conflicts.isEmpty()) return merge.merged
        val local = MergeConflictDialog.show(main.currentStage, dataId, merge.conflicts) ?: return null
        return merge.resolveWithLocal(local)
    }

    private fun loadItemCatalog(onLoaded: (() -> Unit)? = null) {
        if (catalogLoading) return
        catalogRequested = true
        catalogLoading = true
        catalogLoaded = false
        catalogFailed = false
        itemSelectionButton?.isDisable = true
        refreshValidation?.invoke()
        val task = object : Task<Map<ItemInternalId, String>>() {
            override fun call(): Map<ItemInternalId, String> {
                val resources = when (val result = dataService.items.listStoreResources()) {
                    is StoreResult.Success -> result.value
                    is StoreResult.Failure -> error("アイテム一覧を読み込めません: ${result.error.code}")
                }
                return resources.associate { resource ->
                    when (val result = dataService.items.loadStore(resource.id)) {
                        is StoreResult.Success -> result.value.internalId to result.value.id
                        is StoreResult.Failure -> error("アイテムを読み込めません: ${resource.id}")
                    }
                }
            }
        }
        task.setOnSucceeded {
            catalogLoading = false
            itemSelectionButton?.isDisable = false
            itemPublicIds = task.value
            itemIds = itemPublicIds.keys
            catalogLoaded = true
            sidebarButtons.keys.forEach(::refreshButtonVisual)
            refreshValidation?.invoke()
            onLoaded?.invoke()
        }
        task.setOnFailed {
            catalogLoading = false
            itemSelectionButton?.isDisable = false
            catalogFailed = true
            logger.warn("ショップの対象アイテム一覧を取得できませんでした。", task.exception)
            refreshValidation?.invoke()
            CustomDialog.error()
                .title("アイテム一覧の読込エラー")
                .header("対象アイテムを選択できません")
                .content("アイテム一覧を読み込めませんでした。接続状態とデータを確認してください。")
                .owner(main.currentStage)
                .show()
        }
        Thread(task, "shop-editor-item-catalog").apply { isDaemon = true; start() }
    }
}
