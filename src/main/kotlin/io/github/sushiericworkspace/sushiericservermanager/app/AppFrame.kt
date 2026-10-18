package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementState
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.ui.AppTooltip
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.AppearanceDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.ShortcutSettingsDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.update.AppVersion
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceManager
import javafx.beans.value.ChangeListener
import javafx.stage.Window
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.geometry.Pos
import javafx.scene.Parent
import javafx.scene.control.*
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HeaderBar
import javafx.scene.layout.HeaderDragType
import javafx.scene.layout.Region
import javafx.scene.layout.Priority
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.stage.WindowEvent
import javafx.util.Duration

/** 主要画面をOS標準ボタン付きの共通枠で包みます。ダイアログの装飾は変更しません。 */
internal class AppFrame(private val screen: AppScreen, content: Parent) : BorderPane() {
    private val status = Label().apply { id = "connectionStatus"; isWrapText = true; minWidth = 0.0 }
    private val menuBar = MenuBar().apply { id = "applicationMenu"; isUseSystemMenuBar = isMac }
    private val header = HeaderBar().apply { id = "applicationHeader" }
    private val back = Button().apply { id = "navigateBack"; setOnAction { goBack() } }
    private val disconnect = Button("切断").apply { id = "disconnectSession"; setOnAction { disconnect() } }
    private val mode = Button("モード変更").apply { id = "changeMode"; setOnAction { Utility.navigateToModeSelect() } }
    private val refresh = Timeline(KeyFrame(Duration.seconds(1.0), { updateStatus() })).apply {
        cycleCount = Timeline.INDEFINITE
    }
    private var observedStage: Stage? = null
    private val showingListener = ChangeListener<Boolean> { _, _, _ -> updateRefresh() }
    private val windowListener = ChangeListener<Window> { _, _, window -> observeStage(window as? Stage) }
    private val editorMenusKey = Any()
    private data class EditorMenus(val file: List<MenuItem>, val edit: List<MenuItem>)
    private var windowActions: List<MenuItem> = emptyList()
    private val stage: Stage? get() = scene?.window as? Stage
    private val preparing: Boolean get() = screen == AppScreen.STARTUP_PREPARING

    init {
        styleClass.add("application-frame")
        minWidth = 480.0
        val title = Label("SushiEricServerManager").apply {
            id = "applicationName"
            minWidth = 0.0
            tooltip = AppTooltip.create(text)
        }
        HeaderBar.setDragType(title, HeaderDragType.DRAGGABLE)
        HeaderBar.setDragType(menuBar, HeaderDragType.NONE)
        val settings = MenuButton("設定").apply {
            id = "applicationSettings"
            items.addAll(MenuItem("外観").apply { setOnAction { AppearanceDialog.show(stage) } }, MenuItem("キーボードショートカット").apply { setOnAction { ShortcutSettingsDialog.show(stage) } })
        }
        HeaderBar.setDragType(settings, HeaderDragType.NONE)
        header.left = if (isMac) null else menuBar
        header.center = title
        HeaderBar.setAlignment(title, if (isMac) Pos.CENTER else Pos.CENTER_LEFT)
        header.right = settings
        val details = Button("接続の詳細").apply {
            id = "connectionDetails"
            minWidth = Region.USE_PREF_SIZE
            setOnAction { showDetails() }
        }
        val stateRow = HBox(10.0, status, details).apply {
            alignment = Pos.CENTER_LEFT
            styleClass.add("connection-status-row")
            HBox.setHgrow(status, Priority.ALWAYS)
            status.maxWidth = Double.MAX_VALUE
        }
        val navigation = HBox(8.0, back, disconnect, mode).apply { styleClass.add("application-navigation") }
        top = VBox().apply {
            if (isMac) children.add(menuBar)
            children.addAll(header, stateRow, navigation)
        }
        center = content
        buildMenus()
        sceneProperty().addListener { _, previous, current ->
            previous?.windowProperty()?.removeListener(windowListener)
            observeStage(null)
            current?.windowProperty()?.addListener(windowListener)
            observeStage(current?.window as? Stage)
        }
        updateStatus()
    }

