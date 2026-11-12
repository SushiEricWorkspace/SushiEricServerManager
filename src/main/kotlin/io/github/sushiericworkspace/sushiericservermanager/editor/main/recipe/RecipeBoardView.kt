package io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseDataView
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeDataView
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.mutable.MutableRecipeData
import io.github.sushiericworkspace.sushiericservermanager.editor.render.ItemPreviewService
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewResources
import io.github.sushiericworkspace.sushiericservermanager.editor.render.ItemPreviewContent
import io.github.sushiericworkspace.sushiericservermanager.editor.render.itemPreviewReviews
import javafx.collections.FXCollections
import javafx.collections.transformation.FilteredList
import javafx.event.EventHandler
import javafx.beans.value.ChangeListener
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.ContentDisplay
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.MenuItem
import javafx.scene.control.TextField
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.ClipboardContent
import javafx.scene.input.DataFormat
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.input.TransferMode
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Popup
import javafx.stage.Window
import org.slf4j.LoggerFactory

/** 同じ盤面を編集と固定snapshot確認へ使う。編集callbackはMutableRecipeDataの確定変更だけを通知する。 */
internal class RecipeBoardView(
    private val data: RecipeDataView,
    private var references: Map<ItemInternalId, ItemBaseDataView>?,
    private val previews: ItemPreviewService,
    private val source: String,
    private val changed: (() -> Unit)? = null,
    private val editCount: (() -> Unit)? = null
) : VBox(8.0), AutoCloseable {
    private val editing = (data as? MutableRecipeData).takeIf { changed != null }
    private val buttons = linkedMapOf<Int, Button>()
    private val tracked = mutableSetOf<Node>()
    private val extras = VBox(6.0)
    private val status = Label().apply { styleClass.add("recipe-board-caption") }
    private val cancel = Button("コピーを取消").apply { isVisible = false; isManaged = false; setOnAction { cancelCopy() } }
    private val result = button(null)
    private val count = Label().apply { styleClass.add("recipe-board-caption") }
    private val paletteItems = FXCollections.observableArrayList<ItemBaseDataView>()
    private val filtered = FilteredList(paletteItems)
    private val search = TextField().apply { promptText = "公開ID・内部IDで検索"; id = "recipe-palette-search" }
    private val list = ListView(filtered).apply { id = "recipe-palette-list"; styleClass.add("recipe-palette-list") }
    private var paletteReferences: Map<ItemInternalId, ItemBaseDataView>? = null
    private var copying: ItemInternalId? = null
    private var copyScene: Scene? = null
    private var copyImage: Popup? = null
    private var dragging: Node? = null
    private var closed = false
    private var window: Window? = null
    private val showing = ChangeListener<Boolean> { _, _, visible ->
        if (!visible) { cancelCopy(); if (editing == null) close() }
    }
    private val windowChanged = ChangeListener<Window?> { _, _, next -> attachWindow(next) }
    private val mouseMoved = EventHandler<MouseEvent> { event ->
        copying?.let { id ->
            val window = copyScene?.window ?: return@let
            val popup = copyImage ?: Popup().also {
                it.content += symbol(id)
                it.isAutoHide = false
                copyImage = it
            }
            if (!popup.isShowing) popup.show(window, event.screenX + 12, event.screenY + 12)
            popup.x = event.screenX + 12; popup.y = event.screenY + 12
        }
    }
    private val escape = EventHandler<KeyEvent> { event ->
        if (event.code == KeyCode.ESCAPE && copying != null) { cancelCopy(); event.consume() }
    }

    /** 編集画面だけが右側へ置く固定palette。読取確認には配置しない。 */
    val palette: VBox = VBox(8.0, Label("アイテムパレット").apply { styleClass.add("recipe-board-caption") }, search, list).apply {
        styleClass.add("recipe-palette")
        minWidth = 220.0; prefWidth = 250.0; maxWidth = 250.0; minHeight = 0.0
        VBox.setVgrow(list, Priority.ALWAYS)
    }

    init {
        require(changed == null || editing != null)
        styleClass.add("recipe-board")
        val grid = GridPane().apply {
            hgap = 6.0; vgap = 6.0
            data.size.slots.forEach { slot ->
                buttons[slot] = button(slot).also { add(it, slot % data.size.sideLength, slot / data.size.sideLength) }
            }
        }
        children.addAll(HBox(12.0, grid, Label("→").apply { styleClass.add("recipe-board-caption") }, VBox(6.0, result, count)).apply { alignment = Pos.CENTER_LEFT }, extras, HBox(8.0, status, cancel))
        sceneProperty().addListener { _, old, next ->
            old?.windowProperty()?.removeListener(windowChanged)
            next?.windowProperty()?.addListener(windowChanged)
            attachWindow(next?.window)
            cancelCopy()
            if (editing == null && old != null && next == null) close()
        }
        search.textProperty().addListener { _, _, value ->
            filtered.setPredicate { item -> value.isBlank() || item.id.contains(value, true) || item.internalId.value.contains(value, true) }
        }
        list.setCellFactory {
            object : ListCell<ItemBaseDataView>() {
                init { drag(this) { item?.internalId } }
                override fun updateItem(value: ItemBaseDataView?, empty: Boolean) {
                    super.updateItem(value, empty)
                    text = if (empty || value == null) null else value.id
                    graphic = if (empty || value == null) null else symbol(value.internalId)
                    preview(this, if (empty) null else value?.internalId)
                }
            }
        }
        refresh(references)
    }

    fun focusResult() { result.requestFocus() }

    fun focusIngredient(slot: Int): Boolean = buttons[slot]?.let { it.requestFocus(); true } ?: false

    fun refresh(snapshot: Map<ItemInternalId, ItemBaseDataView>?) {
        references = snapshot
        buttons.forEach { (slot, node) -> update(node, recipeSlots(data.ingredients)[slot]) }
        update(result, data.resultItemInternalId)
        count.text = "完成数: ${data.resultCount}"
        extras.children.filterIsInstance<HBox>().forEach { it.children.clear() }
        extras.children.clear()
        recipeSlots(data.ingredients).filterKeys { it !in data.size.slots }.toSortedMap().forEach { (slot, id) ->
            val node = buttons.getOrPut(slot) { button(slot) }
            update(node, id)
            extras.children += HBox(8.0, Label("盤外 ${slot + 1}").apply { styleClass.add("recipe-board-caption") }, node)
        }
        if (extras.children.isNotEmpty()) extras.children.add(0, Label("盤外の材料を保持しています。移動か削除で修正してください。").apply { styleClass.add("error-label"); isWrapText = true })
        if (editing != null && paletteReferences !== snapshot) {
            paletteReferences = snapshot
            paletteItems.setAll(snapshot?.values?.sortedBy { it.id }.orEmpty())
        }
        if (copying == null) status.text = if (data.shaped) "材料は1枠1個です。" else "形状なし: 各枠が1個、空き枠は詰めて保持します。"
    }

    private fun button(slot: Int?): Button = Button().apply {
        id = if (slot == null) "recipe-result" else "recipe-ingredient-$slot"
        styleClass.add("recipe-slot")
        contentDisplay = ContentDisplay.TOP
        isWrapText = true; prefWidth = 88.0; minWidth = 64.0; prefHeight = 88.0
        setOnAction { copying?.let { place(slot, it, null); cancelCopy() } }
        if (editing != null) {
            drag(this, slot) { if (slot == null) data.resultItemInternalId else recipeSlots(data.ingredients)[slot] }
            setOnDragOver { event ->
                val item = (event.gestureSource as? Node)?.properties?.get(DRAG) as? DragItem
                if (!closed && item?.owner === this@RecipeBoardView) {
                    event.acceptTransferModes(if (slot != null && item.slot != null) TransferMode.MOVE else TransferMode.COPY)
                    if ("recipe-drop-target" !in styleClass) styleClass.add("recipe-drop-target")
                    status.text = "${if (slot == null) "完成品" else "${slot + 1}番目"}を置換: ${text.orEmpty()}"
                    event.consume()
                }
            }
            setOnDragExited { styleClass.removeAll("recipe-drop-target") }
            setOnDragDropped { event ->
                val item = (event.gestureSource as? Node)?.properties?.get(DRAG) as? DragItem
                val accepted = !closed && item?.owner === this@RecipeBoardView
                if (accepted && item != null) place(slot, item.id, if (event.transferMode == TransferMode.MOVE) item.slot else null)
                event.isDropCompleted = accepted; styleClass.removeAll("recipe-drop-target"); event.consume()
            }
            setOnContextMenuRequested { event ->
                val id = if (slot == null) data.resultItemInternalId else recipeSlots(data.ingredients)[slot]
                val copy = MenuItem("コピー").apply { isDisable = id == null; setOnAction { id?.let { this@RecipeBoardView.copy(it) } } }
                val action = if (slot == null) MenuItem("完成数を設定").apply { setOnAction { if (!closed) editCount?.invoke() } }
                    else MenuItem("削除").apply { isDisable = id == null; setOnAction { if (!closed && setRecipeSlot(editing, slot, null)) commit() } }
                ContextMenu(copy, action).show(this, event.screenX, event.screenY); event.consume()
            }
        }
    }

    private fun drag(node: Node, slot: Int? = null, item: () -> ItemInternalId?) {
        if (editing == null) return
        node.setOnDragDetected { event ->
            val id = item() ?: return@setOnDragDetected
            cancelCopy()
            val transfer = DragItem(this, slot, id)
            node.properties[DRAG] = transfer; dragging = node
            val board = node.startDragAndDrop(*(if (slot == null) arrayOf(TransferMode.COPY) else TransferMode.COPY_OR_MOVE))
            board.setContent(ClipboardContent().apply { put(FORMAT, "recipe-item") })
            image(id)?.let { board.dragView = it }
            event.consume()
        }
        node.setOnDragDone { node.properties.remove(DRAG); dragging = null; status.text = "配置操作を終了しました。" }
    }

    private fun place(slot: Int?, id: ItemInternalId, from: Int?) {
        if (closed) return
        val model = editing ?: return
        val modified = if (slot == null) {
            if (model.resultItemInternalId == id) false else { model.resultItemInternalId = id; true }
        } else if (from == null) setRecipeSlot(model, slot, id) else moveRecipeSlot(model, from, slot)
        if (modified) commit()
    }

    private fun commit() { changed?.invoke(); refresh(references) }

    private fun copy(id: ItemInternalId) {
        if (closed) return
        cancelCopy(); copying = id; copyScene = scene
        copyScene?.addEventFilter(MouseEvent.MOUSE_MOVED, mouseMoved)
        copyScene?.addEventFilter(KeyEvent.KEY_PRESSED, escape)
        cancel.isVisible = true; cancel.isManaged = true
        status.text = "コピー中: ${references?.get(id)?.id ?: id.value}（共通記号・未収録）。配置またはEscapeで取消"
    }

    private fun cancelCopy() {
        val active = copying != null
        copyScene?.removeEventFilter(MouseEvent.MOUSE_MOVED, mouseMoved)
        copyScene?.removeEventFilter(KeyEvent.KEY_PRESSED, escape)
        copyScene = null; copying = null; copyImage?.hide(); copyImage = null
        cancel.isVisible = false; cancel.isManaged = false
        if (active) status.text = "コピー操作を終了しました。材料は確定した配置だけを保持します。"
    }

    private fun update(node: Button, id: ItemInternalId?) {
        node.text = when {
            id == null -> "空き"
            references == null -> "未取得: ${id.value}"
            else -> references?.get(id)?.id ?: "参照不明: ${id.value}"
        }
        node.graphic = id?.let(::symbol)
        preview(node, id)
    }

    private fun preview(node: Node, id: ItemInternalId?) {
        tracked += node
        previews.installContent(node, id?.let { key ->
            references?.get(key)?.let(ItemPreviewContent::item)
                ?: if (references == null) ItemPreviewContent.unavailable(key.value) else ItemPreviewContent.missing(key)
        }, source)
    }

    private fun image(id: ItemInternalId): Image? =
        if (references?.containsKey(id) == true) common else missing

    private fun symbol(id: ItemInternalId) = ImageView(image(id)).apply { fitWidth = 32.0; fitHeight = 32.0; isMouseTransparent = true }

    private fun attachWindow(next: Window?) {
        window?.showingProperty()?.removeListener(showing)
        window = next
        next?.showingProperty()?.addListener(showing)
    }

    override fun close() {
        if (closed) return
        closed = true
        scene?.windowProperty()?.removeListener(windowChanged)
        attachWindow(null)
        cancelCopy(); dragging?.properties?.remove(DRAG); dragging = null
        tracked.forEach { previews.installContent(it, null, source) }; tracked.clear()
    }

    private data class DragItem(val owner: RecipeBoardView, val slot: Int?, val id: ItemInternalId)

    private companion object {
        const val DRAG = "manager.recipe.drag"
        val FORMAT = DataFormat("application/x-sushieric-recipe-item")
        val common by lazy { load(PreviewResources.ITEM) }
        val missing by lazy { load(PreviewResources.MISSING) }
        fun load(path: String): Image? = try {
            PreviewResources.ensureVerified()
            RecipeBoardView::class.java.classLoader.getResourceAsStream(path)!!.use { Image(it) }.also {
                check(!it.isError) { "共通記号を読み込めません: $path" }
            }
        } catch (error: Exception) {
            LoggerFactory.getLogger(RecipeBoardView::class.java).warn("レシピの共通記号を表示できません。IDと材料は保持します", error)
            null
        }
    }
}

/** データと参照一覧は確認対象sideのsnapshotを渡す。未取得一覧はnullのまま表示する。 */
internal fun recipeDataReview(
    label: String, data: Any?, items: Map<ItemInternalId, ItemBaseDataView>?, previews: ItemPreviewService
): Node = try {
    if (data is RecipeDataView) {
    VBox(6.0, Label("$label: ${data.id}").apply { styleClass.add("recipe-review-heading") }, RecipeBoardView(data, items, previews, label))
    } else previews.review(itemPreviewReviews(label, data, items))
} catch (error: Exception) {
    LoggerFactory.getLogger(RecipeBoardView::class.java).warn("確認用の盤面を表示できません。保存内容は保持します", error)
    Label("$label: 盤面を表示できません。差分本文と元データは保持されています。")
}
