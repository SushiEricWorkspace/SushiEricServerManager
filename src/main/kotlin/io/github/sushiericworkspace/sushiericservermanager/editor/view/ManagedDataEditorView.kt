package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.PublicId
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.scene.control.Label
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.TextField
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.layout.VBox
import javafx.scene.layout.Priority
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
    private val sidebarResults = EditorSidebar(
        noResultsText = "該当する${dataAccess.displayName}がありません",
        stateFor = ::sidebarState,
        onSelect = ::selectTab,
        createDataMenu = { id -> createSidebarContextMenu(id) },
        createDirectoryMenu = ::createDirectoryContextMenu,
        onMove = ::moveData,
        expandedState = sidebarExpandedState,
        onExpandedStateChanged = ::persistSidebarExpandedState,
        onExpansionChanged = ::renderSidebarResults
    )

    private var sidebarDataIds: List<String> = emptyList()
    private var sidebarDirectories: List<String> = emptyList()

    override fun setupSidebar(container: VBox, selectId: String?) {
        container.children.setAll(sidebarSearchField, sidebarResults)
        VBox.setVgrow(sidebarResults, Priority.ALWAYS)
        VBox.setMargin(sidebarSearchField, Insets(10.0))
        sidebarDataIds = emptyList()
        sidebarResults.showRows(emptyList())

        sidebarResults.placeholder = Label("一覧を読み込み中...")
        loadSidebar(onFailed = {
            sidebarResults.placeholder = Label("一覧の取得に失敗しました")
        }) { ids, directories ->
            sidebarResults.placeholder = Label("該当する${dataAccess.displayName}がありません")
            sidebarDataIds = ids
            sidebarDirectories = directories
            registerSidebar(ids, sidebarResults)
            renderSidebarResults()

            val selected = restoreDocumentTabs(ids.filterNot(::isPendingStoreDeletion), selectId)
            if (selected == null) {
                currentSelectedDataId = null
                main.mainContentContainer.children.clear()
            } else selectTab(selected)
            ids.forEach(::refreshSidebarState)
            preloadSidebarDataForVisualStates(ids)
            startAutoSaveTimer()

        }
    }

    private fun renderSidebarResults() {
        sidebarResults.showRows(buildSidebarRows(
            sidebarDataIds, sidebarDirectories, sidebarSearchField.text.orEmpty(),
            sidebarDisplayMode, sidebarExpandedState
        ))
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
                onAction = EventHandler { requestDeleteDirectory(directory, ::onDataDeleted) }
            }
        }
        return ContextMenu(*items.toTypedArray())
    }

    private fun moveData(id: String, directory: String): Boolean {
        if (storeOperationBlocked) return false
        val newId = PublicId.join(directory.split('.').filter(String::isNotEmpty), PublicId.nameOf(id))
        if (newId == id) return false
        if (!dataAccess.descriptor.isValidId(newId)) {
            showDirectoryError("商品はタブ配下へ配置してください")
            return false
        }
        if (newId in sidebarDataIds) {
            showDirectoryError("移動先に同名のデータがあります")
            return false
        }
        return renameData(id, newId) { onDataRenamed(id, newId) }
    }

    private fun showDirectoryError(message: String) {
        CustomDialog.error().title("ディレクトリ操作エラー").header(message).owner(main.currentStage).show()
    }

    private fun createSidebarContextMenu(id: String): ContextMenu {
        if (isPendingStoreDeletion(id)) {
            return ContextMenu(
                MenuItem("削除を保存").apply {
                    onAction = EventHandler { onSave(id) }
                },
                MenuItem("削除を取り消す").apply {
                    onAction = EventHandler {
                        if (cancelPendingStoreDeletion(id)) {
                            main.showTimedTopLabel("$id の削除を取り消しました", Color.GREENYELLOW)
                            refreshLocalSidebar(id)
                        }
                    }
                }
            )
        }
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
                onAction = EventHandler { requestDuplicate(id) }
            },
            MenuItem("IDを変更").apply {
                onAction = EventHandler { requestRename(id) }
            },
            MenuItem("削除").apply {
                styleClass.add("menu-item-danger")
                onAction = EventHandler { requestDelete(id) }
            }
        ).apply {
            setOnShowing {
                saveItem.isDisable = originalDataMap[id] == editingDataMap[id] && !hasPendingStoreOperation(id)
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

    private fun requestRename(id: String) {
        if (storeOperationBlocked) return
        val directory = PublicId.directoryOf(id)
        val newName = requestDataName("名前変更", directory) ?: return
        if (storeOperationBlocked) return
        val newId = PublicId.join(directory, newName)
        renameData(id, newId) { onDataRenamed(id, newId) }
    }

    private fun requestDelete(id: String) {
        if (storeOperationBlocked) return
        val localOnly = isLocalOnlyData(id, remoteDataIds) && !pendingOperationHasStoredSource(id)
        val confirmed = CustomDialog.confirmation()
            .title(if (localOnly) "未保存データを破棄" else "削除を保留")
            .header(if (localOnly) "ローカルの編集内容を破棄します" else "保存時にファイルを削除します")
            .content(
                if (localOnly) {
                    "${dataAccess.displayName}ID: $id\n\n" +
                        "サーバー上のファイルは削除せず、編集内容と自動保存を破棄します。"
                } else {
                    "${dataAccess.displayName}ID: $id\n\n" +
                        "削除はまだサーバーへ反映されません。保存時の確認後に削除します。"
                }
            )
            .okButton("削除", Color.RED)
            .owner(main.currentStage)
            .show()
        if (!confirmed || storeOperationBlocked) return

        if (stageDataDeletion(id)) {
            onDataDeleted(id)
            main.showTimedTopLabel("$id の追加を取り消しました", Color.GREENYELLOW)
            refreshLocalSidebar()
        } else {
            main.showTimedTopLabel("$id の削除を保留しました。保存すると反映されます", Color.GREENYELLOW)
            refreshLocalSidebar()
        }
    }

    protected open fun onDataRenamed(oldId: String, newId: String) = Unit

    protected open fun onDataDeleted(id: String) = Unit

    override fun onPersistedDataDeleted(id: String) {
        onDataDeleted(id)
    }
}