    private fun observeStage(owner: Stage?) {
        observedStage?.showingProperty()?.removeListener(showingListener)
        refresh.stop()
        observedStage = owner
        if (owner == null) return
        if (!owner.isShowing) owner.initStyle(StageStyle.EXTENDED)
        owner.minWidth = maxOf(owner.minWidth, 500.0)
        owner.showingProperty().addListener(showingListener)
        updateRefresh()
    }

    private fun updateRefresh() {
        val owner = observedStage
        if (owner?.isShowing == true && owner.scene === scene) {
            updateStatus()
            refresh.play()
        } else refresh.stop()
    }

    private fun buildMenus() {
        fun item(text: String, action: () -> Unit) = MenuItem(text).apply { setOnAction { action() } }
        val file = Menu("ファイル").apply {
            items.addAll(
                item("このウィンドウを閉じる") { closeWindow() },
                item("アプリケーションを終了") { Utility.closeConnection { javafx.application.Platform.exit() } })
        }
        windowActions = file.items.toList()
        val edit = Menu("編集").apply { isDisable = true }
        val view = Menu("表示").apply {
            items.addAll(item("ホームへ戻る") { goBack() }, item("全画面表示を切り替え") {
                stage?.let { it.isFullScreen = !it.isFullScreen }
            })
            setOnShowing { items[0].isDisable = screen !in homeChildren && WorkspaceManager.current?.frame !== this@AppFrame }
        }
        val server = Menu("サーバー").apply {
            items.addAll(item("接続の詳細") { showDetails() }, item("切断") { disconnect() },
                item("動作モードを変更") { Utility.navigateToModeSelect() })
            items[1].isDisable = preparing || !hasSession()
            items[2].isDisable = preparing || screen == AppScreen.MODE_SELECT
            setOnShowing {
                items[1].isDisable = preparing || !hasSession()
                items[2].isDisable = preparing || screen == AppScreen.MODE_SELECT
            }
        }
        val windows = Menu("ウィンドウ").apply {
            setOnShowing {
                val workspace = WorkspaceManager.current?.takeIf { it.frame === this@AppFrame }
                val featureItems = workspace?.titles().orEmpty().map { feature -> item(feature.title) { workspace?.focus(feature) } }
                items.setAll(featureItems + Stage.getWindows().filterIsInstance<Stage>().filter { it.isShowing }.map { window ->
                    item(window.title) { window.isIconified = false; window.toFront(); window.requestFocus() }
                })
            }
        }
        val help = Menu("ヘルプ").apply {
            items.add(item("SushiEricServerManagerについて") {
                CustomDialog.information().owner(stage).title("アプリケーションについて")
                    .header("SushiEricServerManager").content("バージョン ${AppVersion.CURRENT}").show()
            })
        }
        menuBar.menus.addAll(file, edit, view, server, windows, help)
    }

    /** 同じMenuItemを保持し、選択中の機能の操作と実行条件へ切り替えます。 */
    fun useEditorMenus(container: HBox, refreshActions: () -> Unit) {
        val menus = container.properties[editorMenusKey] as? EditorMenus ?: run {
            val buttons = container.children.filterIsInstance<MenuButton>()
            require(buttons.size == 2) { "Editorのファイル・編集メニューが必要です。" }
            val cached = EditorMenus(buttons[0].items.toList(), buttons[1].items.toList())
            buttons.forEach { it.items.clear() }
            container.children.removeAll(buttons)
            container.properties[editorMenusKey] = cached
            cached
        }
        val file = menuBar.menus[0]
        val edit = menuBar.menus[1]
        file.disableProperty().unbind()
        edit.disableProperty().unbind()
        file.items.setAll(menus.file + SeparatorMenuItem() + windowActions)
        edit.items.setAll(menus.edit)
        file.setOnShowing { refreshActions() }
        edit.setOnShowing { refreshActions() }
        file.disableProperty().bind(container.disableProperty())
        edit.disableProperty().bind(container.disableProperty())
    }

