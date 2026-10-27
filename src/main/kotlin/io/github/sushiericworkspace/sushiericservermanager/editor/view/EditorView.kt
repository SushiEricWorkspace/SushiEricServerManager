package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.PublicId
import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericdatamodel.data.ore.model.OreBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.data.core.validation.SushiEricValidationError
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.config.AppSettingsManager
import io.github.sushiericworkspace.sushiericservermanager.editor.result.ValidationResult
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.LoadResult
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.RenameResult
import io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe.recipeDataReview
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeDataView
import io.github.sushiericworkspace.sushiericservermanager.editor.service.ItemReferenceStatus
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.SavedUploadAction
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.SavedUploadSelection
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.SavedUploadPublication
import javafx.collections.SetChangeListener
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.LocalDataUploader
import io.github.sushiericworkspace.sushiericservermanager.editor.upload.UploadDataCategory
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorSyncService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorSyncSnapshot
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.ThreeWayMergeResult
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptors
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResource
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.DataConflict
import io.github.sushiericworkspace.sushiericservermanager.editor.history.EditorDataHistory
import io.github.sushiericworkspace.sushiericservermanager.editor.history.shouldCommitHistoryOnKeyPress
import io.github.sushiericworkspace.sushiericservermanager.editor.history.shouldCommitHistoryOnMousePress
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.validation.ValidationRepairRegistry
import io.github.sushiericworkspace.sushiericservermanager.editor.validation.ValidationRepairResult
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.ErrorType
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.MergeConflictDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.ShortcutManager
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.ShortcutScope
import io.github.sushiericworkspace.sushiericservermanager.ui.shortcut.EditorShortcut
import io.github.sushiericworkspace.sushiericdatamodel.data.core.SushiEricDataType
import javafx.animation.Animation
import javafx.animation.PauseTransition
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalBackupSnapshot
import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentSnapshot
import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentVersion
import io.github.sushiericworkspace.sushiericservermanager.editor.service.PendingStoreOperationKind
import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentRecord
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentKey
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentSource
import java.util.concurrent.atomic.AtomicBoolean
import javafx.application.Platform
import javafx.event.EventHandler
import javafx.scene.Scene
import javafx.scene.Node
import io.github.sushiericworkspace.sushiericservermanager.config.IdTabsState
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.IdTabStrip
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceManager
import javafx.concurrent.Task
import javafx.beans.value.ChangeListener
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.CheckMenuItem
import javafx.scene.control.ChoiceDialog
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.app.NotificationState
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.TextInputControl
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
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
    dataService: EditorDataService,
    dataAccess: EditorDataService.DataAccess<T>
) {
    private val connectedService = dataService
    protected var dataService: EditorDataService = dataService.localService
        private set
    protected var dataAccess: EditorDataService.DataAccess<T> = this.dataService.documentAccess(dataAccess.descriptor)
        private set
    private data class SourceState<D>(
        val editing: MutableMap<String, D> = mutableMapOf(),
        val original: MutableMap<String, D> = mutableMapOf(),
        val saved: MutableMap<String, D> = mutableMapOf(),
        val pending: MutableMap<String, PendingStoreOperation> = mutableMapOf(),
        val conflicts: MutableMap<String, List<DataConflict>> = mutableMapOf(),
        val removed: MutableSet<String> = mutableSetOf(),
        val expanded: MutableMap<String, Boolean> = mutableMapOf(),
        var selected: String? = null,
        var tabs: IdTabsState = IdTabsState(),
        var tabsRestored: Boolean = false,
        var remoteIds: Set<String> = emptySet()
    )
    private val sourceStates = mutableMapOf<EditorDocumentSource, SourceState<T>>()
    private val sourceState get() = sourceStates.getOrPut(this.dataService.documentSource) {
        SourceState(expanded = AppSettingsManager.load().sidebarDirectoryExpanded[sidebarStateKey].orEmpty().toMutableMap())
    }
    private data class ExternalDocument(val key: EditorDocumentKey, val ready: (Boolean) -> Unit)
    private var externalDocument: ExternalDocument? = null

    /** 左右一覧から同じViewの保存元別編集状態へ移動します。読み込み成功後にだけ通知します。 */
    internal fun openDocument(key: EditorDocumentKey, ready: (Boolean) -> Unit = {}) {
        if (closed || closing || saveRunning || main.operationRunning || syncBusy || repairBusy || importBusy || switchingSource || externalDocument != null) { ready(false); return }
        if (key.source != EditorDocumentSource.Local && key.source != connectedService.documentSource) { ready(false); return }
        externalDocument = ExternalDocument(key, ready)
        val next = if (key.source == connectedService.documentSource) connectedService else connectedService.localService
        if (next !== dataService) {
            if (!main.loadingRunning && !restoringBackup && !switchSource(next)) {
                externalDocument = null
                ready(false)
            }
        } else if (!restoringBackup && !main.loadingRunning) {
            if (key.id !in registeredSidebarIds || !selectTab(key.id)) { externalDocument = null; ready(false) }
        }
    }

    internal fun openDocumentIds(source: EditorDocumentSource, category: String): Set<String> {
        if (category != dataAccess.dataType.categoryDirName) return emptySet()
        val state = sourceStates[source] ?: return emptySet()
        return state.editing.keys.filterTo(mutableSetOf()) { it !in state.removed && state.pending[it] !is PendingStoreOperation.Delete }
    }

    private fun refreshWorkspaceDocuments() {
        WorkspaceManager.current?.takeIf { it.feature(main.shortcutRoot) != null }?.refreshDocuments()
    }

    private var switchingSource = false
    private var initialSourceResolved = false
    private val sourceMenu = MenuButton("ローカル").apply {
        items.add(MenuItem("ローカル").apply { setOnAction { switchSource(connectedService.localService) } })
        if (connectedService.isRemote) items.add(MenuItem("サーバーの作業コピー").apply {
            setOnAction { switchSource(connectedService) }
        })
        if (connectedService.isRemote) items.add(CheckMenuItem("編集用一覧を表示").apply {
            isSelected = main.sidebarContainer.isVisible
            setOnAction { main.sidebarContainer.isVisible = isSelected; main.sidebarContainer.isManaged = isSelected }
        })
        items.add(SeparatorMenuItem())
        items.add(MenuItem("旧退避から復元…").apply { setOnAction { restoreLegacyBackup() } })
    }

    private data class PreparedSave<D : ManagedData<D, *>>(
        val dataId: String,
        val operation: PendingStoreOperation?,
        val original: D,
        val localData: D,
        val automaticallyMergedData: D?,
        val previewData: D,
        val saveData: D?,
        val raw: Map<String, String> = emptyMap(),
        val versions: Map<String, LocalDocumentVersion> = emptyMap()
    )

    private data class SavedCheckpoint<D>(val data: D?, val document: LocalDocumentRecord)

    private data class SaveDataPreview<D>(
        val data: D,
        val automaticallyMergedData: D?
    )

    /** サイドバー表示方式。必要なエディターは従来の平坦表示へ切り替えられます。 */
    protected open val sidebarDisplayMode: SidebarDisplayMode = SidebarDisplayMode.TREE
    private val sidebarStateKey get() = "${dataService.cacheIdentity}:${dataAccess.dataType.categoryDirName}"
    protected val sidebarExpandedState: MutableMap<String, Boolean> get() = sourceState.expanded

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
    protected var currentSelectedDataId: String?
        get() = sourceState.selected
        set(value) { sourceState.selected = value }

    protected val registeredSidebarIds = mutableSetOf<String>()
    private val sidebarStates = mutableMapOf<String, SidebarDataState>()
    private var sidebarView: EditorSidebar? = null
    private val sidebarValidation = mutableMapOf<String, SidebarValidationStatus>()
    private var sidebarProgress: (() -> Unit)? = null

    internal fun registerSidebar(ids: Collection<String>, view: EditorSidebar) {
        registeredSidebarIds.clear()
        registeredSidebarIds.addAll(ids)
        sidebarStates.clear()
        sidebarValidation.keys.retainAll(ids.toSet())
        sidebarView = view
        ids.forEach { id ->
            sidebarStates[id] = SidebarDataState(
                selected = id == currentSelectedDataId,
                modified = false, hasWarnings = false, hasErrors = false,
                localOnly = id !in remoteDataIds, pendingDeletion = isPendingStoreDeletion(id),
                validationStatus = if (id in editingDataMap) SidebarValidationStatus.CHECKED
                    else sidebarValidation[id] ?: SidebarValidationStatus.UNCHECKED
            )
        }
    }

    internal fun sidebarState(id: String): SidebarDataState = sidebarStates.getValue(id)

    protected val logger: Logger = LoggerFactory.getLogger(javaClass)

    // ID（String）をキーにしたキャッシュMap
    protected val editingDataMap get() = sourceState.editing
    protected val originalDataMap get() = sourceState.original
    private var editGeneration = 0L
    private val inputDrafts = mutableMapOf<EditorDocumentKey, MutableMap<String, String>>()
    private val savedInputDrafts = mutableMapOf<EditorDocumentKey, Map<String, String>>()
    private val localDocumentStates = mutableMapOf<EditorDocumentKey, LocalDocumentRecord>()
    private val documentVersions = mutableMapOf<EditorDocumentKey, LocalDocumentVersion>()

    private fun versions(ids: Collection<String>): Map<String, LocalDocumentVersion> = ids.associateWith { id ->
        val key = documentKey(id)
        documentVersions.getOrPut(key) {
            LocalDocumentVersion(key, localDocumentStates[key]?.takeIf { it.key == key }?.revision ?: 0L)
        }
    }

    private fun observeDocument(record: LocalDocumentRecord) {
        localDocumentStates[record.key] = record
        documentVersions[record.key] = LocalDocumentVersion(record.key, record.revision, record.unsaved)
    }

    private var backupGeneration = 0L
    private val removedBackupIds get() = sourceState.removed
    private var closing = false
    private val mergeConflicts get() = sourceState.conflicts
    private val pendingStoreOperations get() = sourceState.pending
    protected fun documentKey(id: String): EditorDocumentKey = dataAccess.documentKey(id)
    private data class EditingSnapshot<D>(val data: D, val raw: Map<String, String>, val loreIds: Map<String, List<String>> = emptyMap())
    private val editHistory = EditorDataHistory<EditingSnapshot<T>>(copy = {
        EditingSnapshot(it.data.deepCopy(), it.raw.toMap(), it.loreIds.mapValues { entry -> entry.value.toList() })
    })

    private fun historySnapshot(id: String, data: T) =
        EditingSnapshot(documentHistoryData(data.deepCopy()), documentHistoryRaw(inputDrafts[documentKey(id)].orEmpty().toMap()), captureLoreHistoryIds(id))

    /** Lore履歴専用。linesとsections:<行ID>だけを保持し、永続データには含めません。 */
    protected open fun captureLoreHistoryIds(id: String): Map<String, List<String>> = emptyMap()
    protected open fun restoreLoreHistoryIds(id: String, ids: Map<String, List<String>>) = Unit

    protected enum class LocalHistoryResult { HANDLED, REJECTED, NOT_APPLICABLE }

    protected open fun documentHistoryData(data: T): T = data
    protected open fun documentHistoryRaw(raw: Map<String, String>): Map<String, String> = raw
    protected open fun restoreDocumentHistory(id: String, data: T): T = data
    protected open fun restoreDocumentHistoryRaw(id: String, raw: Map<String, String>): Map<String, String> = raw
    protected open fun onDocumentHistoryRecorded(id: String) = Unit
    protected open fun onDocumentHistoryTraversed(id: String, forward: Boolean) = Unit
    protected open fun traverseLocalHistory(id: String, forward: Boolean): LocalHistoryResult = LocalHistoryResult.NOT_APPLICABLE
    protected open fun hasLocalHistory(id: String, forward: Boolean): Boolean = false
    protected open fun onDocumentHistoryReset(id: String) = Unit
    /** 型固有の未適用入力がある間は保存成功として扱わず、入力を保持します。 */
    protected open fun canSaveInput(ids: Collection<String>): Boolean = true
    protected open fun onLocalSaveCommitted(id: String) = Unit
    protected open fun resetHistoryAfterSave(id: String): Boolean = true

    /** モーダルを開いた文書と読込世代が現在も有効な間だけ適用を許します。 */
    protected fun editingGuard(id: String): () -> Boolean {
        val key = documentKey(id)
        val generation = loadGeneration
        val editing = editGeneration
        val data = editingDataMap[id]
        return { !editingBlocked &&
            currentSelectedDataId == id && documentKey(id) == key && loadGeneration == generation && editGeneration == editing && editingDataMap[id] === data && !isUploadFrozen(id) }
    }

    protected val editingBlocked: Boolean get() = storeOperationBlocked || saveRunning || syncBusy || repairBusy ||
        importBusy || switchingSource || main.operationRunning || !dataService.store.isAvailable
    protected fun hasInputDraft(id: String): Boolean = inputDrafts[documentKey(id)].orEmpty().isNotEmpty()

    protected fun inputDraft(id: String, field: String): String? = inputDrafts[documentKey(id)]?.get(field)

    protected fun updateInputDraft(id: String, field: String, value: String?) {
        val values = inputDrafts.getOrPut(documentKey(id)) { mutableMapOf() }
        if (value == null) values.remove(field) else values[field] = value
    }

    /** 全キャッシュ置換後に呼び、表示・検証・退避が中間状態を観測しないようにします。 */
    protected fun refreshEditedGroup(ids: Collection<String>) {
        editGeneration++
        ids.forEach(::refreshSidebarState)
        currentSelectedDataId?.let { editingDataMap[it]?.let(::setupMainContent) }
        requestBackup()
    }

    private var historyScene: Scene? = null
    private val historySceneListener = ChangeListener<Scene> { _, _, _ -> installHistoryCommitFilter() }
    private val historyMouseHandler = EventHandler<MouseEvent> { event ->
        val owner = historyScene?.focusOwner
        if (historyScene?.let(ShortcutManager::isComposing) != true && owns(owner) &&
            shouldCommitHistoryOnMousePress(owner is TextInputControl, event.target === owner)) commitPendingTextEdit()
    }
    private val historyKeyHandler = EventHandler<KeyEvent> { event ->
        val owner = historyScene?.focusOwner
        if (historyScene?.let(ShortcutManager::isComposing) != true && owns(owner) &&
            shouldCommitHistoryOnKeyPress(owner is TextInputControl, event.code, event.isShortcutDown)) commitPendingTextEdit()
    }
    private var documentState: IdTabsState
        get() = sourceState.tabs
        set(value) { sourceState.tabs = value }
    private var documentsRestored: Boolean
        get() = sourceState.tabsRestored
        set(value) { sourceState.tabsRestored = value }
    private val idTabs = IdTabStrip(
        onSelect = { if (currentSelectedDataId != it) selectTab(it) },
        onClose = ::closeDocument,
        onMove = { id, index -> documentState = documentState.move(id, index); updateDocumentTabs() }
    )
    private val syncService get() = EditorSyncService(dataAccess)
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
    private var uploadMenuItem: MenuItem? = null
    private var savedUploadMenuItem: MenuItem? = null
    private var uploadAction: SavedUploadAction? = null
    private val uploadDocumentsListener = SetChangeListener<EditorDocumentKey> { refreshUploadUi() }
    private val uploadResultListener = ChangeListener<SavedUploadPublication> { _, _, publication ->
        if (publication != null) acceptUploadPublication(publication)
    }
    private val categoryUploading: Boolean get() = connectedService.uploadingDocuments.any {
        it.category == dataAccess.dataType.categoryDirName
    }
    protected fun isUploadFrozen(id: String): Boolean = documentKey(id).let {
        it in connectedService.uploadingDocuments || it in connectedService.unresolvedUploadDocuments
    }

    private fun refreshUploadUi() {
        if (closed) return
        refreshActionMenuState()
        main.documentFrozen = currentSelectedDataId?.let(::isUploadFrozen) == true
        main.mainContentContainer.isDisable = main.documentFrozen ||
            storeOperationBlocked || main.operationRunning || main.loadingRunning
    }

    private fun acceptUploadPublication(publication: SavedUploadPublication) {
        val final = publication.operation.targetRevisions.associate { it.key to it.revision }
        publication.snapshot.documents.filter { it.key.category == dataAccess.dataType.categoryDirName }.forEach { record ->
            val version = documentVersions[record.key] ?: return@forEach
            if (version.revision != publication.observed[record.key] || record.revision != final[record.key]) return@forEach
            val input = publication.operation.inputs.firstOrNull { it.key == record.key }
            val state = sourceStates[record.key.source]
            if (record.pending == null && input != null && state?.pending?.get(record.key.id)?.toRecord() == input.pending)
                state?.pending?.remove(record.key.id)
            observeDocument(record)
            if (record.key.source == dataService.documentSource) refreshSidebarState(record.key.id)
        }
        refreshUploadUi()
    }

    fun onUploadSaved(all: Boolean = false, readback: Boolean = false) {
        if (closed || closing || uploadAction?.busy == true || storeOperationBlocked || syncBusy || repairBusy || saveRunning) return
        val id = currentSelectedDataId
        if (!all && id == null) return
        if (!readback && id != null && isUploadFrozen(id) && !all) return
        main.mainContentContainer.requestFocus()
        commitPendingTextEdit()
        val origin = dataService
        val action = SavedUploadAction(origin, connectedService, main.currentStage,
            isActive = { !closed && !closing && dataService === origin },
            prepare = { complete -> requestBackup(onComplete = complete) },
            onIdle = { uploadAction = null; refreshUploadUi() })
        uploadAction = action
        if (all) action.startCategory(dataAccess.dataType.categoryDirName, readback)
        else {
            val selections = listOf(SavedUploadSelection(documentKey(requireNotNull(id))))
            if (readback) action.readback(selections) else action.start(selections)
        }
        refreshUploadUi()
    }

    private var importBusy = false
    private var importApplying = false
    private var importGeneration = 0
    private var repairBusy = false
    private var syncBusy = false
    val saveRunning: Boolean get() = main.operationRunning
    internal open val sourceSwitchBusy: Boolean get() = uploadAction?.busy == true || switchingSource || saveRunning || main.loadingRunning || dataService.activeSaves > 0 || syncBusy || repairBusy || importBusy || closing
    internal open val closeBusy: Boolean get() = sourceSwitchBusy || categoryUploading
    internal val storeOperationBlocked: Boolean get() = dataService.activeSaves > 0 || main.loadingRunning || repairBusy || closing || closed || EditorWindowManager.isClosing
    private val saveListener = ChangeListener<Number> { _, _, count ->
        main.actionButtonContainer.isDisable = count.toInt() > 0 || main.loadingRunning || main.operationRunning || closing
        main.sidebarContainer.isDisable = count.toInt() > 0 || main.operationRunning || closing
        refreshUploadUi()
    }

    init {
        dataService.activeSavesProperty.addListener(saveListener)
        connectedService.uploadingDocuments.addListener(uploadDocumentsListener)
        connectedService.unresolvedUploadDocuments.addListener(uploadDocumentsListener)
        connectedService.uploadResultProperty.addListener(uploadResultListener)
        if (connectedService.isRemote) connectedService.refreshUploadState()
        main.shortcutRoot.sceneProperty().addListener(historySceneListener)
        main.actionButtonContainer.isDisable = storeOperationBlocked
        main.sidebarContainer.isDisable = storeOperationBlocked
    }
    private var savedDuringOperation = false
    @Volatile private var closed = false
    @Volatile private var loadGeneration = 0
    private var restoringBackup = false
    private var pendingPreloadIds: List<String> = emptyList()
    private val sessionBound = EditorSession.dataService === connectedService
    private fun acceptsLoad(generation: Int): Boolean = !closed && !closing && generation == loadGeneration &&
        dataService.store.isAvailable && (!sessionBound || EditorSession.dataService === connectedService)

    private var sidebarRefresh: Pair<List<StoreResource>, List<String>>? = null
    private var sidebarSnapshot: Pair<List<StoreResource>, List<String>>? = null

    private fun switchSource(next: EditorDataService): Boolean {
        if (next === dataService) return true
        if (sourceSwitchBusy || main.shortcutRoot.scene?.let(ShortcutManager::isComposing) == true) return false
        commitPendingTextEdit()
        switchingSource = true
        val previous = dataService
        loadGeneration++
        cancelSidebarPreload()
        stopAutoSaveTimer()
        main.showOperation("保存元を切り替えています")
        requestBackup(allowDuringOperation = true) { success ->
            switchingSource = false
            main.finishOperation()
            if (!success) {
                externalDocument?.also { externalDocument = null; it.ready(false) }
                return@requestBackup
            }
            previous.activeSavesProperty.removeListener(saveListener)
            dataService = next
            dataAccess = next.documentAccess(dataAccess.descriptor)
            next.activeSavesProperty.addListener(saveListener)
            sidebarSnapshot = null
            sidebarRefresh = null
            sidebarValidation.clear()
            registeredSidebarIds.clear()
            main.mainContentContainer.children.clear()
            WorkspaceManager.current?.documentSource(main.shortcutRoot, next.cacheIdentity)
            setupActions(main.actionButtonContainer)
            sourceMenu.text = if (next.isRemote) "サーバーの作業コピー" else "ローカル"
            onSourceChanged()
            if (sourceState.tabsRestored) setupSidebar(main.sidebarContainer, currentSelectedDataId)
            else start()
        }
        return true
    }

    private fun restoreLegacyBackup() {
        if (closeBusy || main.shortcutRoot.scene?.let(ShortcutManager::isComposing) == true) return
        commitPendingTextEdit()
        importBusy = true
        importApplying = true
        main.showOperation("旧退避の候補を確認しています")
        fun finish() {
            importBusy = false
            importApplying = false
            main.finishOperation()
        }
        dataService.submitIo { dataAccess.legacyBackupCandidates() }.whenComplete { candidates, error ->
            Platform.runLater {
                if (error != null || candidates.isNullOrEmpty()) {
                    finish()
                    if (error != null) logger.error("旧退避の候補を読み込めませんでした", error)
                    CustomDialog.information().title("旧退避から復元")
                        .header(if (error == null) "復元候補がありません" else "復元候補を読み込めませんでした")
                        .owner(main.currentStage).show()
                    return@runLater
                }
                val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                val choices = candidates.withIndex().associate { (index, candidate) ->
                    (index + 1).toString() + ". " + candidate.name + " / " + format.format(java.util.Date(candidate.modified)) + " / " + candidate.count + " 件" to candidate
                }
                val selected = ChoiceDialog(choices.keys.first(), choices.keys).apply {
                    title = "旧退避から復元"
                    headerText = "この作業コピーへ復元する旧退避を選択してください"
                    contentText = "候補の接続先との由来は確認されていません。"
                    main.currentStage?.let(::initOwner)
                    dialogPane.stylesheets.add(EditorView::class.java.getResource(AppScreen.WIDGETS_ONLY.css)!!.toExternalForm())
                }.showAndWait().orElse(null)?.let(choices::get)
                if (selected == null || !CustomDialog.confirmation().title("旧退避の復元確認")
                    .header(selected.name + " の編集退避をこの作業コピーへ復元しますか？")
                    .content(selected.location + "\n同じIDの編集中内容を置き換えます。現在のローカル保存済み内容と取得基準、旧退避の原本は保持します。接続先との由来の一致は確認されていません。")
                    .owner(main.currentStage).show()) { finish(); return@runLater }
                requestBackup(allowDuringOperation = true) { backedUp ->
                    if (!backedUp) { finish(); return@requestBackup }
                    dataService.submitIo { dataAccess.restoreLegacyBackup(selected.location) }.whenComplete { result, failure ->
                        Platform.runLater {
                            finish()
                            when {
                                failure != null || result is StoreResult.Failure -> {
                                    logger.error("旧退避の復元に失敗しました", failure)
                                    CustomDialog.error().header("旧退避を復元できませんでした")
                                        .content("原本と現在のローカル文書を保持しています。")
                                        .owner(main.currentStage).show()
                                }
                                result is StoreResult.Success -> {
                                    result.value.forEach { onHistoryDataRestored(it.key.id) }
                                    editingDataMap.clear()
                                    originalDataMap.clear()
                                    sourceState.saved.clear()
                                    pendingStoreOperations.clear()
                                    mergeConflicts.clear()
                                    start()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    protected open fun onSourceChanged() = Unit

    /** 画面表示後にバックアップを背景で復元し、その後に一覧を読み込みます。 */
    fun start() {
        val noticeScope = EditorSession.notifications.scope("", dataAccess.dataType.categoryDirName)
        main.notificationScope = { noticeScope.copy(source = dataService.cacheIdentity, target = currentSelectedDataId.orEmpty()) }

        if (!initialSourceResolved) {
            initialSourceResolved = true
            val savedSource = WorkspaceManager.current?.documentSource(main.shortcutRoot)
            if (connectedService.isRemote && savedSource == connectedService.cacheIdentity) {
                dataService.activeSavesProperty.removeListener(saveListener)
                dataService = connectedService
                dataAccess = connectedService.documentAccess(dataAccess.descriptor)
                dataService.activeSavesProperty.addListener(saveListener)
                sourceMenu.text = "サーバーの作業コピー"
                onSourceChanged()
                setupActions(main.actionButtonContainer)
            }
        }
        restoringBackup = true
        val generation = ++loadGeneration
        main.showLoading("自動保存バックアップを復元しています", lockSidebar = true)
        val task = object : Task<RestoredData<T>>() {
            override fun call(): RestoredData<T> =
                if (closed) RestoredData(emptyMap(), emptyMap(), emptyList()) else readAutoSaveCaches()
        }
        task.setOnSucceeded {
            if (!acceptsLoad(generation)) return@setOnSucceeded
            restoringBackup = false
            val restored = task.value
            restored.documents.forEach { record ->
                observeDocument(record)
                inputDrafts[record.key] = record.raw.toMutableMap()
                savedInputDrafts[record.key] = record.savedRaw
            }
            sourceState.saved.clear()
            sourceState.saved.putAll(restored.saved)
            injectAutoSaveCaches(restored.editing, restored.original, restored.operations)
            setupSidebar(main.sidebarContainer)
        }
        task.setOnFailed {
            if (!acceptsLoad(generation)) return@setOnFailed
            showReadFailure("自動保存バックアップ", StoreError(StoreErrorCode.IO_ERROR, cause = task.exception))
        }
        dataService.executeIo(task)
    }

    private data class RestoredData<D>(
        val editing: Map<String, D>,
        val original: Map<String, D>,
        val operations: List<PendingStoreOperation>,
        val documents: List<LocalDocumentRecord> = emptyList(),
        val saved: Map<String, D> = emptyMap()
    )

    private fun readAutoSaveCaches(): RestoredData<T> {
        when (val initialized = dataAccess.listStoreResources()) {
            is StoreResult.Success -> Unit
            is StoreResult.Failure -> error("ローカル文書の準備に失敗しました: " + initialized.error.code)
        }
        val records = when (val loaded = dataAccess.localDocuments()) {
            is StoreResult.Success -> loaded.value.filter { it.movedTo == null && !(it.savedDeleted && it.deleted) }
            is StoreResult.Failure -> error("ローカル文書の復元に失敗しました: " + loaded.error.code)
        }
        val movedSources = records.mapNotNull { record ->
            record.pending?.takeIf { it.kind == PendingStoreOperationKind.RENAME && it != record.savedPending }?.sourceId
        }.toSet()
        val editing = linkedMapOf<String, T>()
        val original = linkedMapOf<String, T>()
        records.filterNot { it.key.id in movedSources }.forEach { record ->
            val text = record.editing ?: record.saved ?: return@forEach
            editing[record.key.id] = dataAccess.decodeDocument(record.key.id, text)
            original[record.key.id] = dataAccess.decodeDocument(record.key.id, record.original ?: record.saved ?: text)
        }
        val operations = records.mapNotNull { record ->
            record.pending?.takeIf { it != record.savedPending }?.toPendingOperation()
        }
        val saved = records.mapNotNull { record ->
            record.saved?.let { record.key.id to dataAccess.decodeDocument(record.key.id, it) }
        }.toMap()
        return RestoredData(editing, original, operations, records, saved)
    }

    /** 一覧とディレクトリを背景で取得します。保存後は既に取得した一覧をそのまま渡します。 */
    protected fun loadSidebar(onFailed: () -> Unit, onLoaded: (List<String>, List<String>) -> Unit) {
        val generation = ++loadGeneration
        cancelSidebarPreload()
        val supplied = sidebarRefresh
        fun display(snapshot: Pair<List<StoreResource>, List<String>>) {
            sidebarSnapshot = snapshot
            remoteDataIds = snapshot.first.mapTo(mutableSetOf()) { it.id }
            finishLoading()
            val ids = mergeSidebarIdsWithPending(remoteDataIds)
            onLoaded(ids, snapshot.second)
            if (currentSelectedDataId == null) {
                loadEditorReferences()
                showRestoredCount()
            }
        }
        if (supplied != null) {
            display(supplied)
            return
        }
        main.showLoading("一覧とディレクトリを取得しています", lockSidebar = true)
        val task = object : Task<StoreResult<Pair<List<StoreResource>, List<String>>>>() {
            override fun call(): StoreResult<Pair<List<StoreResource>, List<String>>> {
                return readSidebarSnapshot()
            }
        }
        task.setOnSucceeded {
            if (!acceptsLoad(generation)) return@setOnSucceeded
            when (val result = task.value) {
                is StoreResult.Success -> display(result.value)
                is StoreResult.Failure -> { onFailed(); showReadFailure("一覧・ディレクトリ", result.error) }
            }
        }
        task.setOnFailed {
            if (!acceptsLoad(generation)) return@setOnFailed
            onFailed()
            showReadFailure("一覧・ディレクトリ", StoreError(StoreErrorCode.IO_ERROR, cause = task.exception))
        }
        dataService.executeIo(task)
    }

    private fun finishLoading() {
        main.finishLoading()
        if (externalDocument != null) Platform.runLater {
            val requested = externalDocument ?: return@runLater
            if (restoringBackup || switchingSource || closed || closing) return@runLater
            if (requested.key.source != dataService.documentSource) {
                val next = if (requested.key.source == connectedService.documentSource) connectedService else connectedService.localService
                if (!switchSource(next)) {
                    externalDocument = null
                    requested.ready(false)
                }
            } else if (requested.key.id !in registeredSidebarIds) {
                externalDocument = null
                requested.ready(false)
            } else if (currentSelectedDataId != requested.key.id && !selectTab(requested.key.id)) {
                externalDocument = null
                requested.ready(false)
            }
        }
        if (dataService.activeSaves > 0) {
            main.actionButtonContainer.isDisable = true
            main.sidebarContainer.isDisable = true
        }
    }

    private fun showReadFailure(target: String, error: StoreError) {
        externalDocument?.also { externalDocument = null; it.ready(false) }
        finishLoading()
        logger.error("{}の取得に失敗しました: {}", target, error, error.cause)
        main.setTopLabel("$target の取得に失敗しました", Color.ORANGERED)
        main.showTimedTopLabel("$target の取得に失敗しました", Color.ORANGERED)
        CustomDialog.error().header("$target の取得に失敗しました。")
            .content(error.detail.orEmpty()).owner(main.currentStage).show()
    }

    /** 保存の完了結果。後処理失敗でも書き込み成功を維持します。一括の対象別結果は[BatchSaveResult]で返します。 */
    enum class SaveOutcome { SAVED, SAVED_WITH_ERRORS, CANCELLED, FAILED, NOTHING_TO_SAVE, BUSY }

    /**
     * このエディターからアップロードできるローカルデータの種別です。
     *
     * サーバーに接続していて、アップロードに対応するデータ種別のときだけ値があります。
     */
    private val uploadCategory: UploadDataCategory? get() =
        UploadDataCategory.of(dataAccess.dataType).takeIf { dataService.isRemote }
    private var sidebarPreloadGeneration = 0

    /** データ種別固有の警告修正アクションです。 */
    protected open val validationRepairRegistry = ValidationRepairRegistry<T>()

    protected var restoredCacheCount = 0

    /**
     * サーバー上に存在するデータIDです。
     *
     * サイドバーを構築するたびに更新し、ローカルにだけ存在するデータの判別へ使用します。
     */
    protected var remoteDataIds: Set<String>
        get() = sourceState.remoteIds
        set(value) { sourceState.remoteIds = value }

    /**
     * 指定したデータの編集キャッシュと自動保存バックアップを破棄します。
     *
     * ストア上のファイルには触れないため、ローカル未保存データの削除や、
     * ストア側の削除が完了した後の後処理に使用します。
     */
    protected fun discardLocalEditingData(id: String) {
        editingDataMap.remove(id)
        originalDataMap.remove(id)
        sourceState.saved.remove(id)
        mergeConflicts.remove(id)
        onDocumentHistoryReset(id)
        editHistory.remove(documentKey(id))
        removeLocalBackup(id)
    }

    /** 新規データを、保存時にストアへ追加する保留操作として登録します。 */
    protected fun stageDataCreation(id: String) {
        pendingStoreOperations[id] = PendingStoreOperation.Create(id)
        onDocumentHistoryReset(id)
        editingDataMap[id]?.let { editHistory.reset(documentKey(id), historySnapshot(id, it)) }
        persistPendingStoreState()
        refreshSidebarState(id)
        refreshSyncButtonState()
    }

    /**
     * ID変更を保留し、編集中データのキーと公開IDだけを先に更新します。
     * ストア上のファイル名は保存が確定するまで変更しません。
     */
    protected fun stageDataRename(oldId: String, newId: String) {
        val previous = pendingStoreOperations.remove(oldId)
        val operation = composePendingRename(previous, oldId, newId)
        renameEditingCache(editingDataMap, oldId, newId)
        renameEditingCache(originalDataMap, oldId, newId)
        renameEditingCache(sourceState.saved, oldId, newId)
        mergeConflicts.remove(oldId)?.let { mergeConflicts[newId] = it }
        onDocumentHistoryReset(oldId)
        onDocumentHistoryReset(newId)
        editHistory.rename(documentKey(oldId), documentKey(newId))
        inputDrafts.remove(documentKey(oldId))?.let { inputDrafts[documentKey(newId)] = it }
        savedInputDrafts.remove(documentKey(oldId))?.let { savedInputDrafts[documentKey(newId)] = it }
        localDocumentStates.remove(documentKey(oldId))?.let { localDocumentStates[documentKey(newId)] = it }
        documentState = documentState.copy(ids = documentState.ids.map { if (it == oldId) newId else it },
            selected = if (documentState.selected == oldId) newId else documentState.selected)
        updateDocumentTabs()
        operation?.let { pendingStoreOperations[newId] = it }
        removeLocalBackup(oldId)
        persistPendingStoreState()
        if (operation == null) persistContentChangeBackup(newId)
        refreshSyncButtonState()
    }

    private fun persistContentChangeBackup(id: String) {
        if (id in editingDataMap) requestBackup()
    }

    private fun removeLocalBackup(id: String) {
        removedBackupIds.add(id)
        requestBackup()
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
                refreshSidebarState(id)
                refreshSyncButtonState()
                false
            }
            is PendingStoreOperation.Delete -> false
            null -> {
                pendingStoreOperations[id] = PendingStoreOperation.Delete(id, id)
                persistPendingStoreState()
                refreshSidebarState(id)
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
        refreshSidebarState(id)
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

        onClose { success ->
            if (success) {
                cancelOpen()
                dataService.forceBackToSelect()
            }
        }
    }

    /**
     * 検索欄と仮想化されたデータ一覧を配置し、一覧取得を背景で開始します。
     * 取得結果の反映と初期選択はJavaFXスレッドで行います。
     * 必要に応じて、初期表示時に対象となるタブ（リソース）を自動で選択する処理もここに記述します。
     *
     * @param container 検索欄と一覧を配置するサイドバーの垂直レイアウトコンテナ
     * @param selectId 初期表示時に選択させたいアイテムのID。省略時（null）はデフォルトの挙動（先頭の要素を選択など）となります。
     */
    abstract fun setupSidebar(container: VBox, selectId: String? = null)

    /**
     * トップバー（コンテナ）に配置する、エディタ固有の共通アクションボタン（「保存」「新規作成」など）を構築します。
     *
     * @param container アクションボタンを水平に並べるためのトップレイアウトコンテナ
     */
    open fun setupActions(container: HBox) {
        main.idTabContainer.children.setAll(idTabs)
        saveMenuItem = actionMenuItem(EditorShortcut.SAVE.displayName, EditorShortcut.SAVE) { onSave() }
        saveAllMenuItem = actionMenuItem(EditorShortcut.SAVE_ALL.displayName, EditorShortcut.SAVE_ALL) { onSaveAll() }

        syncMenuItem = actionMenuItem(EditorShortcut.SYNC.displayName, EditorShortcut.SYNC) { onSynchronizeSelected() }
        syncAllMenuItem = actionMenuItem(EditorShortcut.SYNC_ALL.displayName, EditorShortcut.SYNC_ALL) { onSynchronizeAll() }

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
        uploadCategory?.let { category ->
            fileItems += SeparatorMenuItem()
            uploadMenuItem = actionMenuItem("ローカルデータを取り込む…", EditorShortcut.IMPORT_LOCAL) {
                onUploadLocalData(category)
            }
            fileItems += uploadMenuItem!!
        }

        fileItems += SeparatorMenuItem()
        savedUploadMenuItem = actionMenuItem(EditorShortcut.UPLOAD.displayName, EditorShortcut.UPLOAD) { onUploadSaved() }
        fileItems += savedUploadMenuItem!!
        fileItems += MenuItem("保存済み差分をまとめてアップロード…").apply { setOnAction { onUploadSaved(all = true) } }
        fileItems += MenuItem("アップロード結果を確認・読戻し…").apply { setOnAction { onUploadSaved(all = true, readback = true) } }

        undoMenuItem = actionMenuItem("元に戻す", EditorShortcut.UNDO) { onUndo() }
        redoMenuItem = actionMenuItem("やり直す", EditorShortcut.REDO) { onRedo() }
        ShortcutManager.register(main.shortcutRoot, ShortcutScope.EDITOR, EditorShortcut.REDO_ALTERNATIVE) {
            ShortcutManager.execute(main.shortcutRoot, EditorShortcut.REDO)
        }
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
        container.children.setAll(fileMenu, editMenu, sourceMenu)
        main.refreshEditorMenus(this)
        refreshActionMenuState()
    }

    private fun actionMenuItem(
        text: String,
        shortcut: EditorShortcut,
        action: () -> Unit
    ): MenuItem = MenuItem().apply {
        id = "action-${shortcut.name.lowercase()}"
        textProperty().bind(ShortcutManager.hint(shortcut, text))
        ShortcutManager.register(main.shortcutRoot, ShortcutScope.EDITOR, shortcut) {
            if (isDisable || main.actionButtonContainer.isDisabled || storeOperationBlocked) false else { action(); true }
        }
        onAction = EventHandler { ShortcutManager.execute(main.shortcutRoot, shortcut) }
    }

    /** ローカルデータを、宛先ディレクトリを指定して未保存の編集として取り込みます。 */
    private fun onUploadLocalData(category: UploadDataCategory) {
        if (storeOperationBlocked || importBusy || syncBusy || repairBusy) return
        val generation = ++importGeneration
        fun active(): Boolean = generation == importGeneration && !closing && !closed &&
            dataService.store.isAvailable && (!sessionBound || EditorSession.dataService === connectedService)
        val profileName = dataService.currentProfileName ?: return
        var editingBeforeLoad: Map<String, T> = emptyMap()
        var operationsBeforeLoad: Map<String, PendingStoreOperation> = emptyMap()
        LocalDataUploader.start(
            owner = main.currentStage,
            dataService = dataService,
            profileName = profileName,
            category = category,
            descriptor = dataAccess.descriptor,
            editingIds = { editingDataMap.keys.toSet() },
            onBeforeLoad = {
                main.mainContentContainer.requestFocus()
                commitPendingTextEdit()
                editingBeforeLoad = editingDataMap.mapValues { it.value.deepCopy() }
                operationsBeforeLoad = pendingStoreOperations.toMap()
            },
            onBusyChanged = busyChanged@ { busy ->
                if (generation != importGeneration) return@busyChanged
                importBusy = busy
                uploadMenuItem?.isDisable = busy
                if (!busy) main.finishImportProgress()
            },
            isActive = ::active,
            onProgress = main::showImportProgress,
            onLoaded = { entries, onComplete ->
                fun applyWhenReady() {
                    if (!active()) { onComplete(false); return }
                    if (dataService.activeSaves > 0 || main.loadingRunning || closing || closed || saveRunning || syncBusy || repairBusy) {
                        main.showImportProgress("取り込み結果の適用を待っています", -1.0)
                        PauseTransition(Duration.millis(100.0)).apply {
                            setOnFinished { applyWhenReady() }
                            play()
                        }
                        return
                    }
                    importApplying = true
                    dataService.beginSave()
                    main.showOperation("取り込み結果を確認しています")
                    main.showImportProgress("取り込み結果を確認しています", -1.0)
                    fun finish(applied: Boolean) {
                        if (importApplying) {
                            importApplying = false
                            main.finishOperation()
                            dataService.endSave()
                            refreshSyncButtonState()
                        }
                        onComplete(applied)
                    }
                    try {
                        main.mainContentContainer.requestFocus()
                        commitPendingTextEdit()
                        val changedWhileLoading = entries.filter { entry ->
                            val id = entry.data.id
                            editingDataMap[id] != editingBeforeLoad[id] ||
                                pendingStoreOperations[id] != operationsBeforeLoad[id]
                        }
                        if (changedWhileLoading.isNotEmpty() && !CustomDialog.confirmation()
                                .title("読み込み中に変更された編集内容の確認")
                                .header("読み込み中に編集内容が変わりました。置き換えますか？")
                                .content(changedWhileLoading.map { it.data.id })
                                .owner(main.currentStage)
                                .show()
                        ) {
                            finish(false)
                            return
                        }
                        if (!active()) { finish(false); return }
                        main.showImportProgress("取り込み ${entries.size} 件を編集へ適用しています", -1.0)
                        entries.forEach { entry ->
                            val id = entry.data.id
                            val hasOriginal = id in originalDataMap
                            originalDataMap.putIfAbsent(id, (entry.original ?: dataAccess.createDefault(id)).deepCopy())
                            editingDataMap[id] = entry.data.deepCopy()
                            inputDrafts[documentKey(id)] = entry.raw.toMutableMap()
                            mergeConflicts.remove(id)
                            onDocumentHistoryReset(id)
                            editHistory.reset(documentKey(id), historySnapshot(id, entry.data))
                            val deletion = pendingStoreOperations[id] as? PendingStoreOperation.Delete
                            if (deletion != null) {
                                if (deletion.sourceId == id) pendingStoreOperations.remove(id)
                                else pendingStoreOperations[id] = PendingStoreOperation.Rename(deletion.sourceId, id)
                            }
                            if (!hasOriginal && entry.original == null) {
                                pendingStoreOperations[id] = PendingStoreOperation.Create(id)
                            }
                        }
                        pendingStoreOperations.entries.removeIf { !it.value.isEffective() }
                        main.showImportProgress("取り込み後の一覧を更新しています", -1.0)
                        refreshLocalSidebar(entries.first().data.id)
                        importApplying = false
                        main.finishOperation()
                        dataService.endSave()
                        refreshSyncButtonState()
                        main.showImportProgress("取り込み結果をローカルに退避しています", -1.0)
                        requestBackup { backedUp ->
                            if (!active()) { onComplete(false); return@requestBackup }
                            if (!backedUp) {
                                CustomDialog.error().header("取り込みは編集へ反映済みですが、退避に失敗しました")
                                    .content("編集内容は保持されています。終了前にバックアップを再試行してください。")
                                    .owner(main.currentStage).show()
                            }
                            onComplete(true)
                        }
                    } catch (error: Exception) {
                        logger.error("取り込み結果の適用に失敗しました", error)
                        finish(false)
                        CustomDialog.error().header("取り込み結果の適用に失敗しました")
                            .content("編集内容は保持されています。内容を確認してください。")
                            .owner(main.currentStage).show()
                    }
                }
                applyWhenReady()
            }
        )
    }

    fun onSynchronizeSelected() {
        if (storeOperationBlocked || saveRunning || main.loadingRunning || syncBusy || repairBusy || closing || closed) return
        val id = currentSelectedDataId ?: return
        if (!dataService.isRemote || hasPendingStoreOperation(id)) return
        synchronizeSelected()
    }

    fun onSynchronizeAll() {
        if (storeOperationBlocked || saveRunning || main.loadingRunning || syncBusy || repairBusy || closing || closed) return
        if (!dataService.isRemote || pendingStoreOperations.isNotEmpty()) return
        synchronizeAll()
    }

    fun onCreateData() { if (!storeOperationBlocked) handleCreateNewItem() }

    fun onCreateDirectory() { if (!storeOperationBlocked) handleCreateDirectory() }

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
     * 保存元のローカル作業コピーを背景で読み、最新の選択要求にだけ結果を表示します。
     * 取得中は編集を抑止しますが、別のデータの選択と画面終了は可能です。
     *
     * @param targetId 選択対象となるリソースの識別子（ID）
     * @return 選択の受け付け。背景読み込みの成功は含みません。
     */
    open fun selectTab(targetId: String): Boolean {
        if (closed || closing || repairBusy || (dataService.activeSaves > 0 && sidebarRefresh == null)) return false
        if (isPendingStoreDeletion(targetId)) return false
        commitPendingTextEdit()
        documentState = documentState.open(targetId)
        updateDocumentTabs()
        idTabs.revealSelected()
        val previousId = currentSelectedDataId
        currentSelectedDataId = targetId
        val generation = ++loadGeneration
        val local = editingDataMap[targetId]?.deepCopy()
        previousId?.takeIf { it != targetId }?.let(::refreshSidebarState)
        refreshSidebarState(targetId)
        if (local != null) {
            finishLoading()
            displaySelected(targetId)
            return true
        }
        sidebarValidation[targetId] = SidebarValidationStatus.CHECKING
        refreshSidebarState(targetId)
        main.showLoading("$targetId のローカル作業コピーを読み込んでいます")
        val task = object : Task<StoreResult<Pair<T, LocalDocumentRecord>>>() {
            override fun call(): StoreResult<Pair<T, LocalDocumentRecord>> = dataAccess.observeStore(targetId)
        }
        task.setOnSucceeded {
            if (!acceptsLoad(generation)) return@setOnSucceeded
            finishLoading()
            when (val result = task.value) {
                is StoreResult.Success -> {
                    val remote = result.value.first
                    observeDocument(result.value.second)
                    editingDataMap[targetId] = remote
                    originalDataMap[targetId] = remote.deepCopy()
                    sourceState.saved[targetId] = remote.deepCopy()
                    displaySelected(targetId)
                }
                is StoreResult.Failure -> {
                    sidebarValidation[targetId] = SidebarValidationStatus.FAILED
                    refreshSidebarState(targetId)
                    main.mainContentContainer.children.clear()
                    showReadFailure(targetId, result.error)
                    startPendingPreload()
                }
            }
        }
        task.setOnFailed {
            if (!acceptsLoad(generation)) return@setOnFailed
            sidebarValidation[targetId] = SidebarValidationStatus.FAILED
            refreshSidebarState(targetId)
            showReadFailure(targetId, StoreError(StoreErrorCode.IO_ERROR, cause = task.exception))
            startPendingPreload()
        }
        dataService.executeIo(task)
        return true
    }

    private fun displaySelected(targetId: String) {
        finishLoading()
        refreshSidebarState(targetId)
        refreshSyncButtonState()
        val editing = editingDataMap.getValue(targetId)
        editHistory.initialize(documentKey(targetId), historySnapshot(targetId, editing))
        setupMainContent(editing)
        installHistoryCommitFilter()
        refreshUploadUi()
        loadEditorReferences()
        showRestoredCount()
        startPendingPreload()
        externalDocument?.takeIf { it.key == documentKey(targetId) }?.also {
            externalDocument = null
            it.ready(true)
        }
    }

    private fun startPendingPreload() {
        val ids = pendingPreloadIds
        pendingPreloadIds = emptyList()
        if (ids.isNotEmpty()) preloadSidebarDataForVisualStates(ids)
        else sidebarProgress?.invoke()
    }

    private fun showRestoredCount() {
        if (restoredCacheCount == 0) return
        main.showTimedTopLabel("自動保存から $restoredCacheCount 件のデータを復元しました", Color.GREENYELLOW)
        restoredCacheCount = 0
    }

    /**
     * 入力を確定してスナップショットを取得し、単件保存を背景実行します。
     * 確認と画面更新、[onComplete]はJavaFXスレッドで実行します。
     * 保存中は編集・再実行・画面終了を抑止します。
     */
    fun onSave(targetDataId: String? = null, onComplete: (SaveOutcome) -> Unit = {}) {
        if (closed || closing || storeOperationBlocked || syncBusy || repairBusy) { onComplete(SaveOutcome.BUSY); return }
        main.mainContentContainer.requestFocus()
        commitPendingTextEdit()
        val dataId = targetDataId ?: currentSelectedDataId
        if (dataId != null && (isUploadFrozen(dataId) || !canSaveInput(listOf(dataId)))) { onComplete(SaveOutcome.BUSY); return }
        if (dataId == null || !hasUnsavedChanges(dataId)) {
            onComplete(SaveOutcome.NOTHING_TO_SAVE)
            return
        }
        val local = editingDataMap.getValue(dataId).deepCopy()
        val original = originalDataMap.getValue(dataId).deepCopy()
        val operation = pendingStoreOperations[dataId]
        savedDuringOperation = false
        loadGeneration++
        cancelSidebarPreload()
        dataService.beginSave()
        main.showOperation("ローカル保存を準備しています", dataId)
        val saveNotice = main.operationToken

        var finished = false
        fun finish(outcome: SaveOutcome, beforeNotification: () -> Unit = {}) {
            if (finished) return
            finished = true
            main.finishOperation()
            dataService.endSave()
            preloadSidebarDataForVisualStates(registeredSidebarIds)
            refreshSyncButtonState()
            val notifications = EditorSession.notifications
            when (outcome) {
                SaveOutcome.SAVED -> saveNotice?.let { notifications.result(it, "save", null) }
                SaveOutcome.SAVED_WITH_ERRORS -> saveNotice?.let { notifications.result(it, "save", "ローカル保存済みですが、後処理に失敗しました。") }
                SaveOutcome.FAILED -> saveNotice?.let { notifications.result(it, "save", "ローカル保存を完了できませんでした。編集中データを保持しています。", NotificationState.Severity.ERROR) }
                else -> Unit
            }
            when (outcome) {
                SaveOutcome.SAVED -> main.showTimedTopLabel("$dataId を保存しました", Color.GREENYELLOW)
                SaveOutcome.SAVED_WITH_ERRORS -> main.showTimedTopLabel("$dataId は保存済みですが、後処理に失敗しました", Color.ORANGE, 5.0)
                SaveOutcome.CANCELLED -> main.showTimedTopLabel("保存をキャンセルしました")
                else -> Unit
            }
            try { beforeNotification() } finally { onComplete(outcome) }
        }

        fun fail(error: Throwable) {
            logger.error("単件保存の工程で例外が発生しました: id={}", dataId, error)
            if (finished) return
            val saved = savedDuringOperation
            finish(if (saved) SaveOutcome.SAVED_WITH_ERRORS else SaveOutcome.FAILED) {
                CustomDialog.error()
                    .title(if (saved) "保存後の処理に失敗しました" else "保存に失敗しました")
                    .content(if (saved) "ローカル保存は完了しています。表示や退避の状態を確認してください。"
                        else "編集中の内容は保持されています。保存先と入力内容を確認してください。")
                    .owner(main.currentStage).show()
            }
        }

        fun refreshSidebar() {
            refreshSavedSidebar({ error, _ -> fail(error) }) { finish(SaveOutcome.SAVED) }
        }

        fun persist(prepared: PreparedSave<T>) {
            main.showOperation("ローカルに保存しています")
            saveStep({ writeSingleSave(prepared) }, ::fail) { result ->
                when (result) {
                    is StoreResult.Failure -> {
                        finish(SaveOutcome.FAILED) { handleSaveFailure(result.error) }
                    }
                    is StoreResult.Success -> {
                        savedDuringOperation = true
                        onLocalSaveCommitted(prepared.dataId)
                        applySavedData(prepared, result.value)
                        main.showOperation("バックアップを整理しています")
                        requestBackup(allowDuringOperation = true) { success ->
                            if (success) refreshSidebar()
                            else fail(IllegalStateException("ローカル退避の整理に失敗しました。"))
                        }
                    }
                }
            }
        }

        fun confirm(preview: SaveDataPreview<T>?) {
            if (preview == null) { finish(SaveOutcome.CANCELLED); return }
            val prepared = PreparedSave(dataId, operation, original, local,
                preview.automaticallyMergedData, preview.data, preview.data.takeUnless { operation is PendingStoreOperation.Delete },
                inputDrafts[documentKey(dataId)].orEmpty().toMap(),
                versions(listOfNotNull(dataId, operation?.toRecord()?.sourceId)))
            main.showOperation("保存する差分を確認しています")
            if (!confirmSaveChanges(prepared)) { finish(SaveOutcome.CANCELLED); return }
            persist(prepared)
        }

        try { confirm(SaveDataPreview(local, null)) } catch (error: Exception) { fail(error) }

    }

    private fun <R> saveStep(operation: () -> R, failed: (Throwable) -> Unit, completed: (R) -> Unit) {
        dataService.submitIo(operation).whenComplete { value, error ->
            Platform.runLater {
                if (error != null) failed(error)
                else try { completed(value) } catch (failure: Exception) { failed(failure) }
            }
        }
    }

    private fun writeSingleSave(prepared: PreparedSave<T>, access: EditorDataService.DataAccess<T> = dataAccess): StoreResult<SavedCheckpoint<T>> {
        val deleted = prepared.operation is PendingStoreOperation.Delete
        val data = prepared.localData.deepCopy().apply { id = prepared.dataId }
        return when (val saved = access.saveLocalDocument(prepared.dataId, data,
            prepared.raw, prepared.operation?.toRecord(), deleted, prepared.versions)) {
            is StoreResult.Success -> StoreResult.Success(SavedCheckpoint(data.takeUnless { deleted }, saved.value))
            is StoreResult.Failure -> saved
        }
    }

    private fun applySavedData(prepared: PreparedSave<T>, checkpoint: SavedCheckpoint<T>) {
        val saved = checkpoint.data
        localDocumentStates[checkpoint.document.key] = checkpoint.document
        val id = prepared.dataId
        val operation = prepared.operation
        val sourceId = when (operation) {
            is PendingStoreOperation.Rename -> operation.sourceId
            is PendingStoreOperation.Delete -> operation.sourceId
            else -> id
        }
        removedBackupIds.add(id)
        removedBackupIds.add(sourceId)
        pendingStoreOperations.remove(id)
        savedInputDrafts[documentKey(id)] = inputDrafts[documentKey(id)].orEmpty().toMap()
        mergeConflicts.remove(id)
        if (operation is PendingStoreOperation.Delete) {
            editingDataMap.remove(id)
            originalDataMap.remove(id)
            sourceState.saved.remove(id)
            onDocumentHistoryReset(id)
            editHistory.remove(documentKey(id))
            onPersistedDataDeleted(id)
            remoteDataIds = remoteDataIds - sourceId
        } else {
            val data = requireNotNull(saved)
            editingDataMap[id] = data.deepCopy()
            originalDataMap[id] = data.deepCopy()
            sourceState.saved[id] = data.deepCopy()
            if (resetHistoryAfterSave(id)) editHistory.reset(documentKey(id), historySnapshot(id, data))
            remoteDataIds = (remoteDataIds - sourceId) + id
            refreshSidebarState(id)
        }
    }

    private fun refreshSavedSidebar(failed: (Throwable, Int?) -> Unit, isCurrent: () -> Boolean = { true }, completed: (Boolean) -> Unit) {
        val access = dataAccess
        val service = dataService
        val notice = main.operationToken
        var displayGeneration: Int? = null
        fun fail(error: Throwable) = failed(error, displayGeneration)
        main.showOperation("保存後の一覧を取得しています")
        saveStep({
            val resources = when (val result = access.listStoreResources()) {
                is StoreResult.Success -> result.value
                is StoreResult.Failure -> error("一覧取得失敗: ${result.error}")
            }
            val directories = when (val result = access.listDirectories()) {
                is StoreResult.Success -> result.value
                is StoreResult.Failure -> error("ディレクトリ取得失敗: ${result.error}")
            }
            resources to directories
        }, ::fail) { snapshot ->
            if (!isCurrent()) { completed(false); return@saveStep }
            val ids = mergeSidebarIdsWithPending(snapshot.first.map { it.id }).filterNot(::isPendingStoreDeletion)
            val selected = currentSelectedDataId?.takeIf { it in ids } ?: ids.firstOrNull()
            fun display() {
                if (!isCurrent()) { completed(false); return }
                main.showOperation("保存結果を表示しています")
                sidebarRefresh = snapshot
                try { setupSidebar(main.sidebarContainer, selected) }
                finally {
                    sidebarRefresh = null
                    if (!closed && dataService === service && dataAccess === access && main.operationToken == notice) displayGeneration = loadGeneration
                }
                refreshWorkspaceDocuments()
                completed(true)
            }
            if (selected != null && selected !in editingDataMap) {
                saveStep({ access.observeStore(selected) }, ::fail) { result ->
                    if (!isCurrent()) { completed(false); return@saveStep }
                    when (result) {
                        is StoreResult.Success -> {
                            val data = result.value.first
                            observeDocument(result.value.second)
                            editingDataMap[selected] = data.deepCopy()
                            originalDataMap[selected] = data.deepCopy()
                            sourceState.saved[selected] = data.deepCopy()
                            display()
                        }
                        is StoreResult.Failure -> error("表示対象の取得失敗: ${result.error}")
                    }
                }
            } else display()
        }
    }

    /** 一括保存の対象別結果。後処理エラーがあっても[saved]の書き込みは完了しています。 */
    data class BatchSaveResult(
        val outcome: SaveOutcome,
        val saved: List<String> = emptyList(),
        val failed: Map<String, String> = emptyMap(),
        val cancelled: List<String> = emptyList(),
        val notExecuted: List<String> = emptyList(),
        val postProcessingErrors: List<String> = emptyList()
    )

    /**
     * 入力を確定し、開始時の対象と編集コピーを使って準備・保存を順次背景実行します。
     * 全対象の準備後にまとめた差分を確認し、保存結果の一覧更新は最後に1回行います。
     * [onComplete]はJavaFXスレッドで実行し、開始できない場合もBUSYまたはNOTHING_TO_SAVEを返します。
     * 書き込み開始後の取消・自動再試行は行いません。
     */
    fun onSaveAll(onComplete: (BatchSaveResult) -> Unit = {}) {
        if (storeOperationBlocked || syncBusy || repairBusy) {
            onComplete(BatchSaveResult(SaveOutcome.BUSY)); return
        }
        main.mainContentContainer.requestFocus()
        commitPendingTextEdit()
        val editable = editingDataMap.keys.filterNot(::isUploadFrozen)
        if (!canSaveInput(editable)) { onComplete(BatchSaveResult(SaveOutcome.BUSY)); return }
        val targets = editable.filter(::hasUnsavedChanges).map { id ->
            val local = editingDataMap.getValue(id).deepCopy()
            val operation = pendingStoreOperations[id]
            PreparedSave(id, operation, originalDataMap.getValue(id).deepCopy(), local, null, local, local,
                inputDrafts[documentKey(id)].orEmpty().toMap(),
                versions(listOfNotNull(id, operation?.toRecord()?.sourceId)))
        }
        if (targets.isEmpty()) { onComplete(BatchSaveResult(SaveOutcome.NOTHING_TO_SAVE)); return }
        val batchService = dataService
        val batchAccess = dataAccess
        var batchGeneration = loadGeneration
        val prepared = mutableListOf<PreparedSave<T>>()
        val saved = mutableListOf<String>()
        val failed = linkedMapOf<String, String>()
        val cancelled = mutableListOf<String>()
        val notExecuted = mutableListOf<String>()
        val postErrors = mutableListOf<String>()
        val postFailed = mutableSetOf<String>()
        val notifications = EditorSession.notifications
        val batchScope = main.notificationScope().copy(target = "")
        val saveNotices = targets.associate { entry ->
            entry.dataId to notifications.prepare(batchScope.copy(target = entry.dataId), "operation")
        }
        cancelSidebarPreload()
        dataService.beginSave()
        main.showOperation("保存準備 0/${targets.size} 件", "")
        val batchNotice = main.operationToken
        fun currentBatch() = !closed && dataService === batchService && dataAccess === batchAccess && loadGeneration == batchGeneration && main.operationToken == batchNotice
        var finished = false
        var fullBackupSucceeded = false

        fun finish() {
            if (finished) return
            finished = true
            val outcome = when {
                saved.isNotEmpty() && (failed.isNotEmpty() || cancelled.isNotEmpty() || notExecuted.isNotEmpty() || postErrors.isNotEmpty()) -> SaveOutcome.SAVED_WITH_ERRORS
                saved.isNotEmpty() -> SaveOutcome.SAVED
                failed.isNotEmpty() -> SaveOutcome.FAILED
                else -> SaveOutcome.CANCELLED
            }
            val result = BatchSaveResult(outcome, saved.toList(), failed.toMap(), cancelled.toList(), notExecuted.toList(), postErrors.toList())
            result.saved.forEach { id ->
                saveNotices[id]?.let { notice -> notifications.result(notice, "save",
                    if (id in postFailed) "ローカル保存済みですが、後処理に失敗しました。" else null) }
            }
            result.failed.forEach { (id, reason) ->
                saveNotices[id]?.let { notifications.result(it, "save", reason, NotificationState.Severity.ERROR) }
            }
            if (result.postProcessingErrors.isNotEmpty()) notifications.condition(batchScope, "save-post", result.postProcessingErrors.joinToString("\n"))
            else if (result.saved.isNotEmpty()) notifications.resolve(batchScope, "save-post")
            if (!currentBatch()) { batchService.endSave(); onComplete(result); return }
            main.finishOperation()
            batchService.endSave()
            preloadSidebarDataForVisualStates(registeredSidebarIds)
            refreshSyncButtonState()
            val summary = "${saved.size} 件保存済み、${failed.size} 件失敗、${cancelled.size} 件確認キャンセル、${notExecuted.size} 件未実行"
            main.showTimedTopLabel(summary, if (outcome == SaveOutcome.SAVED) Color.GREENYELLOW else Color.ORANGE, 5.0)
            val details = buildList {
                fun section(title: String, ids: List<String>) {
                    if (ids.isNotEmpty()) { add("【$title（${ids.size} 件）】"); ids.forEach { add("・$it") } }
                }
                section("保存済み", saved)
                section("失敗", failed.map { (id, reason) -> "$id: $reason" })
                section("確認キャンセル", cancelled)
                section("未実行", notExecuted)
                if (postErrors.isNotEmpty()) { add("【保存後の処理で発生したエラー】"); addAll(postErrors) }
            }
            try {
                CustomDialog.information().title("一括保存の結果").header(summary).content(details)
                    .scrollableContent().owner(main.currentStage).show()
            } finally { onComplete(result) }
        }

        fun stopStale(remaining: List<PreparedSave<T>>): Boolean {
            if (currentBatch()) return false
            notExecuted += (prepared + remaining).map { it.dataId }.distinct().filter { it !in saved && it !in failed && it !in cancelled && it !in notExecuted }
            postErrors += "保存元または編集世代が変わったため、残りの保存を実行しませんでした。"
            finish()
            return true
        }

        fun refresh() {
            if (stopStale(emptyList())) return
            if (saved.isEmpty()) { finish(); return }
            refreshSavedSidebar({ error, displayGeneration ->
                if (displayGeneration != null && !closed && dataService === batchService && dataAccess === batchAccess && main.operationToken == batchNotice) batchGeneration = displayGeneration
                if (stopStale(emptyList())) return@refreshSavedSidebar
                logger.error("一括保存後の一覧更新に失敗しました", error)
                postErrors += "一覧更新に失敗しました。保存済みの書き込みは完了しています。"
                finish()
            }, isCurrent = ::currentBatch) { applied ->
                if (applied && !closed && dataService === batchService && dataAccess === batchAccess && main.operationToken == batchNotice) {
                    batchGeneration = loadGeneration
                    finish()
                } else stopStale(emptyList())
            }
        }

        fun persist(index: Int) {
            if (stopStale(prepared.drop(index))) return
            if (index == prepared.size) { refresh(); return }
            val entry = prepared[index]
            val writeStarted = AtomicBoolean()
            main.showOperation("保存中 $index/${prepared.size} 件: ${entry.dataId}")
            fun next() {
                main.showOperation("保存中 ${index + 1}/${prepared.size} 件: ${entry.dataId}")
                Platform.runLater { persist(index + 1) }
            }
            saveStep({
                check(currentBatch()) { "保存元または編集世代が変わりました。" }
                writeStarted.set(true)
                writeSingleSave(entry, batchAccess)
            }, { error ->
                if (writeStarted.get() && entry.dataId !in saved) failed[entry.dataId] = "書き込みの結果を確認できません。ストアの状態を確認してください。"
                if (stopStale(prepared.drop(if (writeStarted.get()) index + 1 else index))) return@saveStep
                logger.error("一括保存の書き込みに失敗しました: id={}", entry.dataId, error)
                if (entry.dataId in saved) {
                    postFailed += entry.dataId
                    postErrors += "${entry.dataId}: 保存結果の反映に失敗しました。"
                    notExecuted += prepared.drop(index + 1).map { it.dataId }
                    refresh()
                } else { failed[entry.dataId] = "書き込みの結果を確認できません。ストアの状態を確認してください。"; next() }
            }) { result ->
                when (result) {
                    is StoreResult.Failure -> {
                        failed[entry.dataId] = result.error.detail ?: "ストアへの書き込みに失敗しました。"
                        if (stopStale(prepared.drop(index + 1))) return@saveStep
                        logger.error("一括保存に失敗しました: id={}, error={}", entry.dataId, result.error)
                        next()
                    }
                    is StoreResult.Success -> {
                        saved += entry.dataId
                        if (stopStale(prepared.drop(index + 1))) return@saveStep
                        onLocalSaveCommitted(entry.dataId)
                        applySavedData(entry, result.value)
                        main.showOperation("保存中 $index/${prepared.size} 件: ${entry.dataId}（バックアップ整理）")
                        val affected = if (fullBackupSucceeded) setOfNotNull(entry.dataId, entry.operation?.toRecord()?.sourceId) else null
                        requestBackup(allowDuringOperation = true, affectedIds = affected, requireCurrent = true) { success ->
                            if (stopStale(prepared.drop(index + 1))) return@requestBackup
                            fullBackupSucceeded = success
                            if (!success) {
                                logger.error("一括保存のバックアップ整理に失敗しました: id={}", entry.dataId)
                                postFailed += entry.dataId
                                postErrors += "${entry.dataId}: バックアップ整理に失敗しました。"
                            }
                            next()
                        }
                    }
                }
            }
        }

        fun confirm() {
            if (stopStale(prepared)) return
            if (prepared.isEmpty()) { finish(); return }
            val details = combinedSaveChangeDetails(prepared.map { entry ->
                entry.dataId to saveChangeDetails(entry.dataId, entry.operation, entry.original, entry.localData,
                    entry.automaticallyMergedData, entry.previewData.takeIf { it != entry.automaticallyMergedData }, ::itemDisplayText)
            }) + failed.map { (id, reason) -> "保存準備失敗: $id（$reason）" } + cancelled.map { "確認キャンセル: $it" }
            main.showOperation("保存準備 ${targets.size}/${targets.size} 件（まとめた差分を確認）")
            if (CustomDialog.confirmation().title("すべて保存")
                    .header("以下の変更をまとめてストアへ反映します（${prepared.size} 件）")
                    .content(details).scrollableContent()
                    .additionalContent(VBox(12.0).apply { prepared.forEach { entry ->
                        children += savePreviewContent(entry.original, entry.previewData)
                    } }).okButton("すべて保存", Color.DODGERBLUE)
                    .owner(main.currentStage).show()) persist(0)
            else { notExecuted += prepared.map { it.dataId }; finish() }
        }

        fun prepare(index: Int) {
            if (stopStale(targets.drop(index))) return
            if (index == targets.size) {
                try { confirm() } catch (error: Exception) {
                    logger.error("一括保存の差分確認に失敗しました", error)
                    notExecuted += prepared.map { it.dataId }
                    postErrors += "差分確認に失敗しました。未実行の編集内容は保持されています。"
                    finish()
                }
                return
            }
            val entry = targets[index]
            val operation = entry.operation
            main.showOperation("保存準備 $index/${targets.size} 件: ${entry.dataId}")
            fun next(preview: SaveDataPreview<T>?) {
                if (preview == null) cancelled += entry.dataId
                else prepared += entry.copy(automaticallyMergedData = preview.automaticallyMergedData,
                    previewData = preview.data, saveData = preview.data.takeUnless { operation is PendingStoreOperation.Delete })
                main.showOperation("保存準備 ${index + 1}/${targets.size} 件: ${entry.dataId}")
                Platform.runLater { prepare(index + 1) }
            }
            fun fail(error: Throwable) {
                logger.error("一括保存の準備に失敗しました: id={}", entry.dataId, error)
                failed[entry.dataId] = "最新データの取得・保存準備に失敗しました。"
                Platform.runLater { prepare(index + 1) }
            }
            next(SaveDataPreview(entry.localData, null))

        }
        prepare(0)
    }

    private fun hasUnsavedChanges(id: String): Boolean {
        val editing = editingDataMap[id] ?: return false
        val saved = sourceState.saved[id]
        return editing != saved || pendingStoreOperations[id] != null || inputDrafts[documentKey(id)].orEmpty() != savedInputDrafts[documentKey(id)].orEmpty()
    }

    private fun savePreviewContent(before: T?, current: T) = dataService.itemReferences.snapshot.let { snapshot ->
        val items = snapshot.byInternalId.takeIf { snapshot.status == ItemReferenceStatus.READY }
        VBox(12.0, recipeDataReview("前回保存", before, items, dataService.itemPreviews),
            recipeDataReview("編集・保存予定", current, items, dataService.itemPreviews))
    }

    private fun confirmSaveChanges(prepared: PreparedSave<T>): Boolean {
        val details = saveChangeDetails(
            dataId = prepared.dataId,
            operation = prepared.operation,
            original = prepared.original,
            current = prepared.localData,
            automaticallyMerged = prepared.automaticallyMergedData,
            finalData = prepared.previewData.takeIf { it != prepared.automaticallyMergedData },
            itemDisplayText = ::itemDisplayText
        ) + if (inputDrafts[documentKey(prepared.dataId)].orEmpty() != savedInputDrafts[documentKey(prepared.dataId)].orEmpty())
            listOf("入力途中の値をローカルに保持します。サーバーへは反映しません。") else emptyList()
        if (details.isEmpty()) return false
        return CustomDialog.confirmation()
            .title("変更内容を保存")
            .header("以下の変更をローカルに保存します")
            .content(details)
            .scrollableContent()
            .additionalContent(savePreviewContent(prepared.original, prepared.previewData))
            .okButton("保存", Color.DODGERBLUE)
            .owner(main.currentStage)
            .show()
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

    /** 最新の編集内容を退避し、成功したときだけキャッシュを解放します。結果はJavaFXスレッドで通知します。 */
    fun onClose(onComplete: (Boolean) -> Unit) {
        prepareClose { success ->
            if (success) completeClose()
            onComplete(success)
        }
    }

    /** 接続終了前の退避だけを行い、終了証明が得られるまで編集キャッシュを保持します。 */
    internal fun prepareClose(onComplete: (Boolean) -> Unit) {
        if (closed) { onComplete(true); return }
        if (uploadAction?.busy == true || categoryUploading || saveRunning || dataService.activeSaves > 0 || syncBusy || repairBusy || importApplying || closing) { onComplete(false); return }
        main.mainContentContainer.requestFocus()
        commitPendingTextEdit()
        closing = true
        externalDocument?.also { externalDocument = null; it.ready(false) }
        importGeneration++
        importBusy = false
        uploadMenuItem?.isDisable = false
        main.finishImportProgress()
        loadGeneration++
        finishLoading()
        cancelSidebarPreload()
        stopAutoSaveTimer()
        main.showOperation("編集内容を退避中")
        val complete: (Boolean) -> Unit = { success ->
            if (!success) {
                cancelClose()
                main.showTimedTopLabel("退避に失敗しました。編集内容を保持しています。終了操作で再試行できます。", Color.ORANGERED, 10.0)
            }
            onComplete(success)
        }
        if (restoringBackup) complete(true)
        else requestBackup(allowDuringOperation = true, onComplete = complete)
    }

    /** 終了失敗時は編集内容を維持して操作と自動退避を再開します。 */
    internal fun cancelClose() {
        if (!closing) return
        closing = false
        main.finishOperation()
        if (restoringBackup) start()
        else {
            preloadSidebarDataForVisualStates(registeredSidebarIds)
            startAutoSaveTimer()
        }
    }

    /** 退避と接続終了を確認した後、JavaFXスレッドで呼び出します。 */
    internal fun completeClose() {
        if (closed) return
        check(closing)
        main.finishOperation()
        closing = false
        dataService.activeSavesProperty.removeListener(saveListener)
        main.shortcutRoot.sceneProperty().removeListener(historySceneListener)
        connectedService.uploadingDocuments.removeListener(uploadDocumentsListener)
        connectedService.unresolvedUploadDocuments.removeListener(uploadDocumentsListener)
        connectedService.uploadResultProperty.removeListener(uploadResultListener)
        detachHistoryFilters()
        idTabs.dispose()
        main.clearShortcuts()

        editingDataMap.clear()
        originalDataMap.clear()
        mergeConflicts.clear()
        pendingStoreOperations.clear()
        editHistory.clear()
        sourceStates.clear()
        registeredSidebarIds.clear()
        sidebarStates.clear()
        sidebarView?.showRows(emptyList())
        sidebarView = null
        currentSelectedDataId = null
        pendingPreloadIds = emptyList()
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

        closed = true
        afterClose()
    }

    /** ローカル退避とキャッシュ解放が成功した後に、種別固有の資源を解放します。 */
    protected open fun afterClose() = Unit

    private fun injectAutoSaveCaches(
        editingCaches: Map<String, T>, originalCaches: Map<String, T>, operations: List<PendingStoreOperation>
    ) {
        editingDataMap.putAll(editingCaches)
        originalDataMap.putAll(originalCaches)
        pendingStoreOperations.putAll(operations.associateBy(PendingStoreOperation::currentId))
        restoredCacheCount = editingCaches.keys.count(::hasUnsavedChanges)
    }

    /** 初期データの表示または空一覧の確定後に、編集用の参照一覧を背景取得します。 */
    protected open fun loadEditorReferences() {}

    protected abstract fun setupMainContent(selectData: T)

    protected fun <F : TextInputControl> retainInputDraft(
        id: String, fieldKey: String, field: F, valid: (String) -> Boolean
    ): F {
        val key = documentKey(id)
        field.properties["retain-input-draft"] = true
        inputDrafts[key]?.get(fieldKey)?.let { field.text = it }
        field.properties["input-draft-invalid"] = !valid(field.text)
        field.textProperty().addListener { _, _, value ->
            field.properties["input-draft-invalid"] = !valid(value)
            val drafts = inputDrafts.getOrPut(key) { mutableMapOf() }
            if (valid(value)) drafts.remove(fieldKey) else drafts[fieldKey] = value
            refreshSidebarState(id)
        }
        return field
    }

    /** 一覧内の欄を削除・移動したとき、対応する未確定入力も同じ欄へ移します。 */
    protected fun moveInputDraft(id: String, from: String, to: String?) {
        val drafts = inputDrafts[documentKey(id)] ?: return
        val value = drafts.remove(from) ?: return
        if (to != null) drafts[to] = value
        refreshSidebarState(id)
    }

    /** 指定したデータIDの選択、変更、検証状態をサイドバーへ反映します。 */
    protected open fun refreshSidebarState(id: String) {
        editingDataMap[id]?.let { data ->
            editHistory.initialize(documentKey(id), historySnapshot(id, data))
            if (main.mainContentContainer.scene?.focusOwner !is TextInputControl) {
                recordHistorySnapshot(id)
            }
        }
        if (id in documentState.ids) updateDocumentTabs()
        if (id !in registeredSidebarIds) return
        val data = editingDataMap[id]
        val pendingDeletion = isPendingStoreDeletion(id)
        val validationErrors = if (pendingDeletion) emptyList() else data?.let(::validationErrors).orEmpty()
        val state = SidebarDataState(
            selected = id == currentSelectedDataId,
            modified = hasUnsavedChanges(id),
            serverTracked = dataService.isRemote,
            unreflected = localDocumentStates[documentKey(id)]?.unreflected,
            hasWarnings = validationErrors.any(SushiEricValidationError::isWarning),
            hasErrors = validationErrors.any(SushiEricValidationError::isError) || inputDrafts[documentKey(id)].orEmpty().isNotEmpty(),
            localOnly = id !in remoteDataIds,
            pendingDeletion = pendingDeletion,
            validationStatus = if (data != null) SidebarValidationStatus.CHECKED
                else sidebarValidation[id] ?: SidebarValidationStatus.UNCHECKED
        )

        sidebarStates[id] = state
        sidebarView?.refreshData(id)
        refreshActionMenuState()
    }

    /** フォーカス状態にかかわらず、現在の編集内容を履歴へ記録します。 */
    protected fun recordHistorySnapshot(id: String) {
        if (main.shortcutRoot.scene?.let(ShortcutManager::isComposing) == true) return
        editingDataMap[id]?.let { data ->
            editHistory.initialize(documentKey(id), historySnapshot(id, data))
            if (editHistory.record(documentKey(id), historySnapshot(id, data))) { editGeneration++; onDocumentHistoryRecorded(id) }
        }
    }

    private fun installHistoryCommitFilter() {
        val next = main.shortcutRoot.scene
        if (historyScene === next) return
        detachHistoryFilters()
        historyScene = next
        next?.addEventFilter(MouseEvent.MOUSE_PRESSED, historyMouseHandler)
        next?.addEventFilter(KeyEvent.KEY_PRESSED, historyKeyHandler)
    }

    private fun detachHistoryFilters() {
        historyScene?.removeEventFilter(MouseEvent.MOUSE_PRESSED, historyMouseHandler)
        historyScene?.removeEventFilter(KeyEvent.KEY_PRESSED, historyKeyHandler)
        historyScene = null
    }

    private fun owns(node: Node?): Boolean {
        var current = node
        while (current != null) { if (current === main.shortcutRoot) return true; current = current.parent }
        return false
    }

    /** 一覧取得後だけ、存在するIDと復元済み編集キャッシュへタブを接続します。 */
    protected fun restoreDocumentTabs(ids: List<String>, preferred: String?): String? {
        val saved = if (!documentsRestored) (WorkspaceManager.current?.tabs(main.shortcutRoot, dataService.cacheIdentity)
            ?: WorkspaceManager.current?.tabs(main.shortcutRoot).takeIf { dataService === connectedService }) else documentState
        documentsRestored = true
        val requested = (externalDocument?.key?.takeIf { it.source == dataService.documentSource }?.id ?: preferred)
            ?.removeSuffix(".yml")?.takeIf { it in ids }
        val retained = saved?.ids.orEmpty().filter { it in ids }
        val selected = requested ?: saved?.selected?.takeIf { it in retained } ?: retained.firstOrNull()
            ?: ids.firstOrNull().takeIf { saved == null }
        documentState = IdTabsState(retained, selected).let { if (selected == null) it else it.open(selected) }
        updateDocumentTabs()
        return selected
    }

    private fun updateDocumentTabs() {
        idTabs.show(documentState, if (dataService.isRemote) "サーバー" else "ローカル", ::hasUnsavedChanges)
        WorkspaceManager.current?.tabs(main.shortcutRoot, documentState, dataService.cacheIdentity)
    }

    private fun closeDocument(id: String) {
        if (id !in documentState.ids || closeBusy || storeOperationBlocked) return
        main.mainContentContainer.requestFocus()
        commitPendingTextEdit()
        requestBackup { success ->
            if (!success) {
                CustomDialog.error().owner(main.currentStage).header("退避に失敗しました。IDタブと編集内容を保持しています。").show()
                return@requestBackup
            }
            documentState = documentState.close(id)
            if (currentSelectedDataId == id) {
                val next = documentState.selected
                if (next != null) selectTab(next)
                else { currentSelectedDataId = null; main.mainContentContainer.children.clear() }
            }
            updateDocumentTabs()
            refreshSidebarState(id)
            refreshSyncButtonState()
        }
    }

    /** 選択中データの入力中の変更を、1件の履歴として確定します。 */
    private fun commitPendingTextEdit() {
        currentSelectedDataId?.let(::recordHistorySnapshot)
    }

    /** 現在選択中のデータを1段階前の編集状態へ戻します。 */
    fun onUndo(): Boolean {
        if (storeOperationBlocked || main.shortcutRoot.scene?.let(ShortcutManager::isComposing) == true) return false
        val id = currentSelectedDataId ?: return false
        if (isUploadFrozen(id)) return false
        val current = editingDataMap[id] ?: return false
        if (editHistory.record(documentKey(id), historySnapshot(id, current))) { editGeneration++; onDocumentHistoryRecorded(id) }
        when (traverseLocalHistory(id, false)) {
            LocalHistoryResult.HANDLED -> return true
            LocalHistoryResult.REJECTED -> return false
            LocalHistoryResult.NOT_APPLICABLE -> Unit
        }
        val restored = editHistory.undo(documentKey(id), historySnapshot(id, current)) ?: return false
        onDocumentHistoryTraversed(id, false)
        applyHistoryData(id, restored, "元に戻しました")
        return true
    }

    /** 現在選択中のデータで、直前に元へ戻した編集をやり直します。 */
    fun onRedo(): Boolean {
        if (storeOperationBlocked || main.shortcutRoot.scene?.let(ShortcutManager::isComposing) == true) return false
        val id = currentSelectedDataId ?: return false
        if (isUploadFrozen(id)) return false
        val current = editingDataMap[id] ?: return false
        if (editHistory.record(documentKey(id), historySnapshot(id, current))) { editGeneration++; onDocumentHistoryRecorded(id) }
        when (traverseLocalHistory(id, true)) {
            LocalHistoryResult.HANDLED -> return true
            LocalHistoryResult.REJECTED -> return false
            LocalHistoryResult.NOT_APPLICABLE -> Unit
        }
        val restored = editHistory.redo(documentKey(id), historySnapshot(id, current)) ?: return false
        onDocumentHistoryTraversed(id, true)
        applyHistoryData(id, restored, "やり直しました")
        return true
    }

    private fun applyHistoryData(id: String, restored: EditingSnapshot<T>, notification: String) {
        editGeneration++
        val data = restoreDocumentHistory(id, restored.data.deepCopy()).apply { this.id = id }
        editingDataMap[id] = data
        inputDrafts[documentKey(id)] = restoreDocumentHistoryRaw(id, restored.raw).toMutableMap()
        restoreLoreHistoryIds(id, restored.loreIds)
        onHistoryDataRestored(id)
        setupMainContent(data)
        refreshSidebarState(id)
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
        items.repairWarnings.isDisable = storeOperationBlocked || errors.none { it.isWarning }
        items.repairErrors.isDisable = errors.none { it.isError }
    }

    /** 現在の参照アイテム集合を使って検証結果を取得します。 */
    protected open fun validationErrors(data: T): List<SushiEricValidationError> =
        dataAccess.validationErrors(data, availableItemInternalIds())
            .filterNot { error -> isHiddenValidationError(data, error) }

    /**
     * 表示・集計の対象から外す検証結果かどうかを返します。
     *
     * 判定を保留したい場合（参照先の一覧を読み込む前など）や、現在の設定では適用されない
     * 項目の結果を出したくない場合に、各エディターが上書きします。既定では何も外しません。
     *
     * @param data 検証対象のデータ。
     * @param error SushiEricDataModel側の検証が返した結果。
     * @return 表示・集計の対象から外す場合は`true`。
     */
    protected open fun isHiddenValidationError(data: T, error: SushiEricValidationError): Boolean = false

    /**
     * エラーに対応する入力UIへ移動します。
     *
     * データ固有の画面構造を共通基盤へ持ち込まないため、各エディターが実装します。
     */
    protected open fun focusValidationError(error: SushiEricValidationError): Boolean = false

    internal fun refreshActionMenuState() {
        val selectedId = currentSelectedDataId
        val selectedErrors = selectedId
            ?.let(editingDataMap::get)
            ?.let(::validationErrors)
            .orEmpty()
        val allErrors = editingDataMap.filterKeys { !isUploadFrozen(it) }.values.flatMap(::validationErrors)
        val frozen = selectedId?.let(::isUploadFrozen) == true

        saveMenuItem?.isDisable = frozen || selectedId == null || !hasUnsavedChanges(selectedId)
        saveAllMenuItem?.isDisable = editingDataMap.keys.none { hasUnsavedChanges(it) && !isUploadFrozen(it) }
        savedUploadMenuItem?.isDisable = frozen || selectedId == null || !connectedService.isRemote || uploadAction?.busy == true
        val textInput = main.shortcutRoot.scene?.focusOwner as? TextInputControl
        undoMenuItem?.isDisable = frozen || if (textInput != null) !textInput.isUndoable else selectedId == null || !(editHistory.canUndo(documentKey(selectedId)) || hasLocalHistory(selectedId, false))
        redoMenuItem?.isDisable = frozen || if (textInput != null) !textInput.isRedoable else selectedId == null || !(editHistory.canRedo(documentKey(selectedId)) || hasLocalHistory(selectedId, true))
        repairWarningsMenuItem?.isDisable = frozen || selectedErrors.none { it.isWarning }
        repairErrorsMenuItem?.isDisable = frozen || selectedErrors.none { it.isError }
        repairAllWarningsMenuItem?.isDisable = allErrors.none { it.isWarning }
        repairAllErrorsMenuItem?.isDisable = allErrors.none { it.isError }

        syncMenuItem?.isDisable = frozen || !dataService.isRemote || syncBusy || selectedId == null ||
            selectedId?.let(::hasPendingStoreOperation) == true
        syncAllMenuItem?.isDisable = !dataService.isRemote || editingDataMap.keys.any(::isUploadFrozen) || syncBusy || pendingStoreOperations.isNotEmpty()
    }

    private fun repairWarnings(ids: Collection<String>, confirmBatch: Boolean) {
        if (storeOperationBlocked || syncBusy || saveRunning) return
        main.mainContentContainer.requestFocus()
        commitPendingTextEdit()
        val targets = ids.filterNot(::isUploadFrozen).mapNotNull { id ->
            val data = editingDataMap[id] ?: return@mapNotNull null
            val warnings = validationErrors(data).filter { it.isWarning }
            warnings.takeIf { it.isNotEmpty() }?.let { Triple(id, data.deepCopy(), it) }
        }
        if (targets.isEmpty()) return

        repairBusy = true
        main.showOperation("警告修正 0/${targets.size} 件")
        fun finish() {
            repairBusy = false
            main.finishOperation()
            main.actionButtonContainer.isDisable = storeOperationBlocked
            main.sidebarContainer.isDisable = storeOperationBlocked
        }
        fun fail(error: Throwable) {
            finish()
            logger.error("警告の修正中に例外が発生しました", error)
            CustomDialog.error(ErrorType.INTERNAL_ERROR)
                .content("警告の修正中に例外が発生しました。編集内容は保持しています。")
                .owner(main.currentStage)
                .show()
        }
        try {
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
                if (!confirmed) { finish(); return }
            }

            val task = object : Task<WarningRepairBatch<T>>() {
                override fun call(): WarningRepairBatch<T> {
                    val updated = mutableMapOf<String, T>()
                    val applied = mutableListOf<String>()
                    val failures = mutableListOf<String>()

                    targets.forEachIndexed { index, (id, snapshot, warnings) ->
                        var data = snapshot
                        var changed = false
                        warnings.sortedByDescending { (it.key as? Int) ?: Int.MIN_VALUE }
                            .forEach { warning ->
                                val description = validationRepairRegistry.description(warning) ?: warning.message
                                updateMessage("警告修正 $index/${targets.size} 件 / $id / $description")
                                val candidate = data.deepCopy()
                                val result = try {
                                    validationRepairRegistry.repair(candidate, warning)
                                } catch (error: Exception) {
                                    logger.error("警告の修正に失敗しました: id={}, warning={}", id, warning, error)
                                    ValidationRepairResult.Failed("修正中に例外が発生しました (${warning.message})")
                                }
                                when (result) {
                                    is ValidationRepairResult.Applied -> {
                                        data = candidate
                                        changed = true
                                        applied += "$id: ${result.description}"
                                    }
                                    is ValidationRepairResult.Failed -> failures += "$id: ${result.reason}"
                                    ValidationRepairResult.Unsupported ->
                                        failures += "$id: 自動修正に対応していません (${warning.message})"
                                }
                            }
                        if (changed) updated[id] = data
                        updateMessage("警告修正 ${index + 1}/${targets.size} 件 / $id")
                    }
                    return WarningRepairBatch(updated, applied, failures)
                }
            }
            task.messageProperty().addListener { _, _, message -> main.showOperation(message) }
            task.setOnSucceeded {
                val result = task.value
                result.updated.forEach { (id, data) -> editingDataMap[id] = data }
                val failures = result.failures.toMutableList()
                try {
                    result.updated.keys.forEach(::refreshSidebarState)
                    currentSelectedDataId
                        ?.takeIf(result.updated::containsKey)
                        ?.let { setupMainContent(editingDataMap.getValue(it)) }
                } catch (error: Exception) {
                    logger.error("修正後の表示更新に失敗しました", error)
                    failures += "表示更新に失敗しました。修正した編集内容は保持しています。"
                }
                val completed: (Boolean) -> Unit = { backedUp ->
                    finish()
                    if (!backedUp) failures += "バックアップに失敗しました。修正した編集内容は保持しています。退避を再試行してください。"
                    if (failures.isNotEmpty()) {
                        CustomDialog.error()
                            .title("警告修正の結果")
                            .header("修正済み: ${result.applied.size} 件 / 未対応・失敗: ${failures.size} 件")
                            .content(failures)
                            .owner(main.currentStage)
                            .show()
                    } else if (result.applied.isNotEmpty()) {
                        main.showTimedTopLabel("${result.applied.size} 件の警告を修正し、バックアップしました", Color.GREENYELLOW)
                    }
                }
                if (result.updated.isEmpty()) completed(true)
                else {
                    main.showOperation("警告修正 ${targets.size}/${targets.size} 件 / ローカルバックアップを更新しています")
                    try {
                        requestBackup(allowDuringOperation = true, onComplete = completed)
                    } catch (error: Exception) {
                        logger.error("修正後のバックアップ開始に失敗しました", error)
                        completed(false)
                    }
                }
            }
            task.setOnFailed { fail(task.exception) }
            dataService.executeIo(task)
        } catch (error: Exception) {
            fail(error)
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
    protected fun preloadSidebarDataForVisualStates(ids: Collection<String>, retryFailed: Boolean = false) {
        if (closed || closing || !dataService.store.isAvailable || (sessionBound && EditorSession.dataService !== connectedService)) return
        if (main.loadingRunning) {
            pendingPreloadIds = ids.toList()
            return
        }
        cancelSidebarPreload()
        val generation = sidebarPreloadGeneration
        val missingIds = ids.distinct().filter { it in registeredSidebarIds && it !in editingDataMap && !isPendingStoreDeletion(it) &&
            (retryFailed || sidebarValidation[it] != SidebarValidationStatus.FAILED) }
        val total = missingIds.size
        var loaded = 0
        var finished = 0
        fun accepts() = !closed && !closing && generation == sidebarPreloadGeneration &&
            dataService.store.isAvailable && (!sessionBound || EditorSession.dataService === connectedService)
        fun progress() {
            val failed = registeredSidebarIds.count { sidebarValidation[it] == SidebarValidationStatus.FAILED && it !in editingDataMap }
            main.showSidebarStatus("検証用データ取得 $loaded / $total 件" +
                (if (finished < total) "（確認中）" else "（完了）") +
                (if (failed > 0) " / 取得失敗 $failed 件" else ""),
                if (finished == total && failed > 0) ({
                    if (!storeOperationBlocked && !saveRunning) preloadSidebarDataForVisualStates(
                        registeredSidebarIds.filter { sidebarValidation[it] == SidebarValidationStatus.FAILED }, retryFailed = true)
                }) else null)
        }
        sidebarProgress = ::progress
        if (total == 0) {
            if (registeredSidebarIds.any { sidebarValidation[it] == SidebarValidationStatus.FAILED && it !in editingDataMap }) progress()
            else { sidebarProgress = null; main.showSidebarStatus("") }
            return
        }
        missingIds.forEach { sidebarValidation[it] = SidebarValidationStatus.CHECKING; refreshSidebarState(it) }
        progress()
        fun loadNext(index: Int) {
            if (!accepts() || index >= total) return
            val id = missingIds[index]
            fun apply(result: StoreResult<Pair<T, LocalDocumentRecord>>) {
                if (!accepts()) return
                finished++
                when (result) {
                    is StoreResult.Success -> {
                        loaded++
                        if (id in registeredSidebarIds && id !in editingDataMap && !isPendingStoreDeletion(id)) {
                            val data = result.value.first
                            observeDocument(result.value.second)
                            editingDataMap[id] = data.deepCopy()
                            originalDataMap[id] = data.deepCopy()
                            sourceState.saved[id] = data.deepCopy()
                        }
                        sidebarValidation[id] = SidebarValidationStatus.CHECKED
                    }
                    is StoreResult.Failure -> {
                        sidebarValidation[id] = SidebarValidationStatus.FAILED
                        logger.warn("サイドバー状態確認用データを読み込めませんでした: {}", result.error)
                    }
                }
                refreshSidebarState(id)
                progress()
                loadNext(index + 1)
            }
            val task = object : Task<StoreResult<Pair<T, LocalDocumentRecord>>>() {
                override fun call(): StoreResult<Pair<T, LocalDocumentRecord>> = dataAccess.observeStore(id)
            }
            task.setOnSucceeded { apply(task.value) }
            task.setOnFailed { apply(StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, id, cause = task.exception))) }
            dataService.executeIo(task)
        }
        loadNext(0)
    }

    private fun cancelSidebarPreload() {
        sidebarProgress = null
        sidebarPreloadGeneration++
        sidebarValidation.filterValues { it == SidebarValidationStatus.CHECKING }.keys.toList().forEach {
            sidebarValidation[it] = SidebarValidationStatus.UNCHECKED
            refreshSidebarState(it)
        }
        main.showSidebarStatus("")
    }

    /** 保存確認へ表示するアイテム識別子を返します。公開IDを解決できる画面では上書きします。 */
    protected open fun itemDisplayText(id: ItemInternalId): String = id.value

    /** 現在の検証で参照可能なアイテム内部IDを返します。 */
    protected open fun availableItemInternalIds(): Set<ItemInternalId> =
        editingDataMap.values
            .filterIsInstance<MutableItemBaseData>()
            .mapTo(mutableSetOf()) { item -> item.internalId }

    /**
     * JavaFXスレッドで取得した世代の編集・保留操作を背景で退避します。編集中の操作は止めません。
     * 完了通知はJavaFXスレッドで行い、その後に加わった編集内容の退避完了は保証しません。
     */
    protected fun executeAutoSave(onComplete: (Boolean) -> Unit = {}) {
        requestBackup(onComplete = onComplete)
    }

    private fun persistPendingStoreState() {
        pendingStoreOperations.entries.removeIf { !it.value.isEffective() }
        requestBackup()
    }

    private fun requestBackup(
        allowDuringOperation: Boolean = false,
        affectedIds: Set<String>? = null,
        requireCurrent: Boolean = false,
        onComplete: (Boolean) -> Unit = {}
    ) {
        if (closed || (!allowDuringOperation && saveRunning)) {
            onComplete(false)
            return
        }
        val access = dataAccess
        val generation = loadGeneration
        val affected = affectedIds?.toMutableSet()?.apply {
            addAll(removedBackupIds)
            do {
                val previousSize = size
                pendingStoreOperations.values.map(PendingStoreOperation::toRecord).forEach { pending ->
                    if (pending.currentId in this || pending.sourceId in this) {
                        add(pending.currentId)
                        pending.sourceId?.let(::add)
                    }
                }
            } while (size != previousSize)
        }
        val entries = editingDataMap.filterKeys { affected == null || it in affected }.mapNotNull { (id, editing) ->
            val original = originalDataMap[id] ?: return@mapNotNull null
            if (!hasUnsavedChanges(id)) return@mapNotNull null
            id to (editing.deepCopy() to original.deepCopy())
        }.toMap()
        val removed = removedBackupIds + ((affected ?: editingDataMap.keys) - entries.keys)
        fun pending() = pendingStoreOperations.values.map(PendingStoreOperation::toRecord)
            .filter { affected == null || it.currentId in affected || it.sourceId in affected }
        val snapshot = LocalBackupSnapshot(++backupGeneration, entries, removed,
            pending(),
            entries.keys.associateWith { inputDrafts[documentKey(it)].orEmpty().toMap() },
            versions(entries.keys + removed))
        val backupNotice = main.notificationScope().copy(target = "")
        if (!saveRunning) main.showBackupStatus("ローカルバックアップを更新しています", scope = backupNotice)
        access.persistBackup(snapshot).whenComplete { success, error ->
            Platform.runLater {
                val saved = error == null && success == true
                if (error != null) logger.error("ローカル退避に失敗しました", error)
                val lifetime = !closed && access === dataAccess && generation == loadGeneration && snapshot.generation == backupGeneration
                val currentIds = editingDataMap.keys.filter { affected == null || it in affected }.filterTo(mutableSetOf(), ::hasUnsavedChanges)
                val current = lifetime && snapshot.entries.keys == currentIds &&
                        snapshot.entries.all { (id, pair) ->
                            editingDataMap[id] == pair.first && originalDataMap[id] == pair.second
                        } && snapshot.entries.keys.all { inputDrafts[documentKey(it)].orEmpty() == snapshot.raw[it].orEmpty() } &&
                        snapshot.pending == pending()
                if (lifetime) {
                    if (saved) removedBackupIds.removeAll(snapshot.removedIds)
                    if (!saveRunning && affected == null) main.showBackupStatus(
                        when {
                            !saved -> "バックアップに失敗しました。編集内容は保持されています。"
                            current -> "ローカルバックアップを更新しました"
                            else -> "退避後に編集されています。最新の変更は次回バックアップします。"
                        },
                        if (!saved) Color.ORANGERED else if (current) Color.GREENYELLOW else Color.WHITE,
                        scope = backupNotice, saved = saved
                    )
                }
                onComplete(saved && (!requireCurrent || current))
            }
        }
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
        val id = currentSelectedDataId ?: return
        if (isUploadFrozen(id) || hasPendingStoreOperation(id)) return
        fetchAndApply(id)
    }

    private fun synchronizeAll() {
        if (pendingStoreOperations.isNotEmpty() || editingDataMap.keys.any(::isUploadFrozen)) return
        fetchAndApply(null)
    }

    private fun fetchAndApply(selectedId: String?) {
        commitPendingTextEdit()
        setSyncBusy(true)
        dataService.beginSave()
        main.showOperation("取得基準を確認しています")
        val task = object : Task<StoreResult<Pair<EditorSyncSnapshot<T>, LocalDocumentSnapshot>>>() {
            override fun call(): StoreResult<Pair<EditorSyncSnapshot<T>, LocalDocumentSnapshot>> {
                val records = when (val loaded = dataService.documents.read()) {
                    is StoreResult.Success -> loaded.value
                    is StoreResult.Failure -> return loaded
                }
                val fetched = if (selectedId == null) syncService.fetchAll()
                else when (val loaded = syncService.fetchOne(selectedId)) {
                    is StoreResult.Success -> StoreResult.Success(EditorSyncSnapshot(emptyList(), emptyList(), mapOf(selectedId to loaded.value)))
                    is StoreResult.Failure -> if (loaded.error.code == StoreErrorCode.FILE_NOT_FOUND)
                        StoreResult.Success(EditorSyncSnapshot(emptyList(), emptyList(), emptyMap())) else loaded
                }
                return when (fetched) {
                    is StoreResult.Success -> StoreResult.Success(fetched.value to records)
                    is StoreResult.Failure -> fetched
                }
            }
        }
        task.setOnSucceeded {
            if (closed || !syncBusy) return@setOnSucceeded
            when (val result = task.value) {
                is StoreResult.Success -> confirmFetched(result.value.first, result.value.second, selectedId)
                is StoreResult.Failure -> {
                    finishSync()
                    showSyncFailure(selectedId ?: dataAccess.displayName, result)
                }
                null -> finishSync("取得に失敗しました", Color.ORANGERED)
            }
        }
        task.setOnFailed {
            logger.error("取得基準の読み込みに失敗しました", task.exception)
            finishSync("取得に失敗しました。ローカル文書を保持しています。", Color.ORANGERED)
        }
        dataService.executeIo(task)
    }

    private fun confirmFetched(
        snapshot: EditorSyncSnapshot<T>,
        records: LocalDocumentSnapshot,
        selectedId: String?
    ) {
        val previous = records.documents.filter { it.key.category == dataAccess.dataType.categoryDirName && it.movedTo == null }.associateBy { it.key.id }
        val ids = selectedId?.let(::setOf) ?: (previous.keys + snapshot.data.keys)
        val confirmed = CustomDialog.confirmation()
            .title("取得の確認")
            .header("最後に取得したサーバー基準を更新します")
            .content("ローカル保存済みの未送信内容は保持し、編集中データは取得結果と比較します。競合がある場合は内容を確認します。キャンセルすると現在の文書を保持します。")
            .owner(main.currentStage).show()
        if (!confirmed) {
            finishSync("取得をキャンセルしました", Color.WHITE)
            return
        }
        val editing = linkedMapOf<String, T>()
        for (id in ids) {
            val record = previous[id]
            val current = if (record?.deleted == true) null
                else editingDataMap[id] ?: record?.editing?.let { dataAccess.decodeDocument(id, it) }
            val latest = snapshot.data[id]
            val baseText = if (record?.reflectedSavedHash != null && record.unreflected == false) record.saved else record?.baseline
            val base = baseText?.let { dataAccess.decodeDocument(id, it) }
            val selected = when {
                current == null -> if (record?.deleted == true && record.unsaved) null else latest
                latest == null -> {
                    val changed = record == null || !record.baselineKnown ||
                        record.unreflected == true || hasUnsavedChanges(id)
                    if (changed) current else null
                }
                base == null -> {
                    if (record?.saved != null && current != latest) {
                        val keep = CustomDialog.confirmation().title("取得基準がない文書")
                            .header(id + " のローカル内容を保持して基準だけ取得しますか？")
                            .content("比較元がないため自動統合しません。キャンセルすると取得結果を適用しません。")
                            .owner(main.currentStage).show()
                        if (!keep) { finishSync("取得をキャンセルしました", Color.WHITE); return }
                        current
                    } else latest
                }
                else -> {
                    val merged = dataAccess.merge(base, current, latest)
                    val conflictScope = main.notificationScope().copy(target = id)
                    val notifications = EditorSession.notifications
                    if (merged.conflicts.isNotEmpty()) notifications.condition(conflictScope, "fetch-conflict", "取得した内容と編集中データの競合を確認してください。")
                    val content = if (base is RecipeDataView && merged.conflicts.isNotEmpty()) {
                        val items = dataService.itemReferences.snapshot.let { reference ->
                            reference.byInternalId.takeIf { reference.status == ItemReferenceStatus.READY }
                        }
                        VBox(12.0,
                            recipeDataReview("取得基準", base, null, dataService.itemPreviews),
                            recipeDataReview("ローカル編集", current, items, dataService.itemPreviews),
                            recipeDataReview("最新サーバー", latest, null, dataService.itemPreviews))
                    } else null
                    val selectedPaths = MergeConflictDialog.show(main.currentStage, id, merged.conflicts, content)
                        ?: run { finishSync("取得をキャンセルしました", Color.WHITE); return }
                    notifications.resolve(conflictScope, "fetch-conflict")
                    merged.resolveWithLocal(selectedPaths)
                }
            }
            if (selected != null) editing[id] = selected.deepCopy()
        }
        main.showOperation("確認した取得基準をローカルへ保持しています")
        dataService.submitIo {
            when (val accepted = dataAccess.acceptFetched(records, snapshot.data, editing, ids,
                snapshot.directories.takeIf { selectedId == null })) {
                is StoreResult.Failure -> accepted
                is StoreResult.Success -> StoreResult.Success(accepted.value to accepted.value.mapNotNull { record ->
                    record.saved?.let { record.key.id to dataAccess.decodeDocument(record.key.id, it) }
                }.toMap())
            }
        }.whenComplete { result, error ->
            Platform.runLater {
                if (closed || !syncBusy) return@runLater
                if (error != null || result is StoreResult.Failure) {
                    logger.error("取得基準の保存に失敗しました", error)
                    finishSync("取得基準を保存できませんでした。ローカル文書を保持しています。", Color.ORANGERED)
                    return@runLater
                }
                val accepted = when (result) {
                    is StoreResult.Success -> result.value
                    else -> { finishSync("取得基準を保存できませんでした", Color.ORANGERED); return@runLater }
                }
                val (stored, savedData) = accepted
                stored.forEach { record ->
                    val id = record.key.id
                    observeDocument(record)
                    savedInputDrafts[record.key] = record.savedRaw
                    val value = editing[id]
                    if (value == null) {
                        editingDataMap.remove(id)
                        originalDataMap.remove(id)
                        sourceState.saved.remove(id)
                        onDocumentHistoryReset(id)
                        editHistory.remove(record.key)
                    } else {
                        editingDataMap[id] = value.deepCopy()
                        originalDataMap[id] = savedData[id]?.deepCopy() ?: value.deepCopy()
                        savedData[id]?.let { sourceState.saved[id] = it }
                            ?: sourceState.saved.remove(id)
                        onDocumentHistoryReset(id)
                        editHistory.reset(record.key, EditingSnapshot(documentHistoryData(value.deepCopy()), documentHistoryRaw(record.raw), captureLoreHistoryIds(id)))
                    }
                    mergeConflicts.remove(id)
                }
                if (selectedId == null) remoteDataIds = snapshot.data.keys
                else if (selectedId in snapshot.data) remoteDataIds = remoteDataIds + selectedId
                else remoteDataIds = remoteDataIds - selectedId
                setupSidebar(main.sidebarContainer, currentSelectedDataId?.takeIf { it in editingDataMap })
                currentSelectedDataId?.let { id -> editingDataMap[id]?.let(::setupMainContent) }
                finishSync("取得基準を更新しました。ローカル保存とアップロードは別操作です。", Color.GREENYELLOW)
            }
        }
    }

    private fun finishSync(message: String? = null, color: Color = Color.WHITE) {
        if (!syncBusy) return
        main.finishOperation()
        dataService.endSave()
        setSyncBusy(false)
        message?.let { main.showTimedTopLabel(it, color) }
    }

    private fun setSyncBusy(busy: Boolean) {
        syncBusy = busy
        refreshActionMenuState()
    }

    private fun refreshSyncButtonState() = setSyncBusy(false)

    private fun showSyncFailure(target: String, failure: StoreResult.Failure) {
        logger.error(
            "{}の取得に失敗しました: code={}, detail={}",
            target,
            failure.error.code,
            failure.error.detail,
            failure.error.cause
        )
        CustomDialog.error()
            .title("取得エラー")
            .header("$target を取得できませんでした")
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
    protected open fun handleCreateDirectory() = requestCreateDirectory("")

    /** 入力はUIで受け取り、ストアの作成と一覧更新を背景で進めます。 */
    protected fun requestCreateDirectory(parent: String) {
        if (storeOperationBlocked || syncBusy || repairBusy || saveRunning) return
        val name = main.requestInput("ディレクトリを追加") { input ->
            when {
                input.isBlank() -> ValidationResult.Error("名前を入力してください")
                !(if (parent.isEmpty()) PublicId.isValidFull(input) else PublicId.isValid(input)) -> ValidationResult.Error(PublicId.DESCRIPTION)
                else -> ValidationResult.Success
            }
        } ?: return
        if (storeOperationBlocked || syncBusy || repairBusy || saveRunning) return
        val directory = PublicId.join(parent.split('.').filter(String::isNotEmpty), name)
        runStoreOperation("$directory を作成しています", {
            when (val result = dataAccess.createDirectory(directory)) {
                is StoreResult.Failure -> result
                is StoreResult.Success -> { showStorePhase("一覧を更新しています"); readSidebarSnapshot() }
            }
        }) { snapshot ->
            sidebarSnapshot = snapshot
            refreshLocalSidebar(currentSelectedDataId)
        }
    }

    /** 最新の削除対象を表示して承認を受け、確認できた削除だけ編集キャッシュへ反映します。 */
    protected fun requestDeleteDirectory(directory: String, onDiscarded: (String) -> Unit) {
        if ((connectedService.uploadingDocuments + connectedService.unresolvedUploadDocuments).any {
            it.source == dataService.documentSource && it.category == dataAccess.dataType.categoryDirName &&
                (it.id == directory || it.id.startsWith(directory + "."))
        }) return
        if (storeOperationBlocked || syncBusy || repairBusy || saveRunning) return
        runStoreOperation("$directory の削除対象を確認しています", ::readSidebarSnapshot) { before ->
            val storedIds = before.first.map { it.id }.toSet()
            val affected = idsInSidebarDirectory(mergeSidebarIdsWithPending(storedIds), directory)
            val sources = editingDataMap.keys.associateWith(::storedSourceId)
            val storedAffected = idsInSidebarDirectory(storedIds.toList(), directory).toSet()
            val localOnly = isLocalOnlyDirectory(directory, affected, storedIds, before.second)
            val confirmed = CustomDialog.confirmation()
                .title("ディレクトリを削除")
                .header(if (localOnly) "ローカルの編集内容を破棄します" else "配下のデータも削除されます")
                .content((listOf("ディレクトリ: $directory", "",
                    if (localOnly) "配下の未保存編集を破棄します。サーバーへは書き込みません。"
                    else "ローカル作業コピーのディレクトリと配下の保存済みデータを削除します。サーバーへの反映は別操作です。", "", "削除対象:") +
                    (storedAffected + affected).sorted()).joinToString("\n"))
                .okButton("削除", Color.RED).owner(main.currentStage).show()
            if (!confirmed || storeOperationBlocked || affected.any(::isUploadFrozen)) return@runStoreOperation
            if (localOnly) {
                discardDirectoryData(affected, onDiscarded)
                sidebarSnapshot = before
                refreshLocalSidebar()
                main.showTimedTopLabel("$directory のローカル編集内容を破棄しました", Color.GREENYELLOW)
                return@runStoreOperation
            }
            runStoreOperation("$directory を削除しています", {
                val deletion = dataAccess.deleteDirectoryObserved(directory)
                showStorePhase("削除結果と一覧を確認しています")
                StoreResult.Success(deletion to readSidebarSnapshot())
            }) { (deletion, after) ->
                if (deletion is StoreResult.Success) deletion.value.forEach(::observeDocument)
                if (after is StoreResult.Success) {
                    val remaining = after.value.first.map { it.id }.toSet()
                    val deleted = storedAffected - remaining
                    val discard = sources.filter { (_, source) -> source in deleted }.keys +
                        affected.filter { it !in storedIds && sources[it] == it && deletion is StoreResult.Success }
                    discardDirectoryData(discard, onDiscarded)
                    sidebarSnapshot = after.value
                    refreshLocalSidebar()
                }
                if (after is StoreResult.Failure && deletion is StoreResult.Success) {
                    val discard = sources.filter { (_, source) -> source in storedAffected }.keys +
                        affected.filter { it !in storedIds && sources[it] == it }
                    discardDirectoryData(discard, onDiscarded)
                    sidebarSnapshot = before.first.filterNot { it.id in storedAffected } to
                        before.second.filterNot { it == directory || it.startsWith("$directory.") }
                    refreshLocalSidebar()
                }
                if (deletion is StoreResult.Failure) showStoreOperationError(deletion.error)
                else if (after is StoreResult.Failure) showStoreOperationError(after.error)
            }
        }
    }

    private fun storedSourceId(id: String): String = when (val operation = pendingStoreOperations[id]) {
        is PendingStoreOperation.Rename -> operation.sourceId
        is PendingStoreOperation.Delete -> operation.sourceId
        is PendingStoreOperation.Create, null -> id
    }

    private fun discardDirectoryData(ids: Collection<String>, onDiscarded: (String) -> Unit) {
        ids.forEach { id ->
            editingDataMap.remove(id)
            originalDataMap.remove(id)
            sourceState.saved.remove(id)
            pendingStoreOperations.remove(id)
            mergeConflicts.remove(id)
            onDocumentHistoryReset(id)
            editHistory.remove(documentKey(id))
            removedBackupIds.add(id)
            onDiscarded(id)
        }
        requestBackup()
    }

    /** 指定したディレクトリへ新しい管理データを作成します。保存まではストアに書き込みません。 */
    protected fun handleCreateNewItem(directory: String) {
        if (storeOperationBlocked || syncBusy || repairBusy || saveRunning) return
        val input = main.requestInput("${dataAccess.displayName}を追加") { input ->
            val fullId = PublicId.join(directory.split('.').filter(String::isNotEmpty), input)
            when {
                input.isBlank() -> ValidationResult.Error("名前を入力してください")
                !(if (directory.isEmpty()) PublicId.isValidFull(input) else PublicId.isValid(input)) -> ValidationResult.Error(PublicId.DESCRIPTION)
                fullId in editingDataMap -> ValidationResult.Error("重複した名称です")
                !dataAccess.descriptor.isValidId(fullId) -> ValidationResult.Error("商品IDにはタブ名と商品名を含めてください（例: weapons.sword）")
                else -> ValidationResult.Success
            }
        } ?: return
        val fullId = PublicId.join(directory.split('.').filter(String::isNotEmpty), input)
        withAvailableId(fullId) { data ->
            val prepared = prepareNewData(data)
            editingDataMap[fullId] = prepared
            originalDataMap[fullId] = prepared.deepCopy()
            stageDataCreation(fullId)
            refreshLocalSidebar(fullId)
            main.showTimedTopLabel("$fullId の追加を保留しました。ローカル保存で確定します", Color.GREENYELLOW)
        }
    }

    /** 未取得の複製元と最新の重複確認を背景で読み、複製は保存まで保留します。 */
    protected fun requestDuplicate(id: String) {
        if (storeOperationBlocked || syncBusy || repairBusy || saveRunning) return
        val directory = PublicId.directoryOf(id)
        val name = requestDataName("${dataAccess.displayName}を複製", directory) ?: return
        val newId = PublicId.join(directory, name)
        withAvailableId(newId, id) { source ->
            val duplicate = dataAccess.duplicateAsNew(source, newId)
            editingDataMap[newId] = duplicate
            originalDataMap[newId] = duplicate.deepCopy()
            stageDataCreation(newId)
            refreshLocalSidebar(newId)
            main.showTimedTopLabel("$id から $newId への複製を保留しました", Color.GREENYELLOW)
        }
    }

    protected fun requestDataName(title: String, directory: List<String>): String? = main.requestInput(title) { input ->
        when {
            input.isBlank() -> ValidationResult.Error("名前を入力してください")
            !PublicId.isValid(input) -> ValidationResult.Error(PublicId.DESCRIPTION)
            PublicId.join(directory, input) in editingDataMap -> ValidationResult.Error("重複した名称です")
            else -> ValidationResult.Success
        }
    }

    /** 最新一覧で移動先を確認し、ID変更を保存まで保留します。戻り値は操作を受け付けたかを表します。 */
    protected fun renameData(id: String, newId: String, onRenamed: () -> Unit): Boolean {
        if (isUploadFrozen(id) || isUploadFrozen(newId) || storeOperationBlocked || syncBusy || repairBusy || saveRunning || newId == id) return false
        if (!dataAccess.descriptor.isValidId(newId)) {
            showStoreOperationError(StoreError(StoreErrorCode.INVALID_ID, newId, "商品はタブ配下へ配置してください"))
            return false
        }
        withAvailableId(newId, id) { source ->
            if (id !in editingDataMap) {
                editingDataMap[id] = source.deepCopy()
                originalDataMap[id] = source.deepCopy()
            }
            stageDataRename(id, newId)
            onRenamed()
            refreshLocalSidebar(newId)
            main.showTimedTopLabel("$id から $newId へのID変更を保留しました", Color.GREENYELLOW)
        }
        return true
    }

    private fun withAvailableId(newId: String, sourceId: String? = null, onReady: (T) -> Unit) {
        if (isUploadFrozen(newId) || sourceId?.let(::isUploadFrozen) == true) return
        if (storeOperationBlocked || syncBusy || repairBusy || saveRunning) return
        if (newId in editingDataMap) {
            showStoreOperationError(StoreError(StoreErrorCode.ALREADY_EXISTS, newId, "重複した名称です"))
            return
        }
        commitPendingTextEdit()
        val cachedSource = sourceId?.let { editingDataMap[it]?.deepCopy() }
        runStoreOperation("$newId の重複と元データを確認しています", {
            when (val snapshot = readSidebarSnapshot()) {
                is StoreResult.Failure -> snapshot
                is StoreResult.Success -> {
                    if (snapshot.value.first.any { it.id == newId }) {
                        StoreResult.Failure(StoreError(StoreErrorCode.ALREADY_EXISTS, newId, "重複した名称です"))
                    } else {
                        val source = when {
                            cachedSource != null -> StoreResult.Success(cachedSource to null)
                            sourceId != null -> dataAccess.observeStore(sourceId)
                            else -> StoreResult.Success(dataAccess.createDefault(newId) to null)
                        }
                        when (source) {
                            is StoreResult.Failure -> source
                            is StoreResult.Success -> StoreResult.Success(Triple(snapshot.value, source.value.first, source.value.second))
                        }
                    }
                }
            }
        }) { (snapshot, data, observed) ->
            sidebarSnapshot = snapshot
            observed?.let(::observeDocument)
            onReady(data)
        }
    }

    /** 保留操作だけが変わった場合は取得済み一覧を使い、再取得せず表示を更新します。 */
    protected fun refreshLocalSidebar(selectId: String? = currentSelectedDataId) {
        sidebarRefresh = sidebarSnapshot
        try { setupSidebar(main.sidebarContainer, selectId) }
        finally { sidebarRefresh = null }
        refreshWorkspaceDocuments()
    }

    private fun readSidebarSnapshot(): StoreResult<Pair<List<StoreResource>, List<String>>> =
        when (val resources = dataAccess.listStoreResources()) {
            is StoreResult.Failure -> resources
            is StoreResult.Success -> when (val directories = dataAccess.listDirectories()) {
                is StoreResult.Failure -> directories
                is StoreResult.Success -> StoreResult.Success(resources.value to directories.value)
            }
        }

    private fun showStorePhase(phase: String) {
        Platform.runLater { if (!closed && saveRunning) main.showOperation(phase) }
    }

    protected fun <R> runStoreOperation(phase: String, operation: () -> StoreResult<R>, onSuccess: (R) -> Unit) {
        if (storeOperationBlocked || syncBusy || repairBusy || saveRunning) return
        val generation = ++loadGeneration
        cancelSidebarPreload()
        commitPendingTextEdit()
        dataService.beginSave()
        main.showOperation(phase)
        val task = object : Task<StoreResult<R>>() {
            override fun call(): StoreResult<R> = operation()
        }
        fun finish() { main.finishOperation(); dataService.endSave() }
        fun resumePreload() {
            if (!storeOperationBlocked && !saveRunning) preloadSidebarDataForVisualStates(registeredSidebarIds)
        }
        task.setOnSucceeded {
            finish()
            if (!acceptsLoad(generation)) return@setOnSucceeded
            try {
                when (val result = task.value) {
                    is StoreResult.Success -> onSuccess(result.value)
                    is StoreResult.Failure -> showStoreOperationError(result.error)
                }
            } finally { resumePreload() }
        }
        task.setOnFailed {
            finish()
            if (!acceptsLoad(generation)) return@setOnFailed
            try { showStoreOperationError(StoreError(StoreErrorCode.IO_ERROR, cause = task.exception)) }
            finally { resumePreload() }
        }
        dataService.executeIo(task)
    }

    private fun showStoreOperationError(error: StoreError) {
        logger.error("データ・ディレクトリ操作に失敗しました: {}", error, error.cause)
        CustomDialog.error().title("データ・ディレクトリ操作エラー")
            .header("操作を完了できませんでした。未確認の編集内容は保持されています。")
            .content("${error.code}: ${error.detail.orEmpty()}").owner(main.currentStage).show()
    }

    /** 新規データを初回保存できる初期状態へ調整します。 */
    protected open fun prepareNewData(data: T): T = data
}
