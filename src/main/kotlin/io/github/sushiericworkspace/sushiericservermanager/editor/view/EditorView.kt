package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.common.data.core.identity.PublicId
import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.common.data.ore.model.OreBaseData
import io.github.sushiericworkspace.common.data.item.model.ItemBaseData
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.data.core.validation.SushiEricValidationError
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.config.AppSettingsManager
import io.github.sushiericworkspace.sushiericservermanager.editor.result.ValidationResult
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.LoadResult
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.DeleteResult
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.RenameResult
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorSyncService
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.DataConflict
import io.github.sushiericworkspace.sushiericservermanager.editor.history.EditorDataHistory
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.validation.ValidationRepairRegistry
import io.github.sushiericworkspace.sushiericservermanager.editor.validation.ValidationRepairResult
import io.github.sushiericworkspace.sushiericservermanager.ui.AppTooltip
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.ErrorType
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.MergeConflictDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.EditorShortcut
import io.github.sushiericworkspace.common.data.core.SushiEricDataType
import javafx.animation.Animation
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.event.EventHandler
import javafx.concurrent.Task
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.TextInputControl
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.util.Duration
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * 各種データエディタ画面の基盤となる抽象クラスです。
 *
 * このクラスは、サイドバー、トップアクションバー、メインコンテンツエリアを持つ
 * エディタウィンドウの共通レイアウトとライフサイクルを定義します。
 *
 * また、扱うデータ型[T]をジェネリックとして受け取ることで、
 * ItemやOreなどの管理データを共通処理として扱えるようにします。
 *
 * 新しいデータ型のエディタを実装する場合は、このクラスを継承し、
 * [T]に対象データ型を指定して、各抽象メソッドを実装してください。
 *
 * @param T このエディタが扱う管理データ型。[ManagedData]を実装している必要があります。
 * @property main 画面遷移やダイアログ表示などの共通UI制御を行うメインコントローラー。
 * @property dataService データのロード、保存、およびリモートリソースの管理を行うデータサービス。
 * @property dataAccess このエディタが扱うデータ種別に対応するデータ操作アクセサ。
 */
