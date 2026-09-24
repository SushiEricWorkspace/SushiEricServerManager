package io.github.sushiericworkspace.sushiericservermanager.feature.modconfig

import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.EditorShortcut
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.ShortcutManager
import javafx.application.Platform
import javafx.concurrent.Task
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.layout.BorderPane
import javafx.stage.Stage
import org.slf4j.LoggerFactory
import java.net.URL
import java.util.ResourceBundle

/**
 * Mod共通設定（config.yml）をYAMLの生テキストとして編集する画面です。
 *
 * 内容の解析、整形、検証は行いません。入力された内容をそのまま保存し、設定値の
 * 妥当性判断と既定値へのフォールバックはServerMod側が行います。
 *
 * 読み込みと保存はSSH通信を伴うため、バックグラウンドで実行します。
 */
class ModConfigController : Initializable {

    @FXML private lateinit var rootPane: BorderPane
    @FXML private lateinit var pathLabel: Label
    @FXML private lateinit var statusLabel: Label
    @FXML private lateinit var contentArea: TextArea
    @FXML private lateinit var reloadButton: Button
    @FXML private lateinit var saveButton: Button

    private val logger = LoggerFactory.getLogger(javaClass)

    private var dataService: EditorDataService? = null

    /** 読み込みまたは保存の実行中かを表します。終わるまで次の操作を受け付けません。 */
    private var busy = false

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        contentArea.isEditable = false
        applyShortcutHints()
        installShortcutsWhenSceneReady()
        refreshControls()
    }

    /** ボタンへ割り当てたショートカットキーを表示します。 */
    private fun applyShortcutHints() {
        saveButton.text = "${EditorShortcut.SAVE.displayName}（${EditorShortcut.SAVE.combination.displayText}）"
        reloadButton.text =
            "${EditorShortcut.RELOAD.displayName}（${EditorShortcut.RELOAD.combination.displayText}）"
    }

    /**
     * この画面のSceneへショートカットキーを登録します。
     *
     * FXMLの読み込み時点ではSceneが未作成のため、割り当てられてから登録します。
     */
    private fun installShortcutsWhenSceneReady() {
        rootPane.sceneProperty().addListener { _, _, scene ->
            if (scene == null) return@addListener

            ShortcutManager.register(scene, EditorShortcut.SAVE) { handleSave() }
            ShortcutManager.register(scene, EditorShortcut.RELOAD) { handleReload() }
        }
    }

    /**
     * 使用するデータサービスを受け取り、設定の読み込みを開始します。
     *
     * @param service 保存先に依存せずファイルを読み書きするデータサービス。
     */
    fun initData(service: EditorDataService) {
        dataService = service
        pathLabel.text = service.modConfig.relativePath
        load()
    }

    /** 保存されている内容を再読み込みします。 */
    @FXML
    @Suppress("unused")
    fun handleReload() {
        if (busy) return

        if (contentArea.isEditable && contentArea.text.isNotEmpty()) {
            val confirmed = CustomDialog.confirmation()
                .title("再読み込み")
                .header("編集中の内容を破棄します")
                .content("保存されている内容で置き換えます。")
                .owner(currentStage())
                .show()

            if (!confirmed) return
        }

        load()
    }

    /** 入力された内容をそのまま保存します。 */
    @FXML
    @Suppress("unused")
    fun handleSave() {
        val service = dataService ?: return
        if (busy) return

        val text = contentArea.text

        runInBackground(
            statusText = "保存しています…",
            action = { service.modConfig.save(text) },
            onSuccess = {
                setStatus("保存しました")
            },
            onFailure = { error ->
                setStatus("保存できませんでした")
                showError("保存エラー", "設定を保存できませんでした", error)
            }
        )
    }

    private fun load() {
        val service = dataService ?: return

        runInBackground(
            statusText = "読み込んでいます…",
            action = { service.modConfig.load() },
            onSuccess = { text ->
                contentArea.text = text
                contentArea.isEditable = true
                setStatus("読み込みました")
            },
            onFailure = { error ->
                contentArea.isEditable = error.code == StoreErrorCode.FILE_NOT_FOUND

                if (error.code == StoreErrorCode.FILE_NOT_FOUND) {
                    contentArea.text = ""
                    setStatus("ファイルがありません。保存すると新しく作成します")
                } else {
                    setStatus("読み込めませんでした")
                    showError("読み込みエラー", "設定を読み込めませんでした", error)
                }
            }
        )
    }

    /**
     * 通信を伴う処理をバックグラウンドで実行し、結果を画面へ反映します。
     *
     * @param statusText 実行中に表示する文言。
     * @param action 実行する処理。
     * @param onSuccess 成功時の処理。JavaFXスレッドで呼びます。
     * @param onFailure 失敗時の処理。JavaFXスレッドで呼びます。
     */
    private fun <T> runInBackground(
        statusText: String,
        action: () -> StoreResult<T>,
        onSuccess: (T) -> Unit,
        onFailure: (StoreError) -> Unit
    ) {
        setBusy(true)
        setStatus(statusText)

        val task = object : Task<StoreResult<T>>() {
            override fun call(): StoreResult<T> = action()
        }

        task.setOnSucceeded {
            setBusy(false)

            when (val result = task.value) {
                is StoreResult.Success -> onSuccess(result.value)
                is StoreResult.Failure -> onFailure(result.error)
            }
        }

        task.setOnFailed {
            setBusy(false)
            setStatus("処理に失敗しました")
            logger.error("Mod設定の処理に失敗しました", task.exception)
            showError(
                title = "エラー",
                header = "処理に失敗しました",
                error = StoreError(StoreErrorCode.IO_ERROR, detail = task.exception?.message)
            )
        }

        Thread(task, "mod-config-io").apply {
            isDaemon = true
            start()
        }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        Platform.runLater(::refreshControls)
    }

    private fun refreshControls() {
        reloadButton.isDisable = busy
        saveButton.isDisable = busy || !contentArea.isEditable
    }

    private fun setStatus(text: String) {
        statusLabel.text = text
        refreshControls()
    }

    private fun showError(title: String, header: String, error: StoreError) {
        logger.error(
            "Mod設定の操作に失敗しました: code={}, detail={}",
            error.code,
            error.detail,
            error.cause
        )

        CustomDialog.error()
            .title(title)
            .header(header)
            .content("${error.code}${error.detail?.let { ": $it" }.orEmpty()}")
            .owner(currentStage())
            .show()
    }

    private fun currentStage(): Stage? = rootPane.scene?.window as? Stage
}
