package io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeSize
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.mutable.MutableRecipeData
import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.config.AppTheme
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.awaitFx
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.export
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.fx
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.startFx
import io.github.sushiericworkspace.sushiericservermanager.editor.service.*
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.*
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorView
import io.github.sushiericworkspace.sushiericservermanager.ui.theme.ThemeManager
import javafx.application.Platform
import javafx.event.Event
import javafx.fxml.FXMLLoader
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.paint.Color
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.*
import javafx.scene.image.ImageView
import javafx.scene.layout.GridPane
import javafx.scene.layout.VBox
import javafx.stage.Popup
import javafx.stage.PopupWindow
import javafx.stage.Stage
import javafx.stage.Window
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** 実FXML/Editor入口をAPIとsyntheticイベントで確認する。OS pointer/Dragboardは別試験。 */
@EnabledOnOs(OS.WINDOWS, OS.MAC)
class RecipeBoardFxTest {
    @TempDir lateinit var directory: File

    @Test
    fun 本番盤面のコピー取消と確定Undoを分離し固定パレットと盤外材料を保持する() {
        startFx()
        RecipeFxFixture.create(directory, itemCount = 1000, outside = true).use { f ->
            val source = fx { f.slot(0) }
            context(source, "コピー")
            fx { Event.fireEvent(source, recipeMouse(source, MouseEvent.MOUSE_MOVED)) }
            awaitFx { Window.getWindows().filterIsInstance<Popup>().any { it.isShowing && it.ownerWindow === f.stage } }
            fx { Event.fireEvent(f.root, KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false)) }
            fx { assertFalse(f.logic.onUndo()); assertEquals("空き", f.slot(1).text) }
            context(fx { f.slot(0) }, "コピー")
            fx { f.slot(1).fire(); assertTrue(f.slot(1).text.contains("item.0000")) }
            fx { assertTrue(f.logic.onUndo()); f.layout(); assertEquals("空き", f.slot(1).text); assertFalse(f.logic.onUndo()) }
            context(fx { f.slot(0) }, "コピー")
            fx { descendants(f.root).filterIsInstance<Button>().single { it.text == "コピーを取消" }.fire(); assertFalse(f.logic.onUndo()) }
            context(fx { f.slot(0) }, "削除")
            fx { assertEquals("空き", f.slot(0).text); assertTrue(f.logic.onUndo()); f.layout(); assertTrue(f.slot(0).text.contains("item.0000")); assertFalse(f.logic.onUndo()) }
            fx {
                f.count().requestFocus(); f.count().text = "2"; f.slot(0).requestFocus()
                assertTrue(f.logic.onUndo()); f.layout(); assertEquals("1", f.count().text); assertFalse(f.logic.onUndo())
                f.board().focusResult()
                assertSame(f.root.lookup("#recipe-result"), f.stage.scene.focusOwner)
            }

            for (size in RecipeSize.entries) for (shaped in listOf(true, false)) {
                fx {
                    @Suppress("UNCHECKED_CAST")
                    val sizes = f.root.lookup("#recipe-size") as ComboBox<RecipeSize>
                    sizes.value = size; f.layout()
                    val shape = f.root.lookup("#recipe-shaped") as CheckBox
                    if (shape.isSelected != shaped) { shape.fire(); f.layout() }
                    val board = f.board()
                    assertEquals(size.sideLength * size.sideLength, descendants(board).filterIsInstance<Button>().count { it.id?.startsWith("recipe-ingredient-") == true && it.parent is javafx.scene.layout.GridPane })
                    assertTrue(descendants(board).filterIsInstance<Button>().any { it.text.contains("unresolved") })
                    for (theme in AppTheme.entries) {
                        assertTrue(ThemeManager.select(theme)); f.layout()
                        export(board.snapshot(null, null), "recipe-board-${size.name.lowercase()}-$shaped-${theme.name.lowercase()}")
                    }
                }
            }
            fx {
                val board = f.board()
                val paletteY = board.palette.localToScene(board.palette.boundsInLocal).minY
                val form = descendants(f.root).filterIsInstance<ScrollPane>().single { it.content is VBox && descendants(it.content).any { node -> node === board } }
                form.vvalue = 1.0; f.layout()
                assertEquals(paletteY, board.palette.localToScene(board.palette.boundsInLocal).minY, 0.5)
                val list = assertIs<ListView<*>>(board.palette.lookup("#recipe-palette-list"))
                assertEquals(1000, list.items.size)
                list.scrollTo(999); board.palette.applyCss(); board.palette.layout()
                assertTrue(list.lookupAll(".list-cell").filterIsInstance<ListCell<*>>().count { !it.isEmpty } < 60)
                assertTrue(list.lookupAll(".list-cell").filterIsInstance<ListCell<*>>().any { it.index == 999 && !it.isEmpty })
                assertTrue(list.lookupAll(".scroll-bar").all { it.opacity == 0.0 })
                export(f.stage.scene.snapshot(null), "recipe-fixed-palette")
            }
        }
        RecipeFxFixture.create(directory.resolve("focus"), missingResult = true).use { f ->
            fx { f.logic.focusSelectedError() }
            awaitFx { f.stage.scene.focusOwner === f.root.lookup("#recipe-result") }
        }
    }

    @Test
    fun 取得競合の実入口に三側盤面が現れ取消は文書を保持し採用は選択値になる() {
        startFx()
        RecipeFxFixture.create(directory, remote = true).use { f ->
            fx { f.count().text = "2"; f.slot(0).requestFocus() }
            val latest = f.original.deepCopy().apply { resultCount = 3 }
            assertIs<StoreResult.Success<Unit>>(f.memory.save(EditorDataDescriptors.recipe, latest.id, latest))
            for (cancel in listOf(true, false)) {
                fx { Platform.runLater { f.logic.onSynchronizeSelected() } }
                val confirmation = dialog("取得の確認")
                fx { fireDialog(confirmation, ButtonBar.ButtonData.OK_DONE) }
                val conflict = dialog("保存競合の解決")
                val boards = fx {
                    val boards = descendants(conflict.scene.root).filterIsInstance<RecipeBoardView>().toList()
                    assertEquals(3, boards.size)
                    assertEquals(listOf("完成数: 1", "完成数: 2", "完成数: 3"), boards.map { board -> descendants(board).filterIsInstance<Label>().single { it.text.startsWith("完成数:") }.text })
                    assertTrue(descendants(boards.first()).filterIsInstance<Button>().any { it.text.startsWith("未取得:") })
                    assertTrue(descendants(boards.last()).filterIsInstance<Button>().any { it.text.startsWith("未取得:") })
                    boards
                }
                val captureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                val captureActive = AtomicBoolean(true)
                val sides = listOf("baseline" to "取得基準", "local" to "ローカル編集", "server" to "最新サーバー")
                try {
                    for (theme in AppTheme.entries) {
                        fx {
                            check(captureActive.get() && System.nanoTime() < captureDeadline)
                            assertTrue(ThemeManager.select(theme))
                            check(captureActive.get() && System.nanoTime() < captureDeadline)
                            conflict.scene.root.applyCss()
                            check(captureActive.get() && System.nanoTime() < captureDeadline)
                            conflict.scene.root.layout()
                        }
                        for ((index, board) in boards.withIndex()) {
                            val shown = mutableSetOf<Node>()
                            for (position in listOf("top", "bottom")) {
                                shown += captureRecipeReview(conflict, board, sides[index].second, index + 1, theme, position,
                                    "recipe-fetch-$cancel-${theme.name.lowercase()}-${sides[index].first}-$position", captureDeadline, captureActive)
                            }
                            fx {
                                check(captureActive.get() && System.nanoTime() < captureDeadline)
                                val expected = recipeReviewTargets(board, sides[index].second, index + 1)
                                check(captureActive.get() && System.nanoTime() < captureDeadline)
                                assertEquals(expected, shown)
                            }
                        }
                    }
                } finally { captureActive.set(false) }
                fx {
                    if (!cancel) descendants(conflict.scene.root).filterIsInstance<Button>().single { it.text == "すべてサーバーを採用" }.fire()
                    fireDialog(conflict, if (cancel) ButtonBar.ButtonData.CANCEL_CLOSE else ButtonBar.ButtonData.OK_DONE)
                }
                awaitFx {
                    if (f.main.operationRunning || f.main.loadingRunning) return@awaitFx false
                    f.layout()
                    if (f.main.operationRunning || f.main.loadingRunning) return@awaitFx false
                    val count = f.root.lookup("#recipe-count") ?: return@awaitFx false
                    assertEquals(if (cancel) "2" else "3", assertIs<TextField>(count).text)
                    true
                }
            }
        }
    }

    @Test
    fun アップロード確認の未選択と全Item採用変更を反映予定盤面へ接続し取消で送信しない() {
        startFx()
        RecipeFxFixture.create(directory, remote = true).use { f ->
            val local = f.service.localService
            val serverBefore = assertIs<StoreResult.Success<MutableItemBaseData>>(f.memory.load(EditorDataDescriptors.item, f.items.first().id)).value.deepCopy()
            val item = serverBefore.deepCopy().apply { itemDetail.maxStackSize = 16; display.displayName = "LOCAL 日本語" }
            val recipe = f.original.deepCopy().apply { resultCount = 2 }
            fun <T : ManagedData<T, *>> save(access: EditorDataService.DataAccess<T>, value: T): LocalDocumentRecord {
                val body = access.encodeDocument(value)
                return assertIs<StoreResult.Success<LocalDocumentRecord>>(access.updateLocalDocument(value.id) { it.copy(editing = body, saved = body, original = body) }).value
            }
            val itemRecord = save(local.items, item)
            val record = save(local.recipes, recipe)
            val preview = assertIs<StoreResult.Success<SavedUploadPreview>>(SavedUploadPlanner(local, f.service).preview(listOf(SavedUploadSelection(itemRecord.key), SavedUploadSelection(record.key)))).value
            val completed = CompletableFuture<SavedUploadConfirmation?>()
            fx { Platform.runLater { try { completed.complete(SavedUploadDialog.show(f.stage, "自己fixture", preview, f.service.itemPreviews)) } catch (error: Throwable) { completed.completeExceptionally(error) } } }
            val window = dialog("アップロードの確認")
            fx {
                assertTrue(descendants(window.scene.root).filterIsInstance<Label>().any { it.text.contains("競合値をすべて選択") })
                val pane = window.scene.root as DialogPane
                assertTrue(pane.lookupButton(pane.buttonTypes.first { it.buttonData == ButtonBar.ButtonData.OK_DONE }).isDisable)
            }
            fun prepareUploadHover(board: RecipeBoardView, target: Button, localChoice: Boolean, deadline: Long) {
                val active = AtomicBoolean(true)
                val completed = CompletableFuture<Unit>()
                val scene = fx {
                    check(active.get() && System.nanoTime() < deadline)
                    window.scene
                }
                val pulse = Runnable {
                    if (!active.get() || completed.isDone) return@Runnable
                    try {
                        check(active.get() && System.nanoTime() < deadline)
                        assertTrue(window.isShowing); assertSame(scene, window.scene)
                        if (board.scene == null || target.scene == null || scene.root.isNeedsLayout || board.isNeedsLayout || target.isNeedsLayout) {
                            check(active.get() && System.nanoTime() < deadline)
                            Platform.requestNextPulse()
                            return@Runnable
                        }
                        assertSame(scene, board.scene); assertSame(scene, target.scene)
                        assertSame(board, plannedBoard(window)); assertSame(target, board.lookup("#recipe-result"))
                        val selected = descendants(scene.root).filterIsInstance<RadioButton>().filter { it.isSelected }.toList()
                        assertTrue(selected.isNotEmpty())
                        assertTrue(selected.all { it.text.startsWith(if (localChoice) "保存済み:" else "サーバー:") })
                        assertEquals("完成数: ${if (localChoice) 2 else 1}", descendants(board).filterIsInstance<Label>().single { it.text.startsWith("完成数:") }.text)
                        val scroll = generateSequence(board.parent) { it.parent }.filterIsInstance<ScrollPane>().single()
                        val viewport = assertNotNull(scroll.lookup(".viewport"))
                        assertTrue(viewport.layoutBounds.contains(viewport.sceneToLocal(target.localToScene(target.layoutBounds))))
                        check(active.get() && System.nanoTime() < deadline)
                        completed.complete(Unit)
                    } catch (error: Throwable) { completed.completeExceptionally(error) }
                }
                var primary: Throwable? = null
                try {
                    fx {
                        check(active.get() && System.nanoTime() < deadline)
                        scene.root.applyCss()
                        check(active.get() && System.nanoTime() < deadline)
                        scene.root.layout()
                        check(active.get() && System.nanoTime() < deadline)
                        scene.addPostLayoutPulseListener(pulse); Platform.requestNextPulse()
                    }
                    val remaining = deadline - System.nanoTime()
                    check(remaining > 0)
                    completed.get(remaining, TimeUnit.NANOSECONDS)
                } catch (error: Throwable) { primary = error; throw error }
                finally {
                    active.set(false)
                    try { fx { scene.removePostLayoutPulseListener(pulse) } }
                    catch (cleanup: Throwable) { primary?.addSuppressed(cleanup) ?: throw cleanup }
                }
            }
            fun captureUpload(board: RecipeBoardView, target: Button, count: Int, localChoice: Boolean, description: String, index: Int) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                for (theme in AppTheme.entries) {
                    val active = AtomicBoolean(true)
                    val completed = CompletableFuture<Unit>()
                    val scene = fx {
                        check(active.get() && System.nanoTime() < deadline)
                        assertSame(board, plannedBoard(window)); assertSame(target, board.lookup("#recipe-result"))
                        assertSame(window.scene, board.scene); assertSame(window.scene, target.scene)
                        window.scene
                    }
                    val pulse = Runnable {
                        if (!active.get() || completed.isDone) return@Runnable
                        try {
                            check(active.get() && System.nanoTime() < deadline)
                            assertTrue(window.isShowing); assertSame(scene, window.scene)
                            if (board.scene == null || target.scene == null || scene.root.isNeedsLayout || board.isNeedsLayout || target.isNeedsLayout) {
                                check(active.get() && System.nanoTime() < deadline)
                                Platform.requestNextPulse()
                                return@Runnable
                            }
                            assertSame(scene, board.scene); assertSame(scene, target.scene)
                            assertSame(board, plannedBoard(window)); assertSame(target, board.lookup("#recipe-result"))
                            assertTrue("theme-${theme.name.lowercase()}" in scene.root.styleClass)
                            val selected = descendants(scene.root).filterIsInstance<RadioButton>().filter { it.isSelected }.toList()
                            assertTrue(selected.isNotEmpty())
                            assertTrue(selected.all { it.text.startsWith(if (localChoice) "保存済み:" else "サーバー:") })
                            assertTrue(description.contains(if (localChoice) "LOCAL" else "SERVER"))
                            val side = assertIs<VBox>(board.parent)
                            val heading = side.children.filterIsInstance<Label>().single()
                            assertEquals("反映予定: recipe.fx", heading.text)
                            val labels = descendants(board).filterIsInstance<Label>().filter { "recipe-board-caption" in it.styleClass }.toList()
                            assertEquals("完成数: $count", labels.single { it.text.startsWith("完成数:") }.text)
                            val color = if (theme == AppTheme.DARK) Color.WHITE else Color.web("#25282c")
                            (labels + heading).forEach { assertEquals(color, it.textFill, "$theme ${it.text}") }
                            descendants(board).filterIsInstance<Button>().filter { "recipe-slot" in it.styleClass }.forEach {
                                assertEquals(color, it.textFill, "$theme ${it.id}")
                            }
                            val scroll = generateSequence(board.parent) { it.parent }.filterIsInstance<ScrollPane>().single()
                            val viewport = assertNotNull(scroll.lookup(".viewport"))
                            assertTrue(viewport.layoutBounds.contains(viewport.sceneToLocal(target.localToScene(target.layoutBounds))))
                            check(active.get() && System.nanoTime() < deadline)
                            export(scene.snapshot(null), "recipe-upload-$index-${theme.name.lowercase()}")
                            check(active.get() && System.nanoTime() < deadline)
                            completed.complete(Unit)
                        } catch (error: Throwable) { completed.completeExceptionally(error) }
                    }
                    var primary: Throwable? = null
                    try {
                        fx {
                            check(active.get() && System.nanoTime() < deadline)
                            assertSame(scene, window.scene)
                            assertTrue(ThemeManager.select(theme))
                            check(active.get() && System.nanoTime() < deadline)
                            scene.root.applyCss()
                            check(active.get() && System.nanoTime() < deadline)
                            scene.root.layout()
                            check(active.get() && System.nanoTime() < deadline)
                            scene.addPostLayoutPulseListener(pulse); Platform.requestNextPulse()
                        }
                        val remaining = deadline - System.nanoTime()
                        check(remaining > 0)
                        completed.get(remaining, TimeUnit.NANOSECONDS)
                    } catch (error: Throwable) { primary = error; throw error }
                    finally {
                        active.set(false)
                        try { fx { scene.removePostLayoutPulseListener(pulse) } }
                        catch (cleanup: Throwable) { primary?.addSuppressed(cleanup) ?: throw cleanup }
                    }
                }
            }
            for ((index, localChoice) in listOf(true, false, true).withIndex()) {
                val board = fx {
                    descendants(window.scene.root).filterIsInstance<RadioButton>().filter { it.text.startsWith(if (localChoice) "保存済み:" else "サーバー:") }.forEach { it.fire() }
                    val expected = if (localChoice) 2 else 1
                    val board = plannedBoard(window)
                    assertEquals("完成数: $expected", descendants(board).filterIsInstance<Label>().single { it.text.startsWith("完成数:") }.text)
                    board.focusResult()
                    board
                }
                val button = fx {
                    assertSame(board, plannedBoard(window)); assertSame(window.scene, board.scene)
                    val target = assertIs<Button>(board.lookup("#recipe-result"))
                    assertSame(window.scene, target.scene)
                    val selected = descendants(window.scene.root).filterIsInstance<RadioButton>().filter { it.isSelected }.toList()
                    assertTrue(selected.isNotEmpty())
                    assertTrue(selected.all { it.text.startsWith(if (localChoice) "保存済み:" else "サーバー:") })
                    assertEquals("完成数: ${if (localChoice) 2 else 1}", descendants(board).filterIsInstance<Label>().single { it.text.startsWith("完成数:") }.text)
                    revealRecipeSlot(target)
                    target
                }
                val hoverDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
                prepareUploadHover(board, button, localChoice, hoverDeadline)
                val description = recipeTooltip(button, if (index < 2) "recipe-upload-hover-$index" else null, hoverDeadline)
                assertTrue(description.contains(if (localChoice) "LOCAL" else "SERVER"))
                if (index < 2) captureUpload(board, button, if (localChoice) 2 else 1, localChoice, description, index)
            }
            fx { fireDialog(window, ButtonBar.ButtonData.CANCEL_CLOSE) }
            assertNull(completed.get(15, TimeUnit.SECONDS))
            assertEquals(1, assertIs<StoreResult.Success<MutableRecipeData>>(f.memory.load(EditorDataDescriptors.recipe, f.original.id)).value.resultCount)
            assertEquals(serverBefore, assertIs<StoreResult.Success<MutableItemBaseData>>(f.memory.load(EditorDataDescriptors.item, item.id)).value)
        }
    }
}

