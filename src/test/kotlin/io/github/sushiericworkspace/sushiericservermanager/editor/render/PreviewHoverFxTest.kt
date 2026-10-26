package io.github.sushiericworkspace.sushiericservermanager.editor.render

import com.sun.net.httpserver.HttpServer
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableCustomComponentLoreSection
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.config.AppTheme
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.awaitFx
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.export
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.fx
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.startFx
import io.github.sushiericworkspace.sushiericservermanager.editor.service.ItemReferenceSnapshot
import io.github.sushiericworkspace.sushiericservermanager.editor.service.ItemReferenceStatus
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorSidebar
import io.github.sushiericworkspace.sushiericservermanager.editor.view.SidebarDataState
import io.github.sushiericworkspace.sushiericservermanager.editor.view.SidebarRow
import io.github.sushiericworkspace.sushiericservermanager.ui.theme.ThemeManager
import javafx.animation.AnimationTimer
import javafx.collections.ListChangeListener
import javafx.event.Event
import javafx.geometry.Bounds
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.ListCell
import javafx.scene.control.Tooltip
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.image.ImageView
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.PickResult
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.stage.PopupWindow
import javafx.stage.Window
import kotlinx.serialization.json.*
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.IOException
import java.lang.management.ManagementFactory
import java.net.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.test.*

/** synthetic MOUSE_MOVEDで通常のTooltip.install経路を確認する。物理入力・OS全通信遮断ではない。 */
@Order(0)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@EnabledOnOs(OS.WINDOWS, OS.MAC)
class PreviewHoverFxTest {
    private data class Shown(val tooltip: Tooltip, val nanos: Long, val pulses: Long)
    private class PulseClock : AnimationTimer() {
        var pulse = 0L
        override fun handle(now: Long) { pulse++ }
    }

    private class Fixture(val items: MutableMap<String, ItemBaseData>) {
        val service = ItemPreviewService()
        val clock = PulseClock()
        val probes = VBox(6.0)
        val sidebar = EditorSidebar("なし", { SidebarDataState(false, true, false, true) }, {},
            { ContextMenu() }, { ContextMenu() }, { _, _ -> false }, mutableMapOf(), {}, {},
            previewData = { node, id -> service.installContent(node, id?.let { ItemPreviewContent.item(items.getValue(it)) }, "saved") }
        ).apply { prefHeight = 420.0; minHeight = 420.0; maxHeight = 420.0 }
        val stage = Stage().apply {
            scene = Scene(VBox(8.0, sidebar, probes), 850.0, 700.0).apply {
                stylesheets += javaClass.getResource(AppScreen.BASE.css)!!.toExternalForm()
            }
            show()
        }
        init { clock.start(); rows(items.size) }
        fun rows(count: Int) {
            sidebar.showRows(items.keys.take(count).map { SidebarRow.Data(it, it, 0) })
            stage.scene.root.applyCss(); stage.scene.root.layout()
        }
        fun button(index: Int, id: String) = sidebar.lookupAll(".list-cell").filterIsInstance<ListCell<*>>()
            .firstOrNull { !it.isEmpty && it.index == index && (it.item as? SidebarRow.Data)?.fullId == id }
            ?.let { cell -> descendants(cell).filterIsInstance<Button>().firstOrNull { it.id == id } }
        fun visibleButtons() = descendants(stage.scene.root).filterIsInstance<Button>().filter { button ->
            button.id in items && button.boundsInLocal.width > 0 && button.boundsInLocal.height > 0 &&
                generateSequence<Node>(button) { it.parent }.all { it.isVisible } &&
                button.localToScene(button.boundsInLocal).intersects(0.0, 0.0, stage.scene.width, stage.scene.height)
        }.toList()
        fun close() { service.close(); clock.stop(); stage.close() }
    }

