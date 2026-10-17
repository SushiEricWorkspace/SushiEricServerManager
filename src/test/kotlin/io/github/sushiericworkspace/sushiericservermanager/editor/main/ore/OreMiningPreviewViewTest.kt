package io.github.sushiericworkspace.sushiericservermanager.editor.main.ore

import io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.VanillaBlockId
import io.github.sushiericworkspace.sushiericdatamodel.data.ore.model.mutable.MutableOreBaseData
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.DialogPane
import javafx.scene.control.ScrollPane
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.stage.Window
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorView.SaveOutcome
import javafx.scene.control.Label
import javafx.scene.control.Spinner
import javafx.scene.control.TextField
import javafx.stage.Stage
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.*

@EnabledOnOs(OS.WINDOWS, OS.MAC)
class OreMiningPreviewViewTest {
    @Test fun `実FXMLで条件と編集中の鉱石へ追従し条件入力を保存対象へ混ぜない`() {
        val directory = createTempDirectory("ore-mining-preview-store").toFile()
        val store = LocalEditorDataStore(directory)
        val original = MutableOreBaseData(id = "ore", blockId = VanillaBlockId("stone"), hardness = 5.0, requiredTier = 1)
        assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.ore, original.id, original))
        val service = EditorDataService(store, createTempDirectory("ore-mining-preview").toFile())
        lateinit var stage: Stage
        lateinit var logic: OreEditorLogic
        lateinit var main: MainController
        var opened = false
        try {
            service.itemReferences.load().get(10, TimeUnit.SECONDS)
            onFx {
                val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
                val root = loader.load<Parent>()
                main = loader.getController()
                logic = OreEditorLogic(main, service)
                stage = Stage().apply {
                    scene = Scene(root, 1100.0, 900.0).apply {
                        stylesheets.add(assertNotNull(javaClass.getResource(AppScreen.BASE.css)).toExternalForm())
                    }
                }
                main.switchView(logic)
                stage.show()
                opened = true
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            var ready = false
            while (!ready) {
                onFx { ready = stage.scene.lookup("#mining-speed") != null }
                assertTrue(System.nanoTime() < deadline, "鉱石編集の読込が完了しません")
                if (!ready) Thread.sleep(20)
            }
            onFx {
                val root = stage.scene.root
                root.applyCss(); root.layout()
                val speed = assertIs<TextField>(root.lookup("#mining-speed"))
                val tier = assertIs<TextField>(root.lookup("#mining-tier"))
                val result = assertIs<Label>(root.lookup("#mining-time"))
                val before = logic.sidebarState("ore")
                assertFalse(before.modified)
                assertEquals("", speed.text); assertEquals("0", tier.text)
                assertTrue(result.text.startsWith("未算出"))
                speed.text = "10"; tier.text = "1"
                assertTrue(result.text.contains("15tick / 0.75秒"))
                speed.text = "NaN"; assertTrue(result.text.startsWith("未算出"))
                speed.text = "0"; assertTrue(result.text.startsWith("採掘不可"))
                speed.text = "10"; tier.text = "0"; assertTrue(result.text.startsWith("採掘不可"))
                tier.text = "1"; assertTrue(result.text.contains("15tick"))
                main.mainContentContainer.requestFocus()
                assertEquals(before, logic.sidebarState("ore"))
                logic.onSave { assertEquals(SaveOutcome.NOTHING_TO_SAVE, it) }
                val hardness = root.lookupAll(".text-field").filterIsInstance<TextField>().single { it.text == "5.0" }
                hardness.text = "-"; assertTrue(result.text.startsWith("未算出"))
                hardness.text = "10"; assertTrue(result.text.contains("30tick"))
                hardness.fireEvent(KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
                assertTrue(logic.sidebarState("ore").modified)
                val required = root.lookupAll(".spinner").filterIsInstance<Spinner<*>>().single()
                required.editor.text = "2"; assertTrue(result.text.startsWith("採掘不可"))
                required.editor.text = "1"
                required.editor.fireEvent(KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false))
                assertTrue(result.text.contains("30tick"))
                assertTrue(root.lookupAll(".label").filterIsInstance<Label>().any { it.text.contains("必要tick = 硬度") })
            }
            val saved = CompletableFuture<SaveOutcome>()
            onFx {
                Platform.runLater {
                    val pane = Window.getWindows().mapNotNull { it.scene?.root as? DialogPane }.single()
                    pane.buttonTypes.map { pane.lookupButton(it) }.filterIsInstance<Button>().single { it.text == "保存" }.fire()
                }
                logic.onSave { saved.complete(it) }
            }
            assertEquals(SaveOutcome.SAVED, saved.get(20, TimeUnit.SECONDS))
            val persisted = assertIs<StoreResult.Success<MutableOreBaseData>>(store.load(EditorDataDescriptors.ore, "ore")).value
            assertEquals(original.copy(hardness = 10.0), persisted)
            assertEquals(original.completed, persisted.completed)
            onFx { stage.width = 800.0; stage.height = 600.0 }
            val resizeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var resized = false
            while (!resized) {
                onFx { resized = stage.scene.width in 750.0..800.0 && stage.scene.height in 500.0..600.0 }
                assertTrue(System.nanoTime() < resizeDeadline, "小画面のScene実寸が反映されません")
                if (!resized) Thread.sleep(20)
            }
            onFx {
                val root = stage.scene.root
                root.applyCss(); root.layout()
                assertTrue(stage.width in 799.0..801.0 && stage.height in 599.0..601.0)
                println("Ore302 small Stage=${stage.width}x${stage.height}, Scene=${stage.scene.width}x${stage.scene.height}")
                assertIs<TextField>(root.lookup("#mining-speed")).text = "10"
                assertIs<TextField>(root.lookup("#mining-tier")).text = "1"
                val scrolls = listOf(assertIs<ScrollPane>(root.lookup("#mainScroll")), assertIs<ScrollPane>(root))
                listOf("mining-speed-label", "mining-tier-label", "mining-speed", "mining-tier", "mining-time", "mining-formula", "mining-conditions").forEach { id ->
                    val node = assertNotNull(root.lookup("#$id"))
                    scrolls.forEach { scroll ->
                        val bounds = scroll.content.sceneToLocal(node.localToScene(node.layoutBounds))
                        val vertical = scroll.content.layoutBounds.height - scroll.viewportBounds.height
                        val horizontal = scroll.content.layoutBounds.width - scroll.viewportBounds.width
                        if (vertical > 0) scroll.vvalue = ((bounds.minY + bounds.height / 2 - scroll.viewportBounds.height / 2) / vertical).coerceIn(0.0, 1.0)
                        if (horizontal > 0) scroll.hvalue = ((bounds.minX + bounds.width / 2 - scroll.viewportBounds.width / 2) / horizontal).coerceIn(0.0, 1.0)
                        root.layout()
                    }
                    if (node is Label) {
                        assertTrue(node.height >= node.prefHeight(node.width) - 1, "$id の折り返し高さが不足しています")
                        if (!node.isWrapText) assertTrue(node.width >= node.prefWidth(node.height) - 1, "$id の入力名が省略されています")
                    }
                    if (node is TextField) assertTrue(node.width >= 120.0, "$id の数値入力幅が不足しています")
                    scrolls.forEach { scroll ->
                        val viewportNode = assertNotNull(scroll.lookup(".viewport"))
                        val viewport = viewportNode.localToScene(viewportNode.layoutBounds)
                        val visible = node.localToScene(node.layoutBounds)
                        assertTrue(visible.minX >= viewport.minX - 2 && visible.maxX <= viewport.maxX + 2, "$id の横幅が表示枠を超えています")
                        assertTrue(visible.minY >= viewport.minY - 2 && visible.maxY <= viewport.maxY + 2, "$id にスクロールで到達できません")
                    }
                }
                val image = root.snapshot(null, null)
                assertTrue(image.width > 0 && image.height > 0)
                val png = BufferedImage(image.width.toInt(), image.height.toInt(), BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until png.height) for (x in 0 until png.width) png.setRGB(x, y, image.pixelReader.getArgb(x, y))
                ImageIO.write(png, "png", File("build/ore-mining302.png"))
            }
        } finally {
            if (opened) {
                val closed = CompletableFuture<Boolean>()
                onFx { logic.onClose { success -> stage.close(); closed.complete(success) } }
                assertTrue(closed.get(15, TimeUnit.SECONDS))
            }
            service.itemReferences.close(); service.closeIo()
        }
    }

    private fun onFx(action: () -> Unit) {
        val started = CountDownLatch(1)
        try { Platform.startup { Platform.setImplicitExit(false); started.countDown() } }
        catch (_: IllegalStateException) { started.countDown() }
        assertTrue(started.await(20, TimeUnit.SECONDS))
        val task = FutureTask(action)
        Platform.runLater(task)
        task.get(20, TimeUnit.SECONDS)
    }
}
