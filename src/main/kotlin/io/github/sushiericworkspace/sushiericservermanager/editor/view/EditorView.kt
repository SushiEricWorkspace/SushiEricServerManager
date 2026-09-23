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
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorSyncService
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.DataConflict
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.validation.ValidationRepairRegistry
import io.github.sushiericworkspace.sushiericservermanager.editor.validation.ValidationRepairResult
import io.github.sushiericworkspace.sushiericservermanager.ui.AppTooltip
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.ErrorType
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.MergeConflictDialog
import io.github.sushiericworkspace.common.data.core.SushiEricDataType
import javafx.animation.Animation
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.event.EventHandler
import javafx.concurrent.Task
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
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
    private val syncService = EditorSyncService(dataAccess)
    private var syncButton: Button? = null
    private var syncAllButton: Button? = null
    private var repairAllWarningsButton: Button? = null
    private var repairAllErrorsButton: Button? = null
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
        dataAccess.deleteLocalBackup(id)
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
        val spacer = Region().apply { HBox.setHgrow(this, Priority.ALWAYS) }
        val actions = mutableListOf<Node>(
            Button("${dataAccess.displayName}保存").apply {
                styleClass.addAll("editor-action-button", "btn-primary")
                isFocusTraversable = false
                onAction = EventHandler { onSave() }
            }
        )
        if (dataService.isRemote) {
            syncButton = Button("同期").apply {
                styleClass.addAll("editor-action-button", "btn-secondary")
                isFocusTraversable = false
                isDisable = currentSelectedDataId == null
                onAction = EventHandler { synchronizeSelected() }
            }
            syncAllButton = Button("すべて同期").apply {
                styleClass.addAll("editor-action-button", "btn-danger")
                isFocusTraversable = false
                onAction = EventHandler { synchronizeAll() }
            }
            actions.add(syncButton!!)
            actions.add(syncAllButton!!)
        }
        repairAllWarningsButton = Button("全警告を修正").apply {
            styleClass.addAll("editor-action-button", "btn-secondary")
            isFocusTraversable = false
            isDisable = true
            onAction = EventHandler { repairWarnings(editingDataMap.keys, confirmBatch = true) }
        }
        repairAllErrorsButton = Button("全エラーを修正").apply {
            styleClass.addAll("editor-action-button", "btn-secondary")
            isFocusTraversable = false
            isDisable = true
            onAction = EventHandler { focusFirstError(editingDataMap.keys, confirmBatch = true) }
        }
        actions.add(repairAllWarningsButton!!)
        actions.add(repairAllErrorsButton!!)
        actions.add(spacer)
        actions.add(
            Button("データを作成").apply {
                styleClass.addAll("editor-action-button", "btn-success")
                isFocusTraversable = false
                onAction = EventHandler { handleCreateNewItem() }
            }
        )
        actions.add(
            Button("ディレクトリを作成").apply {
                styleClass.addAll("editor-action-button", "btn-secondary")
                isFocusTraversable = false
                onAction = EventHandler { handleCreateDirectory() }
            }
        )
        container.children.setAll(actions)
        refreshValidationActionButtons()
    }

    /**
     * 指定された一意の識別子（IDやファイル名など）に対応するタブ（アイテム）を選択状態にします。
     * 内部的には、データのロード、メインコンテンツエリアの再描画、およびサイドバーボタンのハイライト更新などを行います。
     *
     * @param targetId 選択対象となるリソースの識別子（ID）
     */
    open fun selectTab(targetId: String) {
        this.currentSelectedDataId = targetId // 現在選択中のIDを更新

        val hasCache = editingDataMap.containsKey(targetId)
        val isUnchanged = hasCache && (originalDataMap[targetId] == editingDataMap[targetId])

        if (hasCache && dataService.isRemote) {
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
        syncButton?.isDisable = false
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
        val currentEdit = editingDataMap[dataId] ?: return false
        val original = originalDataMap[dataId] ?: return false

        if (original == currentEdit) return false

        val saveData = prepareSaveData(dataId, currentEdit, original) ?: return false
        return persistSaveData(dataId, saveData)
    }

    private fun prepareSaveData(dataId: String, currentEdit: T, original: T): T? {
        if (!dataService.isRemote) return currentEdit.deepCopy()
        val (serverData, accessResult) = dataAccess.load(dataId)

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

    private fun persistSaveData(dataId: String, saveData: T): Boolean {
        return when (val result = dataAccess.saveStore(dataId, saveData)) {
            is StoreResult.Success -> {
                originalDataMap[dataId] = saveData.deepCopy()
                editingDataMap[dataId] = saveData.deepCopy()
                mergeConflicts.remove(dataId)

                if (dataId == currentSelectedDataId) {
                    selectTab(dataId)
                } else {
                    refreshButtonVisual(dataId)
                }

                dataAccess.deleteLocalBackup(dataId)

                main.showTimedTopLabel("$dataId を保存しました", Color.GREENYELLOW)
                true
            }
            is StoreResult.Failure -> handleSaveFailure(result.error)
        }
    }

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

        // どのような経路（通常・強制）で閉じられても、その瞬間の最新データを100%確実にローカルへ退避
        executeAutoSave()

        // 安全にタイマーを停止
        stopAutoSaveTimer()

        main.clearTopLabelTimer()
        main.clearShortcuts()

        editingDataMap.clear()
        originalDataMap.clear()
        mergeConflicts.clear()
        sidebarButtons.clear()
        selectedButton = null
        currentSelectedDataId = null
        syncButton = null
        syncAllButton = null
        repairAllWarningsButton = null
        repairAllErrorsButton = null

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
        val btn = sidebarButtons[id] ?: return
        val data = editingDataMap[id]
        val validationErrors = data?.let(::validationErrors).orEmpty()
        val state = SidebarDataState(
            selected = btn == selectedButton,
            modified = data != originalDataMap[id],
            hasWarnings = validationErrors.any(SushiEricValidationError::isWarning),
            hasErrors = validationErrors.any(SushiEricValidationError::isError),
            localOnly = id !in remoteDataIds
        )

        btn.styleClass.removeAll(SidebarDataState.STYLE_CLASSES)
        btn.styleClass.addAll(state.styleClasses)
        btn.text = ""
        btn.graphic = HBox(5.0).apply {
            if (state.localOnly) children += Label("＋").apply { styleClass.add("sidebar-local-only-mark") }
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
        refreshValidationActionButtons()
    }

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
            .filterNot(::isPendingValidationError)

    /**
     * 判定を保留する検証結果かどうかを返します。
     *
     * 参照アイテム一覧を読み込む前は、参照先が存在しないのか読み込めていないだけなのかを
     * 区別できません。区別できるようになるまで結果を伏せたいエディターが上書きします。
     * 既定では保留しません。
     *
     * @param error Common側の検証が返した結果。
     * @return 表示・集計の対象から外す場合は`true`。
     */
    protected open fun isPendingValidationError(error: SushiEricValidationError): Boolean = false

    /**
     * エラーに対応する入力UIへ移動します。
     *
     * データ固有の画面構造を共通基盤へ持ち込まないため、各エディターが実装します。
     */
    protected open fun focusValidationError(error: SushiEricValidationError): Boolean = false

    private fun refreshValidationActionButtons() {
        val errors = editingDataMap.values.flatMap(::validationErrors)
        repairAllWarningsButton?.isDisable = errors.none { it.isWarning }
        repairAllErrorsButton?.isDisable = errors.none { it.isError }
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

    /**
     * 手元で変更されたデータ（editing != original）だけをローカルに自動保存する
     */
    protected fun executeAutoSave() {
        // 変更があるデータだけをフィルタリング
        val changedData = editingDataMap.filter { (id, data) -> data != originalDataMap[id] }
        if (changedData.isEmpty()) return

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

        main.showTimedTopLabel("${changedData.size} 件の項目を自動バックアップしました。", Color.GREENYELLOW)
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
        remoteData.forEach { (id, data) ->
            editingDataMap[id] = data.deepCopy()
            originalDataMap[id] = data.deepCopy()
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
        syncButton?.isDisable = busy || currentSelectedDataId == null
        syncAllButton?.isDisable = busy
    }

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
     * 新しい管理データを作成し、リモートへ保存したうえでサイドバーに追加します。
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
            when (val result = dataAccess.saveStore(fullId, data)) {
                is StoreResult.Success -> {
                    editingDataMap[fullId] = data
                    originalDataMap[fullId] = data.deepCopy()

                    setupSidebar(main.sidebarContainer)
                    selectTab(fullId)
                }
                is StoreResult.Failure -> handleSaveFailure(result.error)
            }
        }
    }

    /** 新規データを初回保存できる初期状態へ調整します。 */
    protected open fun prepareNewData(data: T): T = data
}
