package io.github.sushiericworkspace.sushiericservermanager.editor.render

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseDataView
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import javafx.animation.AnimationTimer
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.beans.value.ChangeListener
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.ContentDisplay
import javafx.scene.control.Control
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tooltip
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import javafx.scene.image.ImageView
import javafx.stage.Window
import javafx.util.Duration
import java.util.Collections
import java.util.WeakHashMap
import org.slf4j.LoggerFactory

/** 保存先の寿命で共有し、画面に入った候補を一frame一件ずつ準備する。 */
internal class ItemPreviewService : AutoCloseable {
    private val renderer = PreviewRenderer()
    private val cache = ItemPreviewCache<PreviewRenderer.Result>()
    private var revisions: Pair<String, String>? = null
    private val bindings = Collections.newSetFromMap(WeakHashMap<Binding, Boolean>())
    private val pending = linkedSetOf<Binding>()
    @Volatile private var closed = false
    @Volatile private var used = false
    private var timer: AnimationTimer? = null

    fun render(content: ItemPreviewContent, scaleX: Double, scaleY: Double, source: String): PreviewRenderer.Result {
        check(Platform.isFxApplicationThread())
        used = true
        check(!closed)
        val key = key(content, scaleX, scaleY, source)
        return cache[key] ?: renderer.render(content.rows, scaleX, scaleY, content.symbol).also {
            cache.put(key, it, it.image.width.toLong() * it.image.height.toLong() * 4)
        }
    }

    /** 候補更新時に呼ぶ。hover callbackは準備済みcacheの参照だけを行う。 */
    fun install(node: Node, item: ItemBaseDataView?, missing: ItemInternalId? = null, source: String = "saved") {
        val content = item?.let(ItemPreviewContent::item) ?: missing?.let(ItemPreviewContent::missing)
        installContent(node, content, source)
    }

    fun installContent(node: Node, content: ItemPreviewContent?, source: String) {
        check(Platform.isFxApplicationThread())
        used = true
        val previous = node.properties[BINDING] as? Binding
        val control = node as? Control
        val notice = control?.tooltip?.text
        control?.tooltip = null
        if (!closed && previous != null && previous.owner === this && previous.content == content && previous.source == source) {
            previous.notice = notice
            previous.reinstall()
            enqueue(previous)
            return
        }
        previous?.close()
        if (closed || content == null) return
        Binding(node, content, source, notice).also { node.properties[BINDING] = it }
    }

    fun review(entries: List<ItemPreviewReview>): VBox = VBox(6.0).apply {
        children.setAll(entries.map { entry -> Label(entry.label).apply {
            isWrapText = true
            installContent(this, entry.content, entry.source)
        } })
    }

    private fun key(content: ItemPreviewContent, x: Double, y: Double, source: String): ItemPreviewKey {
        val version = revisions ?: (PreviewResources.fontRevision to PreviewResources.imageRevision).also { revisions = it }
        return ItemPreviewKey(source, content, x, y, 2, version.first, version.second)
    }

    private fun enqueue(binding: Binding) {
        if (closed || !binding.inViewport()) { pending.remove(binding); return }
        if (binding.restoreCached()) { pending.remove(binding); return }
        pending += binding
        if (timer == null) {
            timer = object : AnimationTimer() {
                override fun handle(now: Long) {
                    val next = pending.firstOrNull()
                    if (next == null) { stop(); timer = null; return }
                    pending.remove(next)
                    next.prepare()
                }
            }.also { it.start() }
        }
    }

