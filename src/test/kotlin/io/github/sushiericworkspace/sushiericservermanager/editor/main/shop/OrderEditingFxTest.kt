package io.github.sushiericworkspace.sushiericservermanager.editor.main.shop

import io.github.sushiericworkspace.sushiericdatamodel.data.drop.model.mutable.MutableDropItemData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.VanillaBlockId
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.shop.model.mutable.MutableShopProductData
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.component.DropItemEditorDialog
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentRecord
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorView
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.collections.ListChangeListener
import javafx.fxml.FXMLLoader
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.ButtonBar
import javafx.scene.control.DialogPane
import javafx.scene.control.Button
import javafx.scene.control.TextField
import javafx.scene.control.ListView
import javafx.scene.control.ListCell
import javafx.scene.control.ScrollPane
import javafx.scene.control.Spinner
import javafx.scene.input.DragEvent
import javafx.scene.input.Dragboard
import javafx.scene.input.TransferMode
import javafx.scene.input.KeyEvent
import javafx.scene.input.KeyCode
import javafx.event.Event
import javafx.animation.Animation
import kotlin.jvm.internal.Ref
import org.mockito.Mockito
import io.github.sushiericworkspace.sushiericservermanager.editor.store.LocalEditorDataStore
import io.github.sushiericworkspace.sushiericdatamodel.data.ore.model.mutable.MutableOreBaseData
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Duration
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** 実編集面へ接続するが、外部通信と物理ポインター操作を行わない回帰です。 */
@EnabledOnOs(OS.WINDOWS, OS.MAC)
class OrderEditingFxTest {
    @Test
    fun 直下全商品を一括反映し価格履歴と順序Undoを分離する() = withShop { f ->
        fx {
            f.data().getValue("tab.a").purchasePrice = 22
            f.record("tab.a")
            val before = assertNotNull(f.values())
            assertEquals(setOf("tab.a", "tab.b", "tab.unresolved"), before.keys)
            val after = assertNotNull(moveShopOrder(before, "tab.b", "tab.unresolved", false))
            val observed = mutableListOf<Map<String, Int>>()
            val observer = ListChangeListener<Node> { observed += before.keys.associateWith { f.data().getValue(it).displayOrder } }
            f.main.mainContentContainer.children.addListener(observer)
            try { assertTrue(f.move(before, after)) } finally { f.main.mainContentContainer.children.removeListener(observer) }
            assertTrue(observed.isNotEmpty())
            assertTrue(observed.all { it == after.mapValues { entry -> entry.value.order } })
            assertEquals(22L, f.data().getValue("tab.a").purchasePrice)
            assertEquals(8, f.data().getValue("tab.nested.child").displayOrder)
            assertTrue(f.logic.onUndo())
            assertEquals(before, f.values())
            assertTrue(f.logic.onUndo())
            assertEquals(1L, f.data().getValue("tab.a").purchasePrice)
            assertEquals(before, f.values())
            assertTrue(f.logic.onRedo());assertTrue(f.logic.onRedo())
            assertEquals(after, f.values())
            assertEquals(22L, f.data().getValue("tab.a").purchasePrice)
        }
    }

    @Test
    fun 検索取得不足不正raw結果不明と別member変更を拒否し履歴位置を保つ() = withShop { f ->
        fx {
            val before=assertNotNull(f.values());val after=assertNotNull(moveShopOrder(before,"tab.b","tab.a",false))
            val ticket=f.guard()
            assertTrue(f.logic.selectTab("tab.b"));assertTrue(f.logic.selectTab("tab.a"));assertFalse(ticket())
            val query=f.main.sidebarContainer.lookupAll(".sidebar-search-field").filterIsInstance<TextField>().single()
            query.text="tab.a";assertNull(f.values());assertFalse(f.move(before,after));query.clear()
            val missing=f.data().remove("tab.b")!!;assertNull(f.values());f.data()["tab.b"]=missing
            f.raw("tab.b","purchasePrice","invalid");assertNull(f.values());f.raw("tab.b","purchasePrice",null)
            val key=f.service.shops.documentKey("tab.b")
            f.service.unresolvedUploadDocuments.add(key)
            try { assertNull(f.values());assertFalse(f.move(before,after)) } finally { f.service.unresolvedUploadDocuments.remove(key) }
            assertTrue(f.move(before,after))
            f.data().getValue("tab.b").displayOrder=99
            assertFalse(f.logic.onUndo())
            assertEquals(99,f.data().getValue("tab.b").displayOrder)
            f.data().getValue("tab.b").displayOrder=after.getValue("tab.b").order
            assertTrue(f.logic.onUndo());assertEquals(before,f.values())
        }
    }

