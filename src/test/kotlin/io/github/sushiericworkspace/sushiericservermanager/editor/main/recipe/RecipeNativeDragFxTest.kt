package io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe

import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.awaitFx
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.export
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.fx
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.startFx
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseDataView
import javafx.application.Platform
import java.awt.Robot as AwtRobot
import java.awt.GraphicsEnvironment
import java.awt.event.InputEvent
import java.awt.event.KeyEvent as AwtKeyEvent
import java.util.concurrent.Executors
import java.util.concurrent.Future
import javafx.beans.Observable
import javafx.beans.InvalidationListener
import javafx.stage.Screen
import javafx.beans.value.ChangeListener
import javafx.event.EventHandler
import javafx.geometry.Bounds
import javafx.geometry.Point2D
import javafx.scene.control.Label
import javafx.scene.control.Button
import javafx.scene.Node
import javafx.scene.control.ListView
import javafx.scene.control.ListCell
import javafx.scene.control.ScrollPane
import javafx.scene.input.DragEvent
import javafx.scene.input.MouseEvent
import javafx.scene.layout.VBox
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlin.test.*

/** 背景AWT入力を自己Stageへ送るopt-in試験。本物DragboardのDrop/Doneと編集結果まで確認する。 */
@EnabledOnOs(OS.WINDOWS, OS.MAC)
class RecipeNativeDragFxTest {
    @TempDir lateinit var directory: File

    @Test
    @EnabledIfEnvironmentVariable(named = "MANAGER_RECIPE_NATIVE_INPUT", matches = "true")
    fun 自己StageのnativeDragboardで配置移動取消と通常横キー操作を確認する() {
        startFx()
        RecipeFxFixture.create(directory, itemCount = 2).use { f ->
            val fxThread = fx { Thread.currentThread() }
            val input = RecipeAwtInputOwner()
            val trace = RecipeNativeInputTrace(fxThread)
            var preflightImageWritten = false
            val displayChanged = InvalidationListener {
                val error = IllegalStateException("表示構成が変化したため新入力を停止します。")
                input.displayValid.set(false); input.invalidatePlan(error); trace.fail(error)
            }
            fx { Screen.getScreens().addListener(displayChanged) }
            val detected = AtomicInteger(); val dropped = AtomicInteger(); val done = AtomicInteger()
            fx {
                f.root.addEventFilter(MouseEvent.DRAG_DETECTED) { detected.incrementAndGet() }
                f.root.addEventFilter(DragEvent.DRAG_DROPPED) { dropped.incrementAndGet() }
                f.root.addEventFilter(DragEvent.DRAG_DONE) { done.incrementAndGet() }
                f.stage.toFront(); f.stage.requestFocus()
            }
            awaitFx { f.stage.isFocused }
            var primary: Throwable? = null
            var released = false
            var completed = false
            var replacementCueConfirmed = false
            var horizontalBefore: Double? = null
            var horizontalAfter: Double? = null
            try {
                val cell = fx { f.board().palette.lookupAll(".list-cell").filterIsInstance<ListCell<*>>().single { it.index == 1 && !it.isEmpty } }
                val initialGeometry = preparePalette(f, cell, fx { f.slot(0) }, trace)
                val basis = fx {
                    export(f.stage.scene.snapshot(null), "recipe-native-preflight"); preflightImageWritten = true
                    Screen.getPrimary().let { screen ->
                        RecipeNativeDisplay(screen.bounds.minX, screen.bounds.minY, screen.bounds.width, screen.bounds.height, true) to (screen.outputScaleX to screen.outputScaleY)
                    }
                }
                input.initialize(basis.first, basis.second.first, basis.second.second)
                drag(input, f, cell, fx { f.slot(0) }, false, trace, 1, initialGeometry) {
                    assertTrue(f.slot(0).text.contains("item.0000"))
                    assertTrue("recipe-drop-target" in f.slot(0).styleClass)
                    assertTrue(descendants(f.board()).filterIsInstance<Label>().any { it.text == "1番目を置換: ${f.slot(0).text}" })
                    replacementCueConfirmed = true
                    export(f.stage.scene.snapshot(null), "recipe-native-replacement-cue")
                }
                awaitFx { f.slot(0).text.contains("item.0001") }
                fx { assertTrue(f.logic.onUndo()); f.layout(); assertTrue(f.slot(0).text.contains("item.0000")); assertFalse(f.logic.onUndo()) }
                drag(input, f, fx { f.slot(0) }, fx { f.slot(2) }, false, trace, 2)
                awaitFx { f.slot(0).text == "空き" && f.slot(2).text.contains("item.0000") }
                fx { assertTrue(f.logic.onUndo()); f.layout(); assertTrue(f.slot(0).text.contains("item.0000")); assertEquals("空き", f.slot(2).text); assertFalse(f.logic.onUndo()) }
                drag(input, f, fx { f.slot(0) }, fx { f.slot(2) }, true, trace, 3)
                fx { assertTrue(f.slot(0).text.contains("item.0000")); assertEquals("空き", f.slot(2).text); assertFalse(f.logic.onUndo()) }
                awaitFx { done.get() >= 3 }
                assertTrue(detected.get() >= 3); assertTrue(dropped.get() >= 2)
                assertEquals(3, input.initialMoves.get()); assertEquals(3, input.bootstrapMoves.get())
                assertEquals(18, input.permittedMoves.get()); assertEquals(24, input.stepMoves.get())
                fx { export(f.stage.scene.snapshot(null), "recipe-native-after-drag") }
                val form = prepareHorizontal(f, trace)
                fx {
                    horizontalBefore = form.hvalue
                    export(f.stage.scene.snapshot(null), "recipe-native-horizontal-before")
                }
                awaitFx { f.stage.isFocused && f.stage.scene.focusOwner === form }
                val horizontalGeometry = fx { geometry(f, form, form) }
                val horizontalSafety = fx {
                    assertTrue(f.stage.isFocused && f.stage.scene.focusOwner === form)
                    requireRecipeDragGeometry(horizontalGeometry, geometry(f, form, form))
                    RecipeNativeSafety(4, horizontalGeometry, true, f.stage.isShowing, System.nanoTime())
                }
                input.call {
                    input.input(trace, "horizontal:RIGHT", {
                        check(horizontalSafety.focused && horizontalSafety.showing && System.nanoTime() - horizontalSafety.at <= 250_000_000L)
                        input.requireDisplay(horizontalGeometry, listOf(horizontalGeometry.source))
                    }) { input.key(it, AwtKeyEvent.VK_RIGHT) }
                }
                awaitFx { form.hvalue > assertNotNull(horizontalBefore) }
                fx { horizontalAfter = form.hvalue; export(f.stage.scene.snapshot(null), "recipe-native-horizontal-after") }
                completed = true
            } catch (error: Throwable) {
                trace.fail(error)
                primary = error
                throw error
            } finally {
                trace.armed.set(false)
                val failures = mutableListOf<Throwable>()
                try { input.finish(); released = input.released.get() } catch (error: Throwable) { failures += error }
                try { input.close() } catch (error: Throwable) { failures += error }
                released = input.released.get()
                try { fx { Screen.getScreens().removeListener(displayChanged) } } catch (error: Throwable) { failures += error }
                val ownerState = if (input.terminated.get()) "TERMINATED" else "HOLDING_INPUT_THREAD"
                val effectiveComplete = completed && input.terminated.get()
                if (!input.terminated.get()) {
                    val error = IllegalStateException("背景入力threadが未終端のためowner JVMを自然終端まで保持します。")
                    trace.fail(error); failures += error
                }
                try {
                    retainRecipeNativeOwner(input.terminated.get(), {
                        System.getProperty("manager.recipeNativeEvidence")?.let { location ->
                            val record = """{"finalComplete":$effectiveComplete,"injectedInputReleased":$released,"replacementCueConfirmed":$replacementCueConfirmed,"detected":${detected.get()},"dropped":${dropped.get()},"done":${done.get()},"horizontalBefore":$horizontalBefore,"horizontalAfter":$horizontalAfter,"humanGesture":false,"horizontalWheel":false,"inputApi":"AWT","inputThreadCount":${input.threads.get()},"inputThreadTerminated":${input.terminated.get()},"inputOwnerState":"$ownerState","sourcePositionMoves":${input.sourceMoves.get()},"initialDragStepMoves":${input.initialMoves.get()},"bootstrapDragStepMoves":${input.bootstrapMoves.get()},"permittedDragStepMoves":${input.permittedMoves.get()},"dragStepMoves":${input.stepMoves.get()},"callbackInputMoves":0,"preflightImageWritten":$preflightImageWritten,"displayGate":${input.displayEvidence},"callbacks":${trace.callbacksJson()},"geometryDiagnosticAttempted":${trace.geometryDiagnosticAttempted},"geometryDiagnostic":${trace.geometryDiagnostic},"inputDisarmed":${!trace.armed.get()},"diagnostic":${trace.diagnostic}}"""
                            val target = Path.of(location); Files.createDirectories(target.parent)
                            val bytes = record.toByteArray(Charsets.UTF_8); check(bytes.size <= 16384)
                            Files.write(target, bytes, java.nio.file.StandardOpenOption.CREATE_NEW)
                        }
                    }, { input.awaitNatural { failures += it } })
                } catch (error: Throwable) { failures += error }
                if (failures.isNotEmpty()) {
                    val original = primary ?: failures.first()
                    failures.filter { it !== original }.forEach(original::addSuppressed)
                    if (primary == null) throw original
                }
            }
        }
    }