    @Test @Order(1)
    fun preparesVisibleCandidatesAndMeasuresColdAndWarmHover() {
        startFx()
        val items = (0 until 10_000).associateTo(linkedMapOf()) { index ->
            val id = "hover." + index.toString().padStart(5, '0')
            id to item(id, "日本語 é 𠮷 $index")
        }
        val previousTheme = fx { ThemeManager.current }
        val probe = HttpProbe()
        var fixture: Fixture? = null
        var primary: Throwable? = null
        val samples = mutableListOf<JsonObject>()
        val memory = mutableListOf<JsonObject>()
        var phase = "initializing"
        var targetIndex: Int? = null
        var targetId: String? = null
        try {
            probe.start()
            probe.negativeControl()
            val coldStarted = System.nanoTime()
            fixture = fx { Fixture(items).also { it.rows(1000) } }
            val f = assertNotNull(fixture)
            memory += heap("1000-before-prepared")
            val first = awaitFxValue { f.visibleButtons().firstOrNull() }
            awaitPrepared(first, f)
            val coldNanos = System.nanoTime() - coldStarted
            memory += heap("1000-prepared")
            for (theme in AppTheme.entries) {
                fx { assertTrue(ThemeManager.select(theme)) }
                repeat(20) { val shown = show(first, f); fx { assertNotNull(shown.tooltip.graphic) }; hide(first, shown.tooltip) }
                val measured = List(100) {
                    val shown = show(first, f)
                    fx { assertIs<ImageView>(shown.tooltip.graphic) }
                    hide(first, shown.tooltip)
                    shown
                }
                val sorted = measured.map { it.nanos }.sorted()
                val p95 = sorted[ceil(sorted.size * .95).toInt() - 1]
                samples += buildJsonObject {
                    put("theme", theme.name); put("count", 100); put("warmup", 20)
                    put("p95Nanos", p95); put("goal50msMet", p95 <= 50_000_000)
                    put("maxPulseDelta", measured.maxOf { it.pulses })
                    putJsonArray("nanos") { measured.forEach { add(it.nanos) } }
                    putJsonArray("pulseDeltas") { measured.forEach { add(it.pulses) } }
                }
                for (scale in listOf(1.0, 1.5, 2.0)) fx {
                    export(f.service.render(ItemPreviewContent.item(items.getValue(first.id)), scale, scale, "synthetic").image,
                        "hover-${theme.name.lowercase()}-$scale")
                }
            }
            phase = "warm-complete"
            writeEvidence("hover-performance-checkpoint", buildJsonObject {
                put("finalComplete", false); put("phase", phase); put("serviceColdNanos", coldNanos)
                put("samples", JsonArray(samples)); put("memory", JsonArray(memory))
                put("actualOutputScaleX", fx { f.stage.outputScaleX }); put("actualOutputScaleY", fx { f.stage.outputScaleY })
            })
            phase = "rows-10000"
            fx { f.rows(10_000) }
            for (index in listOf(0, 500, 2500, 5000, 9999)) {
                val id = items.keys.elementAt(index)
                phase = "scroll-cell"; targetIndex = index; targetId = id
                fx {
                    assertEquals(id, assertIs<SidebarRow.Data>(f.sidebar.items[index]).fullId)
                    f.sidebar.scrollTo(index)
                    f.stage.scene.root.applyCss(); f.stage.scene.root.layout()
                }
                val node = awaitFxValue { f.button(index, id) }
                phase = "scroll-preview"
                awaitPrepared(node, f)
                memory += heap("10000-scroll-$index")
            }
            fx {
                val content = ItemPreviewContent.item(items.values.first())
                val before = f.service.render(content, 1.0, 1.0, "lru-check")
                repeat(129) { index -> f.service.render(ItemPreviewContent.item(item("stress.$index", "境界 $index")), 1.0, 1.0, "lru-check") }
                assertNotSame(before.image, f.service.render(content, 1.0, 1.0, "lru-check").image)
            }
            phase = "lru-notice"
            val evicted = awaitFxValue { f.visibleButtons().firstOrNull() }
            show(evicted, f).also { shown ->
                fx {
                    assertNull(shown.tooltip.graphic)
                    assertTrue(shown.tooltip.text.contains("エラーあり") && shown.tooltip.text.contains("未準備"))
                }
                hide(evicted, shown.tooltip)
            }
            memory += heap("after-lru-pressure")
            assertEquals(0, probe.attempts.get()); assertEquals(0, probe.hits.get())
            writeEvidence("hover-performance", buildJsonObject {
                put("finalComplete", true); put("serviceColdNanos", coldNanos); put("evictedNoticePreserved", true)
                put("actualOutputScaleX", fx { f.stage.outputScaleX }); put("actualOutputScaleY", fx { f.stage.outputScaleY })
                put("syntheticScales", "1/1.5/2; OS setting unchanged")
                put("samples", JsonArray(samples)); put("memory", JsonArray(memory))
                put("httpNegativeControlAttempts", probe.negativeAttempts); put("hoverHttpAttempts", probe.attempts.get()); put("loopbackHits", probe.hits.get())
                put("memoryLimit", "heap/nonheap samples and LRU image identity; native/workingSet/all release not proven")
                put("latencyLimit", "synthetic MOUSE_MOVED to showing/graphic; pulse is not compositor paint")
            })
            assertTrue(samples.all { it.getValue("goal50msMet").jsonPrimitive.boolean }, "p95設計目標50ms未達・fullへ進めない")
            assertTrue(samples.all { it.getValue("maxPulseDelta").jsonPrimitive.long <= 1 }, "次pulse以内の表示を確認できない")
        } catch (error: Throwable) {
            primary = error
            try {
                val f = fixture
                val cells = f?.let { fx {
                    it.sidebar.lookupAll(".list-cell").filterIsInstance<ListCell<*>>().sortedBy { cell -> cell.index }.take(64).map { cell ->
                        buildJsonObject {
                            put("index", cell.index); put("id", (cell.item as? SidebarRow.Data)?.fullId)
                            put("empty", cell.isEmpty); put("visible", cell.isVisible)
                            put("sceneBounds", bounds(cell.localToScene(cell.boundsInLocal)))
                        }
                    }
                } } ?: emptyList()
                val layout = f?.let { holder -> fx {
                    val sidebar = holder.sidebar
                    val flow = sidebar.lookup(".virtual-flow") as? VirtualFlow<*>
                    val target = targetIndex?.let { flow?.getVisibleCell(it) }
                    buildJsonObject {
                        put("sidebarSize", sidebar.items.size); put("sidebarHeight", sidebar.height.takeIf { it.isFinite() })
                        put("sidebarFixedCellSize", sidebar.fixedCellSize.takeIf { it.isFinite() })
                        put("sidebarBounds", bounds(sidebar.localToScene(sidebar.boundsInLocal)))
                        putJsonObject("sidebarInsets") {
                            put("top", sidebar.insets.top.takeIf { it.isFinite() }); put("bottom", sidebar.insets.bottom.takeIf { it.isFinite() })
                            put("left", sidebar.insets.left.takeIf { it.isFinite() }); put("right", sidebar.insets.right.takeIf { it.isFinite() })
                        }
                        put("flowCellCount", flow?.cellCount); put("flowPosition", flow?.position?.takeIf { it.isFinite() })
                        put("flowFixedCellSize", flow?.fixedCellSize?.takeIf { it.isFinite() })
                        put("flowViewportLength", flow?.viewportLength?.takeIf { it.isFinite() })
                        put("flowHeight", flow?.height?.takeIf { it.isFinite() }); put("flowNeedsLayout", flow?.isNeedsLayout)
                        put("targetVisibleCellIndex", target?.index)
                        put("targetBounds", bounds(target?.boundsInLocal))
                        put("targetSceneBounds", bounds(target?.takeIf { it.scene != null }?.let { it.localToScene(it.boundsInLocal) }))
                        put("stageShowing", holder.stage.isShowing); put("stageFocused", holder.stage.isFocused)
                        put("focusOwnerType", holder.stage.scene.focusOwner?.javaClass?.simpleName)
                        put("focusOwnerId", holder.stage.scene.focusOwner?.id)
                        put("ownedPopupCount", Window.getWindows().filterIsInstance<PopupWindow>().count { it.ownerWindow === holder.stage })
                    }
                } } ?: JsonNull
                writeEvidence("hover-failure", buildJsonObject {
                    put("finalComplete", false); put("phase", phase)
                    put("targetIndex", targetIndex); put("targetId", targetId)
                    put("cellsLimit", 64); put("cells", JsonArray(cells)); put("layout", layout)
                    put("samples", JsonArray(samples)); put("memory", JsonArray(memory))
                })
            } catch (diagnostic: Throwable) { error.addSuppressed(diagnostic) }
            throw error
        }
        finally {
            cleanup(primary,
                { fx { fixture?.close() } },
                { fx { ThemeManager.select(previousTheme) } },
                { probe.close() })
        }
    }