private fun recipeReviewTargets(board: RecipeBoardView, title: String, count: Int): Set<Node> {
    val side = assertIs<VBox>(board.parent)
    val heading = side.children.filterIsInstance<Label>().single()
    assertEquals("$title: recipe.fx", heading.text)
    assertSame(board, side.children.filterIsInstance<RecipeBoardView>().single())
    val slots = descendants(board).filterIsInstance<Button>().filter { it.id?.startsWith("recipe-ingredient-") == true && it.parent is GridPane }.toList()
    assertEquals((0 until 25).toSet(), slots.map { it.id.removePrefix("recipe-ingredient-").toInt() }.toSet())
    val caption = descendants(board).filterIsInstance<Label>().single { it.text.startsWith("完成数:") }
    assertEquals("完成数: $count", caption.text)
    val arrow = descendants(board).filterIsInstance<Label>().single { it.text == "→" }
    val result = assertIs<Button>(board.lookup("#recipe-result"))
    return (slots + listOf(heading, caption, arrow, result)).toSet()
}

private fun captureRecipeReview(window: Window, board: RecipeBoardView, title: String, count: Int, theme: AppTheme,
                                position: String, imageName: String, deadline: Long, captureActive: AtomicBoolean): Set<Node> {
    val active = AtomicBoolean(true)
    val handled = AtomicBoolean(false)
    val completed = CompletableFuture<Set<Node>>()
    val scene = fx {
        check(active.get() && captureActive.get() && System.nanoTime() < deadline)
        window.scene
    }
    lateinit var pulse: Runnable
    pulse = Runnable {
        if (!active.get() || !captureActive.get() || !handled.compareAndSet(false, true)) return@Runnable
        try {
            check(active.get() && captureActive.get() && System.nanoTime() < deadline)
            assertTrue(window.isShowing); assertSame(scene, window.scene)
            val side = assertIs<VBox>(board.parent)
            val scroll = generateSequence(side.parent) { it.parent }.filterIsInstance<ScrollPane>().single()
            val viewport = assertNotNull(scroll.lookup(".viewport"))
            val targets = recipeReviewTargets(board, title, count)
            val visible = targets.filter { node -> viewport.layoutBounds.contains(viewport.sceneToLocal(node.localToScene(node.layoutBounds))) }.toSet()
            val heading = side.children.filterIsInstance<Label>().single()
            if (position == "top") assertTrue(heading in visible) else assertTrue(board.lookup("#recipe-ingredient-24") in visible)
            val valueLabels = descendants(board).filterIsInstance<Label>().filter { it.text.startsWith("完成数:") || it.text == "→" }.toList()
            val color = if (theme == AppTheme.DARK) Color.WHITE else Color.web("#25282c")
            (valueLabels + heading).forEach { assertEquals(color, it.textFill, "$theme ${it.text}") }
            check(active.get() && captureActive.get() && System.nanoTime() < deadline)
            export(scene.snapshot(null), imageName)
            check(active.get() && captureActive.get() && System.nanoTime() < deadline)
            completed.complete(visible)
        } catch (error: Throwable) { completed.completeExceptionally(error) }
        finally { scene.removePostLayoutPulseListener(pulse) }
    }
    var primary: Throwable? = null
    try {
        fx {
            check(active.get() && captureActive.get() && System.nanoTime() < deadline)
            assertTrue(window.isShowing); assertSame(scene, window.scene)
            scene.root.applyCss()
            check(active.get() && captureActive.get() && System.nanoTime() < deadline)
            scene.root.layout()
            check(active.get() && captureActive.get() && System.nanoTime() < deadline)
            val side = assertIs<VBox>(board.parent)
            val scroll = generateSequence(side.parent) { it.parent }.filterIsInstance<ScrollPane>().single()
            val content = scroll.content
            val bounds = content.sceneToLocal(side.localToScene(side.layoutBounds))
            val height = content.layoutBounds.height - scroll.viewportBounds.height
            assertTrue(height > 0 && height.isFinite())
            val offset = if (position == "top") bounds.minY - content.layoutBounds.minY else bounds.maxY - content.layoutBounds.minY - scroll.viewportBounds.height
            val value = scroll.vmin + (offset / height).coerceIn(0.0, 1.0) * (scroll.vmax - scroll.vmin)
            check(active.get() && captureActive.get() && System.nanoTime() < deadline)
            scroll.vvalue = value
            check(active.get() && captureActive.get() && System.nanoTime() < deadline)
            scene.root.applyCss()
            check(active.get() && captureActive.get() && System.nanoTime() < deadline)
            scene.root.layout()
            check(active.get() && captureActive.get() && System.nanoTime() < deadline)
            scene.addPostLayoutPulseListener(pulse); Platform.requestNextPulse()
        }
        val remaining = deadline - System.nanoTime()
        check(remaining > 0)
        return completed.get(remaining, TimeUnit.NANOSECONDS)
    } catch (error: Throwable) { primary = error; throw error }
    finally {
        active.set(false)
        try { fx { scene.removePostLayoutPulseListener(pulse) } }
        catch (cleanup: Throwable) { primary?.addSuppressed(cleanup) ?: throw cleanup }
    }
}