    @Test
    fun 一商品の正本保存境界で順序だけ確定し他商品の価格編集を保持する() = withShop { f ->
        fx {
            val before=assertNotNull(f.values());val after=assertNotNull(moveShopOrder(before,"tab.b","tab.a",false))
            f.data().getValue("tab.a").purchasePrice=33;f.record("tab.a")
            assertTrue(f.move(before,after))
            val otherPrice=f.data().getValue("tab.b").purchasePrice
            // 保存I/Oと後処理の境界自体はEditorSingleSaveTestが実Store成功/失敗で検査する。
            val committed=ShopEditorLogic::class.java.getDeclaredMethod("onLocalSaveCommitted",String::class.java).apply { isAccessible=true }
            committed.invoke(f.logic,"tab.b")
            assertTrue(f.logic.onUndo())
            assertEquals(1L,f.data().getValue("tab.a").purchasePrice)
            assertEquals(after,f.values())
            assertEquals(otherPrice,f.data().getValue("tab.b").purchasePrice)
            assertFalse(f.logic.onUndo())
        }
    }

    @Test
    fun Drop取消とOK前の親変更は適用せず確定時だけ親へ一回記録する() = withShop { f ->
        val parent=mutableListOf(MutableDropItemData(n=1),MutableDropItemData(n=2))
        var records=0
        modal(f,parent,"キャンセル", action = { stage ->
            stage.scene.root.lookupAll(".button").filterIsInstance<Button>().filter { it.text=="▲" }.last().fire()
        }, confirmed = { records++ })
        assertEquals(listOf(1,2),parent.map { it.n });assertEquals(0,records)
        modal(f,parent,"OK", action = { stage ->
            stage.scene.root.lookupAll(".button").filterIsInstance<Button>().filter { it.text=="▲" }.last().fire()
        }, confirmed = { records++ })
        assertEquals(listOf(2,1),parent.map { it.n });assertEquals(1,records)
        modal(f,parent,"OK", action = { parent.add(MutableDropItemData(n=7)) }, confirmed = { records++ })
        assertEquals(listOf(2,1,7),parent.map { it.n });assertEquals(1,records)
    }