    private inner class Binding(val node: Node, val content: ItemPreviewContent, val source: String, var notice: String?) : AutoCloseable {
        val owner = this@ItemPreviewService
        private var prepared: ItemPreviewKey? = null
        private var problem: String? = null
        private var window: Window? = null
        private val invalidated = InvalidationListener {
            if (window?.isShowing != true) pending.remove(this)
            enqueue(this)
        }
        private val windowChanged = ChangeListener<Window?> { _, _, value -> attachWindow(value) }
        private val sceneChanged = ChangeListener<Scene?> { _, old, value ->
            old?.windowProperty()?.removeListener(windowChanged)
            value?.windowProperty()?.addListener(windowChanged)
            if (value == null) pending.remove(this)
            attachWindow(value?.window)
        }
        private val tooltip = Tooltip("プレビューを準備しています").apply {
            contentDisplay = ContentDisplay.TOP
            isWrapText = true
            showDelay = Duration.ZERO
            showDuration = Duration.INDEFINITE
            setOnShowing {
                val result = prepared?.let { cache[it] }
                val state = if (result == null) problem ?: "プレビューは未準備です。内容は保持されています。" else null
                text = listOfNotNull(notice, state).joinToString("\n").takeIf { it.isNotEmpty() }
                graphic = result?.let { value -> ImageView(value.image).apply {
                    fitWidth = value.width; fitHeight = value.height
                    accessibleText = value.description
                } }
            }
            setOnHidden { graphic = null }
        }

        init {
            Tooltip.install(node, tooltip)
            node.sceneProperty().addListener(sceneChanged)
            node.localToSceneTransformProperty().addListener(invalidated)
            node.boundsInLocalProperty().addListener(invalidated)
            node.visibleProperty().addListener(invalidated)
            node.scene?.windowProperty()?.addListener(windowChanged)
            bindings += this
            attachWindow(node.scene?.window)
        }

        private fun attachWindow(value: Window?) {
            window?.outputScaleXProperty()?.removeListener(invalidated)
            window?.outputScaleYProperty()?.removeListener(invalidated)
            window?.showingProperty()?.removeListener(invalidated)
            window = value
            value?.outputScaleXProperty()?.addListener(invalidated)
            value?.outputScaleYProperty()?.addListener(invalidated)
            value?.showingProperty()?.addListener(invalidated)
            prepared = null
            enqueue(this)
        }

        fun reinstall() {
            Tooltip.uninstall(node, tooltip)
            Tooltip.install(node, tooltip)
        }

        fun inViewport(): Boolean {
            val scene = node.scene ?: return false
            if (window?.isShowing != true || !node.isVisible || scene.width <= 0 || scene.height <= 0) return false
            val bounds = node.localToScene(node.boundsInLocal)
            if (!bounds.intersects(0.0, 0.0, scene.width, scene.height)) return false
            var parent = node.parent
            while (parent != null) {
                if (!parent.isVisible) return false
                if (parent is ScrollPane && !bounds.intersects(parent.localToScene(parent.boundsInLocal))) return false
                parent = parent.parent
            }
            return true
        }

        fun restoreCached(): Boolean {
            if (revisions == null) return false
            val x = window?.outputScaleX ?: 1.0
            val y = window?.outputScaleY ?: 1.0
            val request = prepared?.takeIf { it.scaleX == x && it.scaleY == y }
                ?: key(content, x, y, source).also { prepared = it }
            return (cache[request] != null).also { if (it) problem = null }
        }

        fun prepare() {
            if (!inViewport() || closed || restoreCached()) return
            val x = window?.outputScaleX ?: 1.0
            val y = window?.outputScaleY ?: 1.0
            try {
                val request = key(content, x, y, source)
                if (request != prepared || cache[request] == null) render(content, x, y, source)
                prepared = request
                problem = if (cache[request] == null) "プレビュー画像が表示上限を超えています。元データは保持されています。" else null
            } catch (error: Exception) {
                prepared = null
                problem = "プレビュー素材を確認してください。内容は保持されています。"
                LoggerFactory.getLogger(ItemPreviewService::class.java).warn("参照プレビューを準備できませんでした", error)
            }
        }

        override fun close() {
            pending.remove(this); bindings.remove(this)
            node.sceneProperty().removeListener(sceneChanged)
            node.scene?.windowProperty()?.removeListener(windowChanged)
            node.localToSceneTransformProperty().removeListener(invalidated)
            node.boundsInLocalProperty().removeListener(invalidated)
            node.visibleProperty().removeListener(invalidated)
            window?.outputScaleXProperty()?.removeListener(invalidated)
            window?.outputScaleYProperty()?.removeListener(invalidated)
            window?.showingProperty()?.removeListener(invalidated)
            tooltip.hide(); tooltip.graphic = null
            Tooltip.uninstall(node, tooltip)
            node.properties.remove(BINDING)
        }
    }

    override fun close() {
        closed = true
        if (!used) return
        fun release() {
            timer?.stop(); timer = null
            bindings.toList().forEach { it.close() }
            pending.clear(); cache.clear()
        }
        if (Platform.isFxApplicationThread()) release() else Platform.runLater(::release)
    }

    private companion object { const val BINDING = "manager.itemPreview" }
}
