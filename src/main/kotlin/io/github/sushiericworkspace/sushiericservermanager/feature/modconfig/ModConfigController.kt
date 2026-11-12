package io.github.sushiericworkspace.sushiericservermanager.feature.modconfig

import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentKey
import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentVersion
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.EditorShortcut
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.ShortcutManager
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.ShortcutScope
import io.github.sushiericworkspace.sushiericservermanager.app.ApplicationFlow
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.SavedUploadAction
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.SavedUploadSelection
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.SavedUploadPublication
import javafx.beans.value.ChangeListener
import javafx.collections.SetChangeListener
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
    @FXML private lateinit var uploadButton: Button
    @FXML private lateinit var readbackButton: Button

    private val logger = LoggerFactory.getLogger(javaClass)

    private var dataService: EditorDataService? = null
    private var connectedService: EditorDataService? = null
    private var uploadAction: SavedUploadAction? = null
    private var disposed = false
    private val uploadDocumentsListener = SetChangeListener<EditorDocumentKey> { refreshControls() }
    private val uploadResultListener = ChangeListener<SavedUploadPublication> { _, _, publication ->
        val service = dataService
        val version = documentVersion
        if (!disposed && publication != null && service != null && version != null) {
            val record = publication.snapshot.documents.firstOrNull { it.key == version.key }
            val final = publication.operation.targetRevisions.firstOrNull { it.key == version.key }?.revision
            if (record != null && version.revision == publication.observed[record.key] && record.revision == final) {
                version.accepted(record)
                savedText = record.saved
                unreflected = record.unreflected
                showDocumentState()
            }
        }
    }

    private val uploadFrozen: Boolean get() {
        val key = documentVersion?.key ?: return false
        val connected = connectedService ?: return false
        return key in connected.uploadingDocuments || key in connected.unresolvedUploadDocuments
    }

    /** 読み込みまたは保存の実行中かを表します。終わるまで次の操作を受け付けません。 */
    private var busy = false
    private var displaying = false
    private var savedText: String? = null
    private var unreflected: Boolean? = null
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
            if (busy || uploadFrozen || !contentArea.isEditable) false else { save(); true }
        }
        ShortcutManager.register(rootPane, ShortcutScope.MOD_CONFIG, EditorShortcut.RELOAD) {
            if (busy || uploadFrozen) false else { reload(); true }
        }
        ShortcutManager.register(rootPane, ShortcutScope.MOD_CONFIG, EditorShortcut.UPLOAD) {
            if (busy || uploadFrozen || uploadAction?.busy == true) false else { handleUpload(); true }
        }
    }

    /**
     * 使用するデータサービスを受け取り、設定の読み込みを開始します。
     *
     * @param service 保存先に依存せずファイルを読み書きするデータサービス。
     */
    fun initData(service: EditorDataService) {
        connectedService = service
        service.uploadingDocuments.addListener(uploadDocumentsListener)
        service.unresolvedUploadDocuments.addListener(uploadDocumentsListener)
        service.uploadResultProperty.addListener(uploadResultListener)
        if (service.isRemote) service.refreshUploadState()
        sourceMenu.items.setAll(MenuItem("ローカル").apply {
            setOnAction { switchSource(service.localService) }
        })
        if (service.isRemote) sourceMenu.items += MenuItem("サーバーの作業コピー").apply {
            setOnAction { switchSource(service) }
        }
        Platform.runLater {
            if (disposed) return@runLater
            val savedSource = WorkspaceManager.current?.documentSource(rootPane)
            dataService = if (service.isRemote && savedSource == service.cacheIdentity) service else service.localService
            updateSourceLabel()
            load(restoreEditing = true)
        }
    }

    private fun switchSource(next: EditorDataService) {
        if (disposed || busy || uploadAction?.busy == true || next === dataService || rootPane.scene?.let(ShortcutManager::isComposing) == true) return
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

    /** ローカル保存済みの本文で編集中の内容を置き換えます。 */
    @FXML
    @Suppress("unused")
    fun handleReload() { ShortcutManager.execute(rootPane, EditorShortcut.RELOAD) }

    private fun reload() {
        val service = dataService ?: return
        val version = documentVersion
        if (disposed || busy || uploadFrozen) return

        if (contentArea.isEditable) {
            val confirmed = CustomDialog.confirmation()
                .title("再読み込み")
                .header("編集中の内容を破棄します")
                .content("現在の編集本文を破棄し、ローカル保存済みの本文で置き換えます。未アップロードの保存済み本文は保持します。サーバーからの取得やアップロードは行いません。")
                .owner(currentStage())
                .show()

            if (!confirmed) return
        }

        if (disposed || busy || uploadFrozen || dataService !== service || documentVersion !== version ||
            ApplicationFlow.closingConnection) return
        load(restoreEditing = false)
    }

    /** 入力された内容をローカルへそのまま保存し、サーバーへは反映しません。 */
    @FXML
    @Suppress("unused")
    fun handleSave() { ShortcutManager.execute(rootPane, EditorShortcut.SAVE) }

    private fun save() {
        val service = dataService ?: return
        if (busy || uploadFrozen) return

        backupDelay.stop()
        val text = contentArea.text
        val version = documentVersion ?: return

        runInBackground(
            statusText = "保存しています…",
            action = { service.modConfig.saveObserved(text, version) },
            onSuccess = { state ->
                savedText = state.saved
                unreflected = state.unreflected
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
                unreflected = state.unreflected
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
        if (busy || uploadFrozen || !service.isRemote) return
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
                        unreflected = state.unreflected
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

    @FXML
    @Suppress("unused")
    fun handleUpload() { uploadSaved(readback = false) }

    @FXML
    @Suppress("unused")
    fun handleReadback() { uploadSaved(readback = true) }

    private fun uploadSaved(readback: Boolean) {
        val origin = dataService ?: return
        val target = connectedService ?: return
        val version = documentVersion ?: return
        if (busy || uploadAction?.busy == true || (!readback && uploadFrozen)) return
        backupDelay.stop()
        val action = SavedUploadAction(origin, target, currentStage(),
            isActive = { !disposed && dataService === origin },
            prepare = { complete -> backupEditing(complete) },
            onIdle = { uploadAction = null; refreshControls() })
        uploadAction = action
        val selections = listOf(SavedUploadSelection(version.key))
        if (readback) action.readback(selections) else action.start(selections)
        refreshControls()
    }

    private fun showDocumentState() {
        val local = if (contentArea.text == savedText) "ローカル保存済み" else "未保存"
        val remote = when (unreflected) {
            null -> "サーバー基準未取得"
            true -> "未アップロード"
            false -> "反映確認済み"
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
        if (disposed || ApplicationFlow.closingConnection) return
        val service = dataService ?: return
        service.beginSave()
        setBusy(true)
        setStatus(statusText)

        val task = object : Task<StoreResult<T>>() {
            override fun call(): StoreResult<T> = action()
        }

        task.setOnSucceeded {
            service.endSave()
            if (disposed || dataService !== service) return@setOnSucceeded
            setBusy(false)

            when (val result = task.value) {
                is StoreResult.Success -> onSuccess(result.value)
                is StoreResult.Failure -> onFailure(result.error)
            }
        }

        task.setOnFailed {
            service.endSave()
            if (disposed || dataService !== service) return@setOnFailed
            setBusy(false)
            setStatus("処理に失敗しました")
            logger.error("Mod設定の処理に失敗しました", task.exception)
            contentArea.isDisable = false
            onFailure(StoreError(StoreErrorCode.IO_ERROR, detail = task.exception?.message, cause = task.exception))
        }

        service.executeIo(task)
    }

    internal val canClose: Boolean get() = !busy && uploadAction?.busy != true
    internal fun prepareClose(completed: (Boolean) -> Unit) {
        if (busy || uploadAction?.busy == true) { completed(false); return }
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
        disposed = true
        backupDelay.stop()
        connectedService?.uploadingDocuments?.removeListener(uploadDocumentsListener)
        connectedService?.unresolvedUploadDocuments?.removeListener(uploadDocumentsListener)
        connectedService?.uploadResultProperty?.removeListener(uploadResultListener)
        ShortcutManager.unregister(rootPane)
    }

    private fun setBusy(value: Boolean) {
        busy = value
        Platform.runLater(::refreshControls)
    }

    private fun refreshControls() {
        sourceMenu.isDisable = busy || uploadAction?.busy == true
        fetchButton.isVisible = dataService?.isRemote == true
        fetchButton.isManaged = fetchButton.isVisible
        fetchButton.isDisable = busy || uploadFrozen || !contentArea.isEditable
        reloadButton.isDisable = busy || uploadFrozen
        saveButton.isDisable = busy || uploadFrozen || !contentArea.isEditable
        uploadButton.isVisible = connectedService?.isRemote == true
        uploadButton.isManaged = uploadButton.isVisible
        uploadButton.isDisable = busy || uploadFrozen || uploadAction?.busy == true || !contentArea.isEditable
        readbackButton.isVisible = uploadButton.isVisible
        readbackButton.isManaged = readbackButton.isVisible
        readbackButton.isDisable = busy || uploadAction?.busy == true
        contentArea.isDisable = busy || uploadFrozen
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