/** Recipe専用の実FXML fixture。サービスIO・自己Stage・通知差分だけを閉じる。 */
internal class RecipeFxFixture private constructor(private val directory: File, itemCount: Int, outside: Boolean, private val remote: Boolean, missingResult: Boolean) : AutoCloseable {
    val memory = InMemoryEditorDataStore()
    val items = (0 until itemCount).map { index -> MutableItemBaseData(id = "item.${index.toString().padStart(4, '0')}").apply { display.displayName = "SERVER 日本語 é 𠮷" } }
    val original = MutableRecipeData(id = "recipe.fx", size = RecipeSize.FIVE_BY_FIVE,
        ingredients = RecipeIngredients.Shaped(buildMap { put(0, items.first().internalId); if (outside) { put(24, items.first().internalId); put(30, ItemInternalId("unresolved")) } }), resultItemInternalId = items.first().internalId.takeUnless { missingResult })
    val service: EditorDataService
    lateinit var stage: Stage
        private set
    lateinit var root: Parent
        private set
    lateinit var main: MainController
        private set
    lateinit var logic: RecipeEditorLogic
        private set
    private var phase = "created"
    private var documentReady: Boolean? = null
    private val previousTheme = fx { ThemeManager.current }
    private val notices = EditorSession.notifications.snapshot().map { it.scope to it.key }.toSet()