    private fun prepareHorizontal(f: RecipeFxFixture, trace: RecipeNativeInputTrace): ScrollPane {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        val active = AtomicBoolean(true)
        val ready = CompletableFuture<ScrollPane>()
        val widths = AtomicReference("horizontal widths: unknown")
        val (form, oldSceneWidth) = fx {
            requireRecipeNativePreparation(active, trace.armed, deadline)
            val board = f.board()
            descendants(f.root).filterIsInstance<ScrollPane>().single {
                it.content is VBox && descendants(it.content).any { node -> node === board }
            } to f.stage.scene.width
        }
        val scene = fx { requireRecipeNativePreparation(active, trace.armed, deadline); f.stage.scene }
        var previous: List<Double>? = null
        var evaluating = false
        val pulse = Runnable {
            if (active.get()) try {
                check(!evaluating); evaluating = true
                try {
                    requireRecipeNativePreparation(active, trace.armed, deadline)
                    val current = listOf(f.stage.width, scene.width, form.width, form.content.layoutBounds.width, form.viewportBounds.width)
                    requireRecipeNativePreparation(active, trace.armed, deadline)
                    widths.set(("horizontal widths stage/scene/form/content/viewport=" + current.joinToString { if (it.isFinite()) it.toString() else "unknown" }).take(512))
                    check(current.all { it.isFinite() })
                    if (current == previous && current[1] < oldSceneWidth && current[3] > current[4]) {
                        requireRecipeNativePreparation(active, trace.armed, deadline)
                        active.set(false); ready.complete(form)
                    } else {
                        previous = current; Platform.requestNextPulse()
                    }
                } finally { evaluating = false }
            } catch (error: Throwable) { active.set(false); ready.completeExceptionally(error) }
        }
        var primary: Throwable? = null
        try {
            fx {
                requireRecipeNativePreparation(active, trace.armed, deadline)
                scene.addPostLayoutPulseListener(pulse)
                requireRecipeNativePreparation(active, trace.armed, deadline)
                f.stage.width = 950.0
                requireRecipeNativePreparation(active, trace.armed, deadline)
                form.hvalue = 0.0; form.vvalue = 0.0; form.requestFocus()
                requireRecipeNativePreparation(active, trace.armed, deadline)
                f.layout()
                requireRecipeNativePreparation(active, trace.armed, deadline)
                Platform.requestNextPulse()
            }
            val remaining = deadline - System.nanoTime()
            check(remaining > 0)
            return ready.get(remaining, TimeUnit.NANOSECONDS)
        } catch (error: Throwable) {
            primary = error; error.addSuppressed(IllegalStateException(widths.get())); throw error
        } finally {
            active.set(false)
            try { fx { scene.removePostLayoutPulseListener(pulse) } }
            catch (error: Throwable) { if (primary == null) throw error else primary.addSuppressed(error) }
        }
    }