    @Test
    fun 手入力は検索や他商品の不正rawに依存せず確定しbusy拒否入力は保存成功にしない() = withShop { f ->
        val order = fx { f.main.mainContentContainer.lookupAll(".text-field").filterIsInstance<TextField>().single { it.promptText == "表示順（空欄: 0）" } }
        fx {
            f.main.sidebarContainer.lookupAll(".sidebar-search-field").filterIsInstance<TextField>().single().text = "tab.a"
            f.raw("tab.b", "purchasePrice", "invalid")
            order.requestFocus()
        }
        awaitFx { order.scene.focusOwner === order }
        fx { order.text = "5"; f.main.mainContentContainer.requestFocus() }
        assertEquals(5, fx { f.data().getValue("tab.a").displayOrder })
        val completed = CompletableFuture<EditorView.SaveOutcome>()
        var responseActive = true
        val timer = fx {
            Timeline(KeyFrame(Duration.millis(20.0), {
                Platform.runLater {
                    if (!responseActive) return@runLater
                    Window.getWindows().filterIsInstance<Stage>().filter { it.owner === f.stage }.mapNotNull { it.scene?.root as? DialogPane }.forEach { pane ->
                        pane.buttonTypes.firstOrNull { it.buttonData == ButtonBar.ButtonData.OK_DONE }?.let { (pane.lookupButton(it) as Button).fire() }
                    }
                }
            })).apply { cycleCount = Timeline.INDEFINITE; play() }
        }
        try {
            fx { f.logic.onSave("tab.a") { completed.complete(it) } }
            assertEquals(EditorView.SaveOutcome.SAVED, completed.get(15, TimeUnit.SECONDS))
        } finally { fx { responseActive = false; timer.stop() } }
        val records = assertIs<StoreResult.Success<List<LocalDocumentRecord>>>(f.service.shops.localDocuments()).value
        assertEquals(5, f.service.shops.decodeDocument("tab.a", records.single { it.key.id == "tab.a" }.saved!!).displayOrder)
        awaitFx { !f.main.loadingRunning }
        val pending = fx { f.main.mainContentContainer.lookupAll(".text-field").filterIsInstance<TextField>().single { it.promptText == "表示順（空欄: 0）" } }
        fx { pending.requestFocus() }
        awaitFx { pending.scene.focusOwner === pending }
        fx { f.main.shortcutRoot.isFocusTraversable = true; pending.text = "7"; f.service.beginSave() }
        try {
            fx { f.main.shortcutRoot.requestFocus() }
            awaitFx { !pending.isFocused && pending.scene.focusOwner === f.main.shortcutRoot }
            assertEquals("7", fx { f.draft("tab.a", "displayOrder") })
            assertEquals("7", fx { pending.text })
            assertEquals(5, fx { f.data().getValue("tab.a").displayOrder })
        } finally { fx { f.service.endSave() } }
        // busy解除後は入力を再確定せず、新canSaveInputを単件・一括の双方で通す。
        assertEquals("7", fx { f.draft("tab.a", "displayOrder") })
        val busy = CompletableFuture<EditorView.SaveOutcome>()
        fx { f.logic.onSave("tab.a") { busy.complete(it) } }
        assertEquals(EditorView.SaveOutcome.BUSY, busy.get(5, TimeUnit.SECONDS))
        val batchBusy = CompletableFuture<EditorView.BatchSaveResult>()
        fx { f.logic.onSaveAll { batchBusy.complete(it) } }
        assertEquals(EditorView.SaveOutcome.BUSY, batchBusy.get(5, TimeUnit.SECONDS).outcome)
        assertEquals("7", fx { f.draft("tab.a", "displayOrder") })
        val unchanged = assertIs<StoreResult.Success<List<LocalDocumentRecord>>>(f.service.shops.localDocuments()).value
        assertEquals(5, f.service.shops.decodeDocument("tab.a", unchanged.single { it.key.id == "tab.a" }.saved!!).displayOrder)
        fx { pending.onAction.handle(javafx.event.ActionEvent()) }
        assertEquals(7, fx { f.data().getValue("tab.a").displayOrder })
        assertEquals(null, fx { f.draft("tab.a", "displayOrder") })
        assertEquals(EditorView.SaveOutcome.SAVED, save(f).outcome)
        val saved = assertIs<StoreResult.Success<List<LocalDocumentRecord>>>(f.service.shops.localDocuments()).value
        assertEquals(7, f.service.shops.decodeDocument("tab.a", saved.single { it.key.id == "tab.a" }.saved!!).displayOrder)
    }