    init {
        items.forEach { assertIs<StoreResult.Success<Unit>>(memory.save(EditorDataDescriptors.item, it.id, it)) }
        assertIs<StoreResult.Success<Unit>>(memory.save(EditorDataDescriptors.recipe, original.id, original))
        val store = if (remote) object : EditorDataStore by memory {
            override val kind = EditorDataStoreKind.REMOTE
            override val documentSource = EditorDocumentSource.Server("recipe-fx", directory.absolutePath)
        } else memory
        service = EditorDataService(store, directory.resolve("app"))
    }

    private fun start() {
        phase = "prepare-recipe"
        assertIs<StoreResult.Success<*>>(service.submitIo { service.recipes.observeStore(original.id) }.get(15, TimeUnit.SECONDS))
        val preparationStarted = System.nanoTime()
        val preparationDeadline = preparationStarted + TimeUnit.SECONDS.toNanos(60)
        val prepared = mutableListOf<JsonObject>()
        for (source in listOf(service.localService, service).distinct()) {
            phase = "prepare-references:${source.cacheIdentity}"
            val before = source.itemReferences.snapshot.status
            val started = System.nanoTime()
            val future = source.itemReferences.load()
            val remaining = preparationDeadline - System.nanoTime()
            check(remaining > 0)
            assertIs<StoreResult.Success<*>>(future.get(remaining, TimeUnit.NANOSECONDS))
            val snapshot = source.itemReferences.snapshot
            assertEquals(ItemReferenceStatus.READY, snapshot.status)
            prepared += buildJsonObject {
                put("source", source.cacheIdentity.take(512)); put("beforeStatus", before.name)
                put("status", snapshot.status.name); put("loadedCount", snapshot.loadedCount); put("totalCount", snapshot.totalCount)
                put("itemCount", snapshot.items.size); put("elapsedMillis", (System.nanoTime() - started) / 1_000_000.0)
            }
        }
        recordPreparation(prepared, System.nanoTime() - preparationStarted)
        phase = "create-fxml"
        fx {
            val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
            root = loader.load<Parent>()
            main = loader.getController<MainController>()
            val stylesheet = javaClass.getResource(AppScreen.BASE.css)!!.toExternalForm()
            stage = Stage().apply {
                scene = Scene(root, 1450.0, 950.0).apply { stylesheets += stylesheet }
                show()
            }
            logic = RecipeEditorLogic(main, service)
            logic.setupActions(main.actionButtonContainer); logic.start()
            System.out.println("RECIPE_FX actualOutputScale=${stage.outputScaleX},${stage.outputScaleY}")
        }
        phase = "initial-reference-ready"
        awaitFx { !main.loadingRunning && !logic.closeBusy && service.localService.itemReferences.snapshot.status == ItemReferenceStatus.READY }
        if (remote) {
            phase = "open-remote-document"
            val ready = CompletableFuture<Boolean>()
            fx { logic.openDocument(service.recipes.documentKey(original.id)) { ready.complete(it) } }
            documentReady = ready.get(15, TimeUnit.SECONDS)
            assertEquals(true, documentReady)
        }
        phase = "target-content"
        awaitFx { !main.loadingRunning && !logic.closeBusy && service.itemReferences.snapshot.status == ItemReferenceStatus.READY && root.lookup("#recipe-result") is Button && !main.operationRunning }
        awaitFx { (root.lookup("#recipe-ingredient-0") as? Button)?.text?.contains("item.0000") == true }
        fx { layout() }
        phase = "ready"
    }

