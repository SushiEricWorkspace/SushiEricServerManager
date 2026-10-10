package io.github.sushiericworkspace.sushiericservermanager.editor.controller

import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.app.AppMode
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementState
import io.github.sushiericworkspace.sushiericservermanager.monitor.ServerMonitorSnapshot
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.ErrorType
import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfile
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.ItemEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.ore.OreEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorView
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorWindowManager
import io.github.sushiericworkspace.sushiericservermanager.feature.console.ConsoleWindowManager
import io.github.sushiericworkspace.sushiericservermanager.feature.dashboard.DashboardWindowManager
import io.github.sushiericworkspace.sushiericservermanager.feature.modconfig.ModConfigWindowManager
import io.github.sushiericworkspace.sushiericservermanager.feature.servercontrol.ServerControlWindowManager
import io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory.MoneyHistoryWindowManager
import io.github.sushiericworkspace.sushiericservermanager.editor.main.shop.ShopEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe.RecipeEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import io.github.sushiericworkspace.sushiericservermanager.update.UpdateChecker
import io.github.sushiericworkspace.sushiericservermanager.update.UpdateCheckResult
import io.github.sushiericworkspace.sushiericservermanager.update.isNewerVersion
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.UpdateDialog
import javafx.concurrent.Task
import javafx.application.Platform
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.fxml.FXML
import javafx.fxml.FXMLLoader
import javafx.fxml.Initializable
import javafx.scene.layout.GridPane
import javafx.scene.layout.VBox
import org.slf4j.LoggerFactory
import java.net.URL
import java.util.ResourceBundle

/**
 * サーバー接続後のメイン操作画面（ホーム画面）を管理するコントローラー。
 * ファイルリストの表示、SSHセッションの維持、および切断処理を担当します。
 */
class HomeController : Initializable {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** FXMLの一番外側の要素 */
    @FXML
    private lateinit var rootPane: VBox
    @FXML private lateinit var modeLabel: Label
    @FXML private lateinit var compatibilityLabel: Label
    @FXML private lateinit var updateButton: Button
    @FXML private lateinit var managementLabel: Label
    @FXML private lateinit var monitorLabel: Label
    @FXML private lateinit var consoleButton: Button
    @FXML private lateinit var historyButton: Button
    @FXML private lateinit var dashboardButton: Button
    @FXML private lateinit var serverControlButton: Button
    @FXML private lateinit var modeSelectButton: Button
    @FXML private lateinit var backButton: Button
    @FXML private lateinit var modConfigButton: Button
    @FXML private lateinit var onlineToolsPane: VBox
    @FXML private lateinit var homeSections: GridPane

    private val sshManager = EditorSession.sshManager

    /**
     * Management APIの接続状態を表示へ反映します。
     *
     * オフラインではManagement APIを使用しないため表示しません。
     */
    private fun applyManagementState(state: ServerManagementState) {
        if (EditorSession.mode != AppMode.ONLINE) {
            managementLabel.isManaged = false
            managementLabel.isVisible = false
            return
        }

        managementLabel.isManaged = true
        managementLabel.isVisible = true

        managementLabel.text = when (state) {
            is ServerManagementState.Disconnected ->
                "Management API：未接続"

            is ServerManagementState.Connecting ->
                "Management API：接続中..."

            is ServerManagementState.Connected ->
                "Management API：接続済み"

            is ServerManagementState.Failed ->
                "Management API：未接続（${state.reason}）"
        }
        applyConnectionStyle(managementLabel, state is ServerManagementState.Connected)
    }

    /** 接続状態に応じた表示色をラベルへ適用します。 */
    private fun applyConnectionStyle(label: Label, connected: Boolean) {
        label.styleClass.removeAll(CONNECTED_STYLE_CLASS, DISCONNECTED_STYLE_CLASS)
        label.styleClass.add(if (connected) CONNECTED_STYLE_CLASS else DISCONNECTED_STYLE_CLASS)
    }

