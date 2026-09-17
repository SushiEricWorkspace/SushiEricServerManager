package io.github.sushiericworkspace.sushiericservermanager.serverselect.controller

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.communication.HostKeyApprovalHandler
import io.github.sushiericworkspace.sushiericservermanager.communication.SshFailure
import io.github.sushiericworkspace.sushiericservermanager.communication.SshFailureCode
import io.github.sushiericworkspace.sushiericservermanager.communication.SshResult
import io.github.sushiericworkspace.sushiericservermanager.config.ServerConfig
import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfile
import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfileDropPosition
import io.github.sushiericworkspace.sushiericservermanager.config.SettingConfigManager
import io.github.sushiericworkspace.sushiericservermanager.config.reorderServerProfiles
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.ui.AppTooltip
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.SshFailureDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.SshHostKeyDialog
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.application.Platform
import javafx.concurrent.Task
import javafx.css.PseudoClass
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.fxml.Initializable
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.OverrunStyle
import javafx.scene.input.ClipboardContent
import javafx.scene.input.TransferMode
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Modality
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import java.net.URL
import java.util.ResourceBundle

class ServerSelector : Initializable {
    private companion object {
        val DRAGGING_PSEUDO_CLASS: PseudoClass = PseudoClass.getPseudoClass("dragging")
        val DROP_BEFORE_PSEUDO_CLASS: PseudoClass = PseudoClass.getPseudoClass("drop-before")
        val DROP_AFTER_PSEUDO_CLASS: PseudoClass = PseudoClass.getPseudoClass("drop-after")
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    @FXML private lateinit var serverListContainer: VBox
    @FXML private lateinit var mainContent: VBox
    @FXML private lateinit var progressOverlay: VBox
    @FXML private lateinit var progressLabel: Label

    private var draggedProfileName: String? = null
    private var draggedRow: BorderPane? = null
    private var dropTargetRow: BorderPane? = null

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        /*
         * 一覧を最初に表示するここでだけ、移行で取り残された
         * 生成鍵のパスを修復する。以降の読み込みは通常のloadで足りる。
         */
        val config: ServerConfig = SettingConfigManager.loadRepaired()
        refreshServerList(config.list)

        // ボタンが潰れるほどウィンドウを縮小できないようにする。
        Platform.runLater {
            val stage = serverListContainer.scene?.window as? Stage ?: return@runLater
            stage.minWidth = 660.0
            stage.minHeight = 420.0
        }
    }

    private fun refreshServerList(profileList: List<ServerProfile>) {
        clearDragState()
        serverListContainer.children.clear()
        serverListContainer.spacing = 12.0

        if (profileList.isEmpty()) {
            serverListContainer.children += VBox(6.0).apply {
                styleClass.add("server-empty")
                alignment = Pos.CENTER
                children.addAll(
                    Label("登録済みのサーバーはありません").apply {
                        styleClass.add("server-empty-title")
                    },
                    Label("右上の「サーバーを追加」から接続先を登録してください").apply {
                        styleClass.add("server-empty-description")
                    }
                )
            }
            return
        }

        profileList.forEach { profile ->
            serverListContainer.children += createServerRow(profile)
        }
    }

    private fun createServerRow(profile: ServerProfile): BorderPane {
        val detailText = buildString {
            append(profile.user)
            append('@')
            append(profile.host)
            append(':')
            append(profile.port)
            append("  •  ")
            append(profile.resolvedRemoteOperatingSystem().displayName)
        }

        val nameLabel = Label(profile.name).apply {
            styleClass.add("server-name")
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
            textOverrun = OverrunStyle.ELLIPSIS
            tooltip = AppTooltip.create(profile.name)
        }

        val detailLabel = Label(detailText).apply {
            styleClass.add("server-detail")
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
            textOverrun = OverrunStyle.ELLIPSIS
            tooltip = AppTooltip.create(detailText)
        }

        val infoBox = VBox(3.0, nameLabel, detailLabel).apply {
            styleClass.add("server-info")
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
        }

        val dragHandle = Label("⋮").apply {
            styleClass.add("server-drag-handle")
            tooltip = AppTooltip.create("ドラッグして並び替え")
        }

        val dragArea = HBox(10.0, dragHandle, infoBox).apply {
            styleClass.add("server-drag-area")
            alignment = Pos.CENTER_LEFT
            minWidth = 0.0
            maxWidth = Double.MAX_VALUE
            HBox.setHgrow(infoBox, Priority.ALWAYS)
        }

        val connectButton = createActionButton("接続", "btn-primary") {
            handleServerSelection(profile)
        }
        val editButton = createActionButton("編集", "btn-secondary") {
            handleEditServer(profile)
        }
        val deleteButton = createActionButton("削除", "btn-danger") {
            handleDeleteServer(profile)
        }

        val actionBox = HBox(8.0, connectButton, editButton, deleteButton).apply {
            styleClass.add("server-actions")
            alignment = Pos.CENTER_RIGHT
        }

        return BorderPane().apply {
            styleClass.add("server-row")
            maxWidth = Double.MAX_VALUE
            center = dragArea
            right = actionBox
            BorderPane.setMargin(dragArea, Insets(0.0, 16.0, 0.0, 0.0))
            configureDragAndDrop(this, dragArea, profile)
        }
    }