    private fun paletteList(f: RecipeFxFixture, source: ListCell<*>): ListView<*> {
        val list = generateSequence(source.parent) { it.parent }.filterIsInstance<ListView<*>>().single()
        assertSame(list, source.listView)
        assertSame(f.board().palette, list.parent)
        assertEquals(1, source.index)
        assertFalse(source.isEmpty)
        assertSame(source, list.lookupAll(".list-cell").filterIsInstance<ListCell<*>>().single { it.index == 1 && !it.isEmpty })
        assertSame(list.items[1], source.item)
        assertEquals(f.items[1].internalId, assertIs<ItemBaseDataView>(source.item).internalId)
        return list
    }

    private fun preparePalette(f: RecipeFxFixture, source: ListCell<*>, target: Node, trace: RecipeNativeInputTrace): RecipeDragGeometry {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        val ready = CompletableFuture<RecipeDragGeometry>()
        val active = AtomicBoolean(true)
        val (scene, list) = fx {
            requireRecipeNativePreparation(active, trace.armed, deadline)
            f.stage.scene to paletteList(f, source)
        }
        var previous: RecipeDragGeometry? = null
        var evaluating = false
        val pulse = Runnable {
            if (active.get()) try {
                check(!evaluating); evaluating = true
                try {
                    requireRecipeNativePreparation(active, trace.armed, deadline)
                    assertTrue(f.stage.isFocused && list.isFocused)
                    assertSame(list, scene.focusOwner)
                    requireRecipeNativePreparation(active, trace.armed, deadline)
                    val current = geometry(f, source, target)
                    requireRecipeNativePreparation(active, trace.armed, deadline)
                    if (active.get()) {
                        if (current == previous) {
                            requireRecipeNativePreparation(active, trace.armed, deadline)
                            active.set(false); trace.phase = "palette-focus:ready"; ready.complete(current)
                        } else {
                            previous = current; Platform.requestNextPulse()
                        }
                    }
                } finally { evaluating = false }
            } catch (error: Throwable) { active.set(false); ready.completeExceptionally(error) }
        }
        var primary: Throwable? = null
        try {
            fx {
                requireRecipeNativePreparation(active, trace.armed, deadline)
                scene.addPostLayoutPulseListener(pulse)
                requireRecipeNativePreparation(active, trace.armed, deadline)
                list.requestFocus()
                requireRecipeNativePreparation(active, trace.armed, deadline)
                f.layout()
                requireRecipeNativePreparation(active, trace.armed, deadline)
                Platform.requestNextPulse()
            }
            val remaining = deadline - System.nanoTime()
            check(remaining > 0)
            return ready.get(remaining, TimeUnit.NANOSECONDS)
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            active.set(false)
            try { fx { scene.removePostLayoutPulseListener(pulse) } }
            catch (error: Throwable) { if (primary == null) throw error else primary.addSuppressed(error) }
        }
    }