    /**
     * 監視情報を表示へ反映します。
     *
     * 値を受信していない場合は空表示にし、取得元と状態が分かるようにします。
     * CPU使用率などOSレベルの情報は取得元が異なるため、ここでは扱いません。
     */
    private fun applyMonitorSnapshot(snapshot: ServerMonitorSnapshot) {
        if (EditorSession.mode != AppMode.ONLINE) {
            monitorLabel.isManaged = false
            monitorLabel.isVisible = false
            return
        }

        monitorLabel.isManaged = true
        monitorLabel.isVisible = true

        val server = snapshot.server
        val jvm = snapshot.jvm

        if (server == null || jvm == null) {
            monitorLabel.text = "サーバー監視：値なし"
            return
        }

        monitorLabel.text = buildString {
            append("TPS ")
            append(String.format("%.2f", server.ticksPerSecond))
            append(" / MSPT ")
            append(String.format("%.2f", server.millisPerTick))
            append(System.lineSeparator())

            append("プレイヤー ")
            append(server.onlinePlayerCount)
            append(" / ")
            append(server.maxPlayerCount)
            append("　稼働 ")
            append(formatUptime(server.uptimeSeconds))
            append(System.lineSeparator())

            append("Heap ")
            append(formatMegabytes(jvm.heapUsedBytes))
            append(" / ")
            append(jvm.heapMaxBytes?.let(::formatMegabytes) ?: "不明")
            append("　Thread ")
            append(jvm.threadCount)
        }
    }

    /** 稼働時間を時分秒へ整形します。 */
    private fun formatUptime(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val remaining = seconds % 60

        return "%d:%02d:%02d".format(hours, minutes, remaining)
    }

    /** バイト数をMiB表記へ整形します。 */
    private fun formatMegabytes(bytes: Long): String =
        "%,d MiB".format(bytes / (1024 * 1024))

    /** 現在接続中のサーバープロファイル */
    private var selectedProfile: ServerProfile? = null

    /**
     * 画面が表示された後、あるいは適切なタイミングで呼び出します。
     * 親ウィンドウの「閉じる」イベントを監視します。
     */
    override fun initialize(location: URL?, resources: ResourceBundle?) {
        val mode = EditorSession.mode
        modeLabel.text = when (mode) {
            AppMode.ONLINE -> "オンライン：${EditorSession.sshManager.currentProfile?.name.orEmpty()}"
            AppMode.OFFLINE -> "オフライン"
            null -> "モード未選択"
        }
        applyConnectionStyle(modeLabel, mode == AppMode.ONLINE)
        applyManagementState(EditorSession.managementClient.state)

        /*
         * 接続は非同期で進むため、状態が変わるたびに表示を更新する。
         * 通知はUIスレッド以外から届くのでrunLaterで移す。
         */
        EditorSession.managementClient.addStateListener { state ->
            Platform.runLater { applyManagementState(state) }
        }

        applyMonitorSnapshot(EditorSession.serverMonitor.snapshot)

        EditorSession.serverMonitor.addListener { snapshot ->
            Platform.runLater { applyMonitorSnapshot(snapshot) }
        }

        val online = mode == AppMode.ONLINE
        compatibilityLabel.text = sshManager.compatibility.message
        compatibilityLabel.isVisible = online
        compatibilityLabel.isManaged = online
        applyConnectionStyle(compatibilityLabel, sshManager.compatibility.writable)
        val updateRequired = online && sshManager.compatibility is ManagerCompatibility.UpdateRequired
        updateButton.isVisible = updateRequired
        updateButton.isManaged = updateRequired
        consoleButton.isManaged = online
        consoleButton.isVisible = online
        historyButton.isManaged = online
        historyButton.isVisible = online
        dashboardButton.isManaged = online
        dashboardButton.isVisible = online
        serverControlButton.isManaged = online
        serverControlButton.isVisible = online
        modConfigButton.isManaged = online
        modConfigButton.isVisible = online
        onlineToolsPane.isManaged = online
        onlineToolsPane.isVisible = online
        homeSections.columnConstraints[0].percentWidth = if (online) 50.0 else 100.0
        homeSections.columnConstraints[1].percentWidth = if (online) 50.0 else 0.0
        modeSelectButton.isManaged = online
        modeSelectButton.isVisible = online
        backButton.text = if (mode == AppMode.ONLINE) "サーバー選択へ戻る" else "モード選択へ戻る"

        // Platform.runLater を使って Stage が確実に生成された後に処理
        javafx.application.Platform.runLater {
            val stage = rootPane.scene?.window as? javafx.stage.Stage
            stage?.setOnCloseRequest {
                // 親が閉じられたら、エディタウィンドウをすべて閉じる
                EditorWindowManager.closeAll()
                ModConfigWindowManager.close()
                ConsoleWindowManager.close()
                DashboardWindowManager.close()
                ServerControlWindowManager.close()
                MoneyHistoryWindowManager.close()
                // SSH接続も忘れずに切断
                EditorSession.disconnect()
            }
        }
    }