    private fun configureDragAndDrop(
        row: BorderPane,
        dragArea: Node,
        profile: ServerProfile
    ) {
        dragArea.setOnDragDetected { event ->
            val dragboard = row.startDragAndDrop(TransferMode.MOVE)
            dragboard.setContent(
                ClipboardContent().apply {
                    putString(profile.name)
                }
            )

            draggedProfileName = profile.name
            draggedRow = row
            row.pseudoClassStateChanged(DRAGGING_PSEUDO_CLASS, true)
            event.consume()
        }

        row.setOnDragOver { event ->
            val sourceName = draggedProfileName
            if (sourceName != null && sourceName != profile.name) {
                event.acceptTransferModes(TransferMode.MOVE)
                updateDropIndicator(
                    row,
                    if (event.y < row.height / 2.0) {
                        ServerProfileDropPosition.BEFORE
                    } else {
                        ServerProfileDropPosition.AFTER
                    }
                )
            }
            event.consume()
        }

        row.setOnDragExited { event ->
            if (dropTargetRow === row) {
                clearDropIndicator()
            }
            event.consume()
        }

        row.setOnDragDropped { event ->
            val sourceName = draggedProfileName
            val position =
                if (event.y < row.height / 2.0) {
                    ServerProfileDropPosition.BEFORE
                } else {
                    ServerProfileDropPosition.AFTER
                }
            val saved =
                if (sourceName == null || sourceName == profile.name) {
                    false
                } else {
                    saveServerOrder(sourceName, profile.name, position)
                }

            event.isDropCompleted = saved
            clearDragState()
            event.consume()
        }

        row.setOnDragDone { event ->
            clearDragState()
            event.consume()
        }
    }

    private fun updateDropIndicator(
        row: BorderPane,
        position: ServerProfileDropPosition
    ) {
        if (dropTargetRow !== row) {
            clearDropIndicator()
            dropTargetRow = row
        }

        row.pseudoClassStateChanged(
            DROP_BEFORE_PSEUDO_CLASS,
            position == ServerProfileDropPosition.BEFORE
        )
        row.pseudoClassStateChanged(
            DROP_AFTER_PSEUDO_CLASS,
            position == ServerProfileDropPosition.AFTER
        )
    }

    private fun clearDropIndicator() {
        dropTargetRow?.pseudoClassStateChanged(DROP_BEFORE_PSEUDO_CLASS, false)
        dropTargetRow?.pseudoClassStateChanged(DROP_AFTER_PSEUDO_CLASS, false)
        dropTargetRow = null
    }

    private fun clearDragState() {
        clearDropIndicator()
        draggedRow?.pseudoClassStateChanged(DRAGGING_PSEUDO_CLASS, false)
        draggedRow = null
        draggedProfileName = null
    }

    private fun saveServerOrder(
        sourceName: String,
        targetName: String,
        position: ServerProfileDropPosition
    ): Boolean {
        val currentConfig = SettingConfigManager.load()
        val reordered = reorderServerProfiles(
            profiles = currentConfig.list,
            sourceName = sourceName,
            targetName = targetName,
            position = position
        )
        if (reordered == currentConfig.list) {
            return false
        }

        if (!SettingConfigManager.saveAndVerify(currentConfig.copy(list = reordered))) {
            SshFailureDialog.show(
                SshFailure(SshFailureCode.PROFILE_SAVE_FAILED),
                serverListContainer.scene?.window as? Stage
            )
            return false
        }

        refreshServerList(reordered)
        logger.info(
            "サーバープロファイルの表示順を更新しました: source={}, target={}, position={}",
            sourceName,
            targetName,
            position
        )
        return true
    }

    private fun createActionButton(
        text: String,
        colorStyleClass: String,
        action: () -> Unit
    ): Button {
        return Button(text).apply {
            styleClass.addAll("button", colorStyleClass, "server-action-button")
            isFocusTraversable = false
            setOnAction { action() }
        }
    }

