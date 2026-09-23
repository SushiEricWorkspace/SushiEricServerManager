package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.common.data.core.identity.PublicId
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.result.ValidationResult
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.DeleteResult
import io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice.RenameResult
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.ErrorType
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.TextField
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.VBox
import javafx.scene.paint.Color

internal fun filterManagedDataIds(ids: List<String>, query: String): List<String> {
    val normalizedQuery = query.trim()
    if (normalizedQuery.isEmpty()) return ids
    return ids.filter { id ->
        PublicId.normalizeForLoad(id).contains(normalizedQuery, ignoreCase = true)
    }
}

internal fun isLocalOnlyData(id: String, remoteDataIds: Set<String>): Boolean =
    id !in remoteDataIds

/**
 * 保存に成功したデータIDを、サーバー上に存在するIDの集合へ加えます。
 *
 * サイドバーを作り直すまで一覧を取得し直さないため、保存した時点でローカルのみの表示を
 * 解除できるようにします。配下のデータが1件でもサーバー上に存在すれば、そのディレクトリも
 * ローカルのみではなくなります。
 *
 * @param remoteDataIds 現在サーバー上に存在すると分かっているID。
 * @param dataId 保存に成功したデータID。
 * @return [dataId]を加えたID集合。
 */
internal fun withStoredDataId(remoteDataIds: Set<String>, dataId: String): Set<String> =
    remoteDataIds + dataId

internal fun isLocalOnlyDirectory(
    directory: String,
    affectedIds: Collection<String>,
    remoteDataIds: Set<String>,
    remoteDirectories: Collection<String>
): Boolean = directory !in remoteDirectories && affectedIds.none(remoteDataIds::contains)