    /**
     * 最小版を満たす更新だけを案内します。確認失敗でも読み取り専用状態を維持します。
     */
    @FXML
    private fun onRequiredUpdate() {
        val compatibility = sshManager.compatibility as? ManagerCompatibility.UpdateRequired ?: return
        updateButton.isDisable = true
        val task = object : Task<UpdateCheckResult>() {
            override fun call() = UpdateChecker().check()
        }
        task.setOnSucceeded {
            updateButton.isDisable = false
            val update = task.value as? UpdateCheckResult.Update
            if (update == null || isNewerVersion(compatibility.minimumVersion, update.version)) {
                CustomDialog.error().title("更新が必要です").header("必要な版の更新を取得できません")
                    .content("最低版 ${compatibility.minimumVersion} が必要です。公開されるまで読み取り専用で利用してください。").show()
            } else {
                val outcome = UpdateDialog.show(
                    update, openUrl = { java.awt.Desktop.getDesktop().browse(java.net.URI(it)) }, required = true
                )
                if (outcome == UpdateDialog.Outcome.EXIT) {
                    EditorSession.disconnect()
                    Platform.exit()
                }
            }
        }
        task.setOnFailed {
            updateButton.isDisable = false
            logger.warn("必須更新を確認できませんでした", task.exception)
            CustomDialog.error().title("更新が必要です").header("更新を確認できません")
                .content("最低版 ${compatibility.minimumVersion} が必要です。接続を確認して再試行してください。").show()
        }
        Thread(task, "required-update-check").apply { isDaemon = true; start() }
    }

    /** 指定プロファイルへ接続し、成功時にオンラインセッションを開始します。 */
    fun initData(profile: ServerProfile): Boolean {
        this.selectedProfile = profile

        val isConnected = sshManager.connect(profile)

        // 接続成功時にサービスをインスタンス化
        if (isConnected) {
            EditorSession.startOnlineSession()
        }

        return isConnected
    }

    /**
     * ユーザーに確認を求めた後、サーバーとの接続を終了してサーバー選択画面に戻ります。
     * FXML上の「切断」ボタンなどから呼び出されます。
     */
    @FXML
    @Suppress("unused")
    fun handleDisconnect() {
        val online = EditorSession.mode == AppMode.ONLINE
        val isConfirm = CustomDialog.confirmation()
            .header(if (online) "切断の確認" else "モード選択へ戻りますか？")
            .content(if (online) "サーバーとの接続を切り、選択画面に戻りますか？" else "保存済みのオフラインデータは維持されます。")
            .show()
        if (!isConfirm) return

        if (online) Utility.navigateToServerSelect() else Utility.navigateToModeSelect()
    }

    /**
     * 開いている編集内容をローカルへ退避し、接続を解放して動作モード選択画面へ戻ります。
     */
    @FXML
    @Suppress("unused")
    fun handleReturnToModeSelect() {
        if (EditorSession.mode != AppMode.ONLINE) return
        val confirmed = CustomDialog.confirmation()
            .header("モード選択へ戻りますか？")
            .content("サーバーとの接続を切り、開いているウィンドウを閉じます。\n未保存の編集内容はローカルへ退避されます。")
            .show()
        if (!confirmed) return

        Utility.navigateToModeSelect()
    }

    @FXML
    @Suppress("unused")
    fun onOpenItemEditor() {
        openManagedDataEditor(
            key = "ITEM_EDITOR",
            title = "アイテムエディタ",
            dataAccessProvider = { it.items },
            logicFactory = { mainController, service ->
                ItemEditorLogic(
                    main = mainController,
                    dataService = service
                )
            }
        )
    }

    /** ショップ商品を共通の管理データエディターで開きます。 */
    @FXML @Suppress("unused")
    fun onOpenShopEditor() {
        openManagedDataEditor(
            key = "SHOP_EDITOR",
            title = "ショップエディター",
            dataAccessProvider = { it.shops },
            logicFactory = { controller, service -> ShopEditorLogic(controller, service) }
        )
    }

    /** レシピの階層・材料・解放条件を共通エディターで編集します。 */
    @FXML @Suppress("unused")
    fun onOpenRecipeEditor() {
        openManagedDataEditor(
            key = "RECIPE_EDITOR",
            title = "レシピエディター",
            dataAccessProvider = { it.recipes },
            logicFactory = { controller, service -> RecipeEditorLogic(controller, service) }
        )
    }

    /** Minecraftコンソールを開きます。 */
    @FXML
    @Suppress("unused")
    fun onOpenConsole() {
        if (EditorSession.mode != AppMode.ONLINE) return
        ConsoleWindowManager.open(rootPane.scene?.window)
    }

