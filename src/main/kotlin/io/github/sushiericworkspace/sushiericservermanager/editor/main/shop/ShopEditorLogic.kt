package io.github.sushiericworkspace.sushiericservermanager.editor.main.shop

import io.github.sushiericworkspace.sushiericdatamodel.data.core.validation.SushiEricValidationError
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.shop.model.mutable.MutableShopProductData
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.view.ManagedDataEditorView
import io.github.sushiericworkspace.sushiericservermanager.editor.view.createPublicIdDisplay
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.MergeConflictDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.editor.service.ItemReferenceStatus
import io.github.sushiericworkspace.sushiericservermanager.editor.component.ItemReferenceBinding
import io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.PublicId
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.util.Duration
import javafx.scene.control.ListView
import javafx.scene.control.ListCell
import javafx.scene.input.ClipboardContent
import javafx.scene.input.TransferMode
import javafx.scene.input.KeyCode
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.geometry.VPos
import javafx.scene.Node
import javafx.scene.control.CheckBox
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.VBox
import javafx.scene.layout.HBox
import javafx.scene.control.Button
import javafx.scene.control.Dialog
import javafx.scene.control.ButtonType
import io.github.sushiericworkspace.sushiericservermanager.editor.component.SearchableComboBox
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen

/** 商品入力と参照アイテムの検証を提供し、階層・保存・履歴は共通基盤へ委譲します。 */
internal class ShopEditorLogic(main: MainController, service: EditorDataService) :
    ManagedDataEditorView<MutableShopProductData>(main, service, service.shops) {
    private val itemIds get() = dataService.itemReferences.snapshot.byInternalId.keys
    private val catalogLoaded get() = dataService.itemReferences.snapshot.status == ItemReferenceStatus.READY
    private val catalogFailed get() = dataService.itemReferences.snapshot.status == ItemReferenceStatus.FAILED
    private val catalogLoading get() = dataService.itemReferences.snapshot.status == ItemReferenceStatus.LOADING
    private var itemSelectionButton: Button? = null
    private var refreshValidation: (() -> Unit)? = null
    private val focusTargets = mutableMapOf<String, Node>()

    private val orderHistory = ShopOrderHistory()
    private val orderRaw = mutableMapOf<String, String>()
    private var orderList: ListView<String>? = null
    private var orderOwner: String? = null
    private var orderScroll: Timeline? = null

    private var itemBinding = createItemBinding()

    private fun createItemBinding() = ItemReferenceBinding(dataService.itemReferences, {
        itemSelectionButton?.isDisable = catalogLoading
        registeredSidebarIds.forEach(::refreshSidebarState)
        refreshValidation?.invoke()
    }, { error ->
        logger.warn("ショップの対象アイテム一覧を取得できませんでした。", error.cause)
        CustomDialog.error().title("アイテム一覧の読込エラー")
            .header("対象アイテムを選択できません")
            .content("アイテム一覧を読み込めませんでした。接続状態とデータを確認してください。")
            .owner(main.currentStage).show()
    })

    override fun onSourceChanged() {
        orderHistory.clear()
        orderRaw.clear()
        orderScroll?.stop()
        orderList = null
        orderOwner = null
        itemBinding.close()
        itemBinding = createItemBinding()
        refreshValidation = null
        itemSelectionButton = null
    }

    override fun loadEditorReferences() {
        itemBinding.load()
    }

    override fun setupMainContent(selectData: MutableShopProductData) {
        val warnings = VBox(5.0)
        val refreshItems = Button("参照アイテムを更新").apply {
            setOnAction { itemBinding.load(force = true) }
        }
        fun refresh() {
            refreshItems.isDisable = catalogLoading
            val messages = mutableListOf<Node>()
            if (!catalogLoaded) messages += Label(
                if (catalogFailed) "アイテム一覧を取得できませんでした。対象アイテムの存在は確認できません。"
                else "対象アイテム一覧を読み込み中... ${dataService.itemReferences.snapshot.loadedCount} / ${dataService.itemReferences.snapshot.totalCount} 件"
            ).apply { styleClass.add("warning-label"); isWrapText = true }
            validationErrors(selectData).forEach { error ->
                messages += Label(error.message).apply {
                    styleClass.add(if (error.isWarning) "warning-label" else "error-label")
                    isWrapText = true
                }
            }
            warnings.children.setAll(messages)
            refreshSidebarState(selectData.id)
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
                itemBinding.load {
                    val choices = dataService.itemReferences.snapshot.items.sortedBy { it.id }.map { it.internalId }
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
        val order = TextField(orderRaw[selectData.id] ?: selectData.displayOrder.toString()).apply {
            promptText = "表示順（空欄: 0）"
        }
        orderRaw.putIfAbsent(selectData.id, order.text)
        retainInputDraft(selectData.id, "purchasePrice", purchase) { it.isEmpty() || it.toLongOrNull() != null }
        retainInputDraft(selectData.id, "salePrice", sale) { it.isEmpty() || it.toLongOrNull() != null }
        retainInputDraft(selectData.id, "stock", stock) { it.isEmpty() || it.toIntOrNull() != null }
        // 確定前の有効な数値も保持し、拒否時に旧modelだけを保存しない。
        retainInputDraft(selectData.id, "displayOrder", order) { it == orderRaw[selectData.id] }
        val orderDocument = documentKey(selectData.id)
        fun commitOrder() {
            val raw = order.text
            val value = if (raw.isEmpty()) 0 else raw.toIntOrNull() ?: return
            if (raw == orderRaw[selectData.id] && inputDraft(selectData.id, "displayOrder") == null) return
            if (documentKey(selectData.id) != orderDocument || editingDataMap[selectData.id] !== selectData) { orderRejected(); return }
            val before = singleOrderValues(selectData.id, allowPendingInput = true)
            if (before == null) { orderRejected(); return }
            applyOrderCommand(selectData.id, before, mapOf(selectData.id to ShopOrderValue(value, raw)), allSiblings = false)
        }
        order.focusedProperty().addListener { _, _, focused -> if (!focused) commitOrder() }
        order.setOnAction { commitOrder() }
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
        main.mainContentContainer.children.setAll(VBox(16.0, grid, createOrderPanel(selectData.id), refreshItems, warnings).apply { padding = Insets(20.0) })
        refresh()
    }

    override fun documentHistoryData(data: MutableShopProductData): MutableShopProductData = data.apply { displayOrder = 0 }
    override fun documentHistoryRaw(raw: Map<String, String>): Map<String, String> = raw - "displayOrder"
    override fun restoreDocumentHistory(id: String, data: MutableShopProductData): MutableShopProductData =
        data.apply { displayOrder = editingDataMap.getValue(id).displayOrder }
    override fun restoreDocumentHistoryRaw(id: String, raw: Map<String, String>): Map<String, String> =
        inputDraft(id, "displayOrder")?.let { raw + ("displayOrder" to it) } ?: raw
    override fun onDocumentHistoryRecorded(id: String) { orderHistory.recordDocument(id) }
    override fun onDocumentHistoryTraversed(id: String, forward: Boolean) { orderHistory.traversed(id, forward) }
    override fun hasLocalHistory(id: String, forward: Boolean): Boolean = orderHistory.hasOrder(id, forward)
    override fun onDocumentHistoryReset(id: String) { orderHistory.saved(id); orderRaw.remove(id) }
    override fun resetHistoryAfterSave(id: String): Boolean = false
    override fun onLocalSaveCommitted(id: String) { orderHistory.saved(id) }
    override fun canSaveInput(ids: Collection<String>): Boolean {
        val pending = ids.any { id -> inputDraft(id, "displayOrder")?.let { it.isEmpty() || it.toIntOrNull() != null } == true }
        if (pending) orderRejected()
        return !pending
    }

    private fun singleOrderValues(id: String, allowPendingInput: Boolean = false): Map<String, ShopOrderValue>? {
        if (editingBlocked || isUploadFrozen(id) || isPendingStoreDeletion(id) || (!allowPendingInput && inputDraft(id, "displayOrder") != null)) return null
        val data = editingDataMap[id] ?: return null
        return mapOf(id to ShopOrderValue(data.displayOrder, orderRaw[id] ?: data.displayOrder.toString()))
    }

    private fun siblingIds(id: String): Set<String> =
        (registeredSidebarIds + editingDataMap.keys).filterTo(mutableSetOf()) { PublicId.directoryOf(it) == PublicId.directoryOf(id) }

    private fun orderValues(id: String): Map<String, ShopOrderValue>? {
        val ids = siblingIds(id)
        if (editingBlocked || sidebarQueryActive || ids.isEmpty() || ids.any {
                it !in editingDataMap || isPendingStoreDeletion(it) || isUploadFrozen(it) || hasInputDraft(it)
            }) return null
        return ids.associateWith { member ->
            val data = editingDataMap.getValue(member)
            ShopOrderValue(data.displayOrder, orderRaw[member] ?: data.displayOrder.toString())
        }
    }

    private fun orderRejected() {
        main.showTimedTopLabel("表示順を確定できません。入力と保存・送信状態を確認してください。ドラッグ操作では全商品を取得し、検索を解除してください。")
    }

    private fun commitOrder(id: String, before: Map<String, ShopOrderValue>, after: Map<String, ShopOrderValue>): Boolean =
        applyOrderCommand(id, before, after, allSiblings = true)

    private fun applyOrderCommand(id: String, before: Map<String, ShopOrderValue>, after: Map<String, ShopOrderValue>, allSiblings: Boolean): Boolean {
        if (before == after) return false
        if (before.keys != after.keys || (if (allSiblings) orderValues(id) else singleOrderValues(id, allowPendingInput = true)) != before) { orderRejected(); return false }
        val copies = after.mapValues { (member, value) -> editingDataMap.getValue(member).deepCopy().apply { displayOrder = value.order } }
        // 通知や検証を呼ぶ前に全商品の編集コピーを置き換える。
        editingDataMap.putAll(copies)
        after.forEach { (member, value) -> orderRaw[member] = value.raw; updateInputDraft(member, "displayOrder", null) }
        orderHistory.recordOrder(before, after, allSiblings)
        refreshEditedGroup(after.keys)
        return true
    }

    override fun traverseLocalHistory(id: String, forward: Boolean): LocalHistoryResult =
        when (val step = orderHistory.peek(id, forward) { ids, allSiblings ->
            (if (allSiblings) orderValues(id) else singleOrderValues(id))?.takeIf { it.keys == ids }
        }) {
            ShopOrderHistory.Step.Document -> LocalHistoryResult.NOT_APPLICABLE
            ShopOrderHistory.Step.Rejected -> { orderRejected(); LocalHistoryResult.REJECTED }
            is ShopOrderHistory.Step.Order -> {
                val copies = step.values.mapValues { (member, value) -> editingDataMap.getValue(member).deepCopy().apply { displayOrder = value.order } }
                editingDataMap.putAll(copies)
                step.values.forEach { (member, value) -> orderRaw[member] = value.raw; updateInputDraft(member, "displayOrder", null) }
                orderHistory.traversed(id, forward)
                refreshEditedGroup(step.values.keys)
                LocalHistoryResult.HANDLED
            }
        }

    override fun refreshSidebarState(id: String) {
        super.refreshSidebarState(id)
        refreshOrderList()
    }

    private fun refreshOrderList() {
        val id = orderOwner ?: return
        orderList?.items?.setAll(siblingIds(id).sortedWith(compareBy({ editingDataMap[it]?.displayOrder ?: Int.MAX_VALUE }, { it })))
    }

    private fun createOrderPanel(id: String): Node {
        orderScroll?.stop()
        orderOwner = id
        var dragged: String? = null
        var before: Map<String, ShopOrderValue>? = null
        var active: (() -> Boolean)? = null
        var direction = 0
        var scrollIndex = 0
        val token = java.util.UUID.randomUUID().toString()
        val list = ListView<String>().apply {
            idProperty().set("shop-order-list")
            fixedCellSize = 30.0
            prefHeight = 180.0
            minHeight = 90.0
            styleClass.add("sidebar-list")
        }
        val autoScroll = Timeline(KeyFrame(Duration.millis(80.0), {
            scrollIndex = (scrollIndex + direction).coerceIn(0, (list.items.size - 1).coerceAtLeast(0))
            list.scrollTo(scrollIndex)
        })).apply { cycleCount = Timeline.INDEFINITE }
        orderScroll = autoScroll
        list.addEventFilter(javafx.scene.input.DragEvent.DRAG_OVER) { event ->
            if (dragged != null && event.dragboard.string == token) {
                val y = list.sceneToLocal(event.sceneX, event.sceneY).y
                direction = when { y < 30 -> -1; y > list.height - 30 -> 1; else -> 0 }
                if (direction == 0) autoScroll.stop() else autoScroll.play()
            }
        }
        fun move(target: String, below: Boolean): Boolean {
            val from = dragged ?: return false
            val snapshot = before ?: return false
            if (active?.invoke() != true || orderValues(id) != snapshot) { orderRejected(); return false }
            val after = moveShopOrder(snapshot, from, target, below) ?: return false
            return commitOrder(id, snapshot, after)
        }
        list.setCellFactory {
            object : ListCell<String>() {
                init {
                    setOnDragDetected { event ->
                        val member = item ?: return@setOnDragDetected
                        main.mainContentContainer.requestFocus()
                        recordHistorySnapshot(id)
                        before = orderValues(id)
                        if (before == null) { orderRejected(); return@setOnDragDetected }
                        dragged = member
                        active = editingGuard(id)
                        scrollIndex = index
                        startDragAndDrop(TransferMode.MOVE).setContent(ClipboardContent().apply { putString(token) })
                        event.consume()
                    }
                    setOnDragOver { event ->
                        if (!isEmpty && event.dragboard.string == token && dragged != item) event.acceptTransferModes(TransferMode.MOVE)
                        event.consume()
                    }
                    setOnDragDropped { event ->
                        val target = item
                        event.isDropCompleted = target != null && event.dragboard.string == token && move(target, event.y > height / 2)
                        dragged = null; before = null; autoScroll.stop()
                        event.consume()
                    }
                    setOnDragDone { dragged = null; before = null; autoScroll.stop() }
                }
                override fun updateItem(value: String?, empty: Boolean) {
                    super.updateItem(value, empty)
                    text = if (empty || value == null) null else "${editingDataMap[value]?.displayOrder ?: "未取得"}  $value"
                }
            }
        }
        orderList = list
        refreshOrderList()
        return VBox(6.0, Label("同じディレクトリの商品表示順（ドラッグで移動）"),
            Label("ローカル保存すると関連商品の順序Undo/Redoが確定します。他の商品は自動保存しません。").apply { isWrapText = true },
            Button("検索を解除して全件表示").apply { setOnAction { showAllSidebarData(); refreshOrderList() } }, list)
    }

    private fun priceField(initial: Long?, changed: (Long?) -> Unit): TextField = TextField(initial?.toString().orEmpty()).apply {
        promptText = "空欄: この売買を許可しない"
        textProperty().addListener { _, _, value -> if (value.isEmpty() || value.toLongOrNull() != null) changed(value.toLongOrNull()) }
    }

    private fun integerField(initial: Int?, prompt: String, changed: (Int?) -> Unit): TextField = TextField(initial?.toString().orEmpty()).apply {
        promptText = prompt
        textProperty().addListener { _, _, value -> if (value.isEmpty() || value.toIntOrNull() != null) changed(value.toIntOrNull()) }
    }

    override fun availableItemInternalIds(): Set<ItemInternalId> = itemIds

    override fun itemDisplayText(id: ItemInternalId): String = dataService.itemReferences.snapshot.byInternalId[id]?.id ?: "参照先不明"

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

    override fun afterClose() {
        orderScroll?.stop()
        orderHistory.clear()
        orderRaw.clear()
        itemBinding.close()
    }
}
