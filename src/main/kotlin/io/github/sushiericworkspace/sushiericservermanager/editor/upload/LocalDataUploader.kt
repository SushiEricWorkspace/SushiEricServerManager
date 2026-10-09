package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import javafx.concurrent.Task
import javafx.stage.Stage
import org.slf4j.LoggerFactory

/**
 * エディターのメニューから、ローカルデータをサーバーへアップロードする一連の流れを実行します。
 *
 * ローカルデータの確認、データと宛先の選択、上書きの確認、アップロード、結果の表示の順に進みます。
 * 通信を伴う処理はバックグラウンドで実行し、画面の操作はJavaFX Application Threadで行います。
 * JavaFX Application Threadから呼び出してください。
 */
object LocalDataUploader {
    private val logger = LoggerFactory.getLogger(LocalDataUploader::class.java)

    /**
     * アップロードを開始します。
     *
     * @param owner ダイアログの親ウィンドウ。
     * @param remoteStore アップロード先のストア。
     * @param profileName 接続先の表示名。
     * @param category アップロードするデータの種別。
     * @param onBusyChanged 処理中かどうかが変わったときの通知。
     * @param onUploaded 1件以上アップロードに成功した後の通知。エディターの一覧の更新に使います。
     */
    fun start(
        owner: Stage?,
        remoteStore: EditorDataStore,
        profileName: String,
        category: UploadDataCategory,
        onBusyChanged: (Boolean) -> Unit,
        onUploaded: () -> Unit
    ) {
        onBusyChanged(true)
        val scanTask = object : Task<UploadScanResult>() {
            override fun call(): UploadScanResult = service(remoteStore).scan(category)
        }
        scanTask.setOnSucceeded {
            onBusyChanged(false)
            when (val result = scanTask.value) {
                is UploadScanResult.Failure -> showError(owner, result.error.code.name, result.error.detail)
                is UploadScanResult.Success -> selectAndUpload(
                    owner, remoteStore, profileName, category, result, onBusyChanged, onUploaded
                )
            }
        }
        scanTask.setOnFailed {
            onBusyChanged(false)
            logger.error("アップロード対象の確認に失敗しました", scanTask.exception)
            showError(owner, "INTERNAL_ERROR", scanTask.exception?.message)
        }
        run(scanTask, "local-upload-scan")
    }

    private fun selectAndUpload(
        owner: Stage?,
        remoteStore: EditorDataStore,
        profileName: String,
        category: UploadDataCategory,
        scan: UploadScanResult.Success,
        onBusyChanged: (Boolean) -> Unit,
        onUploaded: () -> Unit
    ) {
        if (scan.entries.isEmpty()) {
            CustomDialog.information()
                .title("ローカルデータをアップロード")
                .header("アップロードできる${category.displayName}がありません")
                .owner(owner)
                .show()
            return
        }
        val selection = OfflineUploadDialog.select(owner, profileName, category, scan) ?: return
        if (selection.candidates.isEmpty()) return

        val overwrite = selection.candidates
            .filter { it.state == UploadCandidateState.OVERWRITE }
        if (overwrite.isNotEmpty()) {
            val approved = CustomDialog.confirmation()
                .title("上書き確認")
                .header("${overwrite.size} 件の既存データを上書きします")
                .content(overwrite.joinToString("\n") { it.targetId })
                .owner(owner)
                .show()
            if (!approved) return
        }

        onBusyChanged(true)
        val uploadTask = object : Task<OfflineUploadResult>() {
            override fun call(): OfflineUploadResult = service(remoteStore).upload(
                selected = selection.keys,
                overwriteApproved = overwrite.mapTo(linkedSetOf()) { it.key },
                destination = selection.destination
            )
        }
        uploadTask.setOnSucceeded {
            onBusyChanged(false)
            val result = uploadTask.value
            val failureText = result.failed.joinToString("\n") {
                "${it.key.id}: ${it.error.code}${it.error.detail?.let { detail -> "（$detail）" }.orEmpty()}"
            }
            CustomDialog.information()
                .title("アップロード結果")
                .header("成功 ${result.succeeded.size} 件、失敗 ${result.failed.size} 件")
                .content(failureText.ifBlank { "選択したデータをアップロードしました。ローカルデータは保持されています。" })
                .owner(owner)
                .show()
            if (result.succeeded.isNotEmpty()) onUploaded()
        }
        uploadTask.setOnFailed {
            onBusyChanged(false)
            logger.error("ローカルデータのアップロードに失敗しました", uploadTask.exception)
            showError(owner, "INTERNAL_ERROR", uploadTask.exception?.message)
        }
        run(uploadTask, "local-upload")
    }

    private fun service(remoteStore: EditorDataStore) =
        OfflineUploadService(workspaceRoot = FilePath.OFFLINE_DIR.toFile(), remoteStore = remoteStore)

    private fun run(task: Task<*>, name: String) {
        Thread(task, name).apply {
            isDaemon = true
            start()
        }
    }

    private fun showError(owner: Stage?, code: String, detail: String?) {
        CustomDialog.error()
            .title("アップロードエラー")
            .header("ローカルデータをアップロードできませんでした")
            .content("$code${detail?.let { ": $it" }.orEmpty()}")
            .owner(owner)
            .show()
    }
}