/** 公開IDで管理するデータエディタのサイドバーとCRUD操作を共通提供します。 */
internal abstract class ManagedDataEditorView<T : ManagedData<T, *>>(
    main: MainController,
    dataService: EditorDataService,
    dataAccess: EditorDataService.DataAccess<T>
) : EditorView<T>(main, dataService, dataAccess) {

    private val sidebarSearchField = TextField().apply {
        promptText = "公開IDを検索"
        maxWidth = Double.MAX_VALUE
        styleClass.add("sidebar-search-field")
        textProperty().addListener { _, _, _ -> renderSidebarResults() }
    }
    private val sidebarResultsContainer = VBox().apply {
        maxWidth = Double.MAX_VALUE
        styleClass.add("sidebar-results-container")
    }
    private val sidebarNoResultsLabel = Label("該当する${dataAccess.displayName}がありません").apply {
        maxWidth = Double.MAX_VALUE
        styleClass.add("sidebar-no-results-label")
    }
    private var sidebarDataIds: List<String> = emptyList()
    private var sidebarDirectories: List<String> = emptyList()

    override fun setupSidebar(container: VBox, selectId: String?) {
        container.children.setAll(sidebarSearchField, sidebarResultsContainer)
        VBox.setMargin(sidebarSearchField, Insets(10.0))
        selectedButton = null
        sidebarButtons.clear()
        sidebarDataIds = emptyList()
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

        val remoteIds = fileResources.map { it.name.removeSuffix(".yml") }
        remoteDataIds = remoteIds.toSet()
        val ids = mergeSidebarIds(remoteIds, editingDataMap.keys)
        sidebarDataIds = ids
        sidebarDirectories = when (val result = dataAccess.listDirectories()) {
            is StoreResult.Success -> result.value
            is StoreResult.Failure -> emptyList()
        }
        val existingIds = ids.toSet()
        ids.forEach { id ->
            sidebarButtons[id] = createSidebarButton(id, existingIds)
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
        if (targetId in existingIds) selectTab(targetId)
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
                .render(sidebarDataIds, sidebarSearchField.text.orEmpty())
            sidebarResultsContainer.children.setAll(buttons.ifEmpty { listOf(sidebarNoResultsLabel) })
            return
        }
        val nodes = buildSidebarTree(sidebarDataIds, sidebarDirectories, sidebarSearchField.text.orEmpty())
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
        val affected = idsInSidebarDirectory(sidebarDataIds, directory)
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
            affected.forEach(::discardData)
            main.showTimedTopLabel("$directory のローカル編集内容を破棄しました", Color.GREENYELLOW)
            setupSidebar(main.sidebarContainer)
            return
        }

        when (dataAccess.deleteDirectory(directory)) {
            is StoreResult.Success -> {
                affected.forEach(::discardData)
                setupSidebar(main.sidebarContainer)
            }
            is StoreResult.Failure -> showDirectoryError("ディレクトリを削除できませんでした")
        }
    }

    private fun moveData(id: String, directory: String): Boolean = when (val result = dataAccess.move(id, directory)) {
        is StoreResult.Success -> {
            renameCachedData(editingDataMap, id, result.value)
            renameCachedData(originalDataMap, id, result.value)
            onDataRenamed(id, result.value)
            setupSidebar(main.sidebarContainer, result.value)
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

    private fun createSidebarButton(id: String, existingIds: Set<String>): Button =
        Button(PublicId.normalizeForLoad(id)).apply {
            isFocusTraversable = false
            isMnemonicParsing = false
            this.id = id
            maxWidth = Double.MAX_VALUE
            alignment = Pos.CENTER_LEFT
            onAction = EventHandler { selectTab(id) }
            contextMenu = createSidebarContextMenu(id, existingIds)
        }

    private fun createSidebarContextMenu(id: String, existingIds: Set<String>): ContextMenu {
        val saveItem = MenuItem("保存").apply {
            onAction = EventHandler { onSave(id) }
        }
        val validationItems = createValidationContextMenuItems(id)
        return ContextMenu(
            MenuItem("IDをコピー").apply {
                onAction = EventHandler { copyId(id) }
            },
            saveItem,
            validationItems.repairWarnings,
            validationItems.repairErrors,
            MenuItem("複製").apply {
                onAction = EventHandler { requestDuplicate(id, existingIds) }
            },
            MenuItem("IDを変更").apply {
                onAction = EventHandler { requestRename(id, existingIds) }
            },
            MenuItem("削除").apply {
                styleClass.add("menu-item-danger")
                onAction = EventHandler { requestDelete(id) }
            }
        ).apply {
            setOnShowing {
                saveItem.isDisable = originalDataMap[id] == editingDataMap[id]
                refreshValidationContextMenuItems(id, validationItems)
            }
        }
    }

    private fun copyId(id: String) {
        Clipboard.getSystemClipboard().setContent(
            ClipboardContent().apply { putString(id) }
        )
        main.showTimedTopLabel("コピーしました: $id", Color.GREENYELLOW)
    }

    private fun requestDuplicate(id: String, existingIds: Set<String>) {
        val source = editingDataMap[id]?.deepCopy() ?: dataAccess.load(id).first?.deepCopy()
        if (source == null) {
            CustomDialog.error()
                .title("複製エラー")
                .header("複製元の${dataAccess.displayName}を読み込めませんでした")
                .content("対象ID: $id")
                .owner(main.currentStage)
                .show()
            return
        }
        val directory = PublicId.directoryOf(id)
        val newName = requestNewId("${dataAccess.displayName}を複製", directory, existingIds) ?: return
        val newId = PublicId.join(directory, newName)
        val duplicate = dataAccess.duplicateAsNew(source, newId)
        when (val result = dataAccess.saveStore(newId, duplicate)) {
            is StoreResult.Success -> {
                editingDataMap[newId] = duplicate
                originalDataMap[newId] = duplicate.deepCopy()
                main.showTimedTopLabel("$id を $newId として複製しました", Color.GREENYELLOW)
                setupSidebar(main.sidebarContainer, newId)
            }
            is StoreResult.Failure -> handleSaveFailure(result.error)
        }
    }

    private fun requestRename(id: String, existingIds: Set<String>) {
        val directory = PublicId.directoryOf(id)
        val newName = requestNewId("名前変更", directory, existingIds) ?: return
        val newId = PublicId.join(directory, newName)
        when (dataAccess.rename(id, newName)) {
            RenameResult.SUCCESS -> {
                renameCachedData(editingDataMap, id, newId)
                renameCachedData(originalDataMap, id, newId)
                onDataRenamed(id, newId)
                main.showTimedTopLabel("$id を $newId に変更しました", Color.GREENYELLOW)
                setupSidebar(main.sidebarContainer, newId)
            }
            RenameResult.FILE_NOT_FOUND -> showRenameError("対象のファイルが見つかりません")
            RenameResult.ALREADY_EXISTS -> showRenameError("同名のファイルが既に存在します")
            RenameResult.SFTP_INACTIVE, RenameResult.PROFILE_NOT_SELECTED -> {
                CustomDialog.error(ErrorType.SFTP_ERROR).owner(main.currentStage).show()
                handleForceBackToSelect()
            }
            RenameResult.FAILED -> showRenameError("名前変更に失敗しました")
        }
    }

    private fun requestDelete(id: String) {
        val localOnly = isLocalOnlyData(id, remoteDataIds)
        val confirmed = CustomDialog.confirmation()
            .title(if (localOnly) "未保存データを破棄" else "警告")
            .header(if (localOnly) "ローカルの編集内容を破棄します" else "破壊的変更")
            .content(
                if (localOnly) {
                    "${dataAccess.displayName}ID: $id\n\n" +
                        "サーバー上のファイルは削除せず、編集内容と自動保存を破棄します。"
                } else {
                    "${dataAccess.displayName}ID: $id\n\nファイルを削除します。この操作は元に戻せません。"
                }
            )
            .okButton("削除", Color.RED)
            .owner(main.currentStage)
            .show()
        if (!confirmed) return

        if (localOnly) {
            discardData(id)
            main.showTimedTopLabel("$id のローカル編集内容を破棄しました", Color.GREENYELLOW)
            setupSidebar(main.sidebarContainer)
            return
        }

        when (dataAccess.delete(id)) {
            DeleteResult.SUCCESS -> {
                discardData(id)
                main.showTimedTopLabel("$id を削除しました", Color.GREENYELLOW)
                setupSidebar(main.sidebarContainer)
            }
            DeleteResult.FILE_NOT_FOUND -> {
                discardData(id)
                main.showTimedTopLabel(
                    "$id はサーバー上に存在しないため、ローカル編集内容を破棄しました",
                    Color.GREENYELLOW
                )
                setupSidebar(main.sidebarContainer)
            }
            DeleteResult.FAILED, DeleteResult.PROFILE_NOT_SELECTED, DeleteResult.SFTP_INACTIVE -> {
                CustomDialog.error(ErrorType.NETWORK_ERROR).owner(main.currentStage).show()
                handleForceBackToSelect()
            }
        }
    }

    private fun discardData(id: String) {
        discardLocalEditingData(id)
        onDataDeleted(id)
    }

    private fun requestNewId(
        title: String,
        directory: List<String>,
        existingIds: Set<String>
    ): String? =
        main.requestInput(title) { input ->
            when {
                input.isBlank() -> ValidationResult.Error("名前を入力してください")
                !PublicId.isValid(input) -> ValidationResult.Error(PublicId.DESCRIPTION)
                PublicId.join(directory, input) in existingIds -> ValidationResult.Error("重複した名称です")
                else -> ValidationResult.Success
            }
        }

    private fun renameCachedData(cache: MutableMap<String, T>, oldId: String, newId: String) {
        cache.remove(oldId)?.let { data ->
            data.id = newId
            cache[newId] = data
        }
    }

    private fun showRenameError(header: String) {
        CustomDialog.error()
            .title("名前変更エラー")
            .header(header)
            .owner(main.currentStage)
            .show()
    }

    protected open fun onDataRenamed(oldId: String, newId: String) = Unit

    protected open fun onDataDeleted(id: String) = Unit
}
