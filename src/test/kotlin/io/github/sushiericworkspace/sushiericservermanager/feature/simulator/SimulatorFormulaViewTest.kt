package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.config.AppSettingsManager
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.application.Platform
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.Button
import javafx.scene.control.ButtonBase
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextArea
import javafx.scene.control.TitledPane
import javafx.stage.Stage
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.*

@EnabledOnOs(OS.WINDOWS, OS.MAC)
class SimulatorFormulaViewTest {
    @Test fun `6経路の式と最後の要求を実FXMLへ表示し入力変更で説明を差し替えない`() = withView { view, _ -> fx {
        val pane = view.root.lookup("#formulaHelpPane") as TitledPane
        assertFalse(pane.isExpanded)
        val help = (pane.content.lookup("#formulaHelpArea") as TextArea).text
        for (route in listOf("近接", "投射物", "魔法")) assertTrue(help.contains("Player → Mob $route"))
        assertTrue(help.contains("Mob → Player 近接 / 投射物 / 魔法"))
        assertTrue(help.contains("0.015")); assertTrue(help.contains("0.001")); assertTrue(help.contains("平均能力を式へ代入しません"))
        val form = view.controller.inputEditor
        for (source in listOf(true, false)) for (kind in SimulatorAttackKind.entries) {
            form.playerIsSource.isSelected = source
            form.hits.single().kind.value = kind
            val request = SimulatorMultipleHitRequest(7, form.readInput(), SimulatorMultipleHitMode.Sample(1))
            val result = SimulatorMultipleHitSimulator.calculate(request)
            view.controller.showState(SimulatorRunState.Finished(request, result))
            val explanation = pane.content.lookup("#formulaRequestArea") as TextArea
            val previous = explanation.text
            assertTrue(previous.contains("最後の計算要求 #7"))
            assertTrue(previous.contains("${if (source) "Player → Mob" else "Mob → Player"} $kind"))
            assertTrue(previous.contains("命中ID=${form.hits.single().hitId}"))
            assertTrue(previous.contains("式計算結果="))
            form.hits.single().distance.text = "999"
            form.hits.single().kind.value = SimulatorAttackKind.MAGIC
            assertEquals(previous, explanation.text)
            form.hits.single().distance.text = ""
        }
        val request = SimulatorMultipleHitRequest(8, form.readInput(), SimulatorMultipleHitMode.Expected)
        val expected = SimulatorRunState.Finished(request, SimulatorMultipleHitSimulator.calculate(request))
        assertTrue(expected.formulaExplanation().contains("単一の式結果・中間値は表示しません"))
        val cancelled = SimulatorRunState.Finished(request, SimulatorMultipleHitResult.Cancelled(8)).formulaExplanation()
        assertTrue(cancelled.contains("ダメージ値なし")); assertFalse(cancelled.contains("式計算結果="))
    } }

    @Test fun `51行の説明と詳細がページ移動で同期し編集後も最後の要求を使う`() = withView { view, _ ->
        val ids = fx {
            val form = view.controller.inputEditor
            repeat(50) { form.addHit() }
            form.hits.forEach { it.matched.isSelected = false }
            val ids = form.hits.map { it.hitId }
            view.controller.workspace.start(form.readInput(), SimulatorMultipleHitMode.Sample(1))
            ids
        }
        await { view.controller.workspace.state is SimulatorRunState.Finished }
        fx {
            val pane = view.root.lookup("#formulaHelpPane") as TitledPane
            val explanation = pane.content.lookup("#formulaRequestArea") as TextArea
            val details = view.root.lookup("#detailsArea") as TextArea
            val first = explanation.text
            assertTrue(first.contains(ids[0])); assertTrue(first.contains(ids[49])); assertFalse(first.contains(ids[50]))
            assertTrue(first.contains("式結果なし")); assertFalse(first.contains("式計算結果="))
            view.controller.inputEditor.hits.first().kind.value = SimulatorAttackKind.MAGIC
            button(view.root, "詳細の次ページ").fire()
            assertTrue(explanation.text.contains(ids[50])); assertFalse(explanation.text.contains(ids[0]))
            assertTrue(explanation.text.contains("Player → Mob 投射物"))
            assertTrue(details.text.contains(ids[50])); assertFalse(details.text.contains(ids[0]))
            button(view.root, "詳細の前ページ").fire()
            assertEquals(first, explanation.text)
        }
    }

