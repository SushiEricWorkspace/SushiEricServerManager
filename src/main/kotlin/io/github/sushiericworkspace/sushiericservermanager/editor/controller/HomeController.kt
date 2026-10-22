package io.github.sushiericworkspace.sushiericservermanager.editor.controller

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.app.ApplicationFlow
import io.github.sushiericworkspace.sushiericservermanager.app.AppMode
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementState
import io.github.sushiericworkspace.sushiericservermanager.monitor.ServerMonitorSnapshot
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.ErrorType
import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfile
import io.github.sushiericworkspace.sushiericservermanager.config.WorkspaceFeature
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceManager
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.ItemEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.main.ore.OreEditorLogic
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorView
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorWindowManager
import io.github.sushiericworkspace.sushiericservermanager.feature.console.ConsoleWindowManager
import io.github.sushiericworkspace.sushiericservermanager.feature.dashboard.DashboardWindowManager
import io.github.sushiericworkspace.sushiericservermanager.feature.dashboard.DashboardFormat
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
import javafx.beans.value.ChangeListener
import javafx.scene.control.ProgressIndicator
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
 * 接続とサーバーの要約、機能の入口、および切断操作を担当します。
 */
class HomeController internal constructor(
    private val checkUpdate: () -> UpdateCheckResult,
    private val showUpdate: (UpdateCheckResult.Update) -> UpdateDialog.Outcome
) : Initializable {

    constructor() : this(
        { UpdateChecker().check() },
        { update -> UpdateDialog.show(
            update, openUrl = { java.awt.Desktop.getDesktop().browse(java.net.URI(it)) }, required = true
        ) }
    )

    private val logger = LoggerFactory.getLogger(javaClass)

    /** FXMLの一番外側の要素 */
    @FXML
    private lateinit var rootPane: VBox
    @FXML private lateinit var modeLabel: Label
    @FXML private lateinit var compatibilityLabel: Label
    @FXML private lateinit var updateButton: Button
    @FXML private lateinit var updateCheckLabel: Label
    @FXML private lateinit var updateCheckProgress: ProgressIndicator

    private var requiredUpdateTask: Task<UpdateCheckResult>? = null
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
     * 未受信は未取得と示し、詳細な指標はDashboardへ表示します。
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
        monitorLabel.text = if (server == null) "サーバー要約：未取得" else {
            "プレイヤー ${DashboardFormat.players(server.onlinePlayerCount, server.maxPlayerCount)}　" +
                "TPS ${DashboardFormat.ticksPerSecond(server.ticksPerSecond)}"
        }
    }

    /** 現在接続中のサーバープロファイル */
    private var selectedProfile: ServerProfile? = null

    /**
     * 画面が表示された後、あるいは適切なタイミングで呼び出します。
     * 親ウィンドウの「閉じる」イベントを監視します。
     */
    override fun initialize(location: URL?, resources: ResourceBundle?) {
        rootPane.sceneProperty().addListener { _, _, scene ->
            if (scene == null) requiredUpdateTask?.cancel()
        }
        val mode = EditorSession.mode
        modeLabel.text = when (mode) {
            AppMode.ONLINE -> EditorSession.connection?.contextName
                ?: "オンライン：${EditorSession.sshManager.currentProfile?.name.orEmpty()}"
            AppMode.OFFLINE -> "オフライン"
            null -> "モード未選択"
        }
        applyConnectionStyle(modeLabel, mode == AppMode.ONLINE)
        applyManagementState(EditorSession.managementClient.state)

        /*
         * 接続は非同期で進むため、状態が変わるたびに表示を更新する。
         * 通知はUIスレッド以外から届くのでrunLaterで移す。
         */
        setWorkspaceVisible(true)

        applyMonitorSnapshot(EditorSession.serverMonitor.snapshot)



        val online = mode == AppMode.ONLINE
        val compatibility = EditorSession.connection?.compatibility ?: sshManager.compatibility
        compatibilityLabel.text = compatibility.message
        compatibilityLabel.isVisible = online
        compatibilityLabel.isManaged = online
        applyConnectionStyle(compatibilityLabel, compatibility.writable)
        val updateRequired = online && ApplicationFlow.updatesEnabled && compatibility is ManagerCompatibility.UpdateRequired
        updateButton.isVisible = updateRequired
        updateButton.isManaged = updateRequired
        consoleButton.isManaged = online
        consoleButton.isVisible = online
        historyButton.isManaged = online
        historyButton.isVisible = online
        dashboardButton.isManaged = online
        dashboardButton.isVisible = online
        serverControlButton.isManaged = online && EditorSession.connection == null
        serverControlButton.isVisible = online && EditorSession.connection == null
        modConfigButton.isManaged = online
        modConfigButton.isVisible = online
        onlineToolsPane.isManaged = online
        onlineToolsPane.isVisible = online
        homeSections.columnConstraints[0].percentWidth = if (online) 50.0 else 100.0
        homeSections.columnConstraints[1].percentWidth = if (online) 50.0 else 0.0
        modeSelectButton.isManaged = online
        modeSelectButton.isVisible = online
        backButton.text = when {
            EditorSession.connection != null -> "切断して動作モード選択へ"
            online -> "切断してサーバー選択へ"
            else -> "動作モード選択へ戻る"
        }


    }

    /**
     * 最小版を満たす更新だけを案内します。確認失敗でも読み取り専用状態を維持します。
     */
    @FXML
    private fun onRequiredUpdate() {
        if (!ApplicationFlow.updatesEnabled || requiredUpdateTask != null || EditorSession.mode != AppMode.ONLINE) return
        val compatibility = (EditorSession.connection?.compatibility ?: sshManager.compatibility) as? ManagerCompatibility.UpdateRequired ?: return
        val window = rootPane.scene?.window ?: return
        if (!window.isShowing) return
        val connection = EditorSession.connection
        val client = sshManager.sshClient
        val service = EditorSession.dataService
        fun isCurrent(): Boolean = rootPane.scene?.window === window && window.isShowing &&
            ApplicationFlow.updatesEnabled && EditorSession.mode == AppMode.ONLINE &&
            EditorSession.sshManager === sshManager && EditorSession.connection === connection &&
            sshManager.sshClient === client && EditorSession.dataService === service &&
            (EditorSession.connection?.compatibility ?: sshManager.compatibility) === compatibility

        val task = object : Task<UpdateCheckResult>() {
            override fun call() = checkUpdate()
        }
        requiredUpdateTask = task
        updateButton.isDisable = true
        updateCheckLabel.text = "アップデートを確認中"
        updateCheckLabel.isVisible = true
        updateCheckLabel.isManaged = true
        updateCheckProgress.isVisible = true
        updateCheckProgress.isManaged = true
        val showingListener = ChangeListener<Boolean> { _, _, showing ->
            if (!showing) task.cancel()
        }
        window.showingProperty().addListener(showingListener)

        fun finish(message: String?) {
            requiredUpdateTask = null
            window.showingProperty().removeListener(showingListener)
            updateButton.isDisable = false
            updateCheckProgress.isVisible = false
            updateCheckProgress.isManaged = false
            updateCheckLabel.text = message.orEmpty()
            updateCheckLabel.isVisible = message != null
            updateCheckLabel.isManaged = message != null
        }
        task.setOnSucceeded {
            if (!isCurrent()) {
                finish(null)
                return@setOnSucceeded
            }
            val update = task.value as? UpdateCheckResult.Update
            if (update == null || isNewerVersion(compatibility.minimumVersion, update.version)) {
                finish("必要な版の更新を取得できません。")
                CustomDialog.error().title("更新が必要です").header("必要な版の更新を取得できません")
                    .content("最低版 ${compatibility.minimumVersion} が必要です。公開されるまで読み取り専用で利用してください。").show()
            } else {
                finish("アップデートの確認が完了しました。")
                val outcome = showUpdate(update)
                if (outcome == UpdateDialog.Outcome.EXIT && isCurrent()) {
                    Utility.closeConnection { Platform.exit() }
                }
            }
        }
        task.setOnFailed {
            logger.warn("必須更新を確認できませんでした", task.exception)
            if (!isCurrent()) {
                finish(null)
                return@setOnFailed
            }
            finish("アップデートを確認できません。再試行してください。")
            CustomDialog.error().title("更新が必要です").header("更新を確認できません")
                .content("最低版 ${compatibility.minimumVersion} が必要です。接続を確認して再試行してください。").show()
        }
        task.setOnCancelled { finish(null) }
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
            key = WorkspaceFeature.ITEM_EDITOR,
            title = "アイテムエディタ",
            logicFactory = { mainController, service ->
                ItemEditorLogic(
                    main = mainController,
                    service = service
                )
            }
        )
    }

    /** ショップ商品を共通の管理データエディターで開きます。 */
    @FXML @Suppress("unused")
    fun onOpenShopEditor() {
        openManagedDataEditor(
            key = WorkspaceFeature.SHOP_EDITOR,
            title = "ショップエディター",
            logicFactory = { controller, service -> ShopEditorLogic(controller, service) }
        )
    }

    /** レシピの階層・材料・解放条件を共通エディターで編集します。 */
    @FXML @Suppress("unused")
    fun onOpenRecipeEditor() {
        openManagedDataEditor(
            key = WorkspaceFeature.RECIPE_EDITOR,
            title = "レシピエディター",
            logicFactory = { controller, service -> RecipeEditorLogic(controller, service) }
        )
    }

    /** Minecraftコンソールを開きます。 */
    @FXML
    @Suppress("unused")
    fun onOpenConsole() {
        if (EditorSession.mode != AppMode.ONLINE) return
        ConsoleWindowManager.open()
    }

    /** サーバー状態のDashboardを開きます。 */
    @FXML
    @Suppress("unused")
    fun onOpenDashboard() {
        if (EditorSession.mode != AppMode.ONLINE) return
        DashboardWindowManager.open()
    }

    /** サーバープロセスを操作するServer Controlを開きます。 */
    @FXML
    @Suppress("unused")
    fun onOpenServerControl() {
        if (EditorSession.mode != AppMode.ONLINE) return
        ServerControlWindowManager.open()
    }

    /** Mod共通設定（config.yml）の編集画面を開きます。 */
    @FXML
    @Suppress("unused")
    fun onOpenModConfig() {
        if (EditorSession.mode != AppMode.ONLINE) return
        if (EditorSession.mode == AppMode.ONLINE && EditorSession.dataService?.store?.isAvailable != true) {
            CustomDialog.error(ErrorType.CONNECTION_FAILED).show()
            Utility.navigateToServerSelect()
            return
        }

        val service = EditorSession.dataService

        if (service == null) {
            logger.error("データサービスが見つかりません。")
            return
        }

        ModConfigWindowManager.open(service)
    }

    /** 入出金と実績の履歴閲覧ビューを開きます。 */
    @FXML
    @Suppress("unused")
    fun onOpenMoneyHistory() {
        if (EditorSession.mode != AppMode.ONLINE) return
        val service = EditorSession.dataService ?: return
        MoneyHistoryWindowManager.open(service.store)
    }

    private fun openManagedDataEditor(
        key: WorkspaceFeature,
        title: String,
        logicFactory: (MainController, EditorDataService) -> EditorView<*>
    ) {
        if (EditorSession.mode == AppMode.ONLINE && EditorSession.dataService?.store?.isAvailable != true) {
            CustomDialog.error(ErrorType.CONNECTION_FAILED).show()
            Utility.navigateToServerSelect()
            return
        }

        val service = EditorSession.dataService
        if (service == null) {
            logger.error("データサービスが見つかりません。")
            return
        }

        val loader = FXMLLoader(javaClass.getResource(AppScreen.BASE.fxml!!))

        EditorWindowManager.openEditor(
            key = key,
            title = title,
            loader = loader
        ) { mainController ->
            logicFactory(mainController, service)
        }
    }

    @FXML
    @Suppress("unused")
    fun onOpenOreEditor() {
        openManagedDataEditor(
            key = WorkspaceFeature.ORE_EDITOR,
            title = "鉱石エディタ",
            logicFactory = { mainController, service ->
                OreEditorLogic(
                    main = mainController,
                    service = service
                )
            }
        )
    }

    @Volatile private var workspaceListening = false
    @Volatile private var workspaceGeneration = 0L
    private var workspaceStateListener: ((ServerManagementState) -> Unit)? = null
    private var workspaceMonitorListener: ((ServerMonitorSnapshot) -> Unit)? = null

    internal fun setWorkspaceVisible(visible: Boolean) {
        if (workspaceListening == visible) return
        val generation = ++workspaceGeneration
        workspaceListening = visible
        if (visible) {
            val stateListener: (ServerManagementState) -> Unit = { state ->
                Platform.runLater { if (workspaceListening && generation == workspaceGeneration) applyManagementState(state) }
            }
            val monitorListener: (ServerMonitorSnapshot) -> Unit = { snapshot ->
                Platform.runLater { if (workspaceListening && generation == workspaceGeneration) applyMonitorSnapshot(snapshot) }
            }
            workspaceStateListener = stateListener
            workspaceMonitorListener = monitorListener
            EditorSession.managementClient.addStateListener(stateListener)
            EditorSession.serverMonitor.addListener(monitorListener)
            applyManagementState(EditorSession.managementClient.state)
            applyMonitorSnapshot(EditorSession.serverMonitor.snapshot)
        } else {
            workspaceStateListener?.let(EditorSession.managementClient::removeStateListener)
            workspaceMonitorListener?.let(EditorSession.serverMonitor::removeListener)
            workspaceStateListener = null
            workspaceMonitorListener = null
            requiredUpdateTask?.cancel()
        }
    }

    internal fun workspaceFeatureAvailable(feature: WorkspaceFeature): Boolean = when {
        feature == WorkspaceFeature.HOME -> true
        feature.editor -> EditorSession.dataService?.store?.isAvailable == true
        feature == WorkspaceFeature.SERVER_CONTROL -> EditorSession.mode == AppMode.ONLINE && EditorSession.connection == null
        else -> EditorSession.mode == AppMode.ONLINE
    }

    internal fun openWorkspaceFeature(feature: WorkspaceFeature) {
        if (!workspaceFeatureAvailable(feature)) return
        when (feature) {
            WorkspaceFeature.HOME -> WorkspaceManager.focus(feature)
            WorkspaceFeature.ITEM_EDITOR -> onOpenItemEditor()
            WorkspaceFeature.ORE_EDITOR -> onOpenOreEditor()
            WorkspaceFeature.SHOP_EDITOR -> onOpenShopEditor()
            WorkspaceFeature.RECIPE_EDITOR -> onOpenRecipeEditor()
            WorkspaceFeature.DASHBOARD -> onOpenDashboard()
            WorkspaceFeature.CONSOLE -> onOpenConsole()
            WorkspaceFeature.SERVER_CONTROL -> onOpenServerControl()
            WorkspaceFeature.MOD_CONFIG -> onOpenModConfig()
            WorkspaceFeature.MONEY_HISTORY -> onOpenMoneyHistory()
        }
    }

    private companion object {
        const val CONNECTED_STYLE_CLASS = "home-connection-connected"
        const val DISCONNECTED_STYLE_CLASS = "home-connection-disconnected"
    }
}
