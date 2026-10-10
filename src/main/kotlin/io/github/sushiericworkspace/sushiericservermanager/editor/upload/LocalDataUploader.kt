package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptor
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import javafx.concurrent.Task
import javafx.stage.Stage
import org.slf4j.LoggerFactory

/**
 * エディターのメニューから、ローカルデータを未保存の編集として読み込む一連の流れを実行します。
 *
 * ローカルデータの確認、データと宛先の選択、取り込み前の上書き確認、読み込み、結果の表示の順に進みます。
 * 通信を伴う処理はバックグラウンドで実行し、画面の操作はJavaFX Application Threadで行います。
 * JavaFX Application Threadから呼び出してください。
 */
object LocalDataUploader {
    private val logger = LoggerFactory.getLogger(LocalDataUploader::class.java)

    /**
     * 未保存の編集としての読み込みを開始します。サーバーへの反映は通常の保存で行います。
     *
     * @param owner ダイアログの親ウィンドウ。
     * @param remoteStore 元データの読み込み先。保存は行いません。
     * @param profileName 接続先の表示名。
     * @param category 読み込むデータの種別。
     * @param onBusyChanged 処理中かどうかが変わったときの通知。
     * @param descriptor 読み込むデータの型付き定義。
     * @param editingIds 編集キャッシュの宛先も上書き確認に含めるために取得するID。
     * @param onBeforeLoad 上書き承認後、読み込みを始める直前の通知。
     * @param onLoaded 未保存の編集へ反映する通知。取り込みを中止した場合はfalse。
     */
    fun <T : ManagedData<T, *>> start(
        owner: Stage?,
        remoteStore: EditorDataStore,
        profileName: String,
        category: UploadDataCategory,
        descriptor: EditorDataDescriptor<T>,
        editingIds: () -> Set<String>,
        onBeforeLoad: () -> Unit,
        onBusyChanged: (Boolean) -> Unit,
        onLoaded: (List<LocalEditImport<T>>) -> Boolean
    ) {
        onBusyChanged(true)
        val scanTask = object : Task<UploadScanResult>() {
            override fun call(): UploadScanResult = service(remoteStore).scan(category)
        }
        scanTask.setOnSucceeded {
            onBusyChanged(false)
            when (val result = scanTask.value) {
                is UploadScanResult.Failure -> showError(owner, result.error.code.name, result.error.detail)
                is UploadScanResult.Success -> selectAndLoad(
                    owner, remoteStore, profileName, category, descriptor,
                    result.copy(remoteIds = result.remoteIds + (
                        category to (result.remoteIds.getValue(category) + editingIds())
                    )), onBeforeLoad, onBusyChanged, onLoaded
                )
            }
        }
        scanTask.setOnFailed {
            onBusyChanged(false)
            logger.error("読み込み対象の確認に失敗しました", scanTask.exception)
            showError(owner, "INTERNAL_ERROR", scanTask.exception?.message)
        }
        run(scanTask, "local-import-scan")
    }

    private fun <T : ManagedData<T, *>> selectAndLoad(
        owner: Stage?,
        remoteStore: EditorDataStore,
        profileName: String,
        category: UploadDataCategory,
        descriptor: EditorDataDescriptor<T>,
        scan: UploadScanResult.Success,
        onBeforeLoad: () -> Unit,
        onBusyChanged: (Boolean) -> Unit,
        onLoaded: (List<LocalEditImport<T>>) -> Boolean
    ) {
        if (scan.entries.isEmpty()) {
            CustomDialog.information()
                .title("ローカルデータの読み込み")
                .header("読み込める${category.displayName}がありません")
                .owner(owner)
                .show()
            return
        }
        val selection = OfflineUploadDialog.select(owner, profileName, category, scan, forEditing = true) ?: return
        if (selection.candidates.isEmpty()) return

        val overwrite = selection.candidates
            .filter { it.state == UploadCandidateState.OVERWRITE }
        if (overwrite.isNotEmpty()) {
            val approved = CustomDialog.confirmation()
                .title("上書き確認")
                .header("${overwrite.size} 件の編集内容をローカルデータで置き換えます")
                .content(listOf(
                    overwrite.joinToString("\n") { it.targetId },
                    "サーバーへの反映は、エディターで確認して保存した後に行います。"
                ))
                .owner(owner)
                .show()
            if (!approved) return
        }

        onBeforeLoad()
        onBusyChanged(true)
        val loadTask = object : Task<LocalEditImportResult<T>>() {
            override fun call(): LocalEditImportResult<T> = service(remoteStore).loadForEditing(
                descriptor = descriptor,
                selected = selection.keys,
                overwriteApproved = overwrite.mapTo(linkedSetOf()) { it.key },
                destination = selection.destination
            )
        }
        loadTask.setOnSucceeded {
            onBusyChanged(false)
            val result = loadTask.value
            if (result.entries.isNotEmpty() && !onLoaded(result.entries)) return@setOnSucceeded
            val failureText = result.failed.joinToString("\n") {
                "${it.key.id}: ${it.error.code}${it.error.detail?.let { detail -> "（$detail）" }.orEmpty()}"
            }
            CustomDialog.information()
                .title("ローカルデータの読み込み結果")
                .header("読み込み ${result.entries.size} 件、失敗 ${result.failed.size} 件")
                .content(listOf(
                    "未保存の編集として読み込みました。内容を確認し、保存するとサーバーへ反映されます。ローカルデータは保持されています。",
                    failureText
                ))
                .owner(owner)
                .show()
        }
        loadTask.setOnFailed {
            onBusyChanged(false)
            logger.error("ローカルデータの読み込みに失敗しました", loadTask.exception)
            showError(owner, "INTERNAL_ERROR", loadTask.exception?.message)
        }
        run(loadTask, "local-import")
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
            .title("読み込みエラー")
            .header("ローカルデータを読み込めませんでした")
            .content("$code${detail?.let { ": $it" }.orEmpty()}")
            .owner(owner)
            .show()
    }
}