    @Test fun `小画面で説明を展開しても計算詳細比較へスクロールできる`() = withView { view, stage ->
        fx {
            stage.width = 640.0; stage.height = 480.0
            val pane = view.root.lookup("#formulaHelpPane") as TitledPane
            pane.isExpanded = true
        }
        settle()
        fx {
            val pane = view.root.lookup("#formulaHelpPane") as TitledPane
            val help = pane.content.lookup("#formulaHelpArea") as TextArea
            assertTrue(help.height >= 180.0)
            assertTrue((view.root as ScrollPane).content.layoutBounds.height > view.root.viewportBounds.height)
            for (target in listOf(help, pane.content.lookup("#formulaRequestArea"), button(view.root, "計算"), view.root.lookup("#detailsArea"), button(view.root, "比較"))) {
                assertReachable(view.root, target)
            }
            assertReachable(view.root, help)
            val image = view.root.snapshot(null, null)
            val png = BufferedImage(image.width.toInt(), image.height.toInt(), BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until png.height) for (x in 0 until png.width) png.setRGB(x, y, image.pixelReader.getArgb(x, y))
            ImageIO.write(png, "png", File("build/simulator295-small-formula.png"))
        }
    }

    @Test fun `保存した小画面を復元して式と結果と比較を操作できる`() {
        val previous = fx { AppSettingsManager.load() }
        try {
            withView { _, stage ->
                fx { stage.width = 640.0; stage.height = 480.0 }
                settle()
                fx { stage.close() }
                val saved = fx { AppSettingsManager.load().windowSizes.getValue(AppScreen.SIMULATOR.name) }
                assertEquals(640.0, saved.width, 2.0)
                assertEquals(480.0, saved.height, 2.0)
                val restored = fx { SimulatorView.load() }
                val reopened = fx { Stage().apply {
                    scene = Utility.createScene(AppScreen.SIMULATOR, customRoot = restored.root)
                    show()
                    (restored.root.lookup("#formulaHelpPane") as TitledPane).isAnimated = false
                } }
                try {
                    settle()
                    fx {
                        assertEquals(saved.width, reopened.width, 2.0)
                        assertEquals(saved.height, reopened.height, 2.0)
                        (restored.root.lookup("#formulaHelpPane") as TitledPane).isExpanded = true
                    }
                    settle()
                    for (sample in listOf(true, false)) {
                        fx {
                            restored.controller.inputEditor.hits.single().distance.text = ""
                            restored.controller.inputEditor.sample.isSelected = sample
                            assertReachable(restored.root, button(restored.root, "計算"))
                            button(restored.root, "計算").fire()
                        }
                        await { restored.controller.workspace.state is SimulatorRunState.Finished &&
                            (restored.controller.workspace.state as SimulatorRunState.Finished).request.mode.let {
                                (it is SimulatorMultipleHitMode.Sample) == sample
                            } }
                        fx {
                            val pane = restored.root.lookup("#formulaHelpPane") as TitledPane
                            val explanation = pane.content.lookup("#formulaRequestArea") as TextArea
                            val result = (restored.controller.workspace.state as SimulatorRunState.Finished).result
                            if (sample) assertIs<SimulatorMultipleHitResult.Sample>(result)
                            else assertIs<SimulatorMultipleHitResult.Expected>(result)
                            val finished = explanation.text
                            assertTrue(finished.contains("最後の計算要求"))
                            assertReachable(restored.root, explanation)
                            assertReachable(restored.root, restored.root.lookup("#detailsArea"))
                            assertReachable(restored.root, button(restored.root, "基準を保持・更新"))
                            button(restored.root, "基準を保持・更新").fire()
                            assertReachable(restored.root, button(restored.root, "比較"))
                            button(restored.root, "比較").fire()
                            val comparison = restored.root.lookup("#comparisonLabel") as Label
                            assertTrue(comparison.text.contains("算出:"))
                            assertReachable(restored.root, comparison)
                            val scroll = generateSequence(comparison.parent) { it.parent }.filterIsInstance<ScrollPane>().first()
                            for (end in listOf(0.0, 1.0)) {
                                scroll.vvalue = end
                                restored.root.layout()
                                val bounds = comparison.localToScene(comparison.layoutBounds)
                                val viewport = scroll.lookup(".viewport")
                                val visible = viewport.localToScene(viewport.layoutBounds)
                                val edge = if (end == 0.0) bounds.minY else bounds.maxY
                                assertTrue(edge in (visible.minY - 1)..(visible.maxY + 1), "比較の先頭または末尾が隠れています: $bounds / $visible")
                            }
                            restored.controller.inputEditor.hits.single().distance.text = "999"
                            assertEquals(finished, explanation.text)
                            pane.isExpanded = false
                            pane.isExpanded = true
                            assertEquals(finished, explanation.text)
                        }
                    }
                } finally { fx { restored.dispose(); reopened.close() } }
            }
        } finally { fx { AppSettingsManager.save(previous) } }
    }

