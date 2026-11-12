package io.github.sushiericworkspace.sushiericservermanager.editor.render

import io.github.sushiericworkspace.sushiericservermanager.config.AppTheme
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.componentHash
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.export
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.fx
import io.github.sushiericworkspace.sushiericservermanager.editor.render.PreviewCanvasFxTest.Companion.startFx
import io.github.sushiericworkspace.sushiericservermanager.ui.theme.ThemeManager
import javafx.scene.Scene
import javafx.scene.image.ImageView
import javafx.scene.layout.VBox
import javafx.stage.Stage
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import kotlin.math.ceil
import kotlin.test.*

/** Rendererの合成倍率試験。実ディスプレイの倍率や物理操作の証明には使わない。 */
@EnabledOnOs(OS.WINDOWS, OS.MAC)
class PreviewRendererFxTest {
    @Test
    fun 結合文字と補助文字と装飾を両テーマの合成倍率で欠けずに描く() {
        startFx()
        val source = Component.text("親の色", NamedTextColor.RED, TextDecoration.BOLD)
            .append(Component.text(" 明示解除").decoration(TextDecoration.BOLD, false))
        val styled = Component.text("太字斜体 下線 取消線 影 日本語")
            .decorate(TextDecoration.BOLD, TextDecoration.ITALIC, TextDecoration.UNDERLINED, TextDecoration.STRIKETHROUGH)
        val rows = listOf(source, styled, Component.text("e\u0301 / 𠮷 / \uD83D\uDE00"),
            Component.text("未対応元内容").font(Key.key("minecraft:alt")).decorate(TextDecoration.OBFUSCATED))
        val hashes = rows.map(::componentHash)
        val previousTheme = fx { ThemeManager.current }
        val stage = fx { Stage().apply { scene = Scene(VBox(), 1300.0, 900.0); show() } }
        var primary: Throwable? = null
        try {
            val renderer = PreviewRenderer()
            for (theme in AppTheme.entries) for (scale in listOf(1.0, 1.5, 2.0)) fx {
                assertTrue(ThemeManager.select(theme))
                val result = renderer.render(rows, scale, scale)
                (stage.scene.root as VBox).children.setAll(ImageView(result.image).apply {
                    fitWidth = result.width; fitHeight = result.height
                })
                assertEquals(ceil(result.width * scale).toInt(), result.image.width.toInt())
                assertEquals(ceil(result.height * scale).toInt(), result.image.height.toInt())
                assertTrue(result.description.contains("未対応文字: U+1F600"))
                assertTrue(result.description.contains("未対応フォント: minecraft:alt"))
                assertEquals(hashes, rows.map(::componentHash))
                val pixels = result.image.pixelReader
                val width = result.image.width.toInt()
                val height = result.image.height.toInt()
                for (x in 0 until width) {
                    assertEquals(0, pixels.getArgb(x, 0) and 0xffffff)
                    assertEquals(0, pixels.getArgb(x, height - 1) and 0xffffff)
                }
                for (y in 0 until height) {
                    assertEquals(0, pixels.getArgb(0, y) and 0xffffff)
                    assertEquals(0, pixels.getArgb(width - 1, y) and 0xffffff)
                }
                System.out.println("PREVIEW_FX syntheticScale=$scale actualOutputScale=${stage.outputScaleX},${stage.outputScaleY} theme=$theme")
                export(result.image, "render-${theme.name.lowercase()}-$scale")
                val whole = renderer.render(listOf(Component.text("e\u0301")), scale, scale)
                val split = renderer.render(listOf(Component.text("e").append(Component.text("\u0301"))), scale, scale)
                val base = renderer.render(listOf(Component.text("e")), scale, scale)
                assertTrue(0x301 in PreviewResources.codepoints)
                fun bodyInk(image: javafx.scene.image.Image): Int {
                    var count = 0
                    for (y in ceil(64.0 * scale).toInt() until image.height.toInt())
                        for (x in 0 until image.width.toInt())
                            if (image.pixelReader.getArgb(x, y) and 0xffffff != 0) count++
                    return count
                }
                assertTrue(bodyInk(whole.image) > bodyInk(base.image),
                    "共通記号を除く本文に結合アクセントの追加inkがありません")
                assertEquals(whole.image.width, split.image.width, "同装飾のrun境界で結合文字の幅が変わっています")
                assertEquals(whole.image.height, split.image.height)
                for (y in 0 until whole.image.height.toInt()) for (x in 0 until whole.image.width.toInt()) {
                    assertEquals(whole.image.pixelReader.getArgb(x, y), split.image.pixelReader.getArgb(x, y),
                        "同装飾のrun境界で結合文字の描画が変わっています: $x,$y")
                }
            }
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            val failures = mutableListOf<Throwable>()
            try { fx { stage.close() } } catch (error: Throwable) { failures += error }
            try { fx { ThemeManager.select(previousTheme) } } catch (error: Throwable) { failures += error }
            if (failures.isNotEmpty()) {
                val first = primary ?: failures.first()
                failures.filter { it !== first }.forEach(first::addSuppressed)
                if (primary == null) throw first
            }
        }
    }

}
