package io.github.sushiericworkspace.sushiericservermanager.editor.render

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableCustomComponentLoreSection
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.config.AppTheme
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.ItemEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.PreviewCanvas
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.SaveResult
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.InMemoryEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.ui.theme.ThemeManager
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.control.ScrollPane
import javafx.stage.Stage
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.minimessage.MiniMessage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** 通常/開発の本番FXML入口を同じ回帰で検証する。SSH・物理ポインターは使用しない。 */
@EnabledOnOs(OS.WINDOWS, OS.MAC)
class PreviewCanvasFxTest {
    @Test
    fun 本番FXMLのプレビューとメモリー保存APIがComponentを変えない() {
        startFx()
        val source = Component.text("元の内容 日本語 ABC").font(Key.key("custom:unsupported"))
            .decorate(TextDecoration.BOLD, TextDecoration.ITALIC, TextDecoration.UNDERLINED,
                TextDecoration.STRIKETHROUGH, TextDecoration.OBFUSCATED)
        val originalHash = componentHash(source)
        val data = MutableItemBaseData(id = "preview.fx")
        data.display.displayName = "Preview 日本語"
        data.display.mutableLore.add(mutableListOf(MutableCustomComponentLoreSection(source)))
        val memory = InMemoryEditorDataStore()
        assertIs<StoreResult.Success<Unit>>(memory.save(EditorDataDescriptors.item, data.id, data))
        val service = EditorDataService(memory, createTempDirectory("preview-fx").toFile())
        var stage: Stage? = null
        var editor: ItemEditorLogic? = null
        val previousTheme = fx { ThemeManager.current }
        var primary: Throwable? = null
        try {
            val main = fx {
                val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))
                val root = loader.load<Parent>()
                val controller = loader.getController<MainController>()
                stage = Stage().apply { scene = Scene(root, 1300.0, 900.0); show() }
                editor = ItemEditorLogic(controller, service).also {
                    it.setupActions(controller.actionButtonContainer)
                    it.start()
                }
                controller
            }
            assertNotNull(editor)
            awaitFx {
                !main.loadingRunning && main.mainContentContainer.children.any {
                    it is ScrollPane && (it.content as? ImageView)?.image != null
                }
            }
            val imageView = fx {
                assertIs<ImageView>(main.mainContentContainer.children.filterIsInstance<ScrollPane>().single().content)
            }
            for (theme in AppTheme.entries) fx {
                assertTrue(ThemeManager.select(theme))
                val image = assertNotNull(imageView.image)
                assertTrue(image.width > 0 && image.height > 0)
                assertTrue(imageView.accessibleText.contains("未対応フォント: custom:unsupported"))
                assertTrue(imageView.accessibleText.contains("元の内容 日本語 ABC"))
                assertEquals(originalHash, componentHash(source))
                val window = assertNotNull(stage)
                window.scene.root.applyCss()
                window.scene.root.layout()
                System.out.println("PREVIEW_FX actualOutputScale=${window.outputScaleX},${window.outputScaleY} theme=$theme")
                export(image, "editor-preview-${theme.name.lowercase()}")
                export(window.scene.snapshot(null), "editor-${theme.name.lowercase()}")
            }
            fx { PreviewCanvas(data, imageView).refreshPreview() }
            val component = data.display.mutableLore.single().single().toComponent()
            assertEquals(originalHash, componentHash(component))
            assertEquals(SaveResult.SUCCESS, service.items.save(data))
            val saved = assertIs<StoreResult.Success<MutableItemBaseData>>(memory.load(EditorDataDescriptors.item, data.id)).value
            assertEquals(originalHash, componentHash(saved.display.mutableLore.single().single().toComponent()))
            assertEquals("custom:unsupported", component.font()?.asString())
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            val failures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) { try { action() } catch (error: Throwable) { failures += error } }
            cleanup {
                val closed = CompletableFuture<Boolean>()
                val logic = editor
                if (logic != null) {
                    fx { logic.onClose { closed.complete(it) } }
                    assertTrue(closed.get(20, TimeUnit.SECONDS))
                }
            }
            cleanup { fx { stage?.close() } }
            cleanup { fx { ThemeManager.select(previousTheme) } }
            try { cleanup { service.awaitIo() } } finally { cleanup { service.closeIo() } }
            if (failures.isNotEmpty()) {
                val first = primary ?: failures.first()
                failures.filter { it !== first }.forEach(first::addSuppressed)
                if (primary == null) throw first
            }
        }
    }

    companion object {
        internal fun componentHash(component: Component): String =
            MessageDigest.getInstance("SHA-256").digest(MiniMessage.miniMessage().serialize(component).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        internal fun export(image: Image, name: String) {
            val directory = System.getProperty("manager.previewFxOutput") ?: return
            val target = Path.of(directory, "$name.png")
            Files.createDirectories(target.parent)
            val output = BufferedImage(image.width.toInt(), image.height.toInt(), BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until output.height) for (x in 0 until output.width) output.setRGB(x, y, image.pixelReader.getArgb(x, y))
            assertTrue(ImageIO.write(output, "png", target.toFile()))
        }


        internal fun startFx() {
            System.getProperty("manager.previewProcessIdentity")?.let { location ->
                val process = ProcessHandle.current()
                val start = process.info().startInstant().orElseThrow()
                val record = """{"role":"testWorker","pid":${process.pid()},"startInstant":"$start"}"""
                val target = Path.of(location)
                Files.createDirectories(target.parent)
                if (Files.exists(target)) assertEquals(record, Files.readString(target))
                else Files.writeString(target, record, java.nio.file.StandardOpenOption.CREATE_NEW)
            }
            val ready = CountDownLatch(1)
            try { Platform.startup { Platform.setImplicitExit(false); ready.countDown() } }
            catch (_: IllegalStateException) { Platform.runLater { ready.countDown() } }
            assertTrue(ready.await(15, TimeUnit.SECONDS))
        }
        internal fun <T> fx(action: () -> T): T {
            val task = FutureTask(action)
            Platform.runLater(task)
            return task.get(30, TimeUnit.SECONDS)
        }
        internal fun awaitFx(condition: () -> Boolean) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!fx(condition)) {
                assertTrue(System.nanoTime() < until)
                Thread.sleep(20)
            }
        }
    }
}
