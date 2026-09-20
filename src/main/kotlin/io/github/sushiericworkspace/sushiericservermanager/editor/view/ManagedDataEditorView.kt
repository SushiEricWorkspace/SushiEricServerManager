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
        val existingIds = ids.toSet()
        ids.forEach { id ->
            sidebarButtons[id] = createSidebarButton(id, existingIds)
        }
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
        val visibleButtons = filterManagedDataIds(
            sidebarDataIds,
            sidebarSearchField.text.orEmpty()
        ).mapNotNull(sidebarButtons::get)
        if (visibleButtons.isEmpty()) {
            sidebarResultsContainer.children.setAll(sidebarNoResultsLabel)
        } else {
            sidebarResultsContainer.children.setAll(visibleButtons)
        }
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
        val newId = requestNewId("${dataAccess.displayName}を複製", existingIds) ?: return
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
        val newId = requestNewId("名前変更", existingIds) ?: return
        when (dataAccess.rename(id, newId)) {
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
        val confirmed = CustomDialog.confirmation()
            .title("警告")
            .header("破壊的変更")
            .content("${dataAccess.displayName}ID: $id\n\nファイルを削除します。この操作は元に戻せません。")
            .okButton("削除", Color.RED)
            .owner(main.currentStage)
            .show()
        if (!confirmed) return

        when (dataAccess.delete(id)) {
            DeleteResult.SUCCESS -> {
                editingDataMap.remove(id)
                originalDataMap.remove(id)
                onDataDeleted(id)
                main.showTimedTopLabel("$id を削除しました", Color.GREENYELLOW)
                setupSidebar(main.sidebarContainer)
            }
            DeleteResult.FILE_NOT_FOUND -> {
                CustomDialog.error(ErrorType.FILE_NOT_FOUND).owner(main.currentStage).show()
                setupSidebar(main.sidebarContainer)
            }
            DeleteResult.FAILED, DeleteResult.PROFILE_NOT_SELECTED, DeleteResult.SFTP_INACTIVE -> {
                CustomDialog.error(ErrorType.NETWORK_ERROR).owner(main.currentStage).show()
                handleForceBackToSelect()
            }
        }
    }

    private fun requestNewId(title: String, existingIds: Set<String>): String? =
        main.requestInput(title) { input ->
            when {
                input.isBlank() -> ValidationResult.Error("名前を入力してください")
                !PublicId.isValid(input) -> ValidationResult.Error(PublicId.DESCRIPTION)
                input in existingIds -> ValidationResult.Error("重複した名称です")
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