abstract class EditorView<T : ManagedData<T, *>>(
    protected val main: MainController,
    protected val dataService: EditorDataService,
    protected val dataAccess: EditorDataService.DataAccess<T>
) {
    private data class PreparedSave<D : ManagedData<D, *>>(
        val dataId: String,
        val operation: PendingStoreOperation?,
        val original: D,
        val previewData: D,
        val saveData: D?
    )

    /** サイドバー表示方式。必要なエディターは従来の平坦表示へ切り替えられます。 */
    protected open val sidebarDisplayMode: SidebarDisplayMode = SidebarDisplayMode.TREE
    private val sidebarStateKey = "${dataService.cacheIdentity}:${dataAccess.dataType.categoryDirName}"
    protected val sidebarExpandedState: MutableMap<String, Boolean> =
        AppSettingsManager.load().sidebarDirectoryExpanded[sidebarStateKey].orEmpty().toMutableMap()

    protected fun persistSidebarExpandedState() {
        val settings = AppSettingsManager.load()
        AppSettingsManager.save(settings.copy(
            sidebarDirectoryExpanded = settings.sidebarDirectoryExpanded +
                (sidebarStateKey to sidebarExpandedState.toMap())
        ))
    }
    var openCancelled: Boolean = false
        private set

    /** 自動保存処理用のタイマー */
    protected var autoSaveTimeline: Timeline? = null

    /** 現在画面に表示しているアイテムのID */
    protected var currentSelectedDataId: String? = null

    protected var selectedButton: Button? = null

    /** サイドバーに並んでいるボタンをIDで即座に引き出せるようにするプロパティ */
    protected val sidebarButtons: MutableMap<String, Button> = mutableMapOf()

    protected val logger: Logger = LoggerFactory.getLogger(javaClass)

    // ID（String）をキーにしたキャッシュMap
    protected val editingDataMap = mutableMapOf<String, T>()
    protected val originalDataMap = mutableMapOf<String, T>()
    private val mergeConflicts = mutableMapOf<String, List<DataConflict>>()
    private val pendingStoreOperations = mutableMapOf<String, PendingStoreOperation>()
    private val editHistory = EditorDataHistory<T>(copy = { it.deepCopy() })
    private val syncService = EditorSyncService(dataAccess)
    private var saveMenuItem: MenuItem? = null
    private var saveAllMenuItem: MenuItem? = null
    private var syncMenuItem: MenuItem? = null
    private var syncAllMenuItem: MenuItem? = null
    private var undoMenuItem: MenuItem? = null
    private var redoMenuItem: MenuItem? = null
    private var repairWarningsMenuItem: MenuItem? = null
    private var repairAllWarningsMenuItem: MenuItem? = null
    private var repairErrorsMenuItem: MenuItem? = null
    private var repairAllErrorsMenuItem: MenuItem? = null
    private var syncBusy = false
    private var sidebarPreloadGeneration = 0

    /** データ種別固有の警告修正アクションです。 */
    protected open val validationRepairRegistry = ValidationRepairRegistry<T>()

    protected var restoredCacheCount = 0

    /**
     * サーバー上に存在するデータIDです。
     *
     * サイドバーを構築するたびに更新し、ローカルにだけ存在するデータの判別へ使用します。
     */
    protected var remoteDataIds: Set<String> = emptySet()

    /**
     * 指定したデータの編集キャッシュと自動保存バックアップを破棄します。
     *
     * ストア上のファイルには触れないため、サーバー未保存データの削除や、
     * ストア側の削除が完了した後の後処理に使用します。
     */
    protected fun discardLocalEditingData(id: String) {
        editingDataMap.remove(id)
        originalDataMap.remove(id)
        mergeConflicts.remove(id)
        editHistory.remove(id)
        dataAccess.deleteLocalBackup(id)
    }

    /** 新規データを、保存時にストアへ追加する保留操作として登録します。 */
    protected fun stageDataCreation(id: String) {
        pendingStoreOperations[id] = PendingStoreOperation.Create(id)
        editingDataMap[id]?.let { editHistory.reset(id, it) }
        persistPendingStoreState()
        refreshButtonVisual(id)
        refreshSyncButtonState()
    }

    /**
     * ID変更を保留し、編集中データのキーと公開IDだけを先に更新します。
     * ストア上のファイル名は保存が確定するまで変更しません。
     */
    protected fun stageDataRename(oldId: String, newId: String) {
        val previous = pendingStoreOperations.remove(oldId)
        val operation = when (previous) {
            is PendingStoreOperation.Create -> PendingStoreOperation.Create(newId)
            is PendingStoreOperation.Rename -> PendingStoreOperation.Rename(previous.sourceId, newId)
            is PendingStoreOperation.Delete -> PendingStoreOperation.Delete(newId, previous.sourceId)
            null -> PendingStoreOperation.Rename(oldId, newId)
        }
        renameEditingCache(editingDataMap, oldId, newId)
        renameEditingCache(originalDataMap, oldId, newId)
        mergeConflicts.remove(oldId)?.let { mergeConflicts[newId] = it }
        editHistory.rename(oldId, newId)
        pendingStoreOperations[newId] = operation
        dataAccess.deleteLocalBackup(oldId)
        persistPendingStoreState()
        refreshSyncButtonState()
    }

    /**
     * 削除を保存時まで保留します。
     *
     * @return 未保存の追加を取り消して、その場で一覧から除外した場合は`true`。
     */
    protected fun stageDataDeletion(id: String): Boolean {
        return when (val previous = pendingStoreOperations[id]) {
            is PendingStoreOperation.Create -> {
                pendingStoreOperations.remove(id)
                discardLocalEditingData(id)
                persistPendingStoreState()
                refreshSyncButtonState()
                true
            }
            is PendingStoreOperation.Rename -> {
                pendingStoreOperations[id] = PendingStoreOperation.Delete(id, previous.sourceId)
                persistPendingStoreState()
                refreshButtonVisual(id)
                refreshSyncButtonState()
                false
            }
            is PendingStoreOperation.Delete -> false
            null -> {
                pendingStoreOperations[id] = PendingStoreOperation.Delete(id, id)
                persistPendingStoreState()
                refreshButtonVisual(id)
                refreshSyncButtonState()
                false
            }
        }
    }

    protected fun hasPendingStoreOperation(id: String): Boolean = id in pendingStoreOperations

    protected fun isPendingStoreDeletion(id: String): Boolean =
        pendingStoreOperations[id] is PendingStoreOperation.Delete

    /** 削除保留を取り消し、削除前にID変更があった場合はその保留状態へ戻します。 */
    protected fun cancelPendingStoreDeletion(id: String): Boolean {
        val deletion = pendingStoreOperations[id] as? PendingStoreOperation.Delete ?: return false
        if (deletion.sourceId == id) {
            pendingStoreOperations.remove(id)
        } else {
            pendingStoreOperations[id] = PendingStoreOperation.Rename(deletion.sourceId, id)
        }
        persistPendingStoreState()
        refreshButtonVisual(id)
        refreshSyncButtonState()
        return true
    }

    protected fun pendingOperationHasStoredSource(id: String): Boolean =
        when (val operation = pendingStoreOperations[id]) {
            is PendingStoreOperation.Rename -> operation.sourceId in remoteDataIds
            is PendingStoreOperation.Delete -> operation.sourceId in remoteDataIds
            is PendingStoreOperation.Create, null -> false
        }

    protected fun mergeSidebarIdsWithPending(remoteIds: Collection<String>): List<String> {
        val renamedSourceIds = pendingStoreOperations.values.mapNotNullTo(mutableSetOf()) { operation ->
            when (operation) {
                is PendingStoreOperation.Rename -> operation.sourceId
                is PendingStoreOperation.Delete -> operation.sourceId.takeIf { it != operation.currentId }
                is PendingStoreOperation.Create -> null
            }
        }
        return mergeSidebarIds(remoteIds.filterNot(renamedSourceIds::contains), editingDataMap.keys)
    }

    private fun renameEditingCache(cache: MutableMap<String, T>, oldId: String, newId: String) {
        cache.remove(oldId)?.let { data ->
            data.id = newId
            cache[newId] = data
        }
    }

    protected fun cancelOpen() {
        openCancelled = true
    }

    /**
     * ネットワーク切断など、アプリ側からエディタを強制終了して選択画面へ戻す安全な処理
     */
    protected fun handleForceBackToSelect() {
        logger.warn("ネットワーク切断または不正な状態を検知したため、エディタを強制終了します。")

        executeAutoSave()
        stopAutoSaveTimer()

        cancelOpen()
        dataService.forceBackToSelect()
    }

    /**
     * サイドバー内（コンテナ）のコンポーネント（主にアイテム選択ボタンなど）を構築します。
     * 必要に応じて、初期表示時に対象となるタブ（リソース）を自動で選択する処理もここに記述します。
     *
     * @param container ボタン群を配置するサイドバーの垂直レイアウトコンテナ
     * @param selectId 初期表示時に選択させたいアイテムのID。省略時（null）はデフォルトの挙動（先頭の要素を選択など）となります。
     */
    abstract fun setupSidebar(container: VBox, selectId: String? = null)

    /**
     * トップバー（コンテナ）に配置する、エディタ固有の共通アクションボタン（「保存」「新規作成」など）を構築します。
     *
     * @param container アクションボタンを水平に並べるためのトップレイアウトコンテナ
     */
    open fun setupActions(container: HBox) {
        saveMenuItem = actionMenuItem("保存", EditorShortcut.SAVE) { onSave() }
        saveAllMenuItem = actionMenuItem("すべて保存", EditorShortcut.SAVE_ALL) { onSaveAll() }

        syncMenuItem = actionMenuItem("同期", EditorShortcut.SYNC) { onSynchronizeSelected() }
        syncAllMenuItem = actionMenuItem("すべて同期", EditorShortcut.SYNC_ALL) { onSynchronizeAll() }

        val fileItems = mutableListOf<MenuItem>(
            saveMenuItem!!,
            saveAllMenuItem!!,
            syncMenuItem!!,
            syncAllMenuItem!!
        )
        fileItems += SeparatorMenuItem()
        fileItems += actionMenuItem("新規データ作成", EditorShortcut.CREATE_DATA) { handleCreateNewItem() }
        fileItems += actionMenuItem("新規ディレクトリ作成", EditorShortcut.CREATE_DIRECTORY) {
            handleCreateDirectory()
        }

        undoMenuItem = actionMenuItem("元に戻す", EditorShortcut.UNDO) { onUndo() }
        redoMenuItem = actionMenuItem("やり直す", EditorShortcut.REDO) { onRedo() }
        repairWarningsMenuItem = actionMenuItem("警告を修正", EditorShortcut.REPAIR_WARNING) {
            repairSelectedWarnings()
        }
        repairAllWarningsMenuItem = actionMenuItem("全警告を修正", EditorShortcut.REPAIR_ALL_WARNINGS) {
            repairAllWarnings()
        }
        repairErrorsMenuItem = actionMenuItem("エラーを修正", EditorShortcut.FOCUS_ERROR) {
            focusSelectedError()
        }
        repairAllErrorsMenuItem = actionMenuItem("全エラーを修正", EditorShortcut.FOCUS_ALL_ERRORS) {
            focusAllErrors()
        }

        val fileMenu = MenuButton("ファイル(F)").apply {
            styleClass.addAll("editor-menu-button", "editor-menu-button-first")
            isFocusTraversable = false
            maxHeight = Double.MAX_VALUE
            items.setAll(fileItems)
        }
        val editMenu = MenuButton("編集(E)").apply {
            styleClass.addAll("editor-menu-button", "editor-menu-button-last")
            isFocusTraversable = false
            maxHeight = Double.MAX_VALUE
            items.setAll(
                undoMenuItem,
                redoMenuItem,
                SeparatorMenuItem(),
                repairWarningsMenuItem,
                repairAllWarningsMenuItem,
                repairErrorsMenuItem,
                repairAllErrorsMenuItem
            )
        }
        container.children.setAll(fileMenu, editMenu)
        refreshActionMenuState()
    }

    private fun actionMenuItem(
        text: String,
        shortcut: EditorShortcut,
        action: () -> Unit
    ): MenuItem = MenuItem(text).apply {
        accelerator = shortcut.combination
        onAction = EventHandler { action() }
    }

    fun onSynchronizeSelected() {
        val id = currentSelectedDataId ?: return
        if (!dataService.isRemote || syncBusy || hasPendingStoreOperation(id)) return
        synchronizeSelected()
    }

    fun onSynchronizeAll() {
        if (!dataService.isRemote || syncBusy || pendingStoreOperations.isNotEmpty()) return
        synchronizeAll()
    }

    fun onCreateData() = handleCreateNewItem()

    fun onCreateDirectory() = handleCreateDirectory()

    fun repairSelectedWarnings() {
        currentSelectedDataId?.let { repairWarnings(listOf(it), confirmBatch = false) }
    }

    fun repairAllWarnings() = repairWarnings(editingDataMap.keys, confirmBatch = true)

    fun focusSelectedError() {
        currentSelectedDataId?.let { focusFirstError(listOf(it), confirmBatch = false) }
    }

    fun focusAllErrors() = focusFirstError(editingDataMap.keys, confirmBatch = true)

    /**
     * 指定された一意の識別子（IDやファイル名など）に対応するタブ（アイテム）を選択状態にします。
     * 内部的には、データのロード、メインコンテンツエリアの再描画、およびサイドバーボタンのハイライト更新などを行います。
     *
     * @param targetId 選択対象となるリソースの識別子（ID）
     */
    open fun selectTab(targetId: String) {
        if (isPendingStoreDeletion(targetId)) return
        this.currentSelectedDataId = targetId // 現在選択中のIDを更新

        val hasCache = editingDataMap.containsKey(targetId)
        val isUnchanged = hasCache && (originalDataMap[targetId] == editingDataMap[targetId])

        if (hasCache && hasPendingStoreOperation(targetId)) {
            // 保存前の追加・削除・ID変更はストア上の状態と一致しないため、編集キャッシュをそのまま使う。
        } else if (hasCache && dataService.isRemote) {
            mergeSelectedWithLatest(targetId)
        } else if (!hasCache || isUnchanged) {
            val (data, accessResult) = dataAccess.load(targetId)

            if (data == null) {
                handleLoadFailure(accessResult)
                return
            }

            editingDataMap[targetId] = data
            originalDataMap[targetId] = data.deepCopy()
        }

        selectButtonById(targetId)
        refreshSyncButtonState()
        editHistory.initialize(targetId, editingDataMap.getValue(targetId))
        setupMainContent(editingDataMap[targetId]!!)
    }

    private fun mergeSelectedWithLatest(targetId: String) {
        val base = originalDataMap[targetId] ?: return
        val local = editingDataMap[targetId] ?: return
        val (remote, accessResult) = dataAccess.load(targetId)

        /*
         * サーバーへ未保存のデータは、まだファイルが存在しないのが正常な状態である。
         * 同期の失敗として扱わず、ローカルの編集内容をそのまま表示する。
         */
        if (accessResult == LoadResult.FILE_NOT_FOUND) return

        if (remote == null || accessResult != LoadResult.SUCCESS) {
            main.showTimedTopLabel(
                "$targetId の自動同期に失敗しました。編集中データは維持されています。",
                Color.ORANGERED
            )
            return
        }

        val merge = dataAccess.merge(base, local, remote)
        editingDataMap[targetId] = merge.merged
        originalDataMap[targetId] = remote.deepCopy()
        if (remote != base) editHistory.reset(targetId, merge.merged)
        mergeConflicts[targetId] = merge.conflicts
        if (merge.merged != remote) {
            dataAccess.saveToLocalBackup(targetId, "editing", merge.merged)
            dataAccess.saveToLocalBackup(targetId, "original", remote)
        } else {
            dataAccess.deleteLocalBackup(targetId)
        }
    }

    /**
     * 現在編集中のデータを保存します。
     *
     * この処理は、保存対象データの取得、サーバーデータの読み込み、通信エラー処理、
     * YAML破損時の確認、最終保存、ローカルバックアップ削除、表示更新を共通で行います。
     *
     * リモート保存時はフィールド単位の三者間マージを行い、
     * 実際に競合したフィールドだけを確認します。
     *
     * @param targetDataId 保存対象のデータID。`null`の場合は現在選択中のデータを保存します。
     *
     * @return 保存成功時は`true`失敗またはキャンセル時は`false`を返却します。
     */
    fun onSave(targetDataId: String? = null): Boolean {
        val dataId = targetDataId ?: currentSelectedDataId ?: return false
        val prepared = prepareSave(dataId) ?: return false
        if (!confirmSaveChanges(
                prepared.dataId,
                prepared.operation,
                prepared.original,
                prepared.previewData
            )
        ) return false
        return persistPreparedSave(prepared)
    }

    private fun prepareSave(dataId: String): PreparedSave<T>? {
        val currentEdit = editingDataMap[dataId] ?: return null
        val original = originalDataMap[dataId] ?: return null
        val pendingOperation = pendingStoreOperations[dataId]
        val contentChanged = original != currentEdit

        if (!contentChanged && pendingOperation == null) return null

        if (pendingOperation is PendingStoreOperation.Delete) {
            return PreparedSave(dataId, pendingOperation, original, currentEdit, null)
        }

        val sourceId = (pendingOperation as? PendingStoreOperation.Rename)?.sourceId ?: dataId
        val saveData = prepareSaveData(dataId, sourceId, currentEdit, original) ?: return null
        return PreparedSave(dataId, pendingOperation, original, saveData, saveData)
    }

    private fun persistPreparedSave(prepared: PreparedSave<T>): Boolean {
        val deletion = prepared.operation as? PendingStoreOperation.Delete
        return if (deletion != null) {
            persistDelete(prepared.dataId, deletion)
        } else {
            persistSaveData(prepared.dataId, requireNotNull(prepared.saveData), prepared.operation)
        }
    }

    /** 現在メモリ上で変更されているすべてのデータを順番に保存します。 */
    fun onSaveAll(): Boolean {
        val targets = editingDataMap.keys.filter(::hasUnsavedChanges)
        if (targets.isEmpty()) return false

        val failed = mutableListOf<String>()
        val prepared = targets.mapNotNull { dataId ->
            prepareSave(dataId) ?: run {
                failed += dataId
                null
            }
        }
        if (prepared.isEmpty()) return false

        val details = combinedSaveChangeDetails(prepared.map { entry ->
            entry.dataId to saveChangeDetails(
                entry.dataId,
                entry.operation,
                entry.original,
                entry.previewData
            )
        }) + if (failed.isEmpty()) {
            emptyList()
        } else {
            listOf("", "【保存準備に失敗したデータ】", *failed.map { "・$it" }.toTypedArray())
        }
        val confirmed = CustomDialog.confirmation()
            .title("すべて保存")
            .header("以下の変更をまとめてストアへ反映します（${prepared.size} 件）")
            .content(details)
            .scrollableContent()
            .okButton("すべて保存", Color.DODGERBLUE)
            .owner(main.currentStage)
            .show()
        if (!confirmed) return false

        prepared.filterNot(::persistPreparedSave).mapTo(failed, PreparedSave<T>::dataId)
        val succeededCount = targets.size - failed.size
        if (failed.isEmpty()) {
            main.showTimedTopLabel("$succeededCount 件のデータを保存しました", Color.GREENYELLOW)
            return true
        }

        main.showTimedTopLabel(
            "$succeededCount 件を保存し、${failed.size} 件は未保存です",
            Color.ORANGE
        )
        return false
    }

    private fun hasUnsavedChanges(id: String): Boolean {
        val editing = editingDataMap[id] ?: return false
        val original = originalDataMap[id] ?: return false
        return editing != original || pendingStoreOperations[id] != null
    }

    private fun confirmSaveChanges(
        dataId: String,
        operation: PendingStoreOperation?,
        original: T,
        saveData: T
    ): Boolean {
        val details = saveChangeDetails(dataId, operation, original, saveData)
        if (details.isEmpty()) return false
        return CustomDialog.confirmation()
            .title("変更内容を保存")
            .header("以下の変更をストアへ反映します")
            .content(details)
            .scrollableContent()
            .okButton("保存", Color.DODGERBLUE)
            .owner(main.currentStage)
            .show()
    }

    private fun prepareSaveData(dataId: String, sourceId: String, currentEdit: T, original: T): T? {
        if (!dataService.isRemote) return currentEdit.deepCopy()
        val (serverData, accessResult) = dataAccess.load(sourceId)

        return when (accessResult) {
            LoadResult.FAILED,
            LoadResult.PROFILE_NOT_SELECTED,
            LoadResult.SFTP_INACTIVE -> {
                CustomDialog.error(ErrorType.INTERNAL_ERROR)
                    .content(listOf(
                        "ネットワークまたはその他の例外が発生しました。",
                        "選択画面へ戻ります。"
                    ))
                    .owner(main.currentStage)
                    .show()

                handleForceBackToSelect()
                null
            }

            LoadResult.FILE_NOT_FOUND -> {
                logger.info("サーバー上にファイルが存在しないため、新規ファイルとして保存します: $dataId")
                currentEdit.deepCopy()
            }

            LoadResult.INVALID_YAML -> {
                val confirmed = CustomDialog.confirmation()
                    .title("データ破損警告")
                    .header("サーバー上のYAMLデータが不正、または破損しています。")
                    .content("このまま保存すると、サーバー上の破損データは現在の編集内容で完全に上書きされます。強制保存しますか？")
                    .owner(main.currentStage)
                    .show()

                if (!confirmed) {
                    logger.info("サーバーデータのYAML破損のため、ユーザーが保存を中止しました。")
                    null
                } else {
                    currentEdit.deepCopy()
                }
            }

            LoadResult.SUCCESS -> {
                if (serverData == null) {
                    null
                } else {
                    val merge = dataAccess.merge(original, currentEdit, serverData)
                    mergeConflicts[dataId] = merge.conflicts
                    if (merge.conflicts.isEmpty()) {
                        merge.merged
                    } else {
                        val localPaths = MergeConflictDialog.show(
                            owner = main.currentStage,
                            dataId = dataId,
                            conflicts = merge.conflicts
                        ) ?: return null
                        merge.resolveWithLocal(localPaths)
                    }
                }
            }
        }
    }

    private fun persistSaveData(
        dataId: String,
        saveData: T,
        pendingOperation: PendingStoreOperation?
    ): Boolean {
        val normalizedSaveData = saveData.deepCopy().apply { id = dataId }
        if (pendingOperation is PendingStoreOperation.Rename) {
            when (dataAccess.renameTo(pendingOperation.sourceId, dataId)) {
                RenameResult.SUCCESS -> Unit
                RenameResult.FILE_NOT_FOUND -> {
                    CustomDialog.error(ErrorType.FILE_NOT_FOUND).owner(main.currentStage).show()
                    return false
                }
                RenameResult.ALREADY_EXISTS -> {
                    CustomDialog.error()
                        .title("保存エラー")
                        .header("変更先のIDが既に存在します")
                        .content("対象ID: $dataId")
                        .owner(main.currentStage)
                        .show()
                    return false
                }
                RenameResult.FAILED,
                RenameResult.PROFILE_NOT_SELECTED,
                RenameResult.SFTP_INACTIVE -> {
                    CustomDialog.error(ErrorType.NETWORK_ERROR).owner(main.currentStage).show()
                    return false
                }
            }
        }
        return when (val result = dataAccess.saveStore(dataId, normalizedSaveData)) {
            is StoreResult.Success -> {
                originalDataMap[dataId] = normalizedSaveData.deepCopy()
                editingDataMap[dataId] = normalizedSaveData.deepCopy()
                editHistory.reset(dataId, normalizedSaveData)
                mergeConflicts.remove(dataId)
                pendingStoreOperations.remove(dataId)
                persistPendingStoreState()
                refreshSyncButtonState()
                remoteDataIds = remoteDataIds.toMutableSet().apply {
                    if (pendingOperation is PendingStoreOperation.Rename) remove(pendingOperation.sourceId)
                    add(dataId)
                }

                (pendingOperation as? PendingStoreOperation.Rename)?.let { operation ->
                    dataAccess.deleteLocalBackup(operation.sourceId)
                }
                dataAccess.deleteLocalBackup(dataId)
                setupSidebar(main.sidebarContainer, dataId.takeIf { it == currentSelectedDataId })
                main.showTimedTopLabel("$dataId を保存しました", Color.GREENYELLOW)
                true
            }
            is StoreResult.Failure -> {
                if (pendingOperation is PendingStoreOperation.Rename) {
                    val rollbackResult = dataAccess.renameTo(dataId, pendingOperation.sourceId)
                    if (rollbackResult != RenameResult.SUCCESS) {
                        logger.error(
                            "保存失敗後にID変更を元へ戻せませんでした: currentId={}, sourceId={}, result={}",
                            dataId,
                            pendingOperation.sourceId,
                            rollbackResult
                        )
                    }
                }
                handleSaveFailure(result.error)
            }
        }
    }

    private fun persistDelete(dataId: String, operation: PendingStoreOperation.Delete): Boolean {
        return when (dataAccess.delete(operation.sourceId)) {
            DeleteResult.SUCCESS,
            DeleteResult.FILE_NOT_FOUND -> {
                pendingStoreOperations.remove(dataId)
                discardLocalEditingData(dataId)
                if (operation.sourceId != dataId) dataAccess.deleteLocalBackup(operation.sourceId)
                persistPendingStoreState()
                refreshSyncButtonState()
                remoteDataIds = remoteDataIds - operation.sourceId
                onPersistedDataDeleted(dataId)
                setupSidebar(main.sidebarContainer)
                main.showTimedTopLabel("${operation.sourceId} を削除しました", Color.GREENYELLOW)
                true
            }
            DeleteResult.FAILED,
            DeleteResult.PROFILE_NOT_SELECTED,
            DeleteResult.SFTP_INACTIVE -> {
                CustomDialog.error(ErrorType.NETWORK_ERROR).owner(main.currentStage).show()
                false
            }
        }
    }

    /** 保存が確定した削除に対する、データ種別固有のUIキャッシュ破棄処理です。 */
    protected open fun onPersistedDataDeleted(id: String) = Unit

    protected fun handleSaveFailure(error: StoreError): Boolean {
        logger.error(
            "{}保存に失敗しました: id={}, code={}, detail={}",
            dataAccess.displayName,
            error.dataId,
            error.code,
            error.detail,
            error.cause
        )

        when (error.code) {
            StoreErrorCode.VALIDATION_FAILED -> {
                CustomDialog.error()
                    .title("入力内容を保存できません")
                    .header("入力内容に問題があります。")
                    .content(error.detail ?: "入力内容を確認してください。")
                    .owner(main.currentStage)
                    .show()
            }

            StoreErrorCode.STORE_UNAVAILABLE,
            StoreErrorCode.PROFILE_NOT_SELECTED -> {
                CustomDialog.error(ErrorType.SFTP_ERROR)
                    .content(error.detail.orEmpty())
                    .owner(main.currentStage)
                    .show()
                handleForceBackToSelect()
            }

            else -> {
                CustomDialog.error(ErrorType.INTERNAL_ERROR)
                    .content(error.detail.orEmpty())
                    .owner(main.currentStage)
                    .show()
            }
        }
        return false
    }

    private fun handleLoadFailure(accessResult: LoadResult) {
        when (accessResult) {
            LoadResult.SUCCESS -> {
                CustomDialog.error(ErrorType.INTERNAL_ERROR)
                    .content("データが空（null）です。")
                    .owner(main.currentStage)
                    .show()
            }
            LoadResult.INVALID_YAML, LoadResult.FILE_NOT_FOUND -> {
                val errorType = if (accessResult == LoadResult.INVALID_YAML) {
                    ErrorType.INVALID_YAML
                } else {
                    ErrorType.FILE_NOT_FOUND
                }
                CustomDialog.error(errorType)
                    .content("データを再読み込みします...")
                    .owner(main.currentStage)
                    .show()
            }
            LoadResult.FAILED, LoadResult.PROFILE_NOT_SELECTED, LoadResult.SFTP_INACTIVE -> {
                CustomDialog.error(ErrorType.NETWORK_ERROR)
                    .owner(main.currentStage)
                    .show()
                handleForceBackToSelect()
            }
        }
    }

    /**
     * サーバー上のデータが、編集開始時点のオリジナルデータから変更されていた場合に呼び出されます。
     *
     * この処理はデータ型ごとに差分比較やマージ方法が異なるため、子クラスで実装します。
     *
     * 戻り値として保存に使う最終データを返します。
     * ユーザーが保存をキャンセルした場合は`null`を返します。
     *
     * @param dataId 保存対象のデータID。
     * @param originalData 編集開始時点、または最後に保存した時点のオリジナルデータ。
     * @param currentData 現在手元で編集中のデータ。
     * @param serverData サーバーから読み込んだ最新データ。
     * @return 保存に使用する最終データ。保存を中止する場合は`null`。
     */
    protected abstract fun resolveSaveConflict(
        dataId: String,
        originalData: T,
        currentData: T,
        serverData: T
    ): T?

    /**
     * エディタ（ウィンドウ）が閉じられる直前に呼び出される ライフサイクル関数です。
     *
     * このメソッドは、ユーザーがウィンドウの「×」ボタンを押した際や、システムによって
     * ウィンドウが閉じられる要求が発生した際にトリガーされます。
     * 子クラスでオーバーライドすることで、未保存チェックによる閉じる動作のキャンセルや、
     * メモリ解放のためのキャッシュクリアなどの後処理を実装できます。
     *
     * @return ウィンドウをそのまま閉じてよい場合は `true`、
     *         未保存データがあるなどの理由で閉じる動作を中断（キャンセル）したい場合は `false`。
     */
    open fun onClose(): Boolean {
        logger.info("アイテムエディタのクローズ処理を開始します。未保存の変更をローカルへ即時保存します。")

        // 通常の編集内容と保存待ちの構造変更をローカルへ退避する
        executeAutoSave()

        // 安全にタイマーを停止
        stopAutoSaveTimer()

        main.clearTopLabelTimer()
        main.clearShortcuts()

        editingDataMap.clear()
        originalDataMap.clear()
        mergeConflicts.clear()
        pendingStoreOperations.clear()
        editHistory.clear()
        sidebarButtons.clear()
        selectedButton = null
        currentSelectedDataId = null
        saveMenuItem = null
        saveAllMenuItem = null
        syncMenuItem = null
        syncAllMenuItem = null
        undoMenuItem = null
        redoMenuItem = null
        repairWarningsMenuItem = null
        repairAllWarningsMenuItem = null
        repairErrorsMenuItem = null
        repairAllErrorsMenuItem = null
        syncBusy = false

        return true
    }

    /**
     * 起動時に外側から自動保存バックアップ（新旧ペア）を注入するための関数
     */
    fun injectAutoSaveCaches(editingCaches: Map<String, T>, originalCaches: Map<String, T>) {
        if (editingCaches.isEmpty()) return

        logger.info("外部から ${editingCaches.size} 件の自動保存（新旧ペア）キャッシュが注入されました。")

        // 編集データとオリジナルデータを両方とも最初から完全に復元
        editingDataMap.putAll(editingCaches)
        originalDataMap.putAll(originalCaches)

        val storedOperations = dataAccess.loadPendingStoreOperations()
        val restoredOperations = storedOperations
            .mapNotNull { it.toPendingOperation() }
            .filter { it.currentId in editingCaches && it.currentId in originalCaches }
        pendingStoreOperations.putAll(restoredOperations.associateBy(PendingStoreOperation::currentId))
        if (restoredOperations.size != storedOperations.size) persistPendingStoreState()

        restoredCacheCount = editingCaches.size
    }

    protected abstract fun setupMainContent(selectData: T)

    /**
     * 指定したIDのボタンを選択（アクティブ）状態に切り替える
     */
    protected fun selectButtonById(id: String) {
        val target = sidebarButtons[id] ?: return
        val previousButton = selectedButton

        selectedButton = target

        if (previousButton != null) {
            refreshButtonVisual(previousButton.id ?: "")
        }

        refreshButtonVisual(id)
    }

    /** 指定したデータIDの選択、変更、検証状態をサイドバーへ反映します。 */
    protected open fun refreshButtonVisual(id: String) {
        editingDataMap[id]?.let { data ->
            editHistory.initialize(id, data)
            if (main.mainContentContainer.scene?.focusOwner !is TextInputControl) {
                recordHistorySnapshot(id)
            }
        }
        val btn = sidebarButtons[id] ?: return
        val data = editingDataMap[id]
        val pendingDeletion = isPendingStoreDeletion(id)
        val validationErrors = if (pendingDeletion) emptyList() else data?.let(::validationErrors).orEmpty()
        val state = SidebarDataState(
            selected = btn == selectedButton,
            modified = data != originalDataMap[id] || hasPendingStoreOperation(id),
            hasWarnings = validationErrors.any(SushiEricValidationError::isWarning),
            hasErrors = validationErrors.any(SushiEricValidationError::isError),
            localOnly = id !in remoteDataIds,
            pendingDeletion = pendingDeletion
        )

        btn.styleClass.removeAll(SidebarDataState.STYLE_CLASSES)
        btn.styleClass.addAll(state.styleClasses)
        btn.text = ""
        btn.graphic = HBox(5.0).apply {
            if (state.pendingDeletion) {
                children += Label("－").apply { styleClass.add("sidebar-pending-deletion-mark") }
            } else if (state.localOnly) {
                children += Label("＋").apply { styleClass.add("sidebar-local-only-mark") }
            }
            children += Label(
                btn.properties[TreeSidebarRenderer.DISPLAY_NAME_KEY] as? String
                    ?: PublicId.normalizeForLoad(id)
            ).apply {
                styleClass.add("sidebar-data-name")
                if (state.modified) styleClass.add("sidebar-data-modified")
            }
            if (state.hasErrors) children += Label("⚠").apply { styleClass.add("sidebar-error-mark") }
            if (state.hasWarnings) children += Label("⚠").apply { styleClass.add("sidebar-warning-mark") }
        }
        btn.accessibleText = listOfNotNull(PublicId.normalizeForLoad(id), state.description())
            .joinToString(" / ")
        btn.tooltip = state.description()?.let(AppTooltip::create)
        refreshActionMenuState()
    }

    /** フォーカス状態にかかわらず、現在の編集内容を履歴へ記録します。 */
    protected fun recordHistorySnapshot(id: String) {
        editingDataMap[id]?.let { data ->
            editHistory.initialize(id, data)
            editHistory.record(id, data)
        }
    }

    /** 現在選択中のデータを1段階前の編集状態へ戻します。 */
    fun onUndo(): Boolean {
        val id = currentSelectedDataId ?: return false
        val current = editingDataMap[id] ?: return false
        editHistory.record(id, current)
        val restored = editHistory.undo(id, current) ?: return false
        applyHistoryData(id, restored, "元に戻しました")
        return true
    }

    /** 現在選択中のデータで、直前に元へ戻した編集をやり直します。 */
    fun onRedo(): Boolean {
        val id = currentSelectedDataId ?: return false
        val current = editingDataMap[id] ?: return false
        editHistory.record(id, current)
        val restored = editHistory.redo(id, current) ?: return false
        applyHistoryData(id, restored, "やり直しました")
        return true
    }

    private fun applyHistoryData(id: String, restored: T, notification: String) {
        editingDataMap[id] = restored
        onHistoryDataRestored(id)
        setupMainContent(restored)
        refreshButtonVisual(id)
        main.showTimedTopLabel("$id: $notification", Color.GREENYELLOW)
    }

    /** 履歴から復元する前に、データ種別固有のUIキャッシュを破棄します。 */
    protected open fun onHistoryDataRestored(id: String) = Unit

    /** サイドバーの重大度別修正項目をまとめて保持します。 */
    protected data class ValidationContextMenuItems(
        val repairWarnings: MenuItem,
        val repairErrors: MenuItem
    ) {
        val all: List<MenuItem>
            get() = listOf(repairWarnings, repairErrors)
    }

    /** 指定データ用の警告・エラー修正メニューを生成します。 */
    protected fun createValidationContextMenuItems(id: String): ValidationContextMenuItems =
        ValidationContextMenuItems(
            repairWarnings = MenuItem("警告を修正").apply {
                onAction = EventHandler { repairWarnings(listOf(id), confirmBatch = false) }
            },
            repairErrors = MenuItem("エラーを修正").apply {
                onAction = EventHandler { focusFirstError(listOf(id), confirmBatch = false) }
            }
        ).also { refreshValidationContextMenuItems(id, it) }

    /** メニューを開く時点の検証結果に合わせて操作可否を更新します。 */
    protected fun refreshValidationContextMenuItems(
        id: String,
        items: ValidationContextMenuItems
    ) {
        val errors = editingDataMap[id]?.let(::validationErrors).orEmpty()
        items.repairWarnings.isDisable = errors.none { it.isWarning }
        items.repairErrors.isDisable = errors.none { it.isError }
    }

    /** 現在の参照アイテム集合を使って検証結果を取得します。 */
    protected fun validationErrors(data: T): List<SushiEricValidationError> =
        dataAccess.validationErrors(data, availableItemInternalIds())

    /**
     * エラーに対応する入力UIへ移動します。
     *
     * データ固有の画面構造を共通基盤へ持ち込まないため、各エディターが実装します。
     */
    protected open fun focusValidationError(error: SushiEricValidationError): Boolean = false

    private fun refreshActionMenuState() {
        val selectedId = currentSelectedDataId
        val selectedErrors = selectedId
            ?.let(editingDataMap::get)
            ?.let(::validationErrors)
            .orEmpty()
        val allErrors = editingDataMap.values.flatMap(::validationErrors)

        saveMenuItem?.isDisable = selectedId == null || !hasUnsavedChanges(selectedId)
        saveAllMenuItem?.isDisable = editingDataMap.keys.none(::hasUnsavedChanges)
        undoMenuItem?.isDisable = selectedId == null || !editHistory.canUndo(selectedId)
        redoMenuItem?.isDisable = selectedId == null || !editHistory.canRedo(selectedId)
        repairWarningsMenuItem?.isDisable = selectedErrors.none { it.isWarning }
        repairErrorsMenuItem?.isDisable = selectedErrors.none { it.isError }
        repairAllWarningsMenuItem?.isDisable = allErrors.none { it.isWarning }
        repairAllErrorsMenuItem?.isDisable = allErrors.none { it.isError }

        syncMenuItem?.isDisable = !dataService.isRemote || syncBusy || selectedId == null ||
            selectedId?.let(::hasPendingStoreOperation) == true
        syncAllMenuItem?.isDisable = !dataService.isRemote || syncBusy || pendingStoreOperations.isNotEmpty()
    }

    private fun repairWarnings(ids: Collection<String>, confirmBatch: Boolean) {
        val targets = ids.mapNotNull { id ->
            val data = editingDataMap[id] ?: return@mapNotNull null
            val warnings = validationErrors(data).filter { it.isWarning }
            warnings.takeIf { it.isNotEmpty() }?.let { Triple(id, data.deepCopy(), it) }
        }
        if (targets.isEmpty()) return

        val supportedDescriptions = targets.flatMap { (id, _, warnings) ->
            warnings.mapNotNull { warning ->
                validationRepairRegistry.description(warning)?.let { "$id: $it" }
            }
        }
        if (confirmBatch) {
            val confirmed = CustomDialog.confirmation()
                .title("全警告の修正")
                .header("${targets.size} 件のデータにある警告を修正します")
                .content(
                    listOf(
                        "修正可能な警告: ${supportedDescriptions.size} 件",
                        "変更は未保存状態として反映し、自動でサーバーへ保存しません。"
                    ) + supportedDescriptions.take(8)
                )
                .owner(main.currentStage)
                .show()
            if (!confirmed) return
        }

        val task = object : Task<WarningRepairBatch<T>>() {
            override fun call(): WarningRepairBatch<T> {
                val updated = mutableMapOf<String, T>()
                val applied = mutableListOf<String>()
                val failures = mutableListOf<String>()

                targets.forEach { (id, data, warnings) ->
                    var changed = false
                    warnings.sortedByDescending { (it.key as? Int) ?: Int.MIN_VALUE }
                        .forEach { warning ->
                            when (val result = validationRepairRegistry.repair(data, warning)) {
                                is ValidationRepairResult.Applied -> {
                                    changed = true
                                    applied += "$id: ${result.description}"
                                }
                                is ValidationRepairResult.Failed ->
                                    failures += "$id: ${result.reason}"
                                ValidationRepairResult.Unsupported ->
                                    failures += "$id: 自動修正に対応していません (${warning.message})"
                            }
                        }
                    if (changed) updated[id] = data
                }
                return WarningRepairBatch(updated, applied, failures)
            }
        }
        task.setOnSucceeded {
            val result = task.value
            result.updated.forEach { (id, data) -> editingDataMap[id] = data }
            result.updated.keys.forEach(::refreshButtonVisual)
            currentSelectedDataId
                ?.takeIf(result.updated::containsKey)
                ?.let { setupMainContent(editingDataMap.getValue(it)) }
            if (result.updated.isNotEmpty()) executeAutoSave()

            if (result.failures.isNotEmpty()) {
                CustomDialog.error()
                    .title("警告を修正できませんでした")
                    .header("失敗した項目: ${result.failures.size} 件")
                    .content(result.failures)
                    .owner(main.currentStage)
                    .show()
            } else if (result.applied.isNotEmpty()) {
                main.showTimedTopLabel("${result.applied.size} 件の警告を修正しました", Color.GREENYELLOW)
            }
        }
        task.setOnFailed {
            logger.error("警告の修正中に例外が発生しました", task.exception)
            CustomDialog.error(ErrorType.INTERNAL_ERROR)
                .content("警告の修正中に例外が発生しました。")
                .owner(main.currentStage)
                .show()
        }
        Thread(task, "editor-warning-repair-${dataAccess.dataType.categoryDirName}").apply {
            isDaemon = true
            start()
        }
    }

    private fun focusFirstError(ids: Collection<String>, confirmBatch: Boolean) {
        val targets = ids.flatMap { id ->
            editingDataMap[id]
                ?.let(::validationErrors)
                .orEmpty()
                .filter { it.isError }
                .map { id to it }
        }
        val (id, error) = targets.firstOrNull() ?: return

        if (confirmBatch) {
            val confirmed = CustomDialog.confirmation()
                .title("全エラーの修正")
                .header("${targets.size} 件のエラーがあります")
                .content(
                    listOf(
                        "先頭のエラーがある入力欄へ移動します。",
                        "修正後に再度実行すると、次のエラーへ移動できます。"
                    ) + targets.take(8).map { (targetId, targetError) ->
                        "$targetId: ${targetError.message}"
                    }
                )
                .owner(main.currentStage)
                .show()
            if (!confirmed) return
        }

        if (currentSelectedDataId != id) selectTab(id)
        Platform.runLater {
            if (!focusValidationError(error)) {
                main.showTimedTopLabel("対象: $id / ${error.message}", Color.ORANGE, 5.0)
            }
        }
    }

    private data class WarningRepairBatch<T>(
        val updated: Map<String, T>,
        val applied: List<String>,
        val failures: List<String>
    )

    /**
     * 未選択データも検証状態を表示できるよう、未読込データをバックグラウンドで取得します。
     *
     * 既に編集キャッシュが存在するデータは上書きしません。
     */
    protected fun preloadSidebarDataForVisualStates(ids: Collection<String>) {
        val generation = ++sidebarPreloadGeneration
        val missingIds = ids.filterNot(editingDataMap::containsKey)
        if (missingIds.isEmpty()) return

        val task = object : Task<Map<String, T>>() {
            override fun call(): Map<String, T> = buildMap {
                missingIds.forEach { id ->
                    when (val result = dataAccess.loadStore(id)) {
                        is StoreResult.Success -> put(id, result.value)
                        is StoreResult.Failure -> logger.warn(
                            "サイドバー状態確認用データを読み込めませんでした: id={}, code={}, detail={}",
                            id,
                            result.error.code,
                            result.error.detail
                        )
                    }
                }
            }
        }
        task.setOnSucceeded {
            if (generation != sidebarPreloadGeneration) return@setOnSucceeded
            task.value.forEach { (id, data) ->
                if (id !in sidebarButtons || id in editingDataMap) return@forEach
                editingDataMap[id] = data.deepCopy()
                originalDataMap[id] = data.deepCopy()
                refreshButtonVisual(id)
            }
        }
        task.setOnFailed {
            logger.error("サイドバー状態確認用データの読み込み中に例外が発生しました", task.exception)
        }
        Thread(task, "editor-sidebar-state-${dataAccess.dataType.categoryDirName}").apply {
            isDaemon = true
            start()
        }
    }

    /** 現在の検証で参照可能なアイテム内部IDを返します。 */
    protected open fun availableItemInternalIds(): Set<ItemInternalId> =
        editingDataMap.values
            .filterIsInstance<MutableItemBaseData>()
            .mapTo(mutableSetOf()) { item -> item.internalId }

    /** 内容変更と保存待ちの構造変更をローカルに自動保存します。 */
    protected fun executeAutoSave() {
        val changedData = editingDataMap.filter { (id, data) ->
            hasPendingStoreOperation(id) || data != originalDataMap[id]
        }
        if (changedData.isEmpty()) {
            persistPendingStoreState()
            return
        }

        logger.info("【自動保存】未保存の変更を検知しました（${changedData.size} 件）。ローカルキャッシュを更新します。")

        changedData.forEach { (id, currentData) ->
            // 編集中の最新データを保存
            dataAccess.saveToLocalBackup(id, "editing", currentData)

            // ベースとなったオリジナルを保存
            val originalData = originalDataMap[id]
            if (originalData != null) {
                dataAccess.saveToLocalBackup(id, "original", originalData)
            }
        }

        persistPendingStoreState()

        main.showTimedTopLabel("${changedData.size} 件の項目を自動バックアップしました。", Color.GREENYELLOW)
    }

    private fun persistPendingStoreState() {
        pendingStoreOperations.keys.forEach { id ->
            val editing = editingDataMap[id] ?: return@forEach
            val original = originalDataMap[id] ?: return@forEach
            dataAccess.saveToLocalBackup(id, "editing", editing)
            dataAccess.saveToLocalBackup(id, "original", original)
        }
        dataAccess.savePendingStoreOperations(pendingStoreOperations.values.map(PendingStoreOperation::toRecord))
    }

    /**
     * 自動保存タイマーを開始する
     */
    protected fun startAutoSaveTimer() {
        if (autoSaveTimeline != null) return // 二重起動防止

        autoSaveTimeline = Timeline(
            KeyFrame(Duration.minutes(3.0), { // 3分ごとにチェック
                executeAutoSave()
            })
        ).apply {
            cycleCount = Animation.INDEFINITE
            play()
        }
        logger.info("自動保存タイマーを開始しました（3分間隔）")
    }

    /**
     * 自動保存タイマーを停止する
     */
    protected fun stopAutoSaveTimer() {
        autoSaveTimeline?.stop()
        autoSaveTimeline = null
        logger.info("自動保存タイマーを停止しました")
    }

    private fun synchronizeSelected() {
        val dataId = currentSelectedDataId ?: return
        if (hasPendingStoreOperation(dataId)) return
        val hasUnsavedChanges = editingDataMap[dataId] != originalDataMap[dataId]
        if (hasUnsavedChanges) {
            val confirmed = CustomDialog.confirmation()
                .title("同期の確認")
                .header("$dataId の未保存変更を破棄しますか？")
                .content("サーバー上の最新データで編集中データを完全に置き換えます。")
                .owner(main.currentStage)
                .show()
            if (!confirmed) return
        }

        setSyncBusy(true)
        val task = object : Task<StoreResult<T>>() {
            override fun call(): StoreResult<T> = syncService.fetchOne(dataId)
        }
        task.setOnSucceeded {
            setSyncBusy(false)
            when (val result = task.value) {
                is StoreResult.Success -> {
                    val latest = result.value
                    editingDataMap[dataId] = latest.deepCopy()
                    originalDataMap[dataId] = latest.deepCopy()
                    editHistory.reset(dataId, latest)
                    mergeConflicts.remove(dataId)
                    dataAccess.deleteLocalBackup(dataId)
                    setupMainContent(editingDataMap.getValue(dataId))
                    refreshButtonVisual(dataId)
                    main.showTimedTopLabel("$dataId を同期しました", Color.GREENYELLOW)
                }
                is StoreResult.Failure -> showSyncFailure(dataId, result)
                null -> main.showTimedTopLabel("$dataId の同期に失敗しました", Color.ORANGERED)
            }
        }
        task.setOnFailed {
            setSyncBusy(false)
            logger.error("$dataId の同期に失敗しました", task.exception)
            main.showTimedTopLabel("$dataId の同期に失敗しました", Color.ORANGERED)
        }
        Thread(task, "editor-sync-$dataId").apply {
            isDaemon = true
            start()
        }
    }

    private fun synchronizeAll() {
        if (pendingStoreOperations.isNotEmpty()) return
        setSyncBusy(true)
        val task = object : Task<StoreResult<Map<String, T>>>() {
            override fun call(): StoreResult<Map<String, T>> = syncService.fetchAll()
        }
        task.setOnSucceeded {
            setSyncBusy(false)
            when (val result = task.value) {
                is StoreResult.Failure -> showSyncFailure(dataAccess.displayName, result)
                is StoreResult.Success -> confirmAndApplyAllSync(result.value)
                null -> main.showTimedTopLabel("すべて同期に失敗しました", Color.ORANGERED)
            }
        }
        task.setOnFailed {
            setSyncBusy(false)
            logger.error("${dataAccess.displayName}の全同期に失敗しました", task.exception)
            main.showTimedTopLabel("すべて同期に失敗しました", Color.ORANGERED)
        }
        Thread(task, "editor-sync-all-${dataAccess.dataType.categoryDirName}").apply {
            isDaemon = true
            start()
        }
    }

    private fun confirmAndApplyAllSync(remoteData: Map<String, T>) {
        val unsavedCount = editingDataMap.count { (id, data) -> data != originalDataMap[id] }
        val missingCacheCount = editingDataMap.keys.count { it !in remoteData }
        val confirmed = CustomDialog.confirmation()
            .title("すべて同期の確認")
            .header("${dataAccess.displayName}データ ${remoteData.size} 件をサーバーと同じ状態にします")
            .content(
                listOf(
                    "破棄される未保存変更: $unsavedCount 件",
                    "サーバーに存在しないオンラインキャッシュ: $missingCacheCount 件",
                    "この操作は元に戻せません。",
                    "オフラインデータには影響しません。"
                )
            )
            .owner(main.currentStage)
            .show()
        if (!confirmed) return

        val previousSelection = currentSelectedDataId
        editingDataMap.clear()
        originalDataMap.clear()
        editHistory.clear()
        remoteData.forEach { (id, data) ->
            editingDataMap[id] = data.deepCopy()
            originalDataMap[id] = data.deepCopy()
            editHistory.reset(id, data)
        }
        mergeConflicts.clear()
        dataAccess.clearLocalBackupsExcept()
        setupSidebar(main.sidebarContainer, previousSelection?.takeIf { it in remoteData })
        main.showTimedTopLabel(
            "${dataAccess.displayName}データ ${remoteData.size} 件を同期しました",
            Color.GREENYELLOW
        )
    }

    private fun setSyncBusy(busy: Boolean) {
        syncBusy = busy
        refreshActionMenuState()
    }

    private fun refreshSyncButtonState() = setSyncBusy(false)

    private fun showSyncFailure(target: String, failure: StoreResult.Failure) {
        logger.error(
            "{}の同期に失敗しました: code={}, detail={}",
            target,
            failure.error.code,
            failure.error.detail,
            failure.error.cause
        )
        CustomDialog.error()
            .title("同期エラー")
            .header("$target を同期できませんでした")
            .content("${failure.error.code}: ${failure.error.detail.orEmpty()}\n編集中データは維持されています。")
            .owner(main.currentStage)
            .show()
    }

    /**
     * 新しい管理データを作成し、保存待ちの状態でサイドバーに追加します。
     *
     * 新規データの実体は、このエディタが保持している[dataAccess]の[SushiEricDataType]から生成します。
     * そのため、ItemやOreなどの具体型に依存せず、
     * 共通の新規作成処理として利用できます。
     */
    protected open fun handleCreateNewItem() = handleCreateNewItem("")

    /** ルートから完全IDを指定してディレクトリを作成します。 */
    protected open fun handleCreateDirectory() {
        val directory = main.requestInput("ディレクトリを作成") { input ->
            when {
                input.isBlank() -> ValidationResult.Error("名前を入力してください")
                !PublicId.isValidFull(input) -> ValidationResult.Error(PublicId.DESCRIPTION)
                else -> ValidationResult.Success
            }
        } ?: return
        when (dataAccess.createDirectory(directory)) {
            is StoreResult.Success -> setupSidebar(main.sidebarContainer, currentSelectedDataId)
            is StoreResult.Failure -> CustomDialog.error()
                .title("ディレクトリ作成エラー")
                .header("ディレクトリを作成できませんでした")
                .owner(main.currentStage)
                .show()
        }
    }

    /** 指定したディレクトリへ新しい管理データを作成します。 */
    protected fun handleCreateNewItem(directory: String) {
        val (fileResources, isSuccess) = dataAccess.listYmlResources()
        if (!isSuccess) {
            CustomDialog.error()
                .title("取得失敗")
                .header("ファイルリストの取得に失敗しました。")
                .owner(main.currentStage)
                .show()
            handleForceBackToSelect()
            return
        }

        val inputText = main.requestInput("${dataAccess.displayName}を追加") { input ->
            val containsInvalidChar = !(if (directory.isEmpty()) {
                PublicId.isValidFull(input)
            } else {
                PublicId.isValid(input)
            })
            val fullId = PublicId.join(
                directory.split('.').filter(String::isNotEmpty),
                input
            )
            val isDuplicate = fileResources.any { it.name == "$fullId.yml" }
            when {
                input.isBlank() -> ValidationResult.Error("名前を入力してください")
                containsInvalidChar -> ValidationResult.Error(PublicId.DESCRIPTION)
                isDuplicate -> ValidationResult.Error("重複した名称です")
                else -> ValidationResult.Success
            }
        }

        if (inputText != null) {
            val fullId = PublicId.join(
                directory.split('.').filter(String::isNotEmpty),
                inputText
            )
            val data = prepareNewData(dataAccess.createDefault(fullId))
            editingDataMap[fullId] = data
            originalDataMap[fullId] = data.deepCopy()
            stageDataCreation(fullId)
            setupSidebar(main.sidebarContainer, fullId)
            main.showTimedTopLabel("$fullId の追加を保留しました。保存すると反映されます", Color.GREENYELLOW)
        }
    }

    /** 新規データを初回保存できる初期状態へ調整します。 */
    protected open fun prepareNewData(data: T): T = data
}
