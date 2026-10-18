package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.editor.offline.OfflineManifestRepository
import io.github.sushiericworkspace.sushiericservermanager.editor.offline.ManifestReadResult
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError

import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.editor.store.LocalEditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.UpdateDialog
import io.github.sushiericworkspace.sushiericservermanager.update.UpdateChecker
import io.github.sushiericworkspace.sushiericservermanager.update.UpdateCheckResult
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.application.Platform
import javafx.fxml.FXMLLoader
import javafx.geometry.Pos
import javafx.scene.Parent
import javafx.scene.control.Label
import javafx.scene.control.Button
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.DialogPane
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorWindowManager
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread

object ApplicationFlow {
    private val logger = LoggerFactory.getLogger(javaClass)
    /**
     * 更新が見つかったときに表示する画面です。ダイアログが閉じられるまで戻りません。
     * [UpdateDialog.Outcome.EXIT]を返した場合は、Updaterを起動済みのため、アプリを終了します。
     * JavaFX Application Threadから呼び出されます。
     */
    var showUpdate: (UpdateCheckResult.Update) -> UpdateDialog.Outcome = { UpdateDialog.Outcome.CONTINUE }

    /** 起動画面の生成経路。開発launcherは自身の画面を設定します。 */
    var modeSelection: (Stage) -> Unit = ::showDefaultModeSelection
    /** 正式更新を利用する入口だけで有効にします。 */
    var updatesEnabled = true

    fun showModeSelection(stage: Stage = Stage()) = modeSelection(stage)

    private fun showDefaultModeSelection(stage: Stage) {
        val loader = FXMLLoader(AppScreen::class.java.getResource(AppScreen.MODE_SELECT.fxml!!))
        val root = loader.load<Parent>()
        loader.getController<ModeSelectionController>().configure { mode ->
            prepareMode(stage, mode)
        }

        stage.title = appWindowTitle("動作モード選択")
        stage.scene = Utility.createScene(AppScreen.MODE_SELECT, customRoot = root)
        stage.show()
    }

    fun prepareMode(stage: Stage, mode: AppMode) {
        showPreparing(stage, when {
            mode == AppMode.OFFLINE -> "オフラインデータを確認しています..."
            updatesEnabled -> "アップデートを確認しています..."
            else -> "接続画面を準備しています..."
        })
        thread(isDaemon = true, name = "startup-${mode.name.lowercase()}") {
            when (mode) {
                AppMode.ONLINE -> prepareOnline(stage)
                AppMode.OFFLINE -> prepareOffline(stage)
            }
        }
    }

    private fun prepareOnline(stage: Stage) {
        val coordinator = StartupCoordinator { if (updatesEnabled) UpdateChecker().check() else UpdateCheckResult.UpToDate }
        val result = coordinator.prepare(AppMode.ONLINE)

        Platform.runLater {
            when (result) {
                StartupPreparationResult.Ready -> Unit
                is StartupPreparationResult.UpdateFound ->
                    if (showUpdate(result.update) == UpdateDialog.Outcome.EXIT) {
                        Platform.exit()
                        return@runLater
                    }
                // 確認できない場合（インターネット未接続など）は、更新せずにそのまま起動する。
                is StartupPreparationResult.Failure ->
                    logger.warn("アップデートを確認できませんでした。そのまま起動します。", result.cause)
            }
            EditorSession.prepareOnlineMode()
            stage.close()
            Utility.navigateToServerSelect()
        }
    }

    /** 終了要求を受理してから完了または失敗するまでtrue。JavaFXスレッドで参照します。 */
    var closingConnection = false
        private set