    private fun drag(input: RecipeAwtInputOwner, f: RecipeFxFixture, source: Node, target: Node, cancel: Boolean, trace: RecipeNativeInputTrace, gesture: Int, initialGeometry: RecipeDragGeometry? = null, beforeDrop: () -> Unit = {}) {
        val expected = fx {
            assertTrue(f.stage.isFocused)
            if (initialGeometry != null) {
                val list = paletteList(f, assertIs<ListCell<*>>(source))
                assertSame(list, f.stage.scene.focusOwner)
                assertTrue(list.isFocused)
            }
            val current = geometry(f, source, target)
            if (initialGeometry != null) requireRecipeDragGeometry(initialGeometry, current)
            initialGeometry ?: current
        }
        val positions = (1..8).map { step ->
            val ratio = step / 8.0
            Point2D(expected.source.x + (expected.target.x - expected.source.x) * ratio, expected.source.y + (expected.target.y - expected.source.y) * ratio)
        }
        val plan = RecipeNativeAwtPlan(gesture, source, positions, expected)
        input.track(plan)
        val latest = AtomicReference<RecipeNativeSafety>()
        val cueReady = CompletableFuture<RecipeNativeSafety>(); val doneReady = CompletableFuture<Unit>()
        val firstFailure = AtomicReference<Throwable?>()
        val status = fx { descendants(f.board()).filterIsInstance<Label>().single { it.text == "材料は1枠1個です。" } }
        val cue = fx { "${target.id.substringAfterLast('-').toInt() + 1}番目を置換: ${(target as Button).text}" }
        var droppedHere = false; var inOver = false; var evaluating = false
        val checkingSafety = AtomicBoolean()
        fun safety(): RecipeNativeSafety? {
            assertTrue(Platform.isFxApplicationThread())
            return reevaluateRecipeNativeSafety(expected, { plan.moving && trace.armed.get() }, checkingSafety,
                { RecipeNativeSafety(gesture, geometry(f, source, target), f.stage.isFocused, f.stage.isShowing, System.nanoTime()) },
                { value -> plan.requireSafety(value, System.nanoTime()) }, { value -> latest.set(value) },
                { reason, current, error ->
                    val original = plan.failure ?: firstFailure.get() ?: error
                    rejectRecipeNativeSafety(plan, trace.armed, original) { trace.recordGeometry(reason, expected, current, original) }
                }, { plan.normalStopping && trace.armed.get() && firstFailure.get() == null })
        }
        fun fail(error: Throwable): Throwable {
            val original = plan.invalidate(error)
            if (!firstFailure.compareAndSet(null, original)) return checkNotNull(firstFailure.get())
            trace.fail(original); cueReady.completeExceptionally(original); doneReady.completeExceptionally(original)
            return original
        }
        fun finishIfReady() {
            try {
                assertTrue(Platform.isFxApplicationThread())
                if (status.text != cue || "recipe-drop-target" !in target.styleClass || !plan.reserveCue()) return
                check(!evaluating); evaluating = true
                try { val value = safety() ?: return; beforeDrop(); cueReady.complete(value) }
                finally { evaluating = false }
            } catch (error: Throwable) { fail(error) }
        }
        val over = EventHandler<DragEvent> { event ->
            try {
                val point = Point2D(event.screenX, event.screenY)
                trace.observeCallback(RecipeNativeCallbackKind.OVER) { plan.observation(RecipeNativeCallbackKind.OVER, event.gestureSource, point, trace.armed.get()) }
                assertTrue(Platform.isFxApplicationThread())
                if (!plan.moving) return@EventHandler
                check(!inOver && !evaluating); inOver = true
                try { val value = safety() ?: return@EventHandler; plan.over(event.gestureSource, point, value); finishIfReady() }
                finally { inOver = false }
            } catch (error: Throwable) { fail(error) }
        }
        val entered = EventHandler<DragEvent> { event ->
            try {
                trace.observeCallback(RecipeNativeCallbackKind.ENTERED_TARGET) { plan.observation(RecipeNativeCallbackKind.ENTERED_TARGET, event.gestureSource, Point2D(event.screenX, event.screenY), trace.armed.get()) }
                assertTrue(Platform.isFxApplicationThread())
            } catch (error: Throwable) { fail(error) }
        }
        val drop = EventHandler<DragEvent> { event ->
            if (event.gestureSource === source) try {
                assertTrue(Platform.isFxApplicationThread())
                assertTrue(generateSequence(event.target as? Node) { it.parent }.any { it === target }); droppedHere = true
            } catch (error: Throwable) { fail(error) }
        }
        val done = EventHandler<DragEvent> { event ->
            if (event.gestureSource === source && firstFailure.get() == null) try {
                assertTrue(Platform.isFxApplicationThread() && plan.cueReserved)
                if (cancel) { assertFalse(droppedHere); assertNull(event.transferMode) } else { assertTrue(droppedHere); assertNotNull(event.transferMode) }
                doneReady.complete(Unit)
            } catch (error: Throwable) { fail(error) }
        }
        val changed = ChangeListener<String> { _, _, _ -> finishIfReady() }
        val invalidated = InvalidationListener {
            if (plan.moving) try { safety() } catch (error: Throwable) { fail(error) }
        }
        val observables = fx { listOf<Observable>(f.stage.focusedProperty(), f.stage.showingProperty(), f.stage.xProperty(), f.stage.yProperty(),
            f.stage.widthProperty(), f.stage.heightProperty(), f.stage.outputScaleXProperty(), f.stage.outputScaleYProperty(), f.stage.scene.widthProperty(), f.stage.scene.heightProperty(),
            source.sceneProperty(), source.boundsInLocalProperty(), source.localToSceneTransformProperty(), source.visibleProperty(), source.disabledProperty(),
            target.sceneProperty(), target.boundsInLocalProperty(), target.localToSceneTransformProperty(), target.visibleProperty(), target.disabledProperty()) }
        fx {
            f.stage.scene.addEventFilter(DragEvent.DRAG_OVER, over); f.stage.scene.addEventFilter(DragEvent.DRAG_ENTERED_TARGET, entered)
            f.stage.scene.addEventFilter(DragEvent.DRAG_DROPPED, drop); f.stage.scene.addEventFilter(DragEvent.DRAG_DONE, done)
            status.textProperty().addListener(changed); observables.forEach { it.addListener(invalidated) }
            plan.publishInitial(assertNotNull(safety()))
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        val future = input.submit {
            var primary: Throwable? = null
            try {
                input.requireDisplay(expected, positions + expected.source + expected.target)
                fun initialGate() { plan.initialSafety(System.nanoTime()); plan.requireSafety(latest.get(), System.nanoTime()); check(plan.active) }
                input.input(trace, "drag$gesture:source-move", ::initialGate) { it.mouseMove(kotlin.math.round(expected.source.x).toInt(), kotlin.math.round(expected.source.y).toInt()); input.sourceMoves.incrementAndGet() }
                input.input(trace, "drag$gesture:press", ::initialGate) { input.pressMouse(it) }
                for (step in 1..8) {
                    if (step == 2) { Thread.sleep(50); check(plan.bootstrap(System.nanoTime())) }
                    val permit = plan.await(step, deadline)
                    input.input(trace, "drag$gesture:step$step", { plan.requireCurrent(permit, latest.get(), System.nanoTime()) }) {
                        val point = positions[step - 1]; it.mouseMove(kotlin.math.round(point.x).toInt(), kotlin.math.round(point.y).toInt())
                        when (step) { 1 -> input.initialMoves.incrementAndGet(); 2 -> input.bootstrapMoves.incrementAndGet(); else -> input.permittedMoves.incrementAndGet() }
                        input.stepMoves.incrementAndGet()
                    }
                }
                val proof = cueReady.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
                input.input(trace, "drag$gesture:finish", { plan.requireSafety(latest.get(), System.nanoTime()); plan.releasing(proof, System.nanoTime()) }) {
                    if (cancel) input.key(it, AwtKeyEvent.VK_ESCAPE) else input.releaseMouse(it)
                }
                doneReady.get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
                plan.closeNormally()
            } catch (error: Throwable) {
                val cause = if (error is ExecutionException) error.cause ?: error else error
                primary = fail(firstFailure.get() ?: cause); throw primary
            } finally {
                if (primary != null) plan.invalidate(primary)
                try { input.releaseOwned() } catch (cleanup: Throwable) { fail(cleanup); if (primary != null) primary.addSuppressed(cleanup) else throw cleanup }
            }
        }
        var primary: Throwable? = null
        try {
            future.get(15, TimeUnit.SECONDS)
        } catch (error: Throwable) {
            val cause = if (error is ExecutionException) error.cause ?: error else error
            primary = fail(firstFailure.get() ?: cause); throw primary
        } finally {
            if (primary != null) plan.invalidate(primary)
            try { fx {
                f.stage.scene.removeEventFilter(DragEvent.DRAG_OVER, over); f.stage.scene.removeEventFilter(DragEvent.DRAG_ENTERED_TARGET, entered)
                f.stage.scene.removeEventFilter(DragEvent.DRAG_DROPPED, drop); f.stage.scene.removeEventFilter(DragEvent.DRAG_DONE, done)
                status.textProperty().removeListener(changed); observables.forEach { it.removeListener(invalidated) }
            } } catch (cleanup: Throwable) {
                val original = preserveRecipeNativeCleanupFailure(primary, firstFailure.get(), plan.failure, cleanup)
                if (primary == null) throw original
            } finally { input.untrack(plan) }
        }
        firstFailure.get()?.let { throw it }
    }

    private fun geometry(f: RecipeFxFixture, source: Node, target: Node): RecipeDragGeometry {
        val viewports = mutableListOf<Bounds>()
        fun center(node: Node): Point2D {
            assertTrue(node.scene === f.stage.scene && node.isVisible && !node.isDisabled)
            val bounds = assertNotNull(node.localToScreen(node.layoutBounds))
            val point = Point2D((bounds.minX + bounds.maxX) / 2, (bounds.minY + bounds.maxY) / 2)
            assertTrue(point.x.isFinite() && point.y.isFinite())
            assertTrue(assertNotNull(f.root.localToScreen(f.root.layoutBounds)).contains(point))
            var parent = node.parent
            while (parent != null) {
                assertTrue(parent.isVisible)
                if (parent is ScrollPane) {
                    val viewport = assertNotNull(parent.lookup(".viewport"))
                    val area = assertNotNull(viewport.localToScreen(viewport.layoutBounds))
                    assertTrue(area.contains(point)); viewports += area
                }
                parent = parent.parent
            }
            viewports += bounds
            return point
        }
        val from = center(source); val to = center(target)
        return RecipeDragGeometry(listOf(f.stage.x, f.stage.y, f.stage.width, f.stage.height,
            f.stage.scene.width, f.stage.scene.height, f.stage.outputScaleX, f.stage.outputScaleY), from, to, viewports.toList())
    }
}

internal fun preserveRecipeNativeCleanupFailure(primary: Throwable?, first: Throwable?, failed: Throwable?, cleanup: Throwable): Throwable {
    val original = primary ?: first ?: failed ?: cleanup
    if (original !== cleanup) original.addSuppressed(cleanup)
    return original
}

internal fun requireRecipeNativePreparation(active: AtomicBoolean, armed: AtomicBoolean, deadline: Long, now: Long = System.nanoTime()) {
    check(active.get() && armed.get() && now < deadline)
}

internal data class RecipeDragGeometry(val stage: List<Double>, val source: Point2D, val target: Point2D, val viewports: List<Bounds>)

internal fun requireRecipeDragGeometry(expected: RecipeDragGeometry, current: RecipeDragGeometry) {
    check(expected == current) { "自己Stageまたは対象のgeometryが変化したため新規入力を停止します。" }
}

internal fun requireRecipeNativeInput(armed: AtomicBoolean, action: () -> Unit) {
    check(armed.get()) { "失敗後の新規入力は停止しています。" }
    action()
}

internal class RecipeNativeInputTrace(private val fxThread: Thread) {
    val armed = AtomicBoolean(true)
    @Volatile var phase = "created"
    @Volatile var diagnostic: JsonObject? = null
        private set
    private val captured = AtomicBoolean()
    private val geometryCaptured = AtomicBoolean()
    val geometryDiagnosticAttempted get() = geometryCaptured.get()
    @Volatile var geometryDiagnostic: JsonObject? = null
        private set
    fun recordGeometry(reason: RecipeNativeSafetyReason, expected: RecipeDragGeometry, current: RecipeDragGeometry?, error: Throwable) {
        if (!geometryCaptured.compareAndSet(false, true)) return
        try { geometryDiagnostic = recipeNativeGeometryDiagnostic(reason, expected, current) }
        catch (diagnostic: Throwable) { error.addSuppressed(diagnostic) }
    }