    private fun recordPreparation(sources: List<JsonObject>, elapsed: Long) {
        val number = preparationSequence.getAndIncrement()
        check(number < 8)
        val data = buildJsonObject {
            put("finalComplete", false); put("cachePreparationComplete", true)
            put("stageCreated", false); put("budgetSeconds", 60)
            put("elapsedMillis", elapsed / 1_000_000.0); put("requestedItemCount", items.size)
            put("sources", JsonArray(sources))
        }.toString().toByteArray(Charsets.UTF_8)
        check(data.size <= 16384)
        val output = System.getProperty("manager.previewFxOutput")?.let { Path.of(it).parent } ?: directory.toPath()
        Files.createDirectories(output)
        Files.write(output.resolve("recipe-fixture-preparation-$number.json"), data, StandardOpenOption.CREATE_NEW)
    }

    private fun recordFailure() {
        val number = failureSequence.getAndIncrement()
        check(number < 8)
        val ui = fx {
            buildJsonObject {
                put("currentSource", if (::main.isInitialized) main.notificationScope().source.take(512) else "unknown")
                put("loading", if (::main.isInitialized) main.loadingRunning else null)
                put("closeBusy", if (::logic.isInitialized) logic.closeBusy else null)
                put("slotText", if (::root.isInitialized) (root.lookup("#recipe-ingredient-0") as? Button)?.text?.take(512) else null)
            }
        }
        val data = buildJsonObject {
            put("finalComplete", false)
            put("phase", phase.take(512))
            put("documentReady", documentReady)
            put("expectedPublicId", items.first().id)
            put("expectedInternalId", items.first().internalId.value)
            put("itemCount", items.size)
            put("connectedSource", service.cacheIdentity.take(512))
            put("ui", ui)
            for ((name, source) in listOf("local" to service.localService, "connected" to service)) {
                val snapshot = source.itemReferences.snapshot
                put(name, buildJsonObject {
                    put("source", source.cacheIdentity.take(512)); put("status", snapshot.status.name)
                    put("loadedCount", snapshot.loadedCount); put("totalCount", snapshot.totalCount)
                    put("expectedItemPublicId", snapshot.byInternalId[items.first().internalId]?.id)
                })
            }
        }.toString().toByteArray(Charsets.UTF_8)
        check(data.size <= 16384)
        val output = System.getProperty("manager.previewFxOutput")?.let { Path.of(it).parent } ?: directory.toPath()
        Files.createDirectories(output)
        Files.write(output.resolve("recipe-fixture-failure-$number.json"), data, StandardOpenOption.CREATE_NEW)
    }

