package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.common.data.core.identity.PublicId
import io.github.sushiericworkspace.common.data.core.validation.SushiEricValidationError
import io.github.sushiericworkspace.common.data.item.LoreLineEditor
import io.github.sushiericworkspace.common.data.item.model.HeadSkinSource
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemDetail
import io.github.sushiericworkspace.common.data.item.model.LoreSectionType
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.diff.ItemDiffField
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.ErrorType
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.view.TreeSidebarRenderer
import io.github.sushiericworkspace.sushiericservermanager.editor.view.buildSidebarTree
import io.github.sushiericworkspace.sushiericservermanager.editor.view.FlatSidebarRenderer
import io.github.sushiericworkspace.sushiericservermanager.editor.view.SidebarDisplayMode
import io.github.sushiericworkspace.sushiericservermanager.editor.view.idsInSidebarDirectory
import io.github.sushiericworkspace.sushiericservermanager.editor.view.isLocalOnlyData
import io.github.sushiericworkspace.sushiericservermanager.editor.view.isLocalOnlyDirectory
import io.github.sushiericworkspace.sushiericservermanager.editor.view.createPublicIdDisplay
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorView
import io.github.sushiericworkspace.sushiericservermanager.editor.view.mergeSidebarIds
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.diff.RewriteConfirmation
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.tree.ItemTreeBuilder
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.tree.LoreDragDropTreeCell
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.tree.LoreTreeUiIdMemory
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.tree.TreeRow
import io.github.sushiericworkspace.sushiericservermanager.editor.result.ValidationResult
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.DeleteResult
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.RenameResult
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.tree.EditorContextMenuFactory
import io.github.sushiericworkspace.sushiericservermanager.editor.tree.EditorFolderGraphicFactory
import io.github.sushiericworkspace.sushiericservermanager.editor.validation.ValidationRepairRegistry
import io.github.sushiericworkspace.sushiericservermanager.editor.validation.ValidationRepairResult
import io.github.sushiericworkspace.sushiericservermanager.ui.AppTooltip
import javafx.application.Platform
import javafx.event.EventHandler
import javafx.event.EventTarget
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.geometry.Side
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.ContextMenu
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import javafx.scene.control.ScrollPane
import javafx.scene.control.Spinner
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.control.TreeCell
import javafx.scene.control.skin.VirtualFlow
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Stage
import javafx.scene.control.TreeItem
import javafx.scene.control.TreeView
import javafx.scene.image.ImageView
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.ScrollEvent
import javafx.util.converter.IntegerStringConverter

internal fun resolveItemInternalId(
    cachedData: MutableItemBaseData?,
    loader: () -> MutableItemBaseData?
): String? = (cachedData ?: loader())?.internalId?.value?.takeIf(String::isNotBlank)

internal fun filterSidebarItemIds(
    ids: List<String>,
    query: String
): List<String> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return ids

    return ids.filter { id ->
        PublicId.normalizeForLoad(id).contains(normalizedQuery, ignoreCase = true)
    }
}

internal fun createItemValidationRepairRegistry(
    headSkinTextureLookup: HeadSkinTextureLookup
): ValidationRepairRegistry<MutableItemBaseData> =
    ValidationRepairRegistry<MutableItemBaseData>().apply {
        register(
            matches = { it.property.name == "headSkin" },
            description = { "プレイヤー名指定のヘッドスキンをテクスチャ値へ変換" },
            repair = { data, _ ->
                val headSkin = data.itemDetail.mutableHeadSkin
                if (headSkin == null || headSkin.source != HeadSkinSource.PLAYER_NAME) {
                    ValidationRepairResult.Failed("プレイヤー名指定のヘッドスキンが見つかりません。")
                } else {
                    when (val result = headSkinTextureLookup.lookup(headSkin.source, headSkin.value)) {
                        is HeadSkinTextureLookupResult.Failure ->
                            ValidationRepairResult.Failed(result.message)
                        is HeadSkinTextureLookupResult.Success -> {
                            headSkin.source = HeadSkinSource.TEXTURE
                            headSkin.value = result.texture
                            ValidationRepairResult.Applied("ヘッドスキンをテクスチャ値へ変換")
                        }
                    }
                }
            }
        )
    }

