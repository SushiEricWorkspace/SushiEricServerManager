package io.github.sushiericworkspace.sushiericservermanager.editor.main.item.tree

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutablePlainTextLoreSection
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.ItemEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.shop.reportOrderFixtureFailure
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.tree.EditorTreeCell
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorView
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.EditorShortcut
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.ShortcutManager
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import javafx.animation.Timeline
import javafx.animation.KeyFrame
import javafx.util.Duration
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.DialogPane
import javafx.stage.Window
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.layout.VBox
import javafx.scene.input.DragEvent
import javafx.scene.input.TransferMode
import javafx.animation.Animation
import javafx.scene.control.TextField
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.stage.Stage
import org.mockito.Mockito
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.*

@EnabledOnOs(OS.WINDOWS, OS.MAC)
class LoreOrderFxTest {
    @Test
    fun 重複行sectionと入力を実Editor履歴で個別に戻し旧dragと取消を適用しない() {
        startFx()
        val memory = InMemoryEditorDataStore()
        val failSidebar = java.util.concurrent.atomic.AtomicBoolean(false)
        val sidebarFailures = java.util.concurrent.atomic.AtomicInteger()
        val data = MutableItemBaseData(id = "lore")
        data.display.displayName = "Before"
        repeat(2) { data.display.mutableLore.add(mutableListOf(MutablePlainTextLoreSection("same"), MutablePlainTextLoreSection("same"))) }
        memory.save(EditorDataDescriptors.item, data.id, data)
        val service = EditorDataService(memory, createTempDirectory("lore-order").toFile())
        var stage: Stage? = null
        var logic: ItemEditorLogic? = null
        var responseTimer: Timeline? = null
        var responseActive = true
        var primary: Throwable? = null
        try {
            val main = fx {
                val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
                val root = loader.load<Parent>()
                val controller = loader.getController<MainController>()
                stage = Stage().apply { scene = Scene(root, 1000.0, 800.0); show() }
                logic = Mockito.spy(ItemEditorLogic(controller, service)).also { editor ->
                    Mockito.doAnswer { invocation ->
                        if (failSidebar.get()) {
                            sidebarFailures.incrementAndGet()
                            error("保存後表示のfixture失敗")
                        }
                        invocation.callRealMethod()
                    }.`when`(editor).setupSidebar(controller.sidebarContainer, data.id)
                    editor.setupActions(controller.actionButtonContainer); editor.start()
                }
                controller
            }
            val editor = requireNotNull(logic)
            awaitFx { !main.loadingRunning && editorData(editor) != null }
            val tree = fx { tree(editor).also { expand(it.root); it.applyCss(); it.layout() } }
            awaitFx { tree.lookupAll(".text-field").filterIsInstance<TextField>().any { it.text == "Before" } }
            val field = fx { tree.lookupAll(".text-field").filterIsInstance<TextField>().single { it.text == "Before" } }
            fx { field.requestFocus() }
            awaitFx { field.scene.focusOwner === field }
            fx {
                field.replaceText(0, field.length, "Native")
                assertTrue(field.isUndoable)
                assertTrue(ShortcutManager.execute(main.shortcutRoot, EditorShortcut.UNDO))
                assertEquals("Before", field.text)
                field.replaceText(0, field.length, "Typed")
            }
            val initialIds = fx { ids(editor) }
            fx {
                val lines = rows(tree.root).filter { it.value is TreeRow.Folder.LoreLine }
                val cell = tree.cellFactory.call(tree)
                assertTrue(begin(cell))
                assertTrue(drop(cell, lines[0], lines[1]))
                val moved = ids(editor)
                assertEquals(initialIds.getValue("lines").reversed(), moved.getValue("lines"))
                assertTrue(editor.onUndo())
                assertEquals(initialIds, ids(editor))
                assertEquals("Typed", editorData(editor)!!.display.displayName)
                assertTrue(editor.onUndo())
                assertEquals("Before", editorData(editor)!!.display.displayName)
                assertTrue(editor.onRedo()); assertTrue(editor.onRedo())
                assertEquals(moved, ids(editor))
                val body = editorData(editor)!!.display.mutableLore[0]
                (body[0] as MutablePlainTextLoreSection).text = "first"
                (body[1] as MutablePlainTextLoreSection).text = "second"
                EditorView::class.java.getDeclaredMethod("recordHistorySnapshot", String::class.java).apply { isAccessible = true }.invoke(editor, "lore")
                val sections = rows(tree.root).filter { (it.value as? TreeRow.Folder.LoreSection)?.lineIndex == 0 }
                val beforeSection = ids(editor)
                val sectionCell = tree.cellFactory.call(tree)
                assertTrue(begin(sectionCell)); assertTrue(drop(sectionCell, sections[0], sections[1]))
                val afterSection = ids(editor)
                assertNotEquals(beforeSection, afterSection)
                assertEquals(listOf("second", "first"), editorData(editor)!!.display.mutableLore[0].map { (it as MutablePlainTextLoreSection).text })
                assertTrue(editor.onUndo()); assertEquals(beforeSection, ids(editor))
                assertEquals(listOf("first", "second"), editorData(editor)!!.display.mutableLore[0].map { (it as MutablePlainTextLoreSection).text })
                assertTrue(editor.onRedo()); assertEquals(afterSection, ids(editor))
                val cancelled = tree.cellFactory.call(tree)
                assertTrue(begin(cancelled))
                assertEquals(afterSection, ids(editor))
                assertTrue(editor.selectTab("lore"))
                assertFalse(canDrop(cancelled))
                assertEquals(afterSection, ids(editor))
            }
            val persisted = fx { editorData(editor)!!.deepCopy() }
            val disk = io.github.sushiericworkspace.sushiericservermanager.editor.store.LocalEditorDataStore(createTempDirectory("lore-order-reload").toFile())
            assertIs<StoreResult.Success<Unit>>(disk.save(EditorDataDescriptors.item, "lore", persisted))
            val reread = assertIs<StoreResult.Success<MutableItemBaseData>>(disk.load(EditorDataDescriptors.item, "lore")).value
            assertEquals(persisted.display.mutableLore, reread.display.mutableLore)
            assertNotSame(persisted.display.mutableLore, reread.display.mutableLore)
            // 正本は成功、保存後の表示だけが失敗して旧factoryが残る実経路。
            val oldCell = fx { tree(editor).cellFactory.call(tree(editor)) }
            val oldData = fx { editorData(editor) }
            val oldFactory = fx { tree(editor).cellFactory }
            responseTimer = fx {
                Timeline(KeyFrame(Duration.millis(20.0), {
                    Platform.runLater {
                        if (!responseActive) return@runLater
                        Window.getWindows().filterIsInstance<Stage>().filter { it.owner === stage }.mapNotNull { it.scene?.root as? DialogPane }.forEach { pane ->
                            val type = pane.buttonTypes.firstOrNull { it.buttonData == ButtonBar.ButtonData.OK_DONE } ?: pane.buttonTypes.firstOrNull()
                            if (type != null) { failSidebar.set(true); (pane.lookupButton(type) as Button).fire() }
                        }
                    }
                })).apply { cycleCount = Timeline.INDEFINITE; play() }
            }
            val saved = CompletableFuture<EditorView.SaveOutcome>()
            fx { editor.onSave("lore") { saved.complete(it) } }
            val outcome = saved.get(15, TimeUnit.SECONDS)
            assertTrue(sidebarFailures.get() > 0, "保存後の表示失敗注入に到達すること")
            assertEquals(EditorView.SaveOutcome.SAVED_WITH_ERRORS, outcome)
            fx {
                assertNotSame(oldData, editorData(editor))
                assertSame(oldFactory, tree(editor).cellFactory)
                assertFalse(begin(oldCell))
            }
            val published = assertIs<StoreResult.Success<List<io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentRecord>>>(service.items.localDocuments()).value
            assertNotNull(published.single { it.key.id == "lore" }.saved)
        } catch (error: Throwable) {
            primary = error
            reportOrderFixtureFailure(error, "Lore processing failure")
            throw error
        } finally {
            val cleanupErrors = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) { try { action() } catch (error: Throwable) { cleanupErrors += error } }
            cleanup { fx { responseActive = false; responseTimer?.stop(); failSidebar.set(false) } }
            cleanup {
                val editor = logic
                if (editor != null) {
                    if (primary != null) fx { Window.getWindows().toList().filterIsInstance<Stage>().filter { it.owner === stage }.forEach(Stage::close) }
                    val closed = CompletableFuture<Boolean>()
                    fx { editor.onClose { closed.complete(it) } }
                    val result = closed.get(15, TimeUnit.SECONDS)
                    fx { System.err.println("UI261_LORE_CLOSE result=$result activeSaves=${service.activeSaves} phase=${(stage?.scene?.lookup("#topInfoLabel") as? javafx.scene.control.Label)?.text}") }
                    assertTrue(result)
                }
            }
            cleanup { fx { stage?.close() } }
            if (cleanupErrors.isNotEmpty()) {
                val processing = primary
                val first = processing ?: cleanupErrors.first()
                cleanupErrors.filter { it !== first }.forEach(first::addSuppressed)
                reportOrderFixtureFailure(first, if (processing == null) "Lore cleanup failure after body complete" else "Lore cleanup failure after processing failure")
                if (processing == null) throw first
            }
        }
    }

    @Test
    fun target端scrollは退出とsource終了と取消と窓終了で停止する() {
        startFx()
        val type = EditorTreeCell::class.java
        fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        var stage: Stage? = null
        var source: LoreDragDropTreeCell? = null
        var target: LoreDragDropTreeCell? = null
        var scroll: Timeline? = null
        val previous = fx { listOf("draggedTreeItem", "draggedTreeView", "activeScrollCell").associateWith { field(it).get(null) } }
        assertTrue(previous.values.all { it == null }, "別のdragが残っているため開始しません")
        try {
            val tree = fx {
                val data = MutableItemBaseData(id = "scroll")
                repeat(200) { data.display.mutableLore.add(mutableListOf(MutablePlainTextLoreSection("line$it"))) }
                val memory = LoreTreeUiIdMemory()
                val ids = memory.getLineIds(data.id, 200).toList()
                val root = TreeItem<TreeRow>(TreeRow.Folder.Lore).apply {
                    isExpanded = true
                    children.setAll(ids.mapIndexed { i, id -> TreeItem<TreeRow>(TreeRow.Folder.LoreLine(i, id)) })
                }
                val view = TreeView(root).apply { prefHeight = 240.0; maxHeight = 240.0 }
                fun cell(index: Int) = LoreDragDropTreeCell(data, {}, onRefresh = {}, loreTreeUiIdMemory = memory).apply {
                    updateTreeView(view); updateTreeItem(root.children[index]); updateIndex(index)
                }
                source = cell(0); target = cell(190)
                scroll = field("edgeScroll").get(target) as Timeline
                stage = Stage().apply { scene = Scene(VBox(view, source, target), 500.0, 400.0); show() }
                view.applyCss(); view.layout()
                view
            }
            fun start() = fx {
                field("draggedTreeItem").set(null, tree.root.children[0])
                field("draggedTreeView").set(null, tree)
                field("activeScrollCell").set(null, target)
                field("scrollDirection").setInt(target, 1)
                scroll!!.playFromStart()
            }
            start()
            awaitFx { tree.lookupAll(".tree-cell").filterIsInstance<javafx.scene.control.TreeCell<*>>().any { !it.isEmpty && it.index > 100 } }
            fx {
                target!!.onDragExited.handle(DragEvent(DragEvent.DRAG_EXITED, null, 0.0, 0.0, 0.0, 0.0, TransferMode.MOVE, source, target, null))
                assertEquals(Animation.Status.STOPPED, scroll!!.status)
            }
            start()
            fx {
                source!!.onDragDone.handle(DragEvent(DragEvent.DRAG_DONE, null, 0.0, 0.0, 0.0, 0.0, TransferMode.MOVE, source, target, null))
                assertEquals(Animation.Status.STOPPED, scroll!!.status)
            }
            start()
            fx { field("draggedTreeItem").set(null, null) }
            awaitFx { scroll!!.status == Animation.Status.STOPPED }
            start()
            fx { stage!!.close() }
            awaitFx { scroll!!.status == Animation.Status.STOPPED }
        } finally {
            fx {
                scroll?.stop()
                source?.let { (field("edgeScroll").get(it) as Timeline).stop() }
                stage?.close()
                previous.forEach { (name, value) -> field(name).set(null, value) }
            }
        }
    }

    private fun expand(item: TreeItem<TreeRow>) { item.isExpanded = true; item.children.forEach(::expand) }
    private fun rows(item: TreeItem<TreeRow>): List<TreeItem<TreeRow>> = listOf(item) + item.children.flatMap(::rows)
    @Suppress("UNCHECKED_CAST")
    private fun tree(editor: ItemEditorLogic) = ItemEditorLogic::class.java.getDeclaredField("treeView").apply { isAccessible = true }.get(editor) as TreeView<TreeRow>
    @Suppress("UNCHECKED_CAST")
    private fun editorData(editor: ItemEditorLogic): MutableItemBaseData? =
        (EditorView::class.java.getDeclaredMethod("getEditingDataMap").apply { isAccessible = true }.invoke(editor) as Map<String, MutableItemBaseData>)["lore"]
    @Suppress("UNCHECKED_CAST")
    private fun ids(editor: ItemEditorLogic) = ItemEditorLogic::class.java.getDeclaredMethod("captureLoreHistoryIds", String::class.java).apply { isAccessible = true }.invoke(editor, "lore") as Map<String, List<String>>
    @Suppress("UNCHECKED_CAST")
    private fun begin(cell: javafx.scene.control.TreeCell<TreeRow>): Boolean =
        (EditorTreeCell::class.java.getDeclaredField("beforeDrag").apply { isAccessible = true }.get(cell) as () -> Boolean)()
    @Suppress("UNCHECKED_CAST")
    private fun canDrop(cell: javafx.scene.control.TreeCell<TreeRow>): Boolean =
        (EditorTreeCell::class.java.getDeclaredField("beforeDrop").apply { isAccessible = true }.get(cell) as () -> Boolean)()
    @Suppress("UNCHECKED_CAST")
    private fun drop(cell: javafx.scene.control.TreeCell<TreeRow>, source: TreeItem<TreeRow>, target: TreeItem<TreeRow>): Boolean {
        if (!canDrop(cell)) return false
        val handler = EditorTreeCell::class.java.getDeclaredField("moveHandler").apply { isAccessible = true }.get(cell) as ItemTreeMoveHandler
        val moved = handler.move(source, target)
        if (moved) (EditorTreeCell::class.java.getDeclaredField("onRefresh").apply { isAccessible = true }.get(cell) as (TreeRow) -> Unit)(target.parent.value)
        return moved
    }
    companion object {
        private var started = false
        @Synchronized private fun startFx() {
            if (started) return
            val ready = CountDownLatch(1)
            try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
            catch (_: IllegalStateException) { Platform.runLater { Platform.setImplicitExit(false); ready.countDown() } }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); started = true
        }
        private fun <T> fx(action: () -> T): T { val task = FutureTask(action); Platform.runLater(task); return task.get(15, TimeUnit.SECONDS) }
        private fun awaitFx(test: () -> Boolean) { val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10); while (!fx(test)) { assertTrue(System.nanoTime() < until); Thread.sleep(20) } }
    }
}