    fun layout() { root.applyCss(); root.layout() }
    fun slot(index: Int) = assertIs<Button>(root.lookup("#recipe-ingredient-$index"))
    fun count() = assertIs<TextField>(root.lookup("#recipe-count"))
    fun board() = descendants(root).filterIsInstance<RecipeBoardView>().single()

    override fun close() {
        val failures = mutableListOf<Throwable>()
        fun cleanup(action: () -> Unit) { try { action() } catch (error: Throwable) { failures += error } }
        if (::stage.isInitialized) cleanup { fx { Window.getWindows().toList().filter {
            (it is PopupWindow && it.ownerWindow === stage) || (it is Stage && it.owner === stage)
        }.forEach { it.hide() } } }
        if (::logic.isInitialized) cleanup {
            val done = CompletableFuture<Boolean>()
            fx { logic.onClose { done.complete(it) } }
            assertTrue(done.get(20, TimeUnit.SECONDS))
        }
        cleanup { fx { if (::stage.isInitialized) stage.close(); ThemeManager.select(previousTheme) } }
        try { cleanup { service.awaitIo() } } finally { cleanup { service.closeIo() } }
        cleanup { fx { EditorSession.notifications.snapshot().filter { (it.scope to it.key) !in notices }.forEach { EditorSession.notifications.condition(it.scope, it.key, null) } } }
        failures.firstOrNull()?.let { first -> failures.drop(1).forEach(first::addSuppressed); throw first }
    }