    @Test
    @Suppress("UNCHECKED_CAST")
    fun 大量商品の実端scrollを取消し一括保存後の再表示でも順序を保つ() = withShop(extra = 100) { f ->
        val list = fx {
            (f.main.mainContentContainer.lookup("#shop-order-list") as ListView<String>).also { it.applyCss(); it.layout() }
        }
        val cell = fx { list.cellFactory.call(list) }
        val board = Mockito.mock(Dragboard::class.java)
        val token = cell.onDragDropped.javaClass.getDeclaredField("\$token").apply { isAccessible = true }.get(cell.onDragDropped) as String
        Mockito.`when`(board.string).thenReturn(token)
        Mockito.`when`(board.transferModes).thenReturn(setOf(TransferMode.MOVE))
        val timer = fx { ShopEditorLogic::class.java.getDeclaredField("orderScroll").apply { isAccessible = true }.get(f.logic) as Timeline }
        val firstVisible = fx { list.lookupAll(".list-cell").filterIsInstance<ListCell<*>>().filter { !it.isEmpty && it.localToScene(it.boundsInLocal).intersects(list.localToScene(list.boundsInLocal)) }.minOf { it.index } }
        fx {
            // native Dragboardが供給するdrag中の項目だけを設定し、開始と座標判定は製品handlerへ渡す。
            val drag = cell.onDragDone.javaClass.getDeclaredField("\$dragged").apply { isAccessible = true }.get(cell.onDragDone) as Ref.ObjectRef<String?>
            drag.element = list.items.first()
            val point = list.localToScene(list.width / 2, list.height - 2)
            Event.fireEvent(list, DragEvent(DragEvent.DRAG_OVER, board, point.x, point.y, 0.0, 0.0, TransferMode.MOVE, cell, list, null))
            assertEquals(Animation.Status.RUNNING, timer.status)
        }
        awaitFx { list.lookupAll(".list-cell").filterIsInstance<ListCell<*>>().filter { !it.isEmpty && it.localToScene(it.boundsInLocal).intersects(list.localToScene(list.boundsInLocal)) }.minOf { it.index } > firstVisible }
        fx { cell.onDragDone.handle(DragEvent(DragEvent.DRAG_DONE, board, 0.0, 0.0, 0.0, 0.0, TransferMode.MOVE, cell, list, null)); assertEquals(Animation.Status.STOPPED, timer.status) }
        val stopped = fx { list.lookupAll(".list-cell").filterIsInstance<ListCell<*>>().filter { !it.isEmpty && it.localToScene(it.boundsInLocal).intersects(list.localToScene(list.boundsInLocal)) }.minOf { it.index } }
        Thread.sleep(200)
        assertEquals(stopped, fx { list.lookupAll(".list-cell").filterIsInstance<ListCell<*>>().filter { !it.isEmpty && it.localToScene(it.boundsInLocal).intersects(list.localToScene(list.boundsInLocal)) }.minOf { it.index } })
        val after = fx {
            val before = assertNotNull(f.values())
            val ordered = shopOrderIds(before)
            assertEquals(103, ordered.size)
            val result = assertNotNull(moveShopOrder(before, ordered.last(), ordered.first(), false))
            assertTrue(f.move(before, result))
            result
        }
        assertEquals(EditorView.SaveOutcome.SAVED, save(f, all = true).outcome)
        awaitFx { !f.main.loadingRunning }
        val closed = CompletableFuture<Boolean>()
        fx { f.logic.onClose { closed.complete(it) } }
        assertTrue(closed.get(15, TimeUnit.SECONDS))
        fx { f.logic = ShopEditorLogic(f.main, f.service).also { it.setupActions(f.main.actionButtonContainer); it.start() } }
        awaitFx { !f.main.loadingRunning && f.data().size == 104 }
        fx { f.logic.selectTab("tab.a") }
        awaitFx { f.main.mainContentContainer.lookup("#shop-order-list") != null }
        assertEquals(after, fx { f.values() })
        val disk = LocalEditorDataStore(createTempDirectory("shop-order-reload").toFile())
        val reference = MutableItemBaseData(id = "item", internalId = ItemInternalId("order-item")).apply { display.displayName = "Order item" }
        assertIs<StoreResult.Success<Unit>>(disk.save(EditorDataDescriptors.item, reference.id, reference))
        for (id in after.keys) assertIs<StoreResult.Success<Unit>>(disk.save(EditorDataDescriptors.shop, id, fx { f.data().getValue(id).deepCopy() }))
        val reread = after.keys.associateWith { id -> assertIs<StoreResult.Success<MutableShopProductData>>(disk.load(EditorDataDescriptors.shop, id)).value.displayOrder }
        assertEquals(after.mapValues { it.value.order }, reread)
        assertEquals(shopOrderIds(after), reread.keys.sortedWith(compareBy({ reread.getValue(it) }, { it })))
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun 大量Dropの実端scroll取消と入力上下UndoRedoを保存再読込で保つ() = withShop { f ->
        val dropItem = MutableItemBaseData(id = "drop_item", internalId = ItemInternalId("drop-item")).apply { display.displayName = "Drop item" }
        val parent = (1..60).map { MutableDropItemData(itemId = dropItem.internalId, n = it) }.toMutableList()
        val completed = CompletableFuture<Unit>()
        var records = 0
        Platform.runLater {
            try { DropItemEditorDialog.show(f.stage, parent, listOf(dropItem), f.service.itemPreviews, onConfirmed = { records++ }); completed.complete(Unit) }
            catch (error: Throwable) { completed.completeExceptionally(error) }
        }
        var dialog: Stage? = null
        try {
            awaitFx { Window.getWindows().filterIsInstance<Stage>().singleOrNull { it.owner === f.stage && it.title == "ドロップアイテム編集" }?.also { dialog = it } != null }
            val stage = requireNotNull(dialog)
            val pane = fx { (stage.scene.root.lookup(".drop-item-list-scroll") as ScrollPane).also { stage.scene.root.applyCss(); stage.scene.root.layout() } }
            val row = fx { (pane.content as VBox).children.first() }
            val board = Mockito.mock(Dragboard::class.java)
            val token = row.onDragDropped.javaClass.getDeclaredField("\$dragToken").apply { isAccessible = true }.get(row.onDragDropped) as String
            Mockito.`when`(board.string).thenReturn(token)
            Mockito.`when`(board.transferModes).thenReturn(setOf(TransferMode.MOVE))
            val timer = row.onDragDone.javaClass.declaredFields.single { it.type == Timeline::class.java }.apply { isAccessible = true }.get(row.onDragDone) as Timeline
            val before = fx { assertTrue(pane.content.boundsInLocal.height > pane.viewportBounds.height); pane.vvalue }
            val contentBefore = fx { pane.content.localToScene(pane.content.boundsInLocal).minY }
            fx {
                val drag = row.onDragDone.javaClass.getDeclaredField("\$draggedIndex").apply { isAccessible = true }.get(row.onDragDone) as Ref.ObjectRef<Int?>
                drag.element = 0
                val point = pane.localToScene(pane.width / 2, pane.height - 2)
                Event.fireEvent(pane, DragEvent(DragEvent.DRAG_OVER, board, point.x, point.y, 0.0, 0.0, TransferMode.MOVE, row, pane, null))
                assertEquals(Animation.Status.RUNNING, timer.status)
            }
            awaitFx { pane.vvalue > before && pane.content.localToScene(pane.content.boundsInLocal).minY < contentBefore }
            fx { row.onDragDone.handle(DragEvent(DragEvent.DRAG_DONE, board, 0.0, 0.0, 0.0, 0.0, TransferMode.MOVE, row, pane, null)); assertEquals(Animation.Status.STOPPED, timer.status) }
            val stopped = fx { pane.vvalue }
            val stoppedY = fx { pane.content.localToScene(pane.content.boundsInLocal).minY }
            Thread.sleep(200)
            assertEquals(stopped, fx { pane.vvalue })
            assertEquals(stoppedY, fx { pane.content.localToScene(pane.content.boundsInLocal).minY })
            fun counts(): List<Int> = (pane.content as VBox).children.map { item -> (item.lookupAll(".spinner").filterIsInstance<Spinner<*>>().single { it.value is Int }.value as Int) }
            fun undo(redo: Boolean) {
                stage.scene.root.requestFocus()
                val mac = System.getProperty("os.name").startsWith("Mac")
                Event.fireEvent(stage.scene, KeyEvent(KeyEvent.KEY_PRESSED, "", "", if (redo) KeyCode.Y else KeyCode.Z, false, !mac, false, mac))
            }
            fx {
                val last = (pane.content as VBox).children.last()
                val spinner = last.lookupAll(".spinner").filterIsInstance<Spinner<*>>().single { it.value is Int }
                spinner.editor.text = "600"
                spinner.editor.onAction.handle(javafx.event.ActionEvent())
                last.lookupAll(".button").filterIsInstance<Button>().single { it.text == "▲" }.fire()
                assertEquals(listOf(600, 59), counts().takeLast(2))
                undo(false); assertEquals(listOf(59, 600), counts().takeLast(2))
                undo(false); assertEquals(listOf(59, 60), counts().takeLast(2))
                undo(true); undo(true); assertEquals(listOf(600, 59), counts().takeLast(2))
                stage.scene.root.lookupAll(".button").filterIsInstance<Button>().single { it.text == "OK" }.fire()
            }
            completed.get(15, TimeUnit.SECONDS)
            assertEquals(1, records)
            assertEquals(listOf(600, 59), parent.takeLast(2).map { it.n })
            val disk = LocalEditorDataStore(createTempDirectory("drop-order-reload").toFile())
            assertIs<StoreResult.Success<Unit>>(disk.save(EditorDataDescriptors.item, dropItem.id, dropItem))
            val ore = MutableOreBaseData(id = "drop_order", blockId = VanillaBlockId("iron_ore"), mutableDropItems = parent.map { it.deepCopy() }.toMutableList())
            assertIs<StoreResult.Success<Unit>>(disk.save(EditorDataDescriptors.ore, ore.id, ore))
            val loaded = assertIs<StoreResult.Success<MutableOreBaseData>>(disk.load(EditorDataDescriptors.ore, ore.id)).value
            assertEquals(parent, loaded.mutableDropItems)
            assertNotSame(parent.first(), loaded.mutableDropItems.first())
        } finally { fx { dialog?.close() } }
    }

    private fun save(f: Fixture, all: Boolean = false): EditorView.BatchSaveResult {
        val completed = CompletableFuture<EditorView.BatchSaveResult>()
        var responseActive = true
        val timer = fx {
            Timeline(KeyFrame(Duration.millis(20.0), {
                Platform.runLater {
                    if (!responseActive) return@runLater
                    Window.getWindows().filterIsInstance<Stage>().filter { it.owner === f.stage }.mapNotNull { it.scene?.root as? DialogPane }.forEach { pane ->
                        pane.buttonTypes.firstOrNull { it.buttonData == ButtonBar.ButtonData.OK_DONE }?.let { (pane.lookupButton(it) as Button).fire() }
                    }
                }
            })).apply { cycleCount = Timeline.INDEFINITE; play() }
        }
        try {
            fx { if (all) f.logic.onSaveAll { completed.complete(it) } else f.logic.onSave("tab.a") { completed.complete(EditorView.BatchSaveResult(it)) } }
            return completed.get(15, TimeUnit.SECONDS)
        } finally { fx { responseActive = false; timer.stop() } }
    }

    private fun modal(f: Fixture, parent: MutableList<MutableDropItemData>, reply: String,
                      action: (Stage)->Unit, confirmed: ()->Unit) {
        val completed=CompletableFuture<Unit>()
        val failure=CompletableFuture<Throwable>()
        var responded=false
        val timer=fx {
            Timeline(KeyFrame(Duration.millis(20.0), {
                val stage=Window.getWindows().filterIsInstance<Stage>().singleOrNull { it.owner===f.stage && it.title=="ドロップアイテム編集" }
                if(stage!=null && !responded) {
                    responded=true
                    try {
                        action(stage)
                        stage.scene.root.lookupAll(".button").filterIsInstance<Button>().single { it.text==reply }.fire()
                    } catch(error:Throwable) { failure.complete(error);stage.close() }
                }
            })).apply { cycleCount=Timeline.INDEFINITE;play() }
        }
        try {
            Platform.runLater {
                try { DropItemEditorDialog.show(f.stage,parent,emptyList(),f.service.itemPreviews,onConfirmed=confirmed);completed.complete(Unit) }
                catch(error:Throwable) { completed.completeExceptionally(error) }
            }
            completed.get(15,TimeUnit.SECONDS)
            if(failure.isDone) throw failure.get()
            assertTrue(responded)
        } finally { fx { timer.stop() } }
    }

    private class Fixture(val service:EditorDataService,val main:MainController,val stage:Stage,var logic:ShopEditorLogic) {
        @Suppress("UNCHECKED_CAST")
        fun data()=EditorView::class.java.getDeclaredMethod("getEditingDataMap").apply { isAccessible=true }.invoke(logic) as MutableMap<String,MutableShopProductData>
        @Suppress("UNCHECKED_CAST")
        fun guard()=EditorView::class.java.getDeclaredMethod("editingGuard",String::class.java).apply { isAccessible=true }.invoke(logic,"tab.a") as ()->Boolean
        fun record(id:String) { EditorView::class.java.getDeclaredMethod("recordHistorySnapshot",String::class.java).apply { isAccessible=true }.invoke(logic,id) }
        fun draft(id:String,field:String): String? = EditorView::class.java.getDeclaredMethod("inputDraft",String::class.java,String::class.java).apply { isAccessible=true }.invoke(logic,id,field) as String?
        fun raw(id:String,field:String,value:String?) { EditorView::class.java.getDeclaredMethod("updateInputDraft",String::class.java,String::class.java,String::class.java).apply { isAccessible=true }.invoke(logic,id,field,value) }
        @Suppress("UNCHECKED_CAST")
        fun values()=ShopEditorLogic::class.java.getDeclaredMethod("orderValues",String::class.java).apply { isAccessible=true }.invoke(logic,"tab.a") as Map<String,ShopOrderValue>?
        fun move(before:Map<String,ShopOrderValue>,after:Map<String,ShopOrderValue>)=
            ShopEditorLogic::class.java.getDeclaredMethod("commitOrder",String::class.java,Map::class.java,Map::class.java).apply { isAccessible=true }.invoke(logic,"tab.a",before,after) as Boolean
    }

    private fun withShop(extra: Int = 0, action:(Fixture)->Unit) {
        startFx()
        val memory=InMemoryEditorDataStore()
        val directory=createTempDirectory("order-editing").toFile()
        val service=EditorDataService(memory,directory)
        memory.save(EditorDataDescriptors.item,"item",MutableItemBaseData(id="item",internalId=ItemInternalId("order-item")))
        (listOf("tab.a","tab.b","tab.unresolved","tab.nested.child") + (0 until extra).map { "tab.m" + it.toString().padStart(3, '0') }).forEach { id ->
            memory.save(EditorDataDescriptors.shop,id,MutableShopProductData(id=id,itemInternalId=ItemInternalId(if(id.endsWith("unresolved")) "missing" else "order-item"),purchasePrice=1,displayOrder=8))
        }
        var fixture:Fixture?=null
        var primary: Throwable? = null
        try {
            fixture=fx {
                val loader=FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!));val root=loader.load<Parent>();val main=loader.getController<MainController>()
                val stage=Stage().apply { scene=Scene(root,1000.0,750.0);show() }
                val logic=ShopEditorLogic(main,service)
                Fixture(service,main,stage,logic).also { fixture=it;logic.setupActions(main.actionButtonContainer);logic.start() }
            }
            val f=requireNotNull(fixture)
            awaitFx { f.data().size==4 + extra && !f.main.loadingRunning }
            fx { f.logic.selectTab("tab.a") }
            awaitFx { f.stage.scene.root.lookup("#shop-order-list")!=null && !f.main.loadingRunning }
            fx { assertSame(f.stage, f.main.currentStage) }
            action(f)
        } catch (error: Throwable) { primary = error; reportOrderFixtureFailure(error, "Shop processing failure"); throw error } finally {
            fixture?.let { f ->
                val closed=CompletableFuture<Boolean>()
                var result: Boolean? = null
                try {
                    val processingFailure = primary
                    if (processingFailure != null) fx {
                        System.err.println("UI261_FIXTURE_FAILURE type=${processingFailure.javaClass.name} message=${processingFailure.message} activeSaves=${f.service.activeSaves} operation=${f.main.operationRunning} phase=${(f.stage.scene.lookup("#topInfoLabel") as? javafx.scene.control.Label)?.text} dialogs=" + Window.getWindows().filter { it.scene?.root is DialogPane }.map { window -> "${(window as? Stage)?.title}:owned=${(window as? Stage)?.owner === f.stage}" })
                        Window.getWindows().toList().filterIsInstance<Stage>().filter { it.owner === f.stage }.forEach(Stage::close)
                    }
                    fx { f.logic.onClose { closed.complete(it) } }
                    result = closed.get(15, TimeUnit.SECONDS)
                    assertTrue(result)
                } catch (error: Throwable) {
                    val first = primary
                    if (first == null) { primary = error; throw error }
                    first.addSuppressed(error)
                    reportOrderFixtureFailure(first, "Shop close failure result=$result")
                } finally {
                    try {
                        fx {
                            System.err.println("UI261_FIXTURE_CLOSE result=$result activeSaves=${f.service.activeSaves} operation=${f.main.operationRunning} loading=${f.main.loadingRunning} dialogs=" + Window.getWindows().filterIsInstance<Stage>().filter { it.owner === f.stage }.map { it.title })
                            Window.getWindows().toList().filterIsInstance<Stage>().filter { it.owner===f.stage }.forEach(Stage::close)
                            f.stage.close()
                        }
                    } catch (error: Throwable) {
                        System.err.println("UI261_FIXTURE_CLEANUP_FAILURE type=${error.javaClass.name} message=${error.message}")
                        val first = primary
                        if (first == null) throw error
                        first.addSuppressed(error)
                    }
                }
            }
        }
    }

