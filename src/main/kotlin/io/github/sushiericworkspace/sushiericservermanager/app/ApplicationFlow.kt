package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.editor.offline.OfflineWorkspaceMigrator
import io.github.sushiericworkspace.sushiericservermanager.editor.offline.WorkspaceMigrationResult
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
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import kotlin.concurrent.thread
import io.github.sushiericworkspace.sushiericservermanager.communication.managed.ManagedProfile

object ApplicationFlow {
    private val logger = LoggerFactory.getLogger(javaClass)
    /**
     * 更新が見つかったときに表示する画面です。ダイアログが閉じられるまで戻りません。
     * [UpdateDialog.Outcome.EXIT]を返した場合は、Updaterを起動済みのため、アプリを終了します。
     * JavaFX Application Threadから呼び出されます。
     */
    var showUpdate: (UpdateCheckResult.Update) -> UpdateDialog.Outcome = { UpdateDialog.Outcome.CONTINUE }

    fun showModeSelection(stage: Stage = Stage()) {
        val loader = FXMLLoader(AppScreen::class.java.getResource(AppScreen.MODE_SELECT.fxml!!))
        val root = loader.load<Parent>()
        loader.getController<ModeSelectionController>().configure { mode ->
            prepareMode(stage, mode)
        }

        stage.title = appWindowTitle("動作モード選択")
        stage.scene = Utility.createScene(AppScreen.MODE_SELECT, customRoot = root)
        stage.isResizable = false
        stage.show()
    }

    private fun prepareMode(stage: Stage, mode: AppMode) {
        showPreparing(stage, if (mode == AppMode.ONLINE) "アップデートを確認しています..." else "オフラインデータを確認しています...")
        thread(isDaemon = true, name = "startup-${mode.name.lowercase()}") {
            when (mode) {
                AppMode.ONLINE -> prepareOnline(stage)
                AppMode.OFFLINE -> prepareOffline(stage)
            }
        }
    }

    private fun prepareOnline(stage: Stage) {
        val coordinator = StartupCoordinator { UpdateChecker().check() }
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

    /** 登録・IPC・socketはバックグラウンドで準備し、失敗をSSH接続へ変換しません。 */
    internal fun prepareManaged(stage: Stage, profile: ManagedProfile) {
        showPreparing(stage, "管理writerと起動識別を確認しています...")
        thread(isDaemon = true, name = "managed-connect") {
            try {
                EditorSession.startManagedSession(profile)
                Platform.runLater { stage.close(); Utility.navigateToHome(profile.instanceId) }
            } catch (_: Exception) {
                Platform.runLater {
                    CustomDialog.error().header("管理接続に失敗しました")
                        .content("writer登録が残っている可能性があります。監督statusと診断を確認してください。自動解除・別profileへのfallbackは行いません。")
                        .show()
                    stage.close()
                    showModeSelection()
                }
            }
        }
    }

    /** 終了証明が得られたときだけ次の画面へ進みます。失敗時はsessionを保全します。 */
    internal fun finishManaged(stage: Stage, completed: () -> Unit) {
        showPreparing(stage, "保存キューと管理接続の終了を確認しています...")
        stage.show()
        thread(isDaemon = true, name = "managed-disconnect") {
            try {
                EditorSession.resetMode()
                Platform.runLater(completed)
            } catch (_: Exception) {
                Platform.runLater {
                    CustomDialog.error().header("管理writerの終了が未確認です")
                        .content("writer登録とsessionデータを保全しました。監督statusを確認してください。別profileへ切り替えません。")
                        .show()
                }
            }
        }
    }

    private fun prepareOffline(stage: Stage) {
        val root = FilePath.OFFLINE_DIR.toFile()
        val result = OfflineWorkspaceMigrator(root).migrateToCurrent()
        Platform.runLater {
            when (result) {
                is WorkspaceMigrationResult.Success -> {
                    val store = LocalEditorDataStore(root)
                    EditorSession.startOfflineSession(store)
                    stage.close()
                    Utility.navigateToHome("オフライン")
                }
                is WorkspaceMigrationResult.Failure -> {
                    logger.error(
                        "オフラインワークスペースを準備できませんでした: code={}, detail={}",
                        result.error.code,
                        result.error.detail,
                        result.error.cause
                    )
                    CustomDialog.error()
                        .title("オフラインデータエラー")
                        .header("オフラインデータを開けませんでした")
                        .content("${result.error.code}: ${result.error.detail.orEmpty()}")
                        .show()
                    showModeSelection(stage)
                }
            }
        }
    }

    private fun showPreparing(stage: Stage, message: String) {
        stage.scene = Utility.createScene(
            AppScreen.WIDGETS_ONLY,
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