    companion object {
        private val failureSequence = AtomicInteger()
        private val preparationSequence = AtomicInteger()
        fun create(directory: File, itemCount: Int = 1, outside: Boolean = false, remote: Boolean = false, missingResult: Boolean = false): RecipeFxFixture {
            val fixture = RecipeFxFixture(directory, itemCount, outside, remote, missingResult)
            try { fixture.start(); return fixture }
            catch (error: Throwable) {
                try { fixture.recordFailure() } catch (diagnostic: Throwable) { error.addSuppressed(diagnostic) }
                try { fixture.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }
    }
}

internal fun descendants(node: Node): Sequence<Node> = sequence {
    yield(node)
    if (node is Parent) node.childrenUnmodifiable.forEach { yieldAll(descendants(it)) }
}

internal fun recipeMouse(node: Node, type: javafx.event.EventType<MouseEvent>): MouseEvent {
    val x = node.boundsInLocal.width / 2; val y = node.boundsInLocal.height / 2
    val point = assertNotNull(node.localToScreen(x, y))
    return MouseEvent(type, x, y, point.x, point.y, MouseButton.NONE, 0, false, false, false, false, false, false, false, true, false, true, PickResult(node, x, y))
}

internal fun context(node: Node, action: String) = fx {
    val point = assertNotNull(node.localToScreen(node.boundsInLocal.width / 2, node.boundsInLocal.height / 2))
    Event.fireEvent(node, ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 1.0, 1.0, point.x, point.y, false, PickResult(node, 1.0, 1.0)))
    val menu = Window.getWindows().filterIsInstance<ContextMenu>().single { it.isShowing }
    menu.items.single { it.text == action }.fire(); menu.hide()
}

internal fun dialog(title: String): Window {
    var result: Window? = null
    awaitFx { Window.getWindows().filterIsInstance<Stage>().firstOrNull { it.title == title && it.isShowing }?.also { result = it } != null }
    return assertNotNull(result)
}

internal fun fireDialog(window: Window, action: ButtonBar.ButtonData) {
    val pane = assertIs<DialogPane>(window.scene.root)
    val button = pane.buttonTypes.single { it.buttonData == action }
    assertIs<Button>(pane.lookupButton(button)).fire()
}

private fun plannedBoard(window: Window) = descendants(window.scene.root).filterIsInstance<VBox>().single { box ->
    box.children.filterIsInstance<Label>().any { it.text.startsWith("反映予定:") } && box.children.any { it is RecipeBoardView }
}.children.filterIsInstance<RecipeBoardView>().single()

private fun revealRecipeSlot(node: Node) {
    val root = node.scene.root
    root.applyCss(); root.layout()
    val scroll = generateSequence(node.parent) { it.parent }.filterIsInstance<ScrollPane>().single()
    val content = scroll.content
    val bounds = content.sceneToLocal(node.localToScene(node.layoutBounds))
    val horizontal = (content.layoutBounds.width - scroll.viewportBounds.width).coerceAtLeast(0.0)
    val vertical = (content.layoutBounds.height - scroll.viewportBounds.height).coerceAtLeast(0.0)
    val x = if (horizontal == 0.0) 0.0 else ((bounds.minX + bounds.width / 2 - content.layoutBounds.minX - scroll.viewportBounds.width / 2) / horizontal).coerceIn(0.0, 1.0)
    val y = if (vertical == 0.0) 0.0 else ((bounds.minY + bounds.height / 2 - content.layoutBounds.minY - scroll.viewportBounds.height / 2) / vertical).coerceIn(0.0, 1.0)
    scroll.hvalue = scroll.hmin + x * (scroll.hmax - scroll.hmin)
    scroll.vvalue = scroll.vmin + y * (scroll.vmax - scroll.vmin)
    root.applyCss(); root.layout()
    val viewport = assertNotNull(scroll.lookup(".viewport"))
    assertTrue(viewport.layoutBounds.contains(viewport.sceneToLocal(node.localToScene(node.layoutBounds))))
}

internal fun recipeTooltip(node: Node, imageName: String?, deadline: Long): String {
    val active = AtomicBoolean(true)
    val lastState = AtomicReference("count=ZERO,ownerWindow=unknown,ownerNode=unknown,graphic=null,accessibleText=null,controlTooltipPresent=null,controlTooltipSame=null,onShowing=null,tooltipText=null")
    try {
        val owner = fx {
            check(active.get() && System.nanoTime() < deadline) { "Recipe hover期限超過: ${lastState.get()}" }
            assertNotNull(assertNotNull(node.scene).window)
        }
        while (true) {
            fx {
                check(active.get() && System.nanoTime() < deadline) { "Recipe hover期限超過: ${lastState.get()}" }
                assertTrue(node.scene?.window === owner)
                node.requestFocus()
                check(active.get() && System.nanoTime() < deadline)
                node.scene.root.applyCss()
                check(active.get() && System.nanoTime() < deadline)
                node.scene.root.layout()
            }
            fx {
                check(active.get() && System.nanoTime() < deadline)
                Event.fireEvent(node, recipeMouse(node, MouseEvent.MOUSE_MOVED))
            }
            var showing: Tooltip? = null
            while (showing == null) {
                showing = fx {
                    check(active.get() && System.nanoTime() < deadline) { "Recipe hover期限超過: ${lastState.get()}" }
                    assertTrue(node.scene?.window === owner)
                    val matches = Window.getWindows().filterIsInstance<Tooltip>().filter { it.isShowing && it.ownerWindow === owner }
                    val controlTooltip = (node as? Control)?.tooltip
                    lastState.set("count=${if (matches.isEmpty()) "ZERO" else if (matches.size == 1) "ONE" else "MULTIPLE"},ownerWindow=${matches.isNotEmpty()},ownerNode=unknown,graphic=null,accessibleText=null,controlTooltipPresent=${controlTooltip != null},controlTooltipSame=null,onShowing=null,tooltipText=null")
                    assertTrue(matches.size <= 1, "対象WindowのTooltipが複数: ${lastState.get()}")
                    matches.singleOrNull()?.also { tooltip ->
                        val ownerNode = tooltip.ownerNode
                        val graphic = tooltip.graphic
                        val kind = if (graphic == null) "NONE" else if (graphic is ImageView) "IMAGE_VIEW" else "OTHER"
                        val textKind = when (tooltip.text) {
                            "プレビューを準備しています" -> "INITIAL"
                            "プレビューは未準備です。内容は保持されています。" -> "PENDING"
                            "プレビュー素材を確認してください。内容は保持されています。" -> "ASSET_ERROR"
                            "プレビュー画像が表示上限を超えています。元データは保持されています。" -> "LIMIT"
                            "" -> "EMPTY"
                            else -> "OTHER"
                        }
                        lastState.set("count=ONE,ownerWindow=true,ownerNode=${if (ownerNode == null) "unknown" else (ownerNode === node).toString()},graphic=$kind,accessibleText=${(graphic as? ImageView)?.accessibleText != null},controlTooltipPresent=${controlTooltip != null},controlTooltipSame=${controlTooltip === tooltip},onShowing=${tooltip.onShowing != null},tooltipText=$textKind")
                        assertTrue(ownerNode == null || ownerNode === node, "Tooltip ownerNode不一致: ${lastState.get()}")
                    }
                }
                if (showing == null) {
                    assertTrue(System.nanoTime() < deadline, "Recipe slotのhover準備が完了しない: ${lastState.get()}")
                    Thread.sleep(20)
                }
            }
            val description = fx {
                check(active.get() && System.nanoTime() < deadline) { "Recipe hover期限超過: ${lastState.get()}" }
                val tooltip = assertNotNull(showing)
                assertTrue(tooltip.isShowing && tooltip.ownerWindow === owner && node.scene?.window === owner)
                val ownerNode = tooltip.ownerNode
                val graphic = tooltip.graphic
                val kind = if (graphic == null) "NONE" else if (graphic is ImageView) "IMAGE_VIEW" else "OTHER"
                val text = (graphic as? ImageView)?.accessibleText
                val controlTooltip = (node as? Control)?.tooltip
                val textKind = when (tooltip.text) {
                    "プレビューを準備しています" -> "INITIAL"
                    "プレビューは未準備です。内容は保持されています。" -> "PENDING"
                    "プレビュー素材を確認してください。内容は保持されています。" -> "ASSET_ERROR"
                    "プレビュー画像が表示上限を超えています。元データは保持されています。" -> "LIMIT"
                    "" -> "EMPTY"
                    else -> "OTHER"
                }
                lastState.set("count=ONE,ownerWindow=true,ownerNode=${if (ownerNode == null) "unknown" else (ownerNode === node).toString()},graphic=$kind,accessibleText=${text != null},controlTooltipPresent=${controlTooltip != null},controlTooltipSame=${controlTooltip === tooltip},onShowing=${tooltip.onShowing != null},tooltipText=$textKind")
                assertTrue(ownerNode == null || ownerNode === node, "Tooltip ownerNode不一致: ${lastState.get()}")
                assertTrue(graphic == null || graphic is ImageView, "Tooltip graphic型不一致: ${lastState.get()}")
                check(active.get() && System.nanoTime() < deadline)
                if (text != null && imageName != null) export(assertNotNull(assertNotNull(graphic as? ImageView).image), imageName)
                check(active.get() && System.nanoTime() < deadline)
                Event.fireEvent(node, recipeMouse(node, MouseEvent.MOUSE_EXITED))
                check(active.get() && System.nanoTime() < deadline)
                var primary: Throwable? = null
                try { Event.fireEvent(node, recipeMouse(node, MouseEvent.MOUSE_PRESSED)) }
                catch (error: Throwable) { primary = error; throw error }
                finally {
                    try { Event.fireEvent(node, recipeMouse(node, MouseEvent.MOUSE_RELEASED)) }
                    catch (cleanup: Throwable) { primary?.addSuppressed(cleanup) ?: throw cleanup }
                }
                tooltip.hide()
                text
            }
            if (description != null) return description
            assertTrue(System.nanoTime() < deadline, "Recipe slotのhover準備が完了しない: ${lastState.get()}")
            Thread.sleep(20)
        }
    } finally { active.set(false) }
}
