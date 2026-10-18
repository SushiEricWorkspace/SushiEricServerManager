package io.github.sushiericworkspace.sushiericservermanager.feature.modconfig

import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentVersion
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.EditorShortcut
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.ShortcutManager
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.ShortcutScope
import io.github.sushiericworkspace.sushiericservermanager.app.ApplicationFlow
import javafx.application.Platform
import javafx.animation.PauseTransition
import javafx.util.Duration
import javafx.concurrent.Task
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceManager
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
 * 読み込みとローカル保存・退避はバックグラウンドで実行します。
 */
class ModConfigController : Initializable {

    @FXML private lateinit var rootPane: BorderPane
    @FXML private lateinit var sourceMenu: MenuButton
    @FXML private lateinit var pathLabel: Label
    @FXML private lateinit var statusLabel: Label
    @FXML private lateinit var contentArea: TextArea
    @FXML private lateinit var reloadButton: Button
    @FXML private lateinit var fetchButton: Button
    @FXML private lateinit var saveButton: Button

    private val logger = LoggerFactory.getLogger(javaClass)

    private var dataService: EditorDataService? = null

    /** 読み込みまたは保存の実行中かを表します。終わるまで次の操作を受け付けません。 */
    private var busy = false
    private var displaying = false
    private var savedText: String? = null
    private var baselineText: String? = null
    private var baselineKnown = false
    private var documentVersion: LocalDocumentVersion? = null
    private val backupDelay = PauseTransition(Duration.millis(500.0)).apply {
        setOnFinished { backupEditing() }
    }

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        contentArea.isEditable = false
        contentArea.textProperty().addListener { _, _, _ ->
            if (!displaying && contentArea.isEditable) {
                showDocumentState()
                backupDelay.playFromStart()
            }
        }
        applyShortcutHints()
        installShortcutsWhenSceneReady()
        refreshControls()
    }

    private fun applyShortcutHints() {
        saveButton.textProperty().bind(ShortcutManager.hint(EditorShortcut.SAVE))
        reloadButton.textProperty().bind(ShortcutManager.hint(EditorShortcut.RELOAD))
    }

    private fun installShortcutsWhenSceneReady() {
        ShortcutManager.register(rootPane, ShortcutScope.MOD_CONFIG, EditorShortcut.SAVE) {
            if (busy || !contentArea.isEditable) false else { save(); true }
        }
        ShortcutManager.register(rootPane, ShortcutScope.MOD_CONFIG, EditorShortcut.RELOAD) {
            if (busy) false else { reload(); true }
        }
    }

    /**
     * 使用するデータサービスを受け取り、設定の読み込みを開始します。
     *
     * @param service 保存先に依存せずファイルを読み書きするデータサービス。
     */
    fun initData(service: EditorDataService) {
        sourceMenu.items.setAll(MenuItem("ローカル").apply {
            setOnAction { switchSource(service.localService) }
        })
        if (service.isRemote) sourceMenu.items += MenuItem("サーバーの作業コピー").apply {
            setOnAction { switchSource(service) }
        }
        Platform.runLater {
            val savedSource = WorkspaceManager.current?.documentSource(rootPane)
            dataService = if (service.isRemote && savedSource == service.cacheIdentity) service else service.localService
            updateSourceLabel()
            load(restoreEditing = true)
        }
    }

    private fun switchSource(next: EditorDataService) {
        if (busy || next === dataService || rootPane.scene?.let(ShortcutManager::isComposing) == true) return
        backupDelay.stop()
        setBusy(true)
        contentArea.isDisable = true
        fun loadNext() {
            dataService = next
            WorkspaceManager.current?.documentSource(rootPane, next.cacheIdentity)
            updateSourceLabel()
            contentArea.isDisable = false
            setBusy(false)
            load(restoreEditing = true)
        }
        if (!contentArea.isEditable) loadNext()
        else backupEditing { success ->
            contentArea.isDisable = false
            setBusy(false)
            if (success) loadNext()
        }
    }

    private fun updateSourceLabel() {
        val service = checkNotNull(dataService)
        sourceMenu.text = if (service.isRemote) "サーバーの作業コピー" else "ローカル"
        pathLabel.text = service.modConfig.relativePath
    }

    /** 保存されている内容を再読み込みします。 */
    @FXML
    @Suppress("unused")
    fun handleReload() { ShortcutManager.execute(rootPane, EditorShortcut.RELOAD) }

    private fun reload() {
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

        load(restoreEditing = false)
    }

    /** 入力された内容をそのまま保存します。 */
    @FXML
    @Suppress("unused")
    fun handleSave() { ShortcutManager.execute(rootPane, EditorShortcut.SAVE) }

    private fun save() {
        val service = dataService ?: return
        if (busy) return

        backupDelay.stop()
        val text = contentArea.text
        val version = documentVersion ?: return

        runInBackground(
            statusText = "保存しています…",
            action = { service.modConfig.saveObserved(text, version) },
            onSuccess = {
                savedText = text
                showDocumentState()
            },
            onFailure = { error ->
                setStatus("保存できませんでした")
                showError("保存エラー", "設定を保存できませんでした", error)
            }
        )
    }

    private fun load(restoreEditing: Boolean) {
        val service = dataService ?: return
        backupDelay.stop()
        contentArea.isEditable = false
        runInBackground(
            statusText = "ローカル文書を読み込んでいます…",
            action = { service.modConfig.localState() },
            onSuccess = { state ->
                documentVersion = LocalDocumentVersion(state.key, state.revision)
                savedText = state.saved
                baselineText = state.baseline
                baselineKnown = state.baselineKnown
                displaying = true
                try { contentArea.text = if (restoreEditing) state.editing ?: state.saved.orEmpty() else state.saved.orEmpty() }
                finally { displaying = false }
                contentArea.isEditable = true
                showDocumentState()
                if (!restoreEditing) backupEditing()
            },
            onFailure = { error ->
                setStatus("ローカル文書を読み込めませんでした")
                showError("読み込みエラー", "設定を読み込めませんでした", error)
            }
        )
    }

    @FXML
    @Suppress("unused")
    fun handleFetch() {
        val service = dataService ?: return
        val version = documentVersion ?: return
        if (busy || !service.isRemote) return
        backupDelay.stop()
        val editing = contentArea.text
        contentArea.isDisable = true
        runInBackground(
            statusText = "サーバーから取得しています…",
            action = { service.modConfig.fetchSource() },
            onSuccess = { latest ->
                val confirmed = CustomDialog.confirmation().title("取得の確認")
                    .header("Mod設定のサーバー基準を更新しますか？")
                    .content("未アップロードのローカル保存済み本文と編集中の本文は保持します。基準と一致している文書だけ取得内容で更新します。")
                    .owner(currentStage()).show()
                if (!confirmed) {
                    contentArea.isDisable = false
                    showDocumentState()
                } else runInBackground(
                    statusText = "取得基準をローカルへ保持しています…",
                    action = { service.modConfig.acceptFetched(version, latest, editing) },
                    onSuccess = { state ->
                        savedText = state.saved
                        baselineText = state.baseline
                        baselineKnown = state.baselineKnown
                        displaying = true
                        try { contentArea.text = state.editing.orEmpty() } finally { displaying = false }
                        contentArea.isDisable = false
                        showDocumentState()
                    },
                    onFailure = { error ->
                        contentArea.isDisable = false
                        showError("取得エラー", "現在の文書を保持しています", error)
                    })
            },
            onFailure = { error ->
                contentArea.isDisable = false
                showError("取得エラー", "サーバーから取得できませんでした", error)
            })
    }

    private fun showDocumentState() {
        val local = if (contentArea.text == savedText) "ローカル保存済み" else "未保存"
        val remote = when {
            !baselineKnown -> "サーバー基準未取得"
            savedText != baselineText -> "未アップロード"
            else -> "取得基準と一致"
        }
        setStatus("$local / $remote")
    }

    private fun backupEditing(onComplete: (Boolean) -> Unit = {}) {
        val service = dataService ?: run { onComplete(true); return }
        val text = contentArea.text
        val version = documentVersion ?: run { onComplete(false); return }
        service.modConfig.backup(text, version).whenComplete { result, error ->
            Platform.runLater {
                val success = error == null && result is StoreResult.Success
                if (!success) setStatus("退避に失敗しました。編集内容を保持しています。")
                onComplete(success)
            }
        }
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
        if (ApplicationFlow.closingConnection) return
        val service = dataService ?: return
        service.beginSave()
        setBusy(true)
        setStatus(statusText)

        val task = object : Task<StoreResult<T>>() {
            override fun call(): StoreResult<T> = action()
        }

        task.setOnSucceeded {
            service.endSave()
            setBusy(false)

            when (val result = task.value) {
                is StoreResult.Success -> onSuccess(result.value)
                is StoreResult.Failure -> onFailure(result.error)
            }
        }

        task.setOnFailed {
            service.endSave()
            setBusy(false)
            setStatus("処理に失敗しました")
            logger.error("Mod設定の処理に失敗しました", task.exception)
            contentArea.isDisable = false
            onFailure(StoreError(StoreErrorCode.IO_ERROR, detail = task.exception?.message, cause = task.exception))
        }

        service.executeIo(task)
    }

    internal val canClose: Boolean get() = !busy
    internal fun prepareClose(completed: (Boolean) -> Unit) {
        if (busy) { completed(false); return }
        if (!contentArea.isEditable) { completed(true); return }
        backupDelay.stop()
        setBusy(true)
        contentArea.isDisable = true
        backupEditing { success ->
            contentArea.isDisable = false
            setBusy(false)
            completed(success)
        }
    }

    internal fun dispose() {
        backupDelay.stop()
        ShortcutManager.unregister(rootPane)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        Platform.runLater(::refreshControls)
    }

    private fun refreshControls() {
        sourceMenu.isDisable = busy
        fetchButton.isVisible = dataService?.isRemote == true
        fetchButton.isManaged = fetchButton.isVisible
        fetchButton.isDisable = busy || !contentArea.isEditable
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