    companion object {
        private var started=false
        @Synchronized private fun startFx() {
            if(started)return
            val ready=CountDownLatch(1)
            try { Platform.startup { Platform.setImplicitExit(false);ready.countDown() } }
            catch(_:IllegalStateException) { Platform.runLater { Platform.setImplicitExit(false);ready.countDown() } }
            assertTrue(ready.await(10,TimeUnit.SECONDS));started=true
        }
        private fun <T> fx(action:()->T):T { val task=FutureTask(action);Platform.runLater(task);return task.get(15,TimeUnit.SECONDS) }
        private fun awaitFx(test:()->Boolean) { val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!fx(test)) { assertTrue(System.nanoTime()<until);Thread.sleep(20) } }
    }
}

/** 当JVM内のFXとEditor I/Oだけを失敗時に記録し、診断の失敗でも元例外を維持します。 */
internal fun reportOrderFixtureFailure(primary: Throwable, phase: String) {
    try {
        var group = Thread.currentThread().threadGroup
        while (group.parent != null) group = group.parent
        val candidates = arrayOfNulls<Thread>(128)
        val enumerated = group.enumerate(candidates, true)
        val selected = candidates.filterNotNull().filter { it.name == "JavaFX Application Thread" || it.name == "editor-store-io" }.sortedBy { it.threadId() }
        val lines = mutableListOf("UI261_FIXTURE_THREADS phase=${phase.take(160)} enumerated=$enumerated matched=${selected.size} threadLimit=8 frameLimit=16 byteLimit=16384")
        val info = java.lang.management.ManagementFactory.getThreadMXBean().getThreadInfo(selected.take(8).map { it.threadId() }.toLongArray(), 16)
        info.filterNotNull().forEach { thread ->
            lines += "thread=${thread.threadName} id=${thread.threadId} state=${thread.threadState}"
            thread.stackTrace.forEach { frame -> lines += "  ${frame.toString().take(260)}" }
        }
        val text = StringBuilder()
        var bytes = 0
        for (line in lines) {
            val added = (line + "\n").toByteArray(Charsets.UTF_8).size
            if (bytes + added > 16300) { text.append("[diagnostic truncated]\n"); break }
            text.append(line).append('\n'); bytes += added
        }
        System.err.print(text)
    } catch (diagnosticFailure: Throwable) {
        System.err.println("UI261_FIXTURE_DIAGNOSTIC_FAILURE type=${diagnosticFailure.javaClass.name}")
        primary.addSuppressed(diagnosticFailure)
    }
}