    /** サーバー状態のDashboardを開きます。 */
    @FXML
    @Suppress("unused")
    fun onOpenDashboard() {
        if (EditorSession.mode != AppMode.ONLINE) return
        DashboardWindowManager.open(rootPane.scene?.window)
    }

    /** サーバープロセスを操作するServer Controlを開きます。 */
    @FXML
    @Suppress("unused")
    fun onOpenServerControl() {
        if (EditorSession.mode != AppMode.ONLINE) return
        ServerControlWindowManager.open(rootPane.scene?.window)
    }

    /** Mod共通設定（config.yml）の編集画面を開きます。 */
    @FXML
    @Suppress("unused")
    fun onOpenModConfig() {
        if (EditorSession.mode != AppMode.ONLINE) return
        if (EditorSession.mode == AppMode.ONLINE && !sshManager.isSftpActive) {
            CustomDialog.error(ErrorType.CONNECTION_FAILED).show()
            Utility.navigateToServerSelect()
            return
        }

        val service = EditorSession.dataService

        if (service == null) {
            logger.error("データサービスが見つかりません。")
            return
        }

        ModConfigWindowManager.open(rootPane.scene?.window, service)
    }

    /** 入出金と実績の履歴閲覧ビューを開きます。 */
    @FXML
    @Suppress("unused")
    fun onOpenMoneyHistory() {
        if (EditorSession.mode != AppMode.ONLINE) return
        val service = EditorSession.dataService ?: return
        MoneyHistoryWindowManager.open(rootPane.scene?.window, service.store)
    }

    private fun <T : ManagedData<T, *>, L : EditorView<T>> openManagedDataEditor(
        key: String,
        title: String,
        dataAccessProvider: (EditorDataService) -> EditorDataService.DataAccess<T>,
        logicFactory: (MainController, EditorDataService) -> L
    ) {
        if (EditorSession.mode == AppMode.ONLINE && !sshManager.isSftpActive) {
            CustomDialog.error(ErrorType.CONNECTION_FAILED).show()
            Utility.navigateToServerSelect()
            return
        }

        val service = EditorSession.dataService
        if (service == null) {
            logger.error("データサービスが見つかりません。")
            return
        }

        val dataAccess = dataAccessProvider(service)
        val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))

        EditorWindowManager.openEditor(
            key = key,
            title = title,
            loader = loader
        ) { mainController ->
            val logic = logicFactory(mainController, service)

            try {
                val profileName = service.cacheIdentity

                val dataDir = FilePath.AUTOSAVE_DIR.toFile()
                    .resolve(profileName)
                    .resolve(dataAccess.dataType.categoryDirName)

                val editingDir = dataDir.resolve("editing")

                if (editingDir.exists()) {
                    val editingCaches = mutableMapOf<String, T>()
                    val originalCaches = mutableMapOf<String, T>()

                    editingDir
                        .listFiles { file ->
                            file.isFile && file.extension.lowercase() == "yml"
                        }
                        ?.forEach { file ->
                            val id = file.nameWithoutExtension
                            val backupPair = dataAccess.loadBackupPair(id)

                            if (backupPair != null) {
                                val restoredId = backupPair.first.id
                                editingCaches[restoredId] = backupPair.first
                                originalCaches[restoredId] = backupPair.second.apply { this.id = restoredId }
                                if (restoredId != id) {
                                    dataAccess.saveToLocalBackup(restoredId, "editing", backupPair.first)
                                    dataAccess.saveToLocalBackup(restoredId, "original", backupPair.second)
                                    dataAccess.deleteLocalBackup(id)
                                    logger.info("旧形式の自動保存IDを移行しました: {} -> {}", id, restoredId)
                                }
                            }
                        }

                    logic.injectAutoSaveCaches(
                        editingCaches = editingCaches,
                        originalCaches = originalCaches
                    )
                }
            } catch (e: Exception) {
                logger.error("${dataAccess.displayName}エディタ起動時の自動保存スキャンに失敗しました", e)
            }

            logic
        }
    }

    @FXML
    @Suppress("unused")
    fun onOpenOreEditor() {
        openManagedDataEditor(
            key = "ORE_EDITOR",
            title = "鉱石エディタ",
            dataAccessProvider = { it.ores },
            logicFactory = { mainController, service ->
                OreEditorLogic(
                    main = mainController,
                    dataService = service
                )
            }
        )
    }

    private companion object {
        const val CONNECTED_STYLE_CLASS = "home-connection-connected"
        const val DISCONNECTED_STYLE_CLASS = "home-connection-disconnected"
    }
}