    /**
     * JavaFXスレッドで終了要求を受け付け、操作完了・全画面退避・接続終了を順に表示します。
     * 完了通知はJavaFXスレッドで行います。失敗時は画面とsessionを保持し、通知せず再操作を可能にします。
     * 実行中の重複要求は無視します。implicitExitは失敗・完了の両方で呼び出し前の値へ戻します。
     */
    fun finishConnection(completed: () -> Unit) {
        if (closingConnection) return
        closingConnection = true
        val windows = Stage.getWindows().filterIsInstance<Stage>().toList()
        val roots = windows.map { it.scene.root }.filterNot { it is DialogPane }.associateWith { it.isDisable }
        val implicitExit = Platform.isImplicitExit()
        Platform.setImplicitExit(false)
        roots.keys.forEach { it.isDisable = true }
        val label = Label("処理の完了を待っています").apply { id = "connectionCloseStatus"; isWrapText = true; maxWidth = 420.0 }
        val indicator = ProgressIndicator().apply { setPrefSize(36.0, 36.0) }
        val back = Button("戻る").apply { isVisible = false; isManaged = false }
        val progress = Stage().apply {
            title = appWindowTitle("終了処理")
            scene = Utility.createScene(AppScreen.WIDGETS_ONLY, 460.0, 180.0,
                VBox(12.0, indicator, label, back).apply { alignment = Pos.CENTER })
            setOnCloseRequest { it.consume() }
            show()
        }
        fun fail(message: String) {
            EditorWindowManager.cancelCloseAll()
            roots.forEach { (root, disabled) -> root.isDisable = disabled }
            closingConnection = false
            indicator.isVisible = false
            label.text = message
            back.isVisible = true
            back.isManaged = true
            back.setOnAction { progress.close() }
            progress.setOnCloseRequest(null)
            Platform.setImplicitExit(implicitExit)
        }
        EditorWindowManager.prepareCloseAll({ label.text = it }) { backedUp ->
            if (!backedUp) {
                fail("退避に失敗しました。編集内容を保持しています。終了操作で再試行してください。")
                return@prepareCloseAll
            }
            label.text = "接続を終了中"
            thread(isDaemon = true, name = "editor-disconnect") {
                try {
                    EditorSession.resetMode()
                    Platform.runLater {
                        try {
                            EditorWindowManager.completeCloseAll()
                            roots.forEach { (root, disabled) -> root.isDisable = disabled }
                            windows.forEach { it.close() }
                            completed()
                        } finally {
                            progress.close()
                            closingConnection = false
                            Platform.setImplicitExit(implicitExit)
                        }
                    }
                } catch (error: Exception) {
                    logger.error("接続の終了を確認できませんでした。接続と編集内容を保全します。", error)
                    Platform.runLater {
                        fail("接続の終了が未確認です。編集内容と接続データを保全しました。別の接続へ切り替えません。")
                    }
                }
            }
        }
    }

    private fun prepareOffline(stage: Stage) {
        val root = FilePath.OFFLINE_DIR.toFile()
        val store = LocalEditorDataStore(root)
        val service = EditorDataService(store)
        val result = try {
            when (val manifest = OfflineManifestRepository(root).read()) {
                is ManifestReadResult.FutureVersion ->
                    error("未対応の保存形式です。元データは変更していません。")
                is ManifestReadResult.Invalid ->
                    throw IllegalStateException("保存形式を確認できません。元データは変更していません。", manifest.cause)
                else -> Unit
            }
            service.prepareLocalDocuments()
        } catch (error: Exception) {
            StoreResult.Failure(
                StoreError(
                    StoreErrorCode.IO_ERROR, cause = error))
        }
        Platform.runLater {
            when (result) {
                is StoreResult.Success -> {
                    EditorSession.startOfflineSession(store, service)
                    stage.close()
                    Utility.navigateToHome("オフライン")
                }
                is StoreResult.Failure -> {
                    service.closeIo()
                    logger.error("ローカル文書を準備できませんでした。元データは保持しています。", result.error.cause)
                    CustomDialog.error().title("オフラインデータエラー")
                        .header("オフラインデータを開けませんでした")
                        .content("元のオフラインデータと退避を保持しています。保存形式と保存先を確認してください。")
                        .show()
                    showModeSelection(stage)
                }
            }
        }
    }

    fun showPreparing(stage: Stage, message: String) {
        stage.scene = Utility.createScene(
            AppScreen.STARTUP_PREPARING,
            width = 420.0,
            height = 140.0,
            customRoot = VBox(Label(message)).apply {
                alignment = Pos.CENTER
                styleClass.add("startup-progress")
            }
        )
        stage.title = appWindowTitle("起動準備中")
    }
}