    @Test @Order(2)
    fun preservesSourceValidationAndDisposesBoundedReferences() {
        startFx()
        val fixture = fx { Fixture((0 until 32).associateTo(linkedMapOf()) { "state.$it" to item("state.$it", "原文 $it") }) }
        val f = fixture
        var other: ItemPreviewService? = null
        var primary: Throwable? = null
        try {
            val node = awaitFxValue { f.visibleButtons().firstOrNull() }
            awaitPrepared(node, f)
            fx { f.sidebar.refreshData(node.id) }
            show(node, f).also { shown ->
                fx { assertIs<ImageView>(shown.tooltip.graphic); assertTrue(shown.tooltip.text.contains("エラーあり")) }
                hide(node, shown.tooltip)
            }
            val cold = fx { f.visibleButtons().last() }
            show(cold, f) {
                val visible = f.visibleButtons()
                assertTrue(visible.size > 3)
                visible.forEach { button ->
                    f.items[button.id] = item(button.id, "変更後 ${button.id}")
                    f.sidebar.refreshData(button.id)
                }
            }.also { shown ->
                fx {
                    assertNull(shown.tooltip.graphic)
                    assertTrue(shown.tooltip.text.contains("エラーあり") && shown.tooltip.text.contains("未準備"))
                    export(shown.tooltip.scene.snapshot(null), "notice-cold")
                }
                hide(cold, shown.tooltip)
            }
            val failed = fx {
                Button("準備失敗").also { button ->
                    f.probes.children += button
                    button.tooltip = Tooltip("検証説明: 元データ保持")
                    f.service.installContent(button, ItemPreviewContent(listOf(Component.translatable("preview.unresolved")), PreviewSymbol.COMMON), "failed")
                }
            }
            val failureDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (true) {
                val shown = show(failed, f)
                val failedReady = fx { shown.tooltip.text.contains("プレビュー素材") }
                if (failedReady) fx {
                    assertNull(shown.tooltip.graphic)
                    assertTrue(shown.tooltip.text.contains("検証説明"))
                    export(shown.tooltip.scene.snapshot(null), "notice-failed")
                }
                hide(failed, shown.tooltip)
                if (failedReady) break
                assertTrue(System.nanoTime() < failureDeadline, "準備失敗状態を確認できない")
                pulses(f.clock, 1)
            }
            fx {
                for (state in ItemReferenceStatus.entries) {
                    val content = itemReferencePreview(ItemReferenceSnapshot(state), ItemInternalId("not-loaded"))
                    val result = f.service.render(content, 1.0, 1.0, "status-$state")
                    assertTrue(result.description.contains(if (state == ItemReferenceStatus.READY) "参照先不明" else "未取得"))
                }
            }
            val source = fx { Button("保存元切替").also { f.probes.children += it } }
            val same = ItemPreviewContent.item(item("source", "local原文"))
            fx { f.service.installContent(source, same, "saved") }
            awaitPrepared(source, f)
            show(source, f).also { shown -> fx { export(assertIs<ImageView>(shown.tooltip.graphic).image, "source-local") }; hide(source, shown.tooltip) }
            val detached = fx {
                val oldBinding = source.properties["manager.itemPreview"]
                val replacement = ItemPreviewService().also { other = it }
                replacement.installContent(source, same, "saved")
                assertNotSame(oldBinding, source.properties["manager.itemPreview"])
                f.service.close()
                Button("未接続").also { replacement.installContent(it, same, "saved") }
            }
            fx { other!!.installContent(source, ItemPreviewContent.item(item("source", "server原文")), "saved") }
            awaitPrepared(source, f)
            show(source, f).also { shown ->
                fx {
                    val image = assertIs<ImageView>(shown.tooltip.graphic)
                    assertTrue(image.accessibleText.contains("server原文") && !image.accessibleText.contains("local原文"))
                    export(image.image, "source-server")
                }
                hide(source, shown.tooltip)
            }
            fx { f.stage.hide() }
            other!!.close()
            fx {
                assertFalse(source.properties.containsKey("manager.itemPreview"))
                assertFalse(detached.properties.containsKey("manager.itemPreview"))
                f.stage.show()
                Event.fireEvent(source, mouse(source, MouseEvent.MOUSE_MOVED))
            }
            pulses(f.clock, 3)
            assertTrue(fx { Window.getWindows().filterIsInstance<Tooltip>().none { it.ownerWindow === f.stage && it.isShowing } })
            writeEvidence("hover-lifecycle", buildJsonObject {
                put("sidebarRefreshKeepsAssociation", true); put("coldAndFailedNoticePreserved", true)
                put("statusRendering", "shared Shop/Recipe classification helper; actual editor state gesture not simulated")
                put("separateOwnerRebound", true); put("retainedHiddenAndDetachedDisposed", true)
                put("memoryProof", "explicit node properties/normal event path; heap complete release not inferred")
                put("afterDisposeHeap", heap("after-dispose"))
            })
        } catch (error: Throwable) { primary = error; throw error }
        finally { cleanup(primary, { fx { other?.close(); f.close() } }) }
    }