    private var overCount = 0
    private var enteredCount = 0
    private val callbackSamples = mutableListOf<JsonObject>()

    @Synchronized fun observeCallback(kind: RecipeNativeCallbackKind, sample: () -> JsonObject) {
        when (kind) {
            RecipeNativeCallbackKind.OVER -> overCount++
            RecipeNativeCallbackKind.ENTERED_TARGET -> enteredCount++
        }
        if (callbackSamples.size < 16) callbackSamples += sample()
    }

    @Synchronized fun callbacksJson(): JsonObject = buildJsonObject {
        put("totalCount", overCount + enteredCount); put("overCount", overCount); put("enteredTargetCount", enteredCount)
        put("samples", buildJsonArray { callbackSamples.forEach { add(it) } })
    }


    fun fail(error: Throwable) {
        armed.set(false)
        if (!captured.compareAndSet(false, true)) return
        try {
            diagnostic = buildJsonObject {
                put("phase", phase.take(128)); put("exception", error.javaClass.name.take(128))
                put("fxThreadName", fxThread.name.take(128)); put("fxThreadState", fxThread.state.name)
                put("fxThreadStack", buildJsonArray { fxThread.stackTrace.take(32).forEach { add(it.toString().take(256)) } })
            }
        } catch (failure: Throwable) { error.addSuppressed(failure) }
    }
}

internal enum class RecipeNativeCallbackKind { OVER, ENTERED_TARGET }
internal data class RecipeNativeSafety(val generation: Int, val geometry: RecipeDragGeometry, val focused: Boolean, val showing: Boolean, val at: Long)
internal data class RecipeNativePermit(val generation: Int, val step: Int, val safety: RecipeNativeSafety)
private data class RecipeNativePlanState(val active: Boolean = true, val step: Int = 0, val permit: RecipeNativePermit? = null, val finalOver: Boolean = false, val cue: Boolean = false, val releasing: Boolean = false, val failure: Throwable? = null)

internal class RecipeNativeAwtPlan(val generation: Int, private val source: Any, val positions: List<Point2D>, private val expected: RecipeDragGeometry) {
    private val state = AtomicReference(RecipeNativePlanState())
    private val permits = (1..8).associateWith { CompletableFuture<RecipeNativePermit>() }
    @Volatile private var initial: RecipeNativeSafety? = null
    init { require(positions.size == 8) }
    val active get() = state.get().active
    val moving get() = state.get().let { it.active && !it.releasing }
    val normalStopping get() = state.get().let { it.releasing && it.failure == null }
    val failure get() = state.get().failure
    val cueReserved get() = state.get().cue

