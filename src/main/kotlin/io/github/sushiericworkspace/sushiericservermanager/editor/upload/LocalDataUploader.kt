package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
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
     * @param dataService 元データの読み込み先と接続単位のI/Oキュー。保存は行いません。
     * @param profileName 接続先の表示名。
     * @param category 読み込むデータの種別。
     * @param onBusyChanged 走査から確認・編集反映・結果表示までの受付中はtrue、取消・失敗を含む終了時はfalseを通知。
     * @param descriptor 読み込むデータの型付き定義。
     * @param editingIds 編集キャッシュの宛先も上書き確認に含めるために取得するID。
     * @param onBeforeLoad 上書き承認後、読み込みを始める直前の通知。
     * @param isActive 画面と接続が結果を受け取れるかをUIスレッド上で確認します。
     * @param onProgress 工程・進捗率の通知。負値は不定進捗です。
     * @param onLoaded 未保存の編集へ反映し、退避と一覧更新の完了後に結果を通知します。中止時はfalse。
     */
    fun <T : ManagedData<T, *>> start(
        owner: Stage?,
        dataService: EditorDataService,
        profileName: String,
        category: UploadDataCategory,
        descriptor: EditorDataDescriptor<T>,
        editingIds: () -> Set<String>,
        onBeforeLoad: () -> Unit,
        onBusyChanged: (Boolean) -> Unit,
        isActive: () -> Boolean,
        onProgress: (String, Double) -> Unit,
        onLoaded: (List<LocalEditImport<T>>, (Boolean) -> Unit) -> Unit
    ) {
        onBusyChanged(true)
        onProgress("ローカルデータを走査しています", -1.0)
        val scanTask = object : Task<UploadScanResult>() {
            override fun call(): UploadScanResult = service(dataService.store).scan(category)
        }
        scanTask.setOnSucceeded {
            if (!isActive()) { onBusyChanged(false); return@setOnSucceeded }
            when (val result = scanTask.value) {
                is UploadScanResult.Failure -> {
                    try { showError(owner, result.error.code.name, result.error.detail) }
                    finally { onBusyChanged(false) }
                }
                is UploadScanResult.Success -> selectAndLoad(
                    owner, dataService, profileName, category, descriptor,
                    result.copy(remoteIds = result.remoteIds + (
                        category to (result.remoteIds.getValue(category) + editingIds())
                    )), onBeforeLoad, onBusyChanged, isActive, onProgress, onLoaded
                )
            }
        }
        scanTask.setOnFailed {
            try {
                logger.error("読み込み対象の確認に失敗しました", scanTask.exception)
                if (isActive()) showError(owner, "INTERNAL_ERROR", scanTask.exception?.message)
            } finally { onBusyChanged(false) }
        }
        dataService.executeIo(scanTask)
    }

    private fun <T : ManagedData<T, *>> selectAndLoad(
        owner: Stage?,
        dataService: EditorDataService,
        profileName: String,
        category: UploadDataCategory,
        descriptor: EditorDataDescriptor<T>,
        scan: UploadScanResult.Success,
        onBeforeLoad: () -> Unit,
        onBusyChanged: (Boolean) -> Unit,
        isActive: () -> Boolean,
        onProgress: (String, Double) -> Unit,
        onLoaded: (List<LocalEditImport<T>>, (Boolean) -> Unit) -> Unit
    ) {
        if (scan.entries.isEmpty()) {
            try {
                CustomDialog.information()
                    .title("ローカルデータの読み込み")
                    .header("読み込める${category.displayName}がありません")
                    .owner(owner)
                    .show()
            } finally { onBusyChanged(false) }
            return
        }
        val selection = OfflineUploadDialog.select(owner, profileName, category, scan, forEditing = true)
            ?: run { onBusyChanged(false); return }
        if (!isActive() || selection.candidates.isEmpty()) { onBusyChanged(false); return }

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
            if (!approved || !isActive()) { onBusyChanged(false); return }
        }

        onBeforeLoad()
        onProgress("ローカルデータを読み込みます 0/${selection.keys.size} 件", 0.0)
        val loadTask = object : Task<LocalEditImportResult<T>>() {
            override fun call(): LocalEditImportResult<T> = service(dataService.store).loadForEditing(
                descriptor = descriptor,
                selected = selection.keys,
                overwriteApproved = overwrite.mapTo(linkedSetOf()) { it.key },
                destination = selection.destination,
                onProgress = { completed, total, name ->
                    updateMessage("ローカルデータを読み込み中 $completed/$total 件${name?.let { ": $it" }.orEmpty()}")
                    updateProgress(completed.toLong(), total.toLong())
                }
            )
        }
        loadTask.messageProperty().addListener { _, _, message ->
            if (isActive()) onProgress(message, loadTask.progress)
        }
        loadTask.progressProperty().addListener { _, _, progress ->
            if (isActive()) onProgress(loadTask.message, progress.toDouble())
        }
        loadTask.setOnSucceeded {
            if (!isActive()) { onBusyChanged(false); return@setOnSucceeded }
            val result = loadTask.value
            fun complete(applied: Boolean) {
                if (!applied || !isActive()) { onBusyChanged(false); return }
                try {
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
                } finally { onBusyChanged(false) }
            }
            if (result.entries.isEmpty()) complete(true)
            else onLoaded(result.entries, ::complete)
        }
        loadTask.setOnFailed {
            try {
                logger.error("ローカルデータの読み込みに失敗しました", loadTask.exception)
                if (isActive()) showError(owner, "INTERNAL_ERROR", loadTask.exception?.message)
            } finally { onBusyChanged(false) }
        }
        dataService.executeIo(loadTask)
    }

    private fun service(remoteStore: EditorDataStore) =
        OfflineUploadService(workspaceRoot = remoteStore.offlineDirectory ?: FilePath.OFFLINE_DIR.toFile(), remoteStore = remoteStore)

    private fun showError(owner: Stage?, code: String, detail: String?) {
        CustomDialog.error()
            .title("読み込みエラー")
            .header("ローカルデータを読み込めませんでした")
            .content("$code${detail?.let { ": $it" }.orEmpty()}")
            .owner(owner)
            .show()
    }
}