class ItemEditorLogic(
    main: MainController,
    dataService: EditorDataService,
    private val headSkinTextureLookup: HeadSkinTextureLookup = MojangHeadSkinTextureLookup()
) : EditorView<MutableItemBaseData>(
    main = main,
    dataService = dataService,
    dataAccess = dataService.items
) {
    override val validationRepairRegistry = createItemValidationRepairRegistry(headSkinTextureLookup)

    private companion object {
        /**
         * ホイール1回あたりのツリーのスクロール量の倍率。
         *
         * JavaFX既定の量ではこのツリーの1行が高いため送りすぎになる。
         */
        const val TREE_SCROLL_RATE = 0.5
    }

    private val treeView = TreeView<TreeRow>().apply {
        minHeight = 360.0
        minWidth = 420.0
        prefHeight = 550.0
        prefWidth = 720.0
        maxHeight = Double.MAX_VALUE
        maxWidth = Double.MAX_VALUE
        isShowRoot = false
        styleClass.add("editor-tree-view")

        // 既定のスクロール量を抑えるため、縦スクロールだけ自前で処理する
        addEventFilter(ScrollEvent.SCROLL) { event ->
            if (event.deltaY == 0.0) return@addEventFilter
            if (isScrollHandledByCell(event.target)) return@addEventFilter

            val flow = treeVirtualFlow() ?: return@addEventFilter

            flow.scrollPixels(-event.deltaY * TREE_SCROLL_RATE)
            event.consume()
        }
    }

    private val treeCache = mutableMapOf<String, TreeItem<TreeRow>>()

    private val expandedStateCache = mutableMapOf<String, MutableMap<String, Boolean>>()

    private val loreTreeUiIdMemory = LoreTreeUiIdMemory()

    private val previewImageView = ImageView().apply {
        isPreserveRatio = true
    }

    private val previewScrollPane = ScrollPane(previewImageView).apply {
        minWidth = 280.0
        minHeight = 360.0
        prefWidth = 320.0
        prefHeight = 560.0
        maxWidth = Double.MAX_VALUE
        maxHeight = Double.MAX_VALUE
        isFitToWidth = false
        isFitToHeight = false
        hbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        vbarPolicy = ScrollPane.ScrollBarPolicy.AS_NEEDED
        styleClass.add("editor-preview-scroll")
    }

    private var previewCanvas: PreviewCanvas? = null

    private val currentPublicIdDisplay = HBox().apply {
        styleClass.add("editor-identity-value")
    }

    private val currentInternalIdField = TextField().apply {
        isEditable = false
        isFocusTraversable = true
        maxWidth = Double.MAX_VALUE
        styleClass.add("editor-internal-id-field")
        HBox.setHgrow(this, Priority.ALWAYS)
    }

    private val currentInternalIdCopyButton = Button("コピー").apply {
        isFocusTraversable = false
        onAction = EventHandler {
            currentSelectedDataId?.let(::copyInternalId)
        }
    }

    private val currentIdentityPane = VBox(
        HBox(
            Label("公開ID:").apply { styleClass.add("editor-identity-label") },
            currentPublicIdDisplay
        ).apply {
            alignment = Pos.CENTER_LEFT
            styleClass.add("editor-identity-row")
        },
        HBox(
            Label("内部ID:").apply { styleClass.add("editor-identity-label") },
            currentInternalIdField,
            currentInternalIdCopyButton
        ).apply {
            alignment = Pos.CENTER_LEFT
            styleClass.add("editor-identity-row")
        }
    ).apply {
        styleClass.add("editor-identity-pane")
    }

    private val sidebarSearchField = TextField().apply {
        promptText = "公開IDを検索"
        maxWidth = Double.MAX_VALUE
        styleClass.add("sidebar-search-field")
        textProperty().addListener { _, _, _ ->
            renderSidebarResults()
        }
    }

    private val sidebarResultsContainer = VBox().apply {
        maxWidth = Double.MAX_VALUE
        styleClass.add("sidebar-results-container")
    }

    private val sidebarNoResultsLabel = Label("該当するItemがありません").apply {
        maxWidth = Double.MAX_VALUE
        styleClass.add("sidebar-no-results-label")
    }

    private var sidebarItemIds: List<String> = emptyList()
    private var sidebarDirectories: List<String> = emptyList()

    override fun setupSidebar(container: VBox, selectId: String?) {
        container.children.setAll(sidebarSearchField, sidebarResultsContainer)
        VBox.setMargin(sidebarSearchField, Insets(10.0))
        selectedButton = null
        sidebarButtons.clear()
        sidebarItemIds = emptyList()
        sidebarResultsContainer.children.clear()

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

        /*
         * サーバー上のIDへ、ローカルにだけ存在するIDを加えてサイドバーを作る。
         * 未保存の新規データや複製データを、再オープン後も選べるようにするためである。
         */
        val remoteIds = fileResources.map { it.name.removeSuffix(".yml") }
        remoteDataIds = remoteIds.toSet()

        val ids = mergeSidebarIds(remoteIds, editingDataMap.keys)
        sidebarItemIds = ids
        sidebarDirectories = when (val result = dataAccess.listDirectories()) {
            is StoreResult.Success -> result.value
            is StoreResult.Failure -> emptyList()
        }
        val existingIds = ids.toSet()
        ids.forEach { id ->
            val button = createSidebarButton(id, existingIds)
            sidebarButtons[id] = button
        }
        TreeSidebarRenderer(
            createDataButton = { id -> sidebarButtons.getValue(id) },
            createDirectoryMenu = ::createDirectoryContextMenu,
            onMove = ::moveData,
            expandedState = sidebarExpandedState,
            onExpandedStateChanged = ::persistSidebarExpandedState
        ).enableRootDrop(sidebarResultsContainer)
        renderSidebarResults()

        if (ids.isEmpty()) {
            currentSelectedDataId = null
            selectedButton = null
            main.mainContentContainer.children.clear()
            return
        }

        val targetId = selectId?.removeSuffix(".yml") ?: ids.first()
        if (targetId in existingIds) {
            selectTab(targetId)
        }

        ids.forEach(::refreshButtonVisual)
        preloadSidebarDataForVisualStates(ids)

        startAutoSaveTimer()

        if (restoredCacheCount > 0) {
            main.showTimedTopLabel("自動保存から $restoredCacheCount 件のデータを復元しました", Color.GREENYELLOW)
            restoredCacheCount = 0
        }
    }

    private fun renderSidebarResults() {
        if (sidebarDisplayMode == SidebarDisplayMode.FLAT) {
            val buttons = FlatSidebarRenderer { id -> sidebarButtons.getValue(id) }
                .render(sidebarItemIds, sidebarSearchField.text.orEmpty())
            sidebarResultsContainer.children.setAll(buttons.ifEmpty { listOf(sidebarNoResultsLabel) })
            return
        }
        val nodes = buildSidebarTree(sidebarItemIds, sidebarDirectories, sidebarSearchField.text.orEmpty())
        if (nodes.isEmpty()) {
            sidebarResultsContainer.children.setAll(sidebarNoResultsLabel)
        } else {
            sidebarResultsContainer.children.setAll(
                TreeSidebarRenderer(
                    createDataButton = { id -> sidebarButtons.getValue(id) },
                    createDirectoryMenu = ::createDirectoryContextMenu,
                    onMove = ::moveData,
                    expandedState = sidebarExpandedState,
                    onExpandedStateChanged = ::persistSidebarExpandedState
                ).render(nodes)
            )
        }
    }

    private fun createDirectoryContextMenu(directory: String): ContextMenu {
        val items = mutableListOf<MenuItem>()
        items += MenuItem("ディレクトリを追加").apply {
            onAction = EventHandler { requestCreateDirectory(directory) }
        }
        items += MenuItem("データを追加").apply {
            onAction = EventHandler { handleCreateNewItem(directory) }
        }
        if (directory.isNotEmpty()) {
            items += MenuItem("削除").apply {
                styleClass.add("menu-item-danger")
                onAction = EventHandler { requestDeleteDirectory(directory) }
            }
        }
        return ContextMenu(*items.toTypedArray())
    }

    private fun requestCreateDirectory(parent: String) {
        val name = main.requestInput("ディレクトリを追加") { input ->
            when {
                input.isBlank() -> ValidationResult.Error("名前を入力してください")
                !PublicId.isValid(input) -> ValidationResult.Error(PublicId.DESCRIPTION)
                else -> ValidationResult.Success
            }
        } ?: return
        val directory = PublicId.join(parent.split('.').filter(String::isNotEmpty), name)
        when (dataAccess.createDirectory(directory)) {
            is StoreResult.Success -> setupSidebar(main.sidebarContainer, currentSelectedDataId)
            is StoreResult.Failure -> showDirectoryError("ディレクトリを作成できませんでした")
        }
    }

    private fun requestDeleteDirectory(directory: String) {
        val affected = idsInSidebarDirectory(sidebarItemIds, directory)
        val localOnly = isLocalOnlyDirectory(
            directory = directory,
            affectedIds = affected,
            remoteDataIds = remoteDataIds,
            remoteDirectories = sidebarDirectories
        )
        val confirmed = CustomDialog.confirmation()
            .title("ディレクトリを削除")
            .header(if (localOnly) "ローカルの編集内容を破棄します" else "配下のデータも削除されます")
            .content(
                (listOf(
                    "ディレクトリ: $directory",
                    "",
                    if (localOnly) {
                        "サーバー上のファイルは削除せず、配下の編集内容と自動保存を破棄します。"
                    } else {
                        "サーバー上のディレクトリと配下のデータを削除します。"
                    },
                    "",
                    "削除対象:"
                ) + affected).joinToString("\n")
            )
            .okButton("削除", Color.RED)
            .owner(main.currentStage)
            .show()
        if (!confirmed) return

        if (localOnly) {
            affected.forEach(::discardItemData)
            main.showTimedTopLabel("$directory のローカル編集内容を破棄しました", Color.GREENYELLOW)
            setupSidebar(main.sidebarContainer)
            return
        }

        when (dataAccess.deleteDirectory(directory)) {
            is StoreResult.Success -> {
                affected.forEach(::discardItemData)
                setupSidebar(main.sidebarContainer)
            }
            is StoreResult.Failure -> showDirectoryError("ディレクトリを削除できませんでした")
        }
    }

    private fun moveData(id: String, directory: String): Boolean = when (val result = dataAccess.move(id, directory)) {
        is StoreResult.Success -> {
            finishRename(id, result.value)
            true
        }
        is StoreResult.Failure -> {
            showDirectoryError(if (result.error.code.name == "ALREADY_EXISTS") "移動先に同名のデータがあります" else "データを移動できませんでした")
            false
        }
    }

    private fun showDirectoryError(message: String) {
        CustomDialog.error().title("ディレクトリ操作エラー").header(message).owner(main.currentStage).show()
    }

    private fun createSidebarButton(id: String, existingIds: Set<String>): Button {
        return Button(PublicId.normalizeForLoad(id)).apply {
            isFocusTraversable = false
            /*
             * Buttonは既定でmnemonicParsingが有効なため、IDに含まれる`_`が
             * ニーモニック指定として解釈されて表示から欠落する。IDはそのまま
             * 表示するため無効化する。
             */
            isMnemonicParsing = false
            this.id = id
            maxWidth = Double.MAX_VALUE
            alignment = Pos.CENTER_LEFT
            onAction = EventHandler { selectTab(id) }
            contextMenu = createSidebarContextMenu(id, existingIds)
        }
    }

    private fun createSidebarContextMenu(id: String, existingIds: Set<String>): ContextMenu {
        val saveItem = MenuItem("保存").apply {
            isDisable = true
            onAction = EventHandler { onSave(id) }
        }
        val renameItem = MenuItem("IDを変更").apply {
            onAction = EventHandler { requestRename(id, existingIds) }
        }
        val deleteItem = MenuItem("削除").apply {
            styleClass.add("menu-item-danger")
            onAction = EventHandler { requestDelete(id) }
        }
        val validationItems = createValidationContextMenuItems(id)

        return ContextMenu(
            MenuItem("IDをコピー").apply {
                onAction = EventHandler { copyId(id) }
            },
            MenuItem("内部IDをコピー").apply {
                onAction = EventHandler { copyInternalId(id) }
            },
            saveItem,
            validationItems.repairWarnings,
            validationItems.repairErrors,
            MenuItem("複製").apply {
                onAction = EventHandler { requestDuplicate(id, existingIds) }
            },
            renameItem,
            deleteItem
        ).apply {
            setOnShowing {
                saveItem.isDisable = originalDataMap[id] == editingDataMap[id]
                refreshValidationContextMenuItems(id, validationItems)
                scene?.root?.styleClass?.let { classes ->
                    if ("popup-root-transparent" !in classes) {
                        classes.add("popup-root-transparent")
                    }
                }
            }
        }
    }

    private fun copyId(id: String) {
        copyText(id, "コピーしました: $id")
    }

    private fun copyInternalId(id: String) {
        val internalId = resolveItemInternalId(editingDataMap[id]) {
            dataAccess.load(id).first
        }
        if (internalId == null) {
            CustomDialog.error()
                .title("コピーエラー")
                .header("内部IDをコピーできませんでした")
                .content("対象アイテム: $id")
                .owner(main.currentStage)
                .show()
            return
        }

        copyText(internalId, "内部IDをコピーしました: $internalId")
    }

    private fun copyText(value: String, notification: String) {
        val content = ClipboardContent().apply {
            putString(value)
        }
        Clipboard.getSystemClipboard().setContent(content)
        main.showTimedTopLabel(notification, Color.GREENYELLOW)
    }

    private fun requestDuplicate(id: String, existingIds: Set<String>) {
        val source = if (id == currentSelectedDataId) {
            editingDataMap[id]?.deepCopy()
        } else {
            dataAccess.load(id).first?.deepCopy()
        }
        if (source == null) {
            CustomDialog.error()
                .title("複製エラー")
                .header("複製元のアイテムを読み込めませんでした")
                .content("対象アイテム: $id")
                .owner(main.currentStage)
                .show()
            return
        }

        val directory = PublicId.directoryOf(id)
        val newName = main.requestInput("アイテムを複製") { input ->
            when {
                input.isBlank() -> ValidationResult.Error("名前を入力してください")
                !PublicId.isValid(input) -> ValidationResult.Error(PublicId.DESCRIPTION)
                PublicId.join(directory, input) in existingIds -> ValidationResult.Error("重複した名称です")
                else -> ValidationResult.Success
            }
        } ?: return

        val newId = PublicId.join(directory, newName)
        val duplicate = dataAccess.duplicateAsNew(source, newId)
        when (val result = dataAccess.saveStore(newId, duplicate)) {
            is StoreResult.Success -> {
                editingDataMap[newId] = duplicate
                originalDataMap[newId] = duplicate.deepCopy()
                main.showTimedTopLabel("$id を $newId として複製しました", Color.GREENYELLOW)
                setupSidebar(main.sidebarContainer, newId)
            }
            is StoreResult.Failure -> {
                handleSaveFailure(result.error)
            }
        }
    }

    private fun requestRename(id: String, existingIds: Set<String>) {
        val directory = PublicId.directoryOf(id)
        val newName = main.requestInput("名前変更") { input ->
            when {
                input.isBlank() -> ValidationResult.Error("名前を入力してください")
                !PublicId.isValid(input) -> ValidationResult.Error(PublicId.DESCRIPTION)
                PublicId.join(directory, input) in existingIds -> ValidationResult.Error("重複した名称です")
                else -> ValidationResult.Success
            }
        } ?: return

        /*
         * 永続識別子で解決するため、公開IDの変更で既存アイテムは無効化されない。
         * このため破壊的変更としての確認は行わない。
         */
        val newId = PublicId.join(directory, newName)
        when (dataAccess.rename(id, newName)) {
            RenameResult.SUCCESS -> finishRename(id, newId)
            RenameResult.FILE_NOT_FOUND -> showRenameError(
                header = "対象のファイルが見つかりません",
                content = "変更元のアイテム($id)が、サーバー上で既に削除されている可能性があります。"
            )
            RenameResult.ALREADY_EXISTS -> showRenameError(
                header = "同名のファイルが既に存在します",
                content = "入力された名称($newId)は、サーバー上で他のアイテムに使用されています。\n別の日時や名称を指定してください。"
            )
            RenameResult.SFTP_INACTIVE, RenameResult.PROFILE_NOT_SELECTED -> {
                CustomDialog.error()
                    .title("接続エラー")
                    .header("サーバーに接続されていません")
                    .content("SFTPセッションが切断された可能性があります。再接続してください。")
                    .show()
                dataService.forceBackToSelect()
            }
            RenameResult.FAILED -> {
                showRenameError(
                    header = "名前変更に失敗しました",
                    content = "予期しないエラーまたはネットワーク問題が発生しました。詳細はログを確認してください。",
                    title = "システムエラー"
                )
                dataService.forceBackToSelect()
            }
        }
    }

    private fun finishRename(oldId: String, newId: String) {
        renameCachedData(editingDataMap, oldId, newId)
        renameCachedData(originalDataMap, oldId, newId)
        treeCache.remove(oldId)?.let { treeCache[newId] = it }
        expandedStateCache.remove(oldId)?.let { expandedStateCache[newId] = it }
        loreTreeUiIdMemory.renameItem(oldItemId = oldId, newItemId = newId)

        main.showTimedTopLabel("$oldId を $newId に変更しました", Color.GREENYELLOW)
        setupSidebar(main.sidebarContainer, newId)
    }

    private fun renameCachedData(cache: MutableMap<String, MutableItemBaseData>, oldId: String, newId: String) {
        cache.remove(oldId)?.let { data ->
            data.id = newId
            cache[newId] = data
        }
    }

    private fun showRenameError(header: String, content: String, title: String = "名前変更エラー") {
        CustomDialog.error()
            .title(title)
            .header(header)
            .content(content)
            .show()
    }

    private fun requestDelete(id: String) {
        val localOnly = isLocalOnlyData(id, remoteDataIds)
        val confirmed = CustomDialog.confirmation()
            .title(if (localOnly) "未保存データを破棄" else "警告")
            .header(if (localOnly) "ローカルの編集内容を破棄します" else "破壊的変更")
            .content(
                if (localOnly) {
                    listOf(
                        "アイテムID: $id",
                        "",
                        "サーバー上のファイルは削除せず、編集内容と自動保存を破棄します。"
                    )
                } else {
                    listOf(
                        "アイテムID: $id",
                        "",
                        "この操作を実行するとサーバー上のファイルが物理削除され、",
                        "元の状態に戻すことはできなくなります。",
                        "本当に削除しますか？"
                    )
                }
            )
            .okButton("削除", Color.RED)
            .owner(main.currentStage)
            .show()
        if (!confirmed) return

        if (localOnly) {
            discardItemData(id)
            main.showTimedTopLabel("$id のローカル編集内容を破棄しました", Color.GREENYELLOW)
            setupSidebar(main.sidebarContainer)
            return
        }

        when (dataAccess.delete(id)) {
            DeleteResult.FAILED, DeleteResult.PROFILE_NOT_SELECTED, DeleteResult.SFTP_INACTIVE -> {
                CustomDialog.error(ErrorType.NETWORK_ERROR)
                    .owner(main.currentStage)
                    .show()
                handleForceBackToSelect()
            }
            DeleteResult.FILE_NOT_FOUND -> {
                discardItemData(id)
                main.showTimedTopLabel(
                    "$id はサーバー上に存在しないため、ローカル編集内容を破棄しました",
                    Color.GREENYELLOW
                )
                setupSidebar(main.sidebarContainer)
            }
            DeleteResult.SUCCESS -> {
                main.showTimedTopLabel("$id を削除しました", Color.GREENYELLOW)
                discardItemData(id)
                setupSidebar(main.sidebarContainer)
            }
        }
    }

    private fun discardItemData(id: String) {
        discardLocalEditingData(id)
        removeCachedData(id)
    }

    private fun removeCachedData(id: String) {
        treeCache.remove(id)
        expandedStateCache.remove(id)
        loreTreeUiIdMemory.clearItem(id)
    }

    override fun resolveSaveConflict(
        dataId: String,
        originalData: MutableItemBaseData,
        currentData: MutableItemBaseData,
        serverData: MutableItemBaseData
    ): MutableItemBaseData? {
        val dialog = RewriteConfirmation(originalData, serverData)
        val currentStage = main.sidebarContainer.scene.window as? Stage

        if (currentStage != null) {
            dialog.initOwner(currentStage)
        }

        dialog.showAndWait()

        if (!dialog.isConfirmed) return null

        val checkedFields = dialog.selectedCheckedFields
        val finalSaveData = serverData.deepCopy()

        finalSaveData.completed = currentData.completed

        if (checkedFields.any { it.field == ItemDiffField.RARITY }) {
            finalSaveData.rarity = currentData.rarity
        }

        finalSaveData.itemDetail = mergeItemDetailForConflict(
            server = serverData.itemDetail,
            current = currentData.itemDetail,
            keepLocalDetail = checkedFields.any { it.field == ItemDiffField.DETAIL },
            keepLocalHeadSkin = checkedFields.any { it.field == ItemDiffField.HEAD_SKIN }
        )

        if (checkedFields.any { it.field == ItemDiffField.DISPLAY_NAME }) {
            finalSaveData.display.displayName = currentData.display.displayName
        }

        val maxLoreSize = maxOf(currentData.display.lore.size, serverData.display.lore.size)
        for (i in 0 until maxLoreSize) {
            val isChecked = checkedFields.any {
                it.field == ItemDiffField.LORE && it.index == i
            }

            if (isChecked) {
                val currentLine = currentData.display.mutableLore.getOrNull(i)

                if (currentLine != null) {
                    if (i < finalSaveData.display.mutableLore.size) {
                        finalSaveData.display.mutableLore[i] = currentLine
                    } else {
                        finalSaveData.display.mutableLore.add(currentLine)
                    }
                } else {
                    if (i < finalSaveData.display.mutableLore.size) {
                        finalSaveData.display.mutableLore.removeAt(i)
                    }
                }
            }
        }

        for (key in currentData.stats.keys + serverData.stats.keys) {
            val isChecked = checkedFields.any {
                it.field == ItemDiffField.STATS && it.statsType == key
            }

            if (isChecked) {
                val currentVal = currentData.stats[key]

                if (currentVal != null) {
                    finalSaveData.stats[key] = currentVal
                } else {
                    finalSaveData.stats.remove(key)
                }
            }
        }

        for (key in currentData.statMultipliers.keys + serverData.statMultipliers.keys) {
            val isChecked = checkedFields.any {
                it.field == ItemDiffField.STAT_MULTIPLIER && it.statsType == key
            }

            if (isChecked) {
                val currentVal = currentData.statMultipliers[key]

                if (!currentVal.isNullOrEmpty()) {
                    finalSaveData.statMultipliers[key] = currentVal.toMutableList()
                } else {
                    finalSaveData.statMultipliers.remove(key)
                }
            }
        }

        val maxDescSize = maxOf(currentData.editorMeta.comment.size, serverData.editorMeta.comment.size)
        for (i in 0 until maxDescSize) {
            val isChecked = checkedFields.any {
                it.field == ItemDiffField.COMMENT && it.index == i
            }

            if (isChecked) {
                val currentLine = currentData.editorMeta.comment.getOrNull(i)

                if (currentLine != null) {
                    if (i < finalSaveData.editorMeta.comment.size) {
                        finalSaveData.editorMeta.comment[i] = currentLine
                    } else {
                        finalSaveData.editorMeta.comment.add(currentLine)
                    }
                } else {
                    if (i < finalSaveData.editorMeta.comment.size) {
                        finalSaveData.editorMeta.comment.removeAt(i)
                    }
                }
            }
        }

        return finalSaveData
    }

    override fun setupMainContent(selectData: MutableItemBaseData) {
        previewCanvas = PreviewCanvas(
            itemData = selectData,
            imageView = previewImageView
        )

        previewCanvas?.refreshPreview()

        if (main.mainContentContainer.children.isEmpty()) {
            val editorPane = VBox(12.0).apply {
                styleClass.add("editor-content-pane")
                minWidth = 420.0
                maxWidth = Double.MAX_VALUE
                maxHeight = Double.MAX_VALUE
                children.addAll(
                    currentIdentityPane,
                    treeView
                )
                VBox.setVgrow(treeView, Priority.ALWAYS)
            }
            HBox.setHgrow(editorPane, Priority.ALWAYS)
            HBox.setHgrow(previewScrollPane, Priority.SOMETIMES)

            main.mainContentContainer.children.addAll(
                editorPane,
                previewScrollPane
            )
        }

        currentPublicIdDisplay.children.setAll(createPublicIdDisplay(selectData.id).children)
        currentInternalIdField.text = selectData.internalId.value
        currentInternalIdField.tooltip = AppTooltip.create(selectData.internalId.value)

        // キャッシュからルートオブジェクトを取得、なければ初期展開状態で登録
        val rootItem = treeCache.getOrPut(selectData.id) {
            TreeItem<TreeRow>(TreeRow.Folder.Lore).apply {
                isExpanded = true
                anchorRowOnBranchToggle(this)
            }
        }

        val expandedMap = expandedStateCache.getOrPut(selectData.id) { mutableMapOf() }

        // 構造を再構築する
        createItemTreeBuilder(
            itemId = selectData.id,
            itemData = selectData,
            expandedMap = expandedMap
        ).rebuildRoot(rootItem)

        // ビューに結びつけ
        treeView.root = rootItem

        fun Button.applyTreeInlineButtonSize(): Button = apply {
            styleClass.add("tree-inline-button")
            isFocusTraversable = false

            minWidth = 36.0
            prefWidth = Region.USE_COMPUTED_SIZE
            maxWidth = Region.USE_PREF_SIZE

            minHeight = 24.0
            prefHeight = 24.0
            maxHeight = 24.0
        }

        fun Label.applyTreeFolderLabelSize(): Label = apply {
            minHeight = Region.USE_PREF_SIZE
            prefHeight = Region.USE_COMPUTED_SIZE
            maxHeight = Region.USE_PREF_SIZE
        }

        fun HBox.applyTreeFolderRowSize(): HBox = apply {
            minHeight = Region.USE_PREF_SIZE
            prefHeight = Region.USE_COMPUTED_SIZE
            maxHeight = Region.USE_PREF_SIZE
        }

        fun showInsertIndexDialog(
            title: String,
            header: String,
            maxIndex: Int
        ): Int? {
            val dialog = Dialog<Int>().apply {
                this.title = title
                this.headerText = header
            }

            val insertButtonType = ButtonType("追加", ButtonBar.ButtonData.OK_DONE)

            dialog.dialogPane.buttonTypes.addAll(
                insertButtonType,
                ButtonType.CANCEL
            )

            val spinner = Spinner<Int>(0, maxIndex, maxIndex).apply {
                isEditable = true
                prefWidth = 120.0

                valueFactory.converter = IntegerStringConverter()

                editor.textProperty().addListener { _, _, newValue ->
                    if (newValue.isBlank()) return@addListener

                    val value = newValue.toIntOrNull()
                    if (value == null) {
                        editor.text = valueFactory.value.toString()
                        return@addListener
                    }

                    val safeValue = value.coerceIn(0, maxIndex)
                    if (safeValue != value) {
                        editor.text = safeValue.toString()
                    }
                }
            }

            dialog.dialogPane.content = VBox(8.0).apply {
                children.addAll(
                    Label("挿入位置: 0 ～ $maxIndex"),
                    spinner
                )
            }

            dialog.setResultConverter { button ->
                if (button == insertButtonType) {
                    spinner.value.coerceIn(0, maxIndex)
                } else {
                    null
                }
            }

            return dialog.showAndWait().orElse(null)
        }

        fun createLoreSectionTypeMenuItems(
            onSelected: (LoreSectionType) -> Unit
        ): List<MenuItem> {
            return LoreSectionType.entries.map { type ->
                MenuItem(type.display).apply {
                    onAction = EventHandler {
                        onSelected(type)
                    }
                }
            }
        }

        val contextMenuFactory = EditorContextMenuFactory<TreeRow> { row ->
            when (row) {
                is TreeRow.Folder.LoreLine -> {
                    val lineSystem = LoreLineEditor(selectData.display, row.lineIndex)

                    ContextMenu().apply {
                        val moveForward = MenuItem("前の行と入れ替え").apply {
                            onAction = EventHandler {
                                val fromIndex = row.lineIndex
                                val toIndex = row.lineIndex - 1

                                lineSystem.moveTo(toIndex)
                                loreTreeUiIdMemory.lineMoved(selectData.id, fromIndex, toIndex)

                                refreshButtonVisual(selectData.id)
                                handleRefresh(TreeRow.Folder.Lore)
                            }
                        }

                        val moveBack = MenuItem("後ろの行と入れ替え").apply {
                            onAction = EventHandler {
                                val fromIndex = row.lineIndex
                                val toIndex = row.lineIndex + 1

                                lineSystem.moveTo(toIndex)
                                loreTreeUiIdMemory.lineMoved(selectData.id, fromIndex, toIndex)

                                refreshButtonVisual(selectData.id)
                                handleRefresh(TreeRow.Folder.Lore)
                            }
                        }

                        val moveToSpecification = MenuItem("指定した行と入れ替え").apply {
                            onAction = EventHandler {
                                val insertIndex = showInsertIndexDialog("行を入れ替え", "入れ替え先の行を選択してください", lineSystem.getLineSize()) ?: return@EventHandler

                                lineSystem.moveTo(insertIndex)
                                loreTreeUiIdMemory.lineMoved(selectData.id, row.lineIndex, insertIndex)

                                refreshButtonVisual(selectData.id)
                                handleRefresh(TreeRow.Folder.Lore)
                            }
                        }

                        items.addAll(
                            moveForward,
                            moveBack,
                            moveToSpecification,
                            Menu("前に行を追加").apply {
                                items.addAll(
                                    createLoreSectionTypeMenuItems { type ->
                                        lineSystem.add(type)
                                        loreTreeUiIdMemory.lineInserted(selectData.id, row.lineIndex)

                                        refreshButtonVisual(selectData.id)
                                        handleRefresh(TreeRow.Folder.Lore)
                                    }
                                )
                            },
                            Menu("後ろに行を追加").apply {
                                items.addAll(
                                    createLoreSectionTypeMenuItems { type ->
                                        val idx = row.lineIndex + 1

                                        LoreLineEditor(selectData.display, idx).add(type)
                                        loreTreeUiIdMemory.lineInserted(selectData.id, idx)

                                        refreshButtonVisual(selectData.id)
                                        handleRefresh(TreeRow.Folder.Lore)
                                    }
                                )
                            },
                            Menu("セクション操作").apply {
                                items.addAll(
                                    Menu("末尾にセクションを追加").apply {
                                        items.addAll(
                                            createLoreSectionTypeMenuItems { type ->
                                                val sectionIndex = lineSystem.getSectionSize()
                                                lineSystem.section(sectionIndex).add(type)
                                                loreTreeUiIdMemory.sectionInserted(selectData.id, row.lineUiId, sectionIndex)

                                                refreshButtonVisual(selectData.id)
                                                handleRefresh(row)
                                            }
                                        )
                                    },
                                    Menu("先頭にセクションを追加").apply {
                                        items.addAll(
                                            createLoreSectionTypeMenuItems { type ->
                                                val sectionIndex = 0

                                                lineSystem.section(sectionIndex).add(type)
                                                loreTreeUiIdMemory.sectionInserted(selectData.id, row.lineUiId, sectionIndex)

                                                refreshButtonVisual(selectData.id)
                                                handleRefresh(row)
                                            }
                                        )
                                    },
                                    Menu("指定位置にセクションを追加").apply {
                                        items.addAll(
                                            createLoreSectionTypeMenuItems { type ->
                                                val maxIndex = lineSystem.getSectionSize()

                                                val insertIndex = showInsertIndexDialog("セクションを追加", "追加する位置を選択してください", maxIndex) ?: return@createLoreSectionTypeMenuItems

                                                lineSystem.section(insertIndex).add(type)
                                                loreTreeUiIdMemory.sectionInserted(selectData.id, row.lineUiId, insertIndex)

                                                refreshButtonVisual(selectData.id)
                                                handleRefresh(row)
                                            }
                                        )
                                    }
                                )
                            },
                            MenuItem("この行を削除").apply {
                                onAction = EventHandler {
                                    lineSystem.remove()
                                    loreTreeUiIdMemory.lineRemoved(selectData.id, row.lineIndex)

                                    refreshButtonVisual(selectData.id)
                                    handleRefresh(TreeRow.Folder.Lore)
                                }
                            }
                        )

                        setOnShowing {
                            val lineSize = LoreLineEditor(selectData.display, 0).getLineSize()

                            moveForward.isDisable = row.lineIndex <= 0
                            moveBack.isDisable = row.lineIndex >= lineSize - 1
                            moveToSpecification.isDisable = lineSize <= 1

                            applyTransparentPopupStyle()
                        }
                    }
                }

                is TreeRow.Folder.LoreSection -> {
                    fun refreshParentLine() {
                        refreshButtonVisual(selectData.id)
                        handleRefresh(
                            TreeRow.Folder.LoreLine(
                                lineIndex = row.lineIndex,
                                lineUiId = row.lineUiId
                            )
                        )
                    }

                    val sectionSystem = LoreLineEditor(selectData.display, row.lineIndex).section(row.sectionIndex)

                    ContextMenu().apply {
                        val moveForward = MenuItem("前のセクションと入れ替え").apply {
                            onAction = EventHandler {
                                val fromIndex = row.sectionIndex
                                val toIndex = row.sectionIndex - 1

                                sectionSystem.moveTo(toIndex)
                                loreTreeUiIdMemory.sectionMoved(selectData.id, row.lineUiId, fromIndex, toIndex)

                                refreshParentLine()
                            }
                        }

                        val moveBack = MenuItem("後ろのセクションと入れ替え").apply {
                            onAction = EventHandler {
                                val fromIndex = row.sectionIndex
                                val toIndex = row.sectionIndex + 1

                                sectionSystem.moveTo(toIndex)
                                loreTreeUiIdMemory.sectionMoved(selectData.id, row.lineUiId, fromIndex, toIndex)

                                refreshParentLine()
                            }
                        }

                        val moveToSpecification = MenuItem("指定したセクションと入れ替え").apply {
                            onAction = EventHandler {
                                val sectionSize = LoreLineEditor(selectData.display, row.lineIndex).getSectionSize()
                                val maxIndex = (sectionSize - 1).coerceAtLeast(0)

                                val insertIndex = showInsertIndexDialog(
                                    title = "セクションを入れ替え",
                                    header = "入れ替え先のセクションを選択してください",
                                    maxIndex = maxIndex
                                ) ?: return@EventHandler

                                sectionSystem.moveTo(insertIndex)
                                loreTreeUiIdMemory.sectionMoved(selectData.id, row.lineUiId, row.sectionIndex, insertIndex)

                                refreshParentLine()
                            }
                        }

                        val remove = MenuItem("このセクションを削除").apply {
                            onAction = EventHandler {
                                sectionSystem.remove()
                                loreTreeUiIdMemory.sectionRemoved(selectData.id, row.lineUiId, row.sectionIndex)

                                refreshParentLine()
                            }
                        }

                        items.addAll(
                            moveForward,
                            moveBack,
                            moveToSpecification,
                            Menu("前にセクションを追加").apply {
                                items.addAll(
                                    createLoreSectionTypeMenuItems { type ->
                                        sectionSystem.add(type)
                                        loreTreeUiIdMemory.sectionInserted(selectData.id, row.lineUiId, row.sectionIndex)

                                        refreshParentLine()
                                    }
                                )
                            },
                            Menu("後ろにセクションを追加").apply {
                                items.addAll(
                                    createLoreSectionTypeMenuItems { type ->
                                        val idx = row.sectionIndex + 1

                                        LoreLineEditor(selectData.display, row.lineIndex).section(idx).add(type)
                                        loreTreeUiIdMemory.sectionInserted(selectData.id, row.lineUiId, idx)

                                        refreshParentLine()
                                    }
                                )
                            },
                            remove
                        )

                        setOnShowing {
                            val sectionSize = LoreLineEditor(selectData.display, row.lineIndex).getSectionSize()

                            moveForward.isDisable = row.sectionIndex <= 0
                            moveBack.isDisable = row.sectionIndex >= sectionSize - 1
                            moveToSpecification.isDisable = sectionSize <= 1
                            remove.isDisable = sectionSize <= 1

                            applyTransparentPopupStyle()
                        }
                    }
                }

                else -> null
            }
        }

        val folderGraphicFactory = EditorFolderGraphicFactory<TreeRow> { row ->
            when (row) {
                TreeRow.Folder.Lore -> HBox(6.0).apply {
                    alignment = Pos.CENTER_LEFT
                    styleClass.add("editor-row-hbox")
                    applyTreeFolderRowSize()

                    children.addAll(
                        Label(row.label).apply {
                            styleClass.add("editor-label-highlight")
                            applyTreeFolderLabelSize()
                        },
                        Button("先頭に行を追加").apply {
                            applyTreeInlineButtonSize()

                            styleClass.add("tree-inline-button-add")

                            onAction = EventHandler { event ->
                                ContextMenu().apply {
                                    setOnShowing { applyTransparentPopupStyle() }

                                    items.addAll(
                                        createLoreSectionTypeMenuItems { type ->
                                            val insertIndex = 0

                                            LoreLineEditor(selectData.display, insertIndex).add(type)
                                            loreTreeUiIdMemory.lineInserted(
                                                itemId = selectData.id,
                                                index = insertIndex
                                            )

                                            refreshButtonVisual(selectData.id)
                                            handleRefresh(row)
                                        }
                                    )
                                }.show(this, Side.BOTTOM, 0.0, 0.0)

                                event.consume()
                            }
                        },
                        Button("末尾に行を追加").apply {
                            applyTreeInlineButtonSize()

                            styleClass.add("tree-inline-button-add")

                            onAction = EventHandler { event ->
                                ContextMenu().apply {
                                    setOnShowing { applyTransparentPopupStyle() }

                                    items.addAll(
                                        createLoreSectionTypeMenuItems { type ->
                                            val insertIndex = LoreLineEditor(selectData.display, 0).getLineSize()

                                            LoreLineEditor(selectData.display, insertIndex).add(type)
                                            loreTreeUiIdMemory.lineInserted(
                                                itemId = selectData.id,
                                                index = insertIndex
                                            )

                                            refreshButtonVisual(selectData.id)
                                            handleRefresh(row)
                                        }
                                    )
                                }.show(this, Side.BOTTOM, 0.0, 0.0)

                                event.consume()
                            }
                        },
                        Button("指定位置に行を追加").apply {
                            applyTreeInlineButtonSize()

                            styleClass.add("tree-inline-button-add")

                            onAction = EventHandler { event ->
                                ContextMenu().apply {
                                    setOnShowing { applyTransparentPopupStyle() }

                                    items.addAll(
                                        createLoreSectionTypeMenuItems { type ->
                                            val maxIndex = LoreLineEditor(selectData.display, 0).getLineSize()

                                            val insertIndex = showInsertIndexDialog("行を追加", "追加する位置を選択してください", maxIndex) ?: return@createLoreSectionTypeMenuItems

                                            LoreLineEditor(selectData.display, insertIndex).add(type)
                                            loreTreeUiIdMemory.lineInserted(selectData.id, insertIndex)

                                            refreshButtonVisual(selectData.id)
                                            handleRefresh(row)
                                        }
                                    )
                                }.show(this, Side.BOTTOM, 0.0, 0.0)

                                event.consume()
                            }
                        }
                    )
                }

                is TreeRow.Folder.LoreLine -> HBox(8.0).apply {
                    alignment = Pos.CENTER_LEFT
                    styleClass.add("editor-row-hbox")
                    applyTreeFolderRowSize()

                    val lineSystem = LoreLineEditor(selectData.display, row.lineIndex)

                    children.addAll(
                        Label(row.label).apply {
                            styleClass.add("editor-label-highlight")
                            applyTreeFolderLabelSize()
                        },
                        Button("▲").apply {
                            applyTreeInlineButtonSize()

                            styleClass.add("tree-inline-button-add")

                            isDisable = row.lineIndex <= 0

                            onAction = EventHandler { event ->
                                val toIndex = row.lineIndex - 1

                                lineSystem.moveTo(toIndex)
                                loreTreeUiIdMemory.lineMoved(selectData.id, row.lineIndex, toIndex)

                                refreshButtonVisual(selectData.id)
                                handleRefresh(TreeRow.Folder.Lore)

                                event.consume()
                            }
                        },
                        Button("▼").apply {
                            applyTreeInlineButtonSize()

                            styleClass.add("tree-inline-button-add")

                            isDisable = row.lineIndex >= LoreLineEditor(selectData.display, 0).getLineSize() - 1

                            onAction = EventHandler { event ->
                                val fromIndex = row.lineIndex
                                val toIndex = row.lineIndex + 1

                                lineSystem.moveTo(toIndex)
                                loreTreeUiIdMemory.lineMoved(selectData.id, fromIndex, toIndex)

                                refreshButtonVisual(selectData.id)
                                handleRefresh(TreeRow.Folder.Lore)

                                event.consume()
                            }
                        },
                        Button("×").apply {
                            applyTreeInlineButtonSize()

                            styleClass.add("tree-inline-button-remove")

                            onAction = EventHandler { event ->
                                lineSystem.remove()
                                loreTreeUiIdMemory.lineRemoved(selectData.id, row.lineIndex)

                                refreshButtonVisual(selectData.id)
                                handleRefresh(TreeRow.Folder.Lore)

                                event.consume()
                            }
                        }
                    )
                }

                is TreeRow.Folder.LoreSection -> HBox(8.0).apply {
                    fun refreshParentLine() {
                        refreshButtonVisual(selectData.id)
                        handleRefresh(
                            TreeRow.Folder.LoreLine(
                                lineIndex = row.lineIndex,
                                lineUiId = row.lineUiId
                            )
                        )
                    }

                    alignment = Pos.CENTER_LEFT
                    styleClass.add("editor-row-hbox")
                    applyTreeFolderRowSize()

                    val sectionSystem = LoreLineEditor(selectData.display, row.lineIndex).section(row.sectionIndex)

                    children.addAll(
                        Label(row.label).apply {
                            styleClass.add("editor-label-highlight")
                            applyTreeFolderLabelSize()
                        },
                        Button("▲").apply {
                            applyTreeInlineButtonSize()

                            styleClass.add("tree-inline-button-add")

                            isDisable = row.sectionIndex <= 0

                            onAction = EventHandler { event ->
                                val fromIndex = row.sectionIndex
                                val toIndex = row.sectionIndex - 1

                                sectionSystem.moveTo(toIndex)
                                loreTreeUiIdMemory.sectionMoved(selectData.id, row.lineUiId, fromIndex, toIndex)

                                refreshParentLine()

                                event.consume()
                            }
                        },
                        Button("▼").apply {
                            applyTreeInlineButtonSize()

                            styleClass.add("tree-inline-button-add")

                            isDisable = row.sectionIndex >= LoreLineEditor(
                                selectData.display,
                                row.lineIndex
                            ).getSectionSize() - 1

                            onAction = EventHandler { event ->
                                val fromIndex = row.sectionIndex
                                val toIndex = row.sectionIndex + 1

                                sectionSystem.moveTo(toIndex)
                                loreTreeUiIdMemory.sectionMoved(selectData.id, row.lineUiId, fromIndex, toIndex)

                                refreshParentLine()

                                event.consume()
                            }
                        },
                        Button("×").apply {
                            applyTreeInlineButtonSize()

                            styleClass.add("tree-inline-button-remove")

                            isDisable = LoreLineEditor(selectData.display, row.lineIndex).getSectionSize() <= 1

                            onAction = EventHandler { event ->
                                sectionSystem.remove()
                                loreTreeUiIdMemory.sectionRemoved(selectData.id, row.lineUiId, row.sectionIndex)

                                refreshParentLine()

                                event.consume()
                            }
                        }
                    )
                }

                else -> null
            }
        }

        treeView.setCellFactory {
            LoreDragDropTreeCell(
                itemData = selectData,
                refreshButtonVisual = ::refreshButtonVisual,
                onRefresh = { row -> handleRefresh(row) },
                loreTreeUiIdMemory = loreTreeUiIdMemory,
                folderGraphicFactory = folderGraphicFactory,
                contextMenuFactory = contextMenuFactory
            )
        }
    }

    private fun createItemTreeBuilder(
        itemId: String,
        itemData: MutableItemBaseData,
        expandedMap: MutableMap<String, Boolean>
    ): ItemTreeBuilder {
        val lineSize = LoreLineEditor(itemData.display, 0).getLineSize()
        val lineUiIds = loreTreeUiIdMemory.getLineIds(
            itemId = itemId,
            lineSize = lineSize
        )

        return ItemTreeBuilder(
            itemData = itemData,
            expandedMap = expandedMap,
            lineUiIds = lineUiIds,
            getSectionUiIds = { lineUiId, sectionSize ->
                loreTreeUiIdMemory.getSectionIds(
                    itemId = itemId,
                    lineUiId = lineUiId,
                    sectionSize = sectionSize
                )
            }
        )
    }

    private fun ContextMenu.applyTransparentPopupStyle() {
        scene?.root?.styleClass?.let { classes ->
            if ("popup-root-transparent" !in classes) {
                classes.add("popup-root-transparent")
            }
        }
    }

    /**
     * 折りたたみの開閉時に、操作した行の表示位置が動かないよう固定します。
     *
     * #### 仕様:
     * - TreeViewのVirtualFlowは行数が変化してもスクロール位置を割合で保ちます。
     *   このツリーは行ごとに高さが異なるため、行を開くと表示中の位置が上下へ飛びます。
     * - 開閉の前後で操作した行の位置をpxで比較し、ずれた分だけ戻します。
     *   [javafx.scene.control.TreeView.scrollTo]は表示範囲内の行に対しては位置を変えないため使用できません。
     * - TreeItemのイベントはルートまで伝播するため、ルートへ登録するだけで配下すべての開閉を扱えます。
     *
     * @param rootItem 対象ツリーのルート。
     */
    private fun anchorRowOnBranchToggle(rootItem: TreeItem<TreeRow>) {
        val handler = EventHandler<TreeItem.TreeModificationEvent<TreeRow>> { event ->
            val toggled = event.treeItem ?: return@EventHandler
            val row = treeView.getRow(toggled)
            if (row < 0) return@EventHandler

            val beforeOffset = rowTopOffset(row) ?: return@EventHandler

            Platform.runLater {
                // 行数の変化に伴う再配置を確定させてから位置を比較する
                treeView.layout()

                val afterOffset = rowTopOffset(row) ?: return@runLater
                val delta = afterOffset - beforeOffset
                if (delta == 0.0) return@runLater

                treeVirtualFlow()?.scrollPixels(delta)
            }
        }

        rootItem.addEventHandler(TreeItem.branchExpandedEvent<TreeRow>(), handler)
        rootItem.addEventHandler(TreeItem.branchCollapsedEvent<TreeRow>(), handler)
    }

    /**
     * 指定行のセルの、TreeView上端からの位置をpxで返します。
     *
     * 表示範囲外の行はセルが生成されていないため`null`になります。
     *
     * @param row [javafx.scene.control.TreeView.getRow]で得られる行番号。
     * @return TreeView上端を0とした位置。取得できない場合は`null`。
     */
    private fun rowTopOffset(row: Int): Double? {
        val treeTop = treeView.localToScene(0.0, 0.0)?.y?.takeUnless { it.isNaN() } ?: return null

        val cellTop = treeView.lookupAll(".tree-cell")
            .asSequence()
            .filterIsInstance<TreeCell<*>>()
            .firstOrNull { !it.isEmpty && it.index == row }
            ?.localToScene(0.0, 0.0)
            ?.y
            ?.takeUnless { it.isNaN() }
            ?: return null

        return cellTop - treeTop
    }

    /**
     * ツリーのVirtualFlowを取得します。
     *
     * スキンの生成後にだけ取得できるため、表示前は`null`になります。
     */
    private fun treeVirtualFlow(): VirtualFlow<*>? {
        return treeView.lookup(".virtual-flow") as? VirtualFlow<*>
    }

    /**
     * スクロールイベントの発生元が、セル内で自前のスクロールを持つコントロールかを判定します。
     *
     * TextAreaなどの上では、ツリーではなくそのコントロールをスクロールさせます。
     *
     * @param target スクロールイベントの発生元。
     * @return セル側で処理すべき場合は`true`。
     */
    private fun isScrollHandledByCell(target: EventTarget): Boolean {
        var node = target as? Node

        while (node != null && node !== treeView) {
            if (node is ScrollPane || node is TextArea) return true
            node = node.parent
        }

        return false
    }

    private fun handleRefresh(targetRow: TreeRow) {
        val currentItemId = currentSelectedDataId ?: return
        val currentData = editingDataMap[currentItemId] ?: return
        val targetItem = findTreeItemByRow(treeView.root, targetRow) ?: return

        val expandedMap = expandedStateCache.getOrPut(currentItemId) {
            mutableMapOf()
        }

        val builder = createItemTreeBuilder(
            itemId = currentItemId,
            itemData = currentData,
            expandedMap = expandedMap
        )

        when (targetRow) {
            is TreeRow.Folder.LoreLine -> {
                builder.rebuildLoreLine(targetItem, targetRow.lineIndex)
            }

            else -> {
                builder.rebuildRoot(targetItem)
            }
        }
    }

    private fun findTreeItemByRow(root: TreeItem<TreeRow>, targetRow: TreeRow): TreeItem<TreeRow>? {
        if (root.value == targetRow) return root
        for (child in root.children) {
            val found = findTreeItemByRow(child, targetRow)
            if (found != null) return found
        }
        return null
    }

    override fun focusValidationError(error: SushiEricValidationError): Boolean {
        val targetRow = when (error.property.name) {
            "displayName" -> TreeRow.Editor.DisplayName
            "stats", "statMultipliers" -> TreeRow.Editor.StatsContent
            "id" -> return false
            else -> TreeRow.Editor.DetailContent
        }
        val targetItem = findTreeItemByRow(treeView.root, targetRow) ?: return false
        var parent = targetItem.parent
        while (parent != null) {
            parent.isExpanded = true
            parent = parent.parent
        }
        treeView.selectionModel.select(targetItem)
        treeView.getRow(targetItem).takeIf { it >= 0 }?.let(treeView::scrollTo)
        treeView.requestFocus()
        return true
    }

    override fun refreshButtonVisual(id: String) {
        // プレビュー更新
        previewCanvas?.refreshPreview()
        super.refreshButtonVisual(id)
    }
}

internal fun mergeItemDetailForConflict(
    server: MutableItemDetail,
    current: MutableItemDetail,
    keepLocalDetail: Boolean,
    keepLocalHeadSkin: Boolean
): MutableItemDetail {
    val merged = if (keepLocalDetail) current.deepCopy() else server.deepCopy()
    merged.mutableHeadSkin = if (keepLocalHeadSkin) {
        current.mutableHeadSkin?.deepCopy()
    } else {
        server.mutableHeadSkin?.deepCopy()
    }
    return merged
}
