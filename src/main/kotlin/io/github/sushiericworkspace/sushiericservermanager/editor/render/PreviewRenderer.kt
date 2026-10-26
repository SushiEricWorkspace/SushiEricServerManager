package io.github.sushiericworkspace.sushiericservermanager.editor.render

import javafx.application.Platform
import javafx.geometry.BoundingBox
import javafx.geometry.Bounds
import javafx.scene.Group
import javafx.scene.SnapshotParameters
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.scene.text.TextBoundsType
import javafx.scene.transform.Scale
import javafx.scene.transform.Shear
import net.kyori.adventure.text.Component
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 同梱書体で内容確認用の画像を作る。JavaFX Application Threadから呼ぶ。 */
internal class PreviewRenderer {
    data class Result(val image: Image, val width: Double, val height: Double, val description: String)

    private val loader = PreviewRenderer::class.java.classLoader
    private val size = 22.0
    private val regular by lazy { loadFont(PreviewResources.REGULAR, "Regular") }
    private val bold by lazy { loadFont(PreviewResources.BOLD, "Bold") }
    private val item by lazy { loadSymbol(PreviewResources.ITEM) }
    private val missing by lazy { loadSymbol(PreviewResources.MISSING) }

    private fun loadSymbol(path: String): Image {
        PreviewResources.ensureVerified()
        return loader.getResourceAsStream(path)!!.use { Image(it) }.also {
            check(!it.isError) { "同梱プレビュー記号を読めません" }
        }
    }

    private fun loadFont(path: String, style: String): Font {
        PreviewResources.ensureVerified()
        val font = loader.getResourceAsStream(path)!!.use { Font.loadFont(it, size) }
            ?: error("同梱プレビューフォントを読めません: $path")
        check(font.family == "Noto Sans JP" && font.style == style) {
            "同梱プレビューフォントが別の書体へ置換されました: $path"
        }
        return font
    }

    fun render(components: List<Component>, scaleX: Double, scaleY: Double, symbol: PreviewSymbol = PreviewSymbol.COMMON): Result {
        check(Platform.isFxApplicationThread()) { "プレビュー描画はJavaFX Application Threadで実行してください" }
        require(scaleX.isFinite() && scaleX > 0 && scaleY.isFinite() && scaleY > 0)
        PreviewResources.ensureVerified()
        val lines = components.flatMap(ComponentSegments::convert)
        val description = mutableListOf(when (symbol) {
            PreviewSymbol.COMMON -> "Manager標準書体・独自の共通アイテム記号による確認表示（個別画像は未収録）"
            PreviewSymbol.MISSING -> "参照先不明・未収録記号による確認表示"
            PreviewSymbol.UNAVAILABLE -> "プレビュー未取得・内容は保持されています"
        })
        val content = Group()
        content.children.add(ImageView(if (symbol == PreviewSymbol.COMMON) item else missing).apply { x = 16.0; y = 16.0 })
        content.children.add(Text(when (symbol) {
            PreviewSymbol.COMMON -> "共通記号・未収録"
            PreviewSymbol.MISSING -> "参照先不明"
            PreviewSymbol.UNAVAILABLE -> "未取得"
        }).apply {
            font = regular
            fill = Color.LIGHTGRAY
            x = 58.0
            y = 38.0
        })
        var top = 64.0
        var right = 264.0

        lines.forEach { runs ->
            val line = Group()
            var advance = 0.0
            var logicalTop = -size
            var logicalBottom = size * 0.3
            val displayRuns = runs.flatMap { run ->
                val display = PreviewText.display(run, PreviewResources.codepoints)
                display.notice?.let { description.add("$it / 元の文字列: ${run.text}") }
                display.runs
            }
            displayRuns.forEach { run ->
                val font = if (run.style.bold) bold else regular
                fun textNode(color: Color) = Text(run.text).apply {
                    this.font = font
                    fill = color
                    isUnderline = run.style.underlined
                    isStrikethrough = run.style.strikethrough
                    boundsType = TextBoundsType.LOGICAL
                    if (run.style.italic) transforms.add(Shear(-0.20, 0.0))
                }
                val color = Color.rgb((run.style.color shr 16) and 255,
                    (run.style.color shr 8) and 255, run.style.color and 255)
                val foreground = textNode(color)
                val logical = foreground.layoutBounds
                foreground.boundsType = TextBoundsType.VISUAL
                logicalTop = min(logicalTop, logical.minY)
                logicalBottom = max(logicalBottom, logical.maxY)
                val shadow = textNode(Color.color(color.red * 0.25, color.green * 0.25, color.blue * 0.25))
                shadow.boundsType = TextBoundsType.VISUAL
                shadow.x = advance + 1.0
                shadow.y = 1.0
                foreground.x = advance
                line.children.addAll(shadow, foreground)
                advance += logical.width
            }
            val painted: Bounds = if (line.children.isEmpty()) BoundingBox(0.0, logicalTop, 1.0, logicalBottom - logicalTop)
                else line.boundsInLocal
            val left = min(0.0, painted.minX)
            val upper = min(logicalTop, painted.minY)
            val lower = max(logicalBottom, painted.maxY)
            line.translateX = 16.0 - left
            line.translateY = top - upper
            content.children.add(line)
            right = max(right, 16.0 + max(advance, painted.maxX) - left)
            top += ceil(lower - upper) + 4.0
        }

        val width = ceil(max(280.0, right + 16.0))
        val height = ceil(max(560.0, top + 16.0))
        val root = Group(Rectangle(width, height, Color.BLACK), content)
        val image = root.snapshot(SnapshotParameters().apply {
            fill = Color.TRANSPARENT
            transform = Scale(scaleX, scaleY)
        }, null)
        description.addAll(lines.map { line -> line.joinToString("") { it.text } })
        return Result(image, width, height, description.joinToString("\n"))
    }
}