    fun publishInitial(safety: RecipeNativeSafety) {
        check(initial == null); initial = safety
        publish(1, safety, 0)
    }
    private fun publish(step: Int, safety: RecipeNativeSafety, previous: Int): Boolean {
        while (true) {
            val old = state.get()
            if (!old.active || old.releasing || old.step != previous || old.permit != null) return false
            val permit = RecipeNativePermit(generation, step, safety)
            if (state.compareAndSet(old, old.copy(permit = permit))) { permits.getValue(step).complete(permit); return true }
        }
    }
    fun bootstrap(now: Long): Boolean {
        val safety = checkNotNull(initial)
        requireSafety(safety, now)
        return publish(2, safety, 1)
    }
    fun initialSafety(now: Long): RecipeNativeSafety = checkNotNull(initial).also { requireSafety(it, now); check(active) }
    fun consume(step: Int, now: Long): RecipeNativePermit? {
        while (true) {
            val old = state.get(); val permit = old.permit ?: return null
            if (!old.active || permit.step != step || permit.generation != generation) return null
            requireSafety(permit.safety, now)
            if (state.compareAndSet(old, old.copy(step = step, permit = null))) return permit
        }
    }
    fun await(step: Int, deadline: Long): RecipeNativePermit {
        permits.getValue(step).get((deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
        return checkNotNull(consume(step, System.nanoTime())) { "失効済みまたは消費済みの入力許可です。" }
    }
    fun requireSafety(safety: RecipeNativeSafety, now: Long) {
        check(safety.generation == generation && safety.focused && safety.showing)
        requireRecipeDragGeometry(expected, safety.geometry)
        check(now >= safety.at && now - safety.at <= 250_000_000L) { "自己Stageの公開状態が鮮度上限を超えました。" }
    }
    fun requireCurrent(permit: RecipeNativePermit, latest: RecipeNativeSafety, now: Long) {
        val current = state.get()
        check(current.active && current.step == permit.step && permit.generation == generation)
        requireSafety(permit.safety, now); requireSafety(latest, now)
    }
    fun over(token: Any?, point: Point2D, safety: RecipeNativeSafety): Boolean {
        while (true) {
            val old = state.get()
            if (!old.active || old.releasing || token !== source || old.step < 2) return false
            val expectedPoint = positions[old.step - 1]
            if (!point.x.isFinite() || !point.y.isFinite() || kotlin.math.abs(point.x - expectedPoint.x) > 1 || kotlin.math.abs(point.y - expectedPoint.y) > 1) return false
            if (old.step == 8) {
                if (old.finalOver) return false
                if (state.compareAndSet(old, old.copy(finalOver = true))) return true
            } else return publish(old.step + 1, safety, old.step)
        }
    }
    fun reserveCue(): Boolean {
        while (true) {
            val old = state.get()
            if (!old.active || old.step != 8 || !old.finalOver || old.cue) return false
            if (state.compareAndSet(old, old.copy(cue = true))) return true
        }
    }
    fun releasing(safety: RecipeNativeSafety, now: Long) {
        requireSafety(safety, now)
        while (true) {
            val old = state.get(); check(old.active && old.cue && old.step == 8)
            if (state.compareAndSet(old, old.copy(releasing = true))) return
        }
    }
    fun closeNormally() {
        state.updateAndGet {
            it.failure?.let { error -> throw error }
            check(it.releasing && it.cue && it.step == 8)
            it.copy(active = false, permit = null)
        }
    }
    fun invalidate(error: Throwable = IllegalStateException("入力許可は失効しています。")): Throwable {
        val failed = state.updateAndGet { it.copy(active = false, permit = null, failure = it.failure ?: error) }
        val original = checkNotNull(failed.failure)
        permits.values.forEach { it.completeExceptionally(original) }
        return original
    }
    fun observation(kind: RecipeNativeCallbackKind, token: Any?, point: Point2D, armed: Boolean): JsonObject {
        val current = state.get(); val step = current.step.coerceAtLeast(1); val expectedPoint = positions[step - 1]
        val dx = point.x - expectedPoint.x; val dy = point.y - expectedPoint.y
        val reject = when {
            !current.active -> "INACTIVE"
            current.step == 0 -> "NOT_STARTED"
            token !== source -> "OTHER_SOURCE"
            !dx.isFinite() || !dy.isFinite() -> "POSITION_UNKNOWN"
            kotlin.math.abs(dx) > 1 || kotlin.math.abs(dy) > 1 -> "POSITION_MISMATCH"
            !armed -> "DISARMED"
            else -> "NONE"
        }
        return buildJsonObject {
            put("kind", kind.name); put("sameSource", token === source); put("started", current.step > 0); put("active", current.active); put("armed", armed)
            put("step", step); put("reject", reject)
            fun number(key: String, value: Double) { put(key, value.takeIf { it.isFinite() }) }
            number("x", point.x); number("y", point.y); number("expectedX", expectedPoint.x); number("expectedY", expectedPoint.y); number("dx", dx); number("dy", dy)
        }
    }
}

internal class RecipeNativeOwnedInput {
    private var primary = false
    private var primaryReleaseAttempted = false
    private val keys = linkedMapOf<Int, Boolean>()
    val released get() = !primary && keys.isEmpty()
    fun pressPrimary() { check(!primary); primary = true; primaryReleaseAttempted = false }
    fun pressKey(key: Int) { check(key !in keys); keys[key] = false }
    fun releasePrimary(action: () -> Unit) {
        if (!primary || primaryReleaseAttempted) return
        primaryReleaseAttempted = true; action(); primary = false
    }
    fun releaseKey(key: Int, action: () -> Unit) {
        if (keys[key] != false) return
        keys[key] = true; action(); keys.remove(key)
    }
    fun releaseAll(mouse: () -> Unit, key: (Int) -> Unit) {
        var failure: Throwable? = null
        try { releasePrimary(mouse) } catch (error: Throwable) { failure = error }
        keys.keys.toList().forEach { value ->
            try { releaseKey(value) { key(value) } }
            catch (error: Throwable) { val original = failure; if (original == null) failure = error else original.addSuppressed(error) }
        }
        failure?.let { throw it }
    }
}

internal data class RecipeNativeDisplay(val x: Double, val y: Double, val width: Double, val height: Double, val identity: Boolean)
internal fun requireRecipeNativeDisplay(fx: RecipeNativeDisplay, scaleX: Double, scaleY: Double, devices: List<RecipeNativeDisplay>, geometry: RecipeDragGeometry?, points: List<Point2D>): Int {
    check(devices.size in 1..8 && scaleX == 1.0 && scaleY == 1.0 && fx.identity)
    val index = devices.indices.single { devices[it] == fx && devices[it].identity }
    fun contains(x: Double, y: Double) = x.isFinite() && y.isFinite() && x >= fx.x && y >= fx.y && x < fx.x + fx.width && y < fx.y + fx.height
    points.forEach { check(contains(it.x, it.y) && contains(kotlin.math.round(it.x), kotlin.math.round(it.y))) }
    geometry?.let { g ->
        check(contains(g.stage[0], g.stage[1]) && contains(g.stage[0] + g.stage[2] - 1, g.stage[1] + g.stage[3] - 1))
        check(g.stage[6] == 1.0 && g.stage[7] == 1.0)
    }
    return index
}

private class RecipeAwtInputOwner : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val currentPlan = AtomicReference<RecipeNativeAwtPlan?>()
    fun track(plan: RecipeNativeAwtPlan) { check(currentPlan.compareAndSet(null, plan)) }
    fun untrack(plan: RecipeNativeAwtPlan) { currentPlan.compareAndSet(plan, null) }
    fun invalidatePlan(error: Throwable) { currentPlan.get()?.invalidate(error) }
    private var robot: AwtRobot? = null
    private var display: RecipeNativeDisplay? = null
    private val owned = RecipeNativeOwnedInput()
    val displayValid = AtomicBoolean(true)
    val released = AtomicBoolean(true)
    val threads = AtomicInteger()
    val terminated = AtomicBoolean()
    val sourceMoves = AtomicInteger(); val initialMoves = AtomicInteger(); val bootstrapMoves = AtomicInteger(); val permittedMoves = AtomicInteger(); val stepMoves = AtomicInteger()
    @Volatile var displayEvidence: JsonObject? = null
        private set
    private var inputThread: Thread? = null

    fun initialize(fx: RecipeNativeDisplay, scaleX: Double, scaleY: Double) = call {
        check(inputThread == null && displayValid.get()); inputThread = Thread.currentThread(); threads.incrementAndGet()
        val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        check(devices.size in 1..8)
        val descriptions = devices.map { device -> device.defaultConfiguration.let { c ->
            RecipeNativeDisplay(c.bounds.x.toDouble(), c.bounds.y.toDouble(), c.bounds.width.toDouble(), c.bounds.height.toDouble(), c.defaultTransform.isIdentity)
        } }
        val selected = requireRecipeNativeDisplay(fx, scaleX, scaleY, descriptions, null, emptyList())
        display = descriptions[selected]
        displayEvidence = buildJsonObject { put("deviceCount", devices.size); put("selectedIndex", selected); put("identityTransform", true); put("x", fx.x); put("y", fx.y); put("width", fx.width); put("height", fx.height) }
        check(displayValid.get())
        robot = AwtRobot(devices[selected]).apply { isAutoWaitForIdle = false; autoDelay = 0 }
    }
    fun requireDisplay(geometry: RecipeDragGeometry, points: List<Point2D>) { check(displayValid.get()); val value = checkNotNull(display); requireRecipeNativeDisplay(value, 1.0, 1.0, listOf(value), geometry, points) }
    fun submit(action: () -> Unit): Future<*> = executor.submit(action)
    fun call(action: () -> Unit) {
        try { submit(action).get(30, TimeUnit.SECONDS) }
        catch (error: ExecutionException) { throw (error.cause ?: error) }
    }
    fun input(trace: RecipeNativeInputTrace, label: String, gate: () -> Unit, action: (AwtRobot) -> Unit) {
        check(Thread.currentThread() === inputThread && displayValid.get())
        trace.phase = "$label:entered"
        requireRecipeNativeInput(trace.armed) { gate(); trace.phase = "$label:guarded"
            requireRecipeNativeInput(trace.armed) { trace.phase = "$label:robot"; action(checkNotNull(robot)); trace.phase = "$label:robot-returned" }
        }
    }
    fun pressMouse(robot: AwtRobot) { owned.pressPrimary(); released.set(false); robot.mousePress(InputEvent.BUTTON1_DOWN_MASK) }
    fun releaseMouse(robot: AwtRobot) { owned.releasePrimary { robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }; released.set(owned.released) }
    fun key(robot: AwtRobot, code: Int) { owned.pressKey(code); released.set(false); robot.keyPress(code); owned.releaseKey(code) { robot.keyRelease(code) }; released.set(owned.released) }
    fun releaseOwned() {
        check(inputThread == null || Thread.currentThread() === inputThread)
        val value = robot
        if (value != null) owned.releaseAll({ value.mouseRelease(InputEvent.BUTTON1_DOWN_MASK) }, value::keyRelease)
        released.set(owned.released)
    }
    fun finish() = call { releaseOwned() }
    override fun close() { executor.shutdown(); terminated.set(executor.awaitTermination(30, TimeUnit.SECONDS)) }
    fun awaitNatural(failure: (Throwable) -> Unit) {
        while (!executor.isTerminated) {
            try { executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS) }
            catch (error: InterruptedException) { failure(error) }
        }
        terminated.set(true)
    }
}

internal fun retainRecipeNativeOwner(terminated: Boolean, publish: () -> Unit, awaitNatural: () -> Unit) {
    var primary: Throwable? = null
    try { publish() } catch (error: Throwable) { primary = error }
    if (!terminated) try { awaitNatural() } catch (error: Throwable) {
        val original = primary; if (original == null) primary = error else original.addSuppressed(error)
    }
    primary?.let { throw it }
}

internal enum class RecipeNativeSafetyReason { GEOMETRY_NOT_EQUAL, UNAVAILABLE, REENTRANT, INACTIVE }
internal class RecipeNativeSafetyRejected(val reason: RecipeNativeSafetyReason) : IllegalStateException(reason.name)

internal fun reevaluateRecipeNativeSafety(
    expected: RecipeDragGeometry, allowed: () -> Boolean, evaluating: AtomicBoolean,
    read: () -> RecipeNativeSafety, validate: (RecipeNativeSafety) -> Unit,
    publish: (RecipeNativeSafety) -> Unit,
    reject: (RecipeNativeSafetyReason, RecipeDragGeometry?, Throwable) -> Unit,
    normalStop: () -> Boolean = { false }
): RecipeNativeSafety? {
    fun refused(reason: RecipeNativeSafetyReason, current: RecipeDragGeometry?, error: Throwable): Nothing {
        try { reject(reason, current, error) } catch (diagnostic: Throwable) { error.addSuppressed(diagnostic) }
        throw error
    }
    if (!evaluating.compareAndSet(false, true)) refused(RecipeNativeSafetyReason.REENTRANT, null, RecipeNativeSafetyRejected(RecipeNativeSafetyReason.REENTRANT))
    var current: RecipeNativeSafety? = null
    try {
        if (!allowed()) {
            if (normalStop()) return null
            throw RecipeNativeSafetyRejected(RecipeNativeSafetyReason.INACTIVE)
        }
        current = read()
        if (!allowed()) {
            if (normalStop()) { validate(current); return null }
            throw RecipeNativeSafetyRejected(RecipeNativeSafetyReason.INACTIVE)
        }
        validate(current)
        if (!allowed()) {
            if (normalStop()) return null
            throw RecipeNativeSafetyRejected(RecipeNativeSafetyReason.INACTIVE)
        }
        publish(current)
        return current
    } catch (error: Throwable) {
        val reason = (error as? RecipeNativeSafetyRejected)?.reason
            ?: if (current != null && current.geometry != expected) RecipeNativeSafetyReason.GEOMETRY_NOT_EQUAL else RecipeNativeSafetyReason.UNAVAILABLE
        refused(reason, current?.geometry, error)
    } finally { evaluating.set(false) }
}

internal fun recipeNativeGeometryDiagnostic(reason: RecipeNativeSafetyReason, old: RecipeDragGeometry, current: RecipeDragGeometry?): JsonObject {
    val differences = mutableListOf<JsonObject>(); var count = 0; var omitted = false
    fun difference(field: String, index: Int, component: Int, before: Double?, after: Double?) {
        if (before?.toBits() == after?.toBits()) return
        count++
        if (differences.size == 8 || field == "BOUNDS" && index >= 16) { omitted = true; return }
        differences += buildJsonObject {
            put("field", field); put("index", index); put("component", component)
            put("old", before?.takeIf { it.isFinite() }); put("new", after?.takeIf { it.isFinite() })
        }
    }
    if (current != null) {
        old.stage.indices.forEach { difference("STAGE", it, 0, old.stage[it], current.stage.getOrNull(it)) }
        difference("SOURCE", 0, 0, old.source.x, current.source.x); difference("SOURCE", 1, 0, old.source.y, current.source.y)
        difference("TARGET", 0, 0, old.target.x, current.target.x); difference("TARGET", 1, 0, old.target.y, current.target.y)
        difference("BOUNDS_COUNT", 0, 0, old.viewports.size.toDouble(), current.viewports.size.toDouble())
        fun values(value: Bounds?) = value?.let { listOf(it.minX, it.minY, it.minZ, it.width, it.height, it.depth) }
        repeat(maxOf(old.viewports.size, current.viewports.size)) { index ->
            val before = values(old.viewports.getOrNull(index)); val after = values(current.viewports.getOrNull(index))
            repeat(6) { difference("BOUNDS", index, it, before?.get(it), after?.get(it)) }
        }
    }
    return buildJsonObject {
        put("reason", reason.name); put("currentAvailable", current != null); put("differenceCount", count); put("truncated", omitted)
        put("differences", buildJsonArray { differences.forEach { add(it) } })
    }.also { check(it.toString().toByteArray(Charsets.UTF_8).size <= 2048) }
}

internal fun rejectRecipeNativeSafety(plan: RecipeNativeAwtPlan, armed: AtomicBoolean, error: Throwable, diagnostic: () -> Unit) {
    armed.set(false)
    plan.invalidate(error)
    diagnostic()
}