    internal fun useWorkspaceActions() { windowActions.first().text = "この機能を閉じる" }

    internal fun useDefaultMenus() {
        val file = menuBar.menus[0]
        val edit = menuBar.menus[1]
        file.disableProperty().unbind()
        edit.disableProperty().unbind()
        file.isDisable = false
        edit.isDisable = true
        file.setOnShowing(null)
        edit.setOnShowing(null)
        file.items.setAll(windowActions)
        edit.items.clear()
    }

    private fun hasSession() = EditorSession.dataService != null || EditorSession.connection != null || EditorSession.sshManager.isConnected

    private fun currentStatus(): ConnectionStatus = ConnectionStatus.from(
        EditorSession.mode,
        EditorSession.connection?.contextName ?: EditorSession.sshManager.currentProfile?.name,
        EditorSession.sshManager.isConnected, EditorSession.connection != null,
        EditorSession.managementClient.state, EditorSession.serverMonitor.snapshot.server?.ticksPerSecond,
        EditorSession.connection?.compatibility ?: EditorSession.sshManager.compatibility
    )

    private fun updateStatus() {
        val snapshot = currentStatus()
        status.text = snapshot.summary
        EditorSession.sshManager.currentProfile?.let { ServerStatusHistory.record(it, snapshot) }
        back.text = if (screen == AppScreen.SERVER_SELECT) "動作モード選択へ戻る" else "ホームへ戻る"
        back.isVisible = screen in homeChildren || screen == AppScreen.SERVER_SELECT
        back.isManaged = back.isVisible
        disconnect.isDisable = preparing || !hasSession()
        mode.isDisable = preparing || screen == AppScreen.MODE_SELECT
    }

    private fun showDetails() {
        val compatibility = EditorSession.connection?.compatibility ?: EditorSession.sshManager.compatibility
        val failure = (EditorSession.managementClient.state as? ServerManagementState.Failed)?.reason
        val detail = listOfNotNull(currentStatus().summary, "Protocol: 未取得", "Schema: 未取得", compatibility.message, failure)
        CustomDialog.information().owner(stage).title("接続の詳細").header(currentStatus().serverName)
            .content(detail.joinToString("\n"))
            .show()
    }

    private fun disconnect() {
        if (!hasSession()) return
        if (EditorSession.mode == AppMode.OFFLINE || EditorSession.connection != null) Utility.navigateToModeSelect()
        else Utility.navigateToServerSelect()
    }

    private fun goBack() {
        WorkspaceManager.current?.takeIf { it.frame === this }?.let {
            it.openHome()
            return
        }
        if (screen == AppScreen.SERVER_SELECT) { Utility.navigateToModeSelect(); return }
        if (screen !in homeChildren) return
        val home = Stage.getWindows().filterIsInstance<Stage>().firstOrNull {
            (it.scene?.root as? AppFrame)?.screen == AppScreen.HOME
        } ?: return
        home.isIconified = false
        home.toFront()
        closeWindow()
    }

    private fun closeWindow() {
        WorkspaceManager.current?.takeIf { it.frame === this }?.let { it.closeActive(); return }
        stage?.let {
            val request = WindowEvent(it, WindowEvent.WINDOW_CLOSE_REQUEST)
            it.fireEvent(request)
            if (!request.isConsumed) it.close()
        }
    }

    companion object {
        private val isMac = System.getProperty("os.name").startsWith("Mac")
        private val homeChildren = setOf(AppScreen.BASE, AppScreen.CONSOLE, AppScreen.DASHBOARD, AppScreen.MONEY_HISTORY, AppScreen.SERVER_CONTROL, AppScreen.MOD_CONFIG)
        fun includes(screen: AppScreen) = screen != AppScreen.WIDGETS_ONLY
    }
}
