package io.github.sushiericworkspace.sushiericservermanager.app.workspace

import io.github.sushiericworkspace.sushiericservermanager.app.AppFrame
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.config.*
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.ShortcutManager
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.css.PseudoClass
import javafx.event.EventHandler
import javafx.stage.Stage
import javafx.stage.WindowEvent
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.geometry.Orientation
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.*
import javafx.scene.input.ClipboardContent
import javafx.scene.input.DataFormat
import javafx.scene.input.MouseEvent
import javafx.scene.input.TransferMode
import javafx.scene.layout.*
import javafx.stage.Window
import javafx.util.Duration

/** 分割木と生存中の機能Nodeを対応させます。移動では生成・読込・終了処理を呼びません。 */
internal class WorkspaceView(
    internal val frame: AppFrame,
    private val home: Parent,
    private val identity: String?,
    private val openFeature: (WorkspaceFeature) -> Unit,
    private val available: (WorkspaceFeature) -> Boolean,
    private val homeVisible: (Boolean) -> Unit
) : BorderPane() {
    private class Feature(
        val root: Parent,
        val pane: BorderPane,
        val close: ((Boolean) -> Unit) -> Unit,
        val dispose: () -> Unit
    ) { var menus: (() -> Unit)? = null }

    private val features = linkedMapOf<WorkspaceFeature, Feature>()
    private val original = identity?.let { AppSettingsManager.load().workspaceLayouts[it] } ?: WorkspaceLayout()
    internal var layout = original.copy(root = null, active = null)
        private set
    private var restoring = false
    private var restored = false
    private var disposed = false
    private val dividerListeners = mutableListOf<Pair<SplitPane.Divider, ChangeListener<Number>>>()
    private val saveTimer = PauseTransition(Duration.millis(200.0)).apply { setOnFinished { persist() } }
    private val focusListener = ChangeListener<Node> { _, _, node ->
        features.entries.firstOrNull { (_, value) -> contains(value.pane, node) }?.let { activate(it.key, false) }
    }
    private var closeStage: Stage? = null
    private val closeRequestHandler = EventHandler<WindowEvent> { event ->
        event.consume()
        Utility.closeConnection { Platform.exit() }
    }
    private val showingListener = ChangeListener<Boolean> { _, _, showing -> if (showing) restore() else dispose() }
    private val windowListener = ChangeListener<Window> { _, old, next ->
        old?.showingProperty()?.removeListener(showingListener)
        next?.showingProperty()?.addListener(showingListener)
        bindCloseStage(next as? Stage)
        if (next?.isShowing == true) restore()
        else if (old != null && next == null) dispose()
    }

    init {
        frame.useWorkspaceActions()
        id = "workspace"
        prefWidth = 1400.0
        prefHeight = 800.0
        styleClass.add("workspace")
        minWidth = 0.0
        minHeight = 0.0
        left = VBox(2.0).apply {
            styleClass.add("workspace-navigation")
            for (feature in WorkspaceFeature.entries) children.add(Button(feature.title).apply {
                id = "workspace-open-${feature.name.lowercase()}"
                maxWidth = Double.MAX_VALUE
                isDisable = !available(feature)
                setOnAction { if (feature == WorkspaceFeature.HOME) showHome() else openFeature(feature) }
            })
        }
        showHome()
        sceneProperty().addListener { _, old, next ->
            old?.focusOwnerProperty()?.removeListener(focusListener)
            old?.windowProperty()?.removeListener(windowListener)
            old?.window?.showingProperty()?.removeListener(showingListener)
            next?.focusOwnerProperty()?.addListener(focusListener)
            next?.windowProperty()?.addListener(windowListener)
            next?.window?.showingProperty()?.addListener(showingListener)
            bindCloseStage(next?.window as? Stage)
            if (next?.window?.isShowing == true) restore()
            else if (old != null && next == null) dispose()
        }
    }

    fun contains(root: Parent): Boolean = features.values.any { it.root === root }
    fun feature(root: Node): WorkspaceFeature? = features.entries.firstOrNull { contains(it.value.root, root) }?.key
    fun titles(): List<WorkspaceFeature> = layout.root?.features().orEmpty()

    fun focus(feature: WorkspaceFeature): Boolean {
        if (feature !in features) return false
        activate(feature, true)
        return true
    }

    fun show(
        feature: WorkspaceFeature, root: Parent, title: String = feature.title,
        close: ((Boolean) -> Unit) -> Unit = { it(true) },
        dispose: () -> Unit = {}
    ) {
        check(feature !in features)
        root.isFocusTraversable = true
        if (root is Region) { root.minWidth = 0.0; root.minHeight = 0.0 }
        val heading = HBox(6.0).apply {
            styleClass.add("workspace-feature-heading")
            children.addAll(Label(title).apply { maxWidth = Double.MAX_VALUE; HBox.setHgrow(this, Priority.ALWAYS) },
                Button("×").apply { id = "workspace-close-${feature.name.lowercase()}"; setOnAction { this@WorkspaceView.close(feature) } })
        }
        val pane = BorderPane(root, heading, null, null, null).apply {
            id = "workspace-feature-${feature.name.lowercase()}"
            styleClass.add("workspace-feature")
            minWidth = 0.0; minHeight = 64.0
            addEventFilter(MouseEvent.MOUSE_PRESSED) { activate(feature, false) }
        }
        heading.setOnMouseClicked { event ->
            if (event.button == javafx.scene.input.MouseButton.PRIMARY && event.isStillSincePress) activate(feature, true)
        }
        heading.setOnDragDetected { event ->
            if (scene?.let(ShortcutManager::isComposing) == true) return@setOnDragDetected
            heading.startDragAndDrop(TransferMode.MOVE).setContent(ClipboardContent().apply { put(FEATURE_FORMAT, feature.name) })
            event.consume()
        }
        pane.setOnDragOver { event ->
            val source = dragFeature(event.dragboard.getContent(FEATURE_FORMAT))
            if (source != null && source != feature) { event.acceptTransferModes(TransferMode.MOVE); event.consume() }
        }
        pane.setOnDragEntered { event -> if (dragFeature(event.dragboard.getContent(FEATURE_FORMAT)) != null) pane.pseudoClassStateChanged(DROP_TARGET, true) }
        pane.setOnDragExited { pane.pseudoClassStateChanged(DROP_TARGET, false) }
        pane.setOnDragDropped { event ->
            val source = dragFeature(event.dragboard.getContent(FEATURE_FORMAT))
            val point = pane.sceneToLocal(event.sceneX, event.sceneY)
            val x = point.x / pane.width
            val y = point.y / pane.height
            val side = listOf(DockSide.LEFT to x, DockSide.RIGHT to 1 - x, DockSide.TOP to y, DockSide.BOTTOM to 1 - y).minBy { it.second }.first
            event.isDropCompleted = source != null && move(source, feature, side)
            pane.pseudoClassStateChanged(DROP_TARGET, false)
            event.consume()
        }
        val screen = when (feature) {
            WorkspaceFeature.HOME -> AppScreen.HOME
            WorkspaceFeature.ITEM_EDITOR, WorkspaceFeature.ORE_EDITOR, WorkspaceFeature.SHOP_EDITOR, WorkspaceFeature.RECIPE_EDITOR -> AppScreen.BASE
            WorkspaceFeature.DASHBOARD -> AppScreen.DASHBOARD
            WorkspaceFeature.CONSOLE -> AppScreen.CONSOLE
            WorkspaceFeature.SERVER_CONTROL -> AppScreen.SERVER_CONTROL
            WorkspaceFeature.MOD_CONFIG -> AppScreen.MOD_CONFIG
            WorkspaceFeature.MONEY_HISTORY -> AppScreen.MONEY_HISTORY
        }
        scene?.let { owner ->
            val css = requireNotNull(javaClass.getResource(screen.css)).toExternalForm()
            if (css !in owner.stylesheets) owner.stylesheets.add(css)
        }
        val placement = newFeaturePlacement()
        features[feature] = Feature(root, pane, close, dispose)
        layout = layout.open(feature, placement?.first, placement?.second ?: DockSide.RIGHT)
        render()
        activate(feature, true)
        changed()
    }

    fun close(feature: WorkspaceFeature): Boolean {
        val view = features[feature] ?: return false
        view.close { success ->
            if (success) remove(feature)
            else CustomDialog.error().owner(scene?.window as? javafx.stage.Stage).header("処理を完了できないため、機能を閉じられませんでした").show()
        }
        return true
    }

    /** 退避完了後の呼出し専用です。閉じる処理を再実行しません。 */
    fun remove(feature: WorkspaceFeature) {
        val view = features.remove(feature) ?: return
        view.dispose()
        layout = layout.close(feature)
        render()
        layout.active?.let { activate(it, true) } ?: frame.useDefaultMenus()
        changed()
    }

    /** 接続終了時は表示配置を保存するため、終了済みViewの登録だけを外します。 */
    fun completeClose(feature: WorkspaceFeature) { features.remove(feature)?.dispose?.invoke() }

    fun closeActive() { layout.active?.let(::close) }

    fun editorMenus(root: Parent, menus: () -> Unit) {
        val feature = feature(root) ?: return
        features.getValue(feature).menus = menus
        if (layout.active == feature) menus()
    }

    fun tabs(root: Node): IdTabsState? = feature(root)?.let { layout.documents[it] }
    fun tabs(root: Node, state: IdTabsState) {
        val feature = feature(root) ?: return
        layout = layout.copy(documents = layout.documents + (feature to state))
        changed()
    }

    internal fun tabs(root: Node, source: String): IdTabsState? =
        feature(root)?.let { layout.sourceDocuments[it.name + ":" + source] }

    internal fun tabs(root: Node, state: IdTabsState, source: String) {
        val feature = feature(root) ?: return
        layout = layout.copy(sourceDocuments = layout.sourceDocuments + (feature.name + ":" + source to state))
        changed()
    }

    internal fun documentSource(root: Node): String? = feature(root)?.let { layout.documentSources[it] }

    internal fun documentSource(root: Node, source: String) {
        val feature = feature(root) ?: return
        layout = layout.copy(documentSources = layout.documentSources + (feature to source))
        changed()
    }

    fun move(feature: WorkspaceFeature, target: WorkspaceFeature, side: DockSide): Boolean {
        if (feature !in features || target !in features || feature == target || scene?.let(ShortcutManager::isComposing) == true) return false
        layout = layout.copy(root = layout.root?.dock(feature, target, side))
        render()
        activate(feature, !contains(features.getValue(feature).root, scene?.focusOwner))
        changed()
        return true
    }

    fun openHome() = showHome()

    private fun showHome() {
        if (focus(WorkspaceFeature.HOME)) return
        homeVisible(true)
        show(WorkspaceFeature.HOME, home, dispose = { homeVisible(false) })
    }

    private fun activate(feature: WorkspaceFeature, focus: Boolean) {
        val target = features[feature] ?: return
        val previous = layout.active
        layout = layout.copy(active = feature)
        features.forEach { (id, value) -> value.pane.pseudoClassStateChanged(ACTIVE, id == feature) }
        frame.useDefaultMenus()
        target.menus?.invoke()
        ShortcutManager.activate(target.root)
        if (focus) target.root.requestFocus()
        if (previous != feature) changed()
    }

    private fun render() {
        val input = scene?.focusOwner as? TextInputControl
        val anchor = input?.anchor ?: 0
        val caret = input?.caretPosition ?: 0
        dividerListeners.forEach { (divider, listener) -> divider.positionProperty().removeListener(listener) }
        dividerListeners.clear()
        center = null
        features.values.forEach { (it.pane.parent as? SplitPane)?.items?.remove(it.pane) }
        fun build(node: WorkspaceNode, path: List<Boolean>): Node = when (node) {
            is WorkspaceNode.Feature -> features.getValue(node.feature).pane
            is WorkspaceNode.Split -> SplitPane(build(node.first, path + false), build(node.second, path + true)).apply {
                orientation = if (node.direction == SplitDirection.HORIZONTAL) Orientation.HORIZONTAL else Orientation.VERTICAL
                minWidth = 0.0; minHeight = 0.0
                setDividerPositions(node.ratio)
                val listener = ChangeListener<Number> { _, _, next ->
                    if (next.toDouble().isFinite()) { layout = layout.copy(root = layout.root?.ratio(path, next.toDouble())); changed() }
                }
                dividers.single().positionProperty().addListener(listener)
                dividerListeners += dividers.single() to listener
            }
        }
        center = layout.root?.let { build(it, emptyList()) } ?: Label("左の一覧から機能を開いてください")
        if (scene != null) { applyCss(); layout() }
        if (input != null && input.scene === scene) { input.requestFocus(); input.selectRange(anchor, caret) }
    }

    private fun WorkspaceNode.ratio(path: List<Boolean>, value: Double): WorkspaceNode {
        if (this !is WorkspaceNode.Split) return this
        if (path.isEmpty()) return copy(ratio = value)
        return if (path.first()) copy(second = second.ratio(path.drop(1), value)) else copy(first = first.ratio(path.drop(1), value))
    }

    private fun restore() {
        if (restored || disposed) return
        WorkspaceManager.acquire(this)
        restored = true
        restoring = true
        val wanted = original.root?.features().orEmpty()
        for (feature in wanted) if (feature != WorkspaceFeature.HOME && available(feature)) openFeature(feature)
        fun available(node: WorkspaceNode?): WorkspaceNode? = when (node) {
            null -> null
            is WorkspaceNode.Feature -> node.takeIf { it.feature in features }
            is WorkspaceNode.Split -> {
                val first = available(node.first); val second = available(node.second)
                when { first == null -> second; second == null -> first; else -> node.copy(first = first, second = second) }
            }
        }
        if (original.root != null) {
            layout = layout.copy(root = available(original.root), active = original.active?.takeIf { it in features })
            if (WorkspaceFeature.HOME !in layout.root?.features().orEmpty()) features.remove(WorkspaceFeature.HOME)?.dispose?.invoke()
            render()
            (layout.active ?: layout.root?.features()?.firstOrNull())?.let { activate(it, true) }
        }
        restoring = false
    }

    private fun changed() { if (!restoring && !disposed) saveTimer.playFromStart() }
    private fun persist() {
        val key = identity ?: return
        val previous = AppSettingsManager.load()
        AppSettingsManager.save(previous.copy(workspaceLayouts = previous.workspaceLayouts + (key to layout)))
        if (AppSettingsManager.load().workspaceLayouts[key] != layout) CustomDialog.error().header("画面配置を保存できませんでした").show()
    }

    private fun dispose() {
        if (disposed) return
        saveTimer.stop()
        persist()
        disposed = true
        dividerListeners.forEach { (divider, listener) -> divider.positionProperty().removeListener(listener) }
        dividerListeners.clear()
        features.values.forEach { it.dispose() }
        scene?.focusOwnerProperty()?.removeListener(focusListener)
        scene?.windowProperty()?.removeListener(windowListener)
        scene?.window?.showingProperty()?.removeListener(showingListener)
        bindCloseStage(null)
        WorkspaceManager.release(this)
    }

    private fun newFeaturePlacement(): Pair<WorkspaceFeature, DockSide>? {
        fun splittable(view: Feature) = view.pane.width >= MIN_WORK_WIDTH * 2 || view.pane.height >= MIN_WORK_HEIGHT * 2
        val active = features.entries.firstOrNull { it.key == layout.active && splittable(it.value) }
        val target = active ?: features.entries.filter { splittable(it.value) }.maxByOrNull { it.value.pane.width * it.value.pane.height }
            ?: features.entries.maxByOrNull { it.value.pane.width * it.value.pane.height } ?: return null
        val pane = target.value.pane
        val side = when {
            pane.width >= MIN_WORK_WIDTH * 2 && (pane.height < MIN_WORK_HEIGHT * 2 || pane.width >= pane.height * 1.5) -> DockSide.RIGHT
            pane.height >= MIN_WORK_HEIGHT * 2 -> DockSide.BOTTOM
            pane.width / MIN_WORK_WIDTH >= pane.height / MIN_WORK_HEIGHT -> DockSide.RIGHT
            else -> DockSide.BOTTOM
        }
        return target.key to side
    }

    private fun bindCloseStage(next: Stage?) {
        closeStage?.let { if (it.onCloseRequest === closeRequestHandler) it.onCloseRequest = null }
        closeStage = next
        next?.onCloseRequest = closeRequestHandler
    }

    private fun dragFeature(value: Any?): WorkspaceFeature? = WorkspaceFeature.entries.firstOrNull { it.name == value && it in features }
    private fun contains(root: Node, node: Node?): Boolean {
        var current = node
        while (current != null) { if (current === root) return true; current = current.parent }
        return false
    }

    companion object {
        // 既存サイドバーと本文を残す、新規分割時の最低作業領域。Stageの最小サイズではありません。
        private const val MIN_WORK_WIDTH = 640.0
        private const val MIN_WORK_HEIGHT = 220.0
        private val FEATURE_FORMAT = DataFormat("application/x-sushieric-workspace-feature")
        private val ACTIVE = PseudoClass.getPseudoClass("active")
        private val DROP_TARGET = PseudoClass.getPseudoClass("drop-target")
    }
}