    private fun <T> showScreen(
        screen: AppScreen,
        title: String,
        modality: Modality = Modality.NONE,
        initController: ((T) -> Unit)? = null
    ): Stage {
        val loader = FXMLLoader(AppScreen::class.java.getResource(screen.fxml!!))
        val root = loader.load<Parent>()
        initController?.invoke(loader.getController<T>())
        val scene = Utility.createScene(screen, customRoot = root)

        return Stage().apply {
            this.title = title
            this.scene = scene
            initModality(modality)
            if (modality == Modality.APPLICATION_MODAL) showAndWait() else show()
        }
    }

    private fun handleServerSelection(profile: ServerProfile) {
        setBusy(true, "${profile.name} へSSH接続しています...")
        val owner = serverListContainer.scene?.window as? Stage
        val approval = HostKeyApprovalHandler { prompt -> SshHostKeyDialog.confirm(prompt, owner) }

        val task = object : Task<SshResult<Unit>>() {
            override fun call(): SshResult<Unit> {
                return EditorSession.sshManager.connect(profile, approval)
            }
        }

        task.setOnSucceeded {
            setBusy(false, "")
            val result = task.value
            if (result == null) {
                SshFailureDialog.show(SshFailure(SshFailureCode.UNEXPECTED), owner)
            } else {
                when (result) {
                    is SshResult.Success -> openHome(profile)
                    is SshResult.Failure -> SshFailureDialog.show(result.failure, owner)
                }
            }
        }
        task.setOnFailed {
            setBusy(false, "")
            SshFailureDialog.show(
                SshFailure(SshFailureCode.UNEXPECTED, task.exception?.let { it::class.simpleName }),
                owner
            )
        }

        Thread(task, "ssh-normal-connect").apply {
            isDaemon = true
            start()
        }
    }

    private fun openHome(profile: ServerProfile) {
        if (!EditorSession.sshManager.isConnected || EditorSession.sshManager.currentProfile != profile) {
            EditorSession.disconnect()
            CustomDialog.error()
                .title("接続初期化エラー")
                .header("接続済みセッションを確認できませんでした")
                .content("SSH接続を切断しました。")
                .show()
            return
        }

        // HomeControllerは既存実装をそのまま維持する。
        // 接続後に必要な共有サービスだけをServerSelector側で初期化する。
        EditorSession.startOnlineSession()

        val loader = FXMLLoader(AppScreen::class.java.getResource(AppScreen.HOME.fxml!!))
        val root = loader.load<Parent>()

        Stage().apply {
            title = "SushiEricServerManager - ${profile.name}"
            scene = Utility.createScene(AppScreen.HOME, customRoot = root)
            show()
        }
        (serverListContainer.scene?.window as? Stage)?.close()
    }

    @FXML
    fun openCreateServerWindow() {
        showScreen<Any>(AppScreen.SERVER_CREATE, "サーバーの追加", Modality.APPLICATION_MODAL)
        refreshServerList(SettingConfigManager.load().list)
    }

    private fun handleEditServer(profile: ServerProfile) {
        showScreen<EditController>(AppScreen.SERVER_EDIT, "サーバーの編集", Modality.APPLICATION_MODAL) {
            it.initData(profile)
        }
        refreshServerList(SettingConfigManager.load().list)
    }

    private fun handleDeleteServer(profile: ServerProfile) {
        val confirmed = CustomDialog.confirmation()
            .owner(serverListContainer.scene?.window as? Stage)
            .title("サーバーの削除")
            .header("サーバー '${profile.name}' を削除しますか？")
            .content("接続設定だけを削除します。生成済み秘密鍵は自動削除しません。")
            .okButton("削除", Color.RED)
            .show()
        if (!confirmed) return

        val currentConfig = SettingConfigManager.load()
        val updatedList = currentConfig.list.filter { it.name != profile.name }
        val saved = SettingConfigManager.saveAndVerify(currentConfig.copy(list = updatedList))
        if (!saved) {
            SshFailureDialog.show(
                SshFailure(SshFailureCode.PROFILE_SAVE_FAILED),
                serverListContainer.scene?.window as? Stage
            )
            return
        }
        refreshServerList(updatedList)
        logger.info("サーバープロファイルを削除しました: profile={}", profile.name)
    }

    private fun setBusy(busy: Boolean, message: String) {
        mainContent.isDisable = busy
        progressOverlay.isManaged = busy
        progressOverlay.isVisible = busy
        if (message.isNotBlank()) progressLabel.text = message
    }
}