    private fun assertReachable(root: Parent, node: Node) {
        root.applyCss()
        root.layout()
        val scrolls = generateSequence(node.parent) { it.parent }.filterIsInstance<ScrollPane>().toList()
        for (scroll in scrolls) {
            val bounds = scroll.content.sceneToLocal(node.localToScene(node.layoutBounds))
            val content = scroll.content.layoutBounds
            val viewport = scroll.viewportBounds
            if (content.width > viewport.width) scroll.hvalue = ((bounds.minX + bounds.width / 2 - viewport.width / 2) / (content.width - viewport.width)).coerceIn(0.0, 1.0)
            if (content.height > viewport.height) scroll.vvalue = ((bounds.minY + bounds.height / 2 - viewport.height / 2) / (content.height - viewport.height)).coerceIn(0.0, 1.0)
            root.layout()
        }
        val bounds = node.localToScene(node.layoutBounds)
        val x = bounds.minX + bounds.width / 2
        val y = bounds.minY + bounds.height / 2
        assertTrue(x in -1.0..(root.scene.width + 1) && y in -1.0..(root.scene.height + 1), "表示へ到達できません: $bounds")
        for (scroll in scrolls) {
            val viewport = scroll.lookup(".viewport")
            val visible = viewport.localToScene(viewport.layoutBounds)
            assertTrue(x in (visible.minX - 1)..(visible.maxX + 1) && y in (visible.minY - 1)..(visible.maxY + 1), "表示中心が隠れています: $bounds / $visible")
            assertTrue(bounds.minX >= visible.minX - 1 && bounds.maxX <= visible.maxX + 1, "横方向に隠れています: $bounds / $visible")
            if (node is ButtonBase) assertTrue(bounds.minY >= visible.minY - 1 && bounds.maxY <= visible.maxY + 1, "ボタン全体が隠れています: $bounds / $visible")
        }
    }

    private fun withView(action: (SimulatorView, Stage) -> Unit) {
        val view = fx { SimulatorView.load() }
        val stage = fx { Stage().apply {
            scene = Utility.createScene(AppScreen.SIMULATOR, customRoot = view.root)
            width = 900.0; height = 700.0; show()
            (view.root.lookup("#formulaHelpPane") as TitledPane).isAnimated = false
        } }
        try { settle(); action(view, stage) } finally { fx { view.dispose(); stage.close() } }
    }
    private fun settle() { Thread.sleep(250); fx {} }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (!fx(condition)) { assertTrue(System.nanoTime() < deadline); Thread.sleep(10) }
    }
    private fun nodes(node: Node): Sequence<Node> = sequence {
        yield(node)
        if (node is Parent) node.childrenUnmodifiable.forEach { yieldAll(nodes(it)) }
    }
    private fun button(root: Parent, text: String) = nodes(root).filterIsInstance<Button>().single { it.text == text }
    private fun <T> fx(action: () -> T): T {
        val ready = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
        catch (_: IllegalStateException) { ready.countDown() }
        assertTrue(ready.await(20, TimeUnit.SECONDS))
        val task = FutureTask(action)
        Platform.runLater(task)
        return task.get(30, TimeUnit.SECONDS)
    }
}