    private class HttpProbe : AutoCloseable {
        val attempts = AtomicInteger(); val hits = AtomicInteger()
        var negativeAttempts = 0
        private val previous = ProxySelector.getDefault()
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        fun start() {
            server.createContext("/probe") { exchange -> hits.incrementAndGet(); exchange.sendResponseHeaders(204, -1); exchange.close() }
            server.start()
            ProxySelector.setDefault(object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> { attempts.incrementAndGet(); throw SecurityException("fixture HTTP経路を拒否") }
                override fun connectFailed(uri: URI, address: SocketAddress, error: IOException) = Unit
            })
        }
        fun negativeControl() {
            assertFailsWith<SecurityException> { URI("http://127.0.0.1:${server.address.port}/probe").toURL().openStream().use { it.read() } }
            negativeAttempts = attempts.get(); assertTrue(negativeAttempts > 0); assertEquals(0, hits.get()); attempts.set(0)
        }
        override fun close() { try { ProxySelector.setDefault(previous) } finally { server.stop(0) } }
    }

    private fun show(node: Node, f: Fixture, beforeMove: () -> Unit = {}): Shown {
        val ready = CompletableFuture<Shown>()
        var started = 0L; var pulse = 0L
        val listener = ListChangeListener<Window> { change ->
            while (change.next()) for (window in change.addedSubList) {
                val tooltip = window as? Tooltip ?: continue
                if (tooltip.ownerWindow === f.stage) ready.complete(Shown(tooltip, System.nanoTime() - started, f.clock.pulse - pulse))
            }
        }
        try {
            fx {
                Window.getWindows().addListener(listener)
                beforeMove()
                started = System.nanoTime(); pulse = f.clock.pulse
                Event.fireEvent(node, mouse(node, MouseEvent.MOUSE_MOVED))
            }
            return ready.get(15, TimeUnit.SECONDS)
        } finally { fx { Window.getWindows().removeListener(listener) } }
    }

    private fun hide(node: Node, tooltip: Tooltip) = fx {
        Event.fireEvent(node, mouse(node, MouseEvent.MOUSE_EXITED))
        Event.fireEvent(node, mouse(node, MouseEvent.MOUSE_PRESSED))
        Event.fireEvent(node, mouse(node, MouseEvent.MOUSE_RELEASED))
        tooltip.hide()
    }

    private fun mouse(node: Node, type: javafx.event.EventType<MouseEvent>): MouseEvent {
        val x = node.boundsInLocal.width / 2; val y = node.boundsInLocal.height / 2
        val screen = assertNotNull(node.localToScreen(x, y))
        return MouseEvent(type, x, y, screen.x, screen.y, MouseButton.NONE, 0,
            false, false, false, false, false, false, false, true, false, true, PickResult(node, x, y))
    }

    private fun awaitPrepared(node: Node, f: Fixture) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (true) {
            val shown = show(node, f)
            val ready = fx { shown.tooltip.graphic is ImageView }
            hide(node, shown.tooltip)
            if (ready) return
            assertTrue(System.nanoTime() < until, "候補の準備が完了しない")
            pulses(f.clock, 1)
        }
    }

    private fun pulses(clock: PulseClock, count: Long) { val target = fx { clock.pulse + count }; awaitFx { clock.pulse >= target } }
    private fun <T : Any> awaitFxValue(value: () -> T?): T {
        var result: T? = null
        awaitFx { value().also { result = it } != null }
        return assertNotNull(result)
    }
    private fun bounds(value: Bounds?): JsonElement = value?.let { buildJsonObject {
        put("minX", it.minX.takeIf { v -> v.isFinite() }); put("minY", it.minY.takeIf { v -> v.isFinite() })
        put("width", it.width.takeIf { v -> v.isFinite() }); put("height", it.height.takeIf { v -> v.isFinite() })
    } } ?: JsonNull
    private fun heap(phase: String) = ManagementFactory.getMemoryMXBean().let { bean -> buildJsonObject {
        put("phase", phase); put("heapUsed", bean.heapMemoryUsage.used); put("heapCommitted", bean.heapMemoryUsage.committed)
        put("heapMax", bean.heapMemoryUsage.max); put("nonHeapUsed", bean.nonHeapMemoryUsage.used)
    } }
    private fun writeEvidence(name: String, value: JsonObject) {
        val directory = System.getProperty("manager.previewFxOutput") ?: return
        Files.createDirectories(Path.of(directory))
        Files.writeString(Path.of(directory, "$name.json"), Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), value))
    }
    private fun cleanup(primary: Throwable?, vararg actions: () -> Unit) {
        val errors = mutableListOf<Throwable>()
        actions.forEach { try { it() } catch (error: Throwable) { errors += error } }
        if (errors.isNotEmpty()) {
            val first = primary ?: errors.first()
            errors.filter { it !== first }.forEach(first::addSuppressed)
            if (primary == null) throw first
        }
    }
    companion object {
        private fun item(id: String, name: String) = MutableItemBaseData(id = id).apply {
            display.displayName = name
            display.mutableLore.add(mutableListOf(MutableCustomComponentLoreSection(Component.text("元の内容 日本語 é 𠮷 😀")
                .font(Key.key("http", "//127.0.0.1/probe")).decorate(TextDecoration.BOLD, TextDecoration.ITALIC))))
        }.freeze()
        private fun descendants(node: Node): Sequence<Node> = sequence {
            yield(node)
            if (node is Parent) node.childrenUnmodifiable.forEach { yieldAll(descendants(it)) }
        }
    }
}
