package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.editor.service.*
import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import io.github.sushiericworkspace.sushiericservermanager.editor.view.EditorWindowManager
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import javafx.application.Platform
import javafx.scene.control.*
import javafx.scene.input.*
import javafx.scene.layout.*
import javafx.stage.Stage
import java.util.UUID

/** 一つの接続内でLocal一覧と宛先一覧を常設し、dropと通信実行を分離します。 */
internal class UploadWorkspacePanels(private val target: EditorDataService, private val owner: () -> Stage?) {
    internal data class Entry(val key: EditorDocumentKey, val directory: Boolean, val label: String)
    private data class Listing(
        val local: List<Entry>, val server: Result<List<Entry>>, val plan: StoreResult<UploadPlan>,
        val requests: StoreResult<LocalDocumentSnapshot>
    )
    private var requests: Map<String, SavedUploadOperation> = emptyMap()
    private val plans = UploadPlanService(target)
    private var plan: UploadPlan? = null
    private var disposed = false
    private var busy = false
    private var refreshPending = false
    private var readbackAction: SavedUploadAction? = null
    val operationRunning: Boolean get() = busy || readbackAction?.busy == true
    private var dragged: List<UploadPlanRoot> = emptyList()
    private val token = UUID.randomUUID().toString()
    private val orderFormat = DataFormat("application/x-sushieric-upload-order-251-" + token)
    private val format = DataFormat("application/x-sushieric-upload-reservation-251-" + token)
    val local = TreeView<Entry>().apply { id = "upload-local-tree"; isShowRoot = false; selectionModel.selectionMode = SelectionMode.MULTIPLE }
    val server = TreeView<Entry>().apply { id = "upload-server-tree"; isShowRoot = false }
    private val groups = ListView<UploadPlanGroup>().apply { id = "upload-plan-groups"; prefHeight = 110.0 }
    private val state = Label("予約を読み込んでいます")
    private val refresh = Button("一覧を更新").apply { setOnAction { reload() } }
    private val apply = Button("予約を確認して一括実行").apply { id = "upload-plan-apply"; setOnAction { executeAll() } }
    val localPane = VBox(4.0, Label("ローカル（オフラインと共用）"), local,
        button("選択を宛先へ予約") {
            server.selectionModel.selectedItem?.value?.takeIf { it.directory }?.let { destination ->
                reserve(local.selectionModel.selectedItems.map { UploadPlanRoot(it.value.key, it.value.directory) }, destination.key)
            }
        }.apply { id = "upload-reserve-selection" }).apply {
        prefWidth = 220.0; minWidth = 100.0; VBox.setVgrow(local, Priority.ALWAYS)
    }
    val serverPane = VBox(4.0, Label("接続先の作業コピー / 宛先"), server).apply {
        prefWidth = 220.0; minWidth = 100.0; VBox.setVgrow(server, Priority.ALWAYS)
    }
    val planPane = VBox(4.0).apply {
        id = "upload-plan"; minWidth = 0.0
        children.addAll(HBox(8.0, Label("送信予約（追加だけでは通信しません）"), refresh, apply), groups,
            HBox(6.0, button("↑") { reorder(-1) }, button("↓") { reorder(1) },
                button("宛先へ再割当て") { reassign() }, button("予約を削除") { remove() },
                button("同じ要求を読戻し") { readback() }, state))
    }

    init {
        local.setCellFactory { cell(false) }; server.setCellFactory { cell(true) }
        groups.setCellFactory { object : ListCell<UploadPlanGroup>() {
            init {
                setOnDragDetected { event ->
                    item?.takeIf { !operationRunning }?.let { group ->
                        startDragAndDrop(TransferMode.MOVE).setContent(ClipboardContent().apply { put(orderFormat, group.id) })
                        event.consume()
                    }
                }
                setOnDragOver { event ->
                    if (!operationRunning && event.dragboard.hasContent(orderFormat)) event.acceptTransferModes(TransferMode.MOVE)
                    event.consume()
                }
                setOnDragDropped { event ->
                    val id = event.dragboard.getContent(orderFormat) as? String
                    val current = plan
                    if (!operationRunning && id != null && current != null && current.groups.any { it.id == id } && item?.id != id) {
                        val remaining = current.groups.filterNot { it.id == id }
                        val destination = remaining.indexOfFirst { it.id == item?.id }.takeIf { it >= 0 } ?: remaining.size
                        mutate { plans.save(it.move(id, destination)) }
                        event.isDropCompleted = true
                    } else event.isDropCompleted = false
                    event.consume()
                }
            }
            override fun updateItem(item: UploadPlanGroup?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null else item.documents.joinToString(" / ") {
                    it.key.category + ": " + it.key.id + " → " + it.targetId
                } + " | Item ID対応 " + item.itemIds.entries.joinToString { it.key + "→" + it.value } + " / Ore ID対応 " + item.oreIds.entries.joinToString { it.key + "→" + it.value } +
                    (item.operationId?.let { id -> " | 要求 " + id + " / " +
                        (requests[id]?.steps?.joinToString { it.key.id + ": " + it.state.displayName } ?: "台帳未読・保留") } ?: " | 未実行")
            }
        } }
        reload()
    }

    private fun button(text: String, action: () -> Unit) = Button(text).apply { setOnAction { action() } }

    private fun cell(destination: Boolean) = object : TreeCell<Entry>() {
        override fun updateItem(item: Entry?, empty: Boolean) {
            super.updateItem(item, empty); text = if (empty) null else item?.label
        }
        init {
            setOnMouseClicked { if (it.clickCount == 2) item?.takeUnless { it.directory }?.let { entry ->
                EditorWindowManager.openDocument(entry.key)
            } }
            if (!destination) setOnDragDetected { event ->
                dragged = local.selectionModel.selectedItems.map { UploadPlanRoot(it.value.key, it.value.directory) }
                if (!operationRunning && dragged.isNotEmpty()) {
                    startDragAndDrop(TransferMode.COPY).setContent(ClipboardContent().apply { put(format, token) })
                    event.consume()
                }
            }
            setOnDragDone { dragged = emptyList() }
            if (destination) {
                setOnDragOver { event ->
                    if (!busy && item?.directory == true && event.dragboard.getContent(format) == token)
                        event.acceptTransferModes(TransferMode.COPY)
                    event.consume()
                }
                setOnDragDropped { event ->
                    val entry = item
                    val accepted = !busy && entry?.directory == true && event.dragboard.getContent(format) == token
                    if (accepted) reserve(dragged, entry!!.key)
                    event.isDropCompleted = accepted; event.consume()
                }
            }
        }
    }

    internal fun reserve(roots: List<UploadPlanRoot>, destination: EditorDocumentKey) {
        if (operationRunning || disposed || roots.isEmpty()) return
        if (roots.any { it.key.category != destination.category }) { message("同じデータ種別の宛先へ追加してください。"); return }
        // 正規保存を順に完了してから、固定savedだけを予約へ追加します。
        if (!CustomDialog.confirmation().title("保存して送信予約へ追加")
            .header("選択したローカル文書を保存して予約へ追加しますか？")
            .content("保存の取消・失敗時は予約へ追加しません。サーバーへの送信は後の一括実行で確認します。")
            .owner(owner()).show()) return
        setBusy(true)
        target.localService.submitIo {
            listOf(target.localService.items.descriptor, target.localService.ores.descriptor,
                target.localService.shops.descriptor, target.localService.recipes.descriptor).forEach { target.localService.store.list(it) }
            target.localService.documents.read()
        }.whenComplete { result, error -> Platform.runLater {
            if (disposed) { setBusy(false); return@runLater }
            if (error != null || result !is StoreResult.Success) { setBusy(false); message("ローカル文書を読み込めません。"); return@runLater }
            val keys = result.value.documents.filter { record -> roots.any { root -> root.key.category == record.key.category &&
                if (root.directory) root.key.id.isEmpty() || record.key.id.startsWith(root.key.id + ".") else root.key == record.key
            } && !record.deleted && record.movedTo == null }.map { it.key }
            fun save(index: Int) {
                if (index == keys.size) {
                    mutate { current -> when (val created = plans.createGroup(roots, destination.id)) {
                        is StoreResult.Failure -> created
                        is StoreResult.Success -> plans.save(current.add(created.value))
                    } }; return
                }
                EditorWindowManager.openDocument(keys[index], save = true) { ok ->
                    if (ok && !disposed) save(index + 1) else { setBusy(false); message("保存を完了できなかったため予約を変更していません。") }
                }
            }
            save(0)
        } }
    }

    private fun reassign() {
        if (operationRunning) return
        val group = groups.selectionModel.selectedItem ?: return
        val destination = server.selectionModel.selectedItem?.value?.takeIf { it.directory } ?: return
        if (group.documents.none { it.key.category == destination.key.category }) { message("同じ種別の宛先を選択してください。"); return }
        mutate { current -> when (val moved = plans.reassign(current, group.id, destination.key.id)) {
            is StoreResult.Failure -> moved
            is StoreResult.Success -> plans.save(moved.value)
        } }
    }
    private fun remove() { if (operationRunning) return; val group = groups.selectionModel.selectedItem ?: return; mutate { plans.remove(it, group.id) } }
    private fun reorder(offset: Int) {
        if (operationRunning) return
        val group = groups.selectionModel.selectedItem ?: return
        mutate { plans.save(it.move(group.id, it.groups.indexOfFirst { row -> row.id == group.id } + offset)) }
    }

    private fun mutate(action: (UploadPlan) -> StoreResult<UploadPlan>) {
        val current = plan ?: run { setBusy(false); return }
        setBusy(true)
        target.submitIo { action(current) }.whenComplete { result, error -> Platform.runLater {
            setBusy(false)
            if (error != null) failed("予約を変更できません。原本を保持しています。")
            else when (result) {
                is StoreResult.Success -> displayPlan(result.value)
                is StoreResult.Failure -> failed(result.error.detail ?: result.error.code.name)
                null -> failed("予約の結果を確認できません。")
            }
        } }
    }

    private fun displayPlan(value: UploadPlan) { plan = value; groups.items.setAll(value.groups); state.text = "予約 " + value.groups.size + " 件（復元による自動実行なし）" }
    private fun setBusy(value: Boolean) {
        busy = value; apply.isDisable = value; refresh.isDisable = value
        if (!value && refreshPending) { refreshPending = false; Platform.runLater { reload() } }
    }
    fun refreshDocuments() { if (operationRunning) refreshPending = true else reload() }
    private fun message(text: String) { state.text = text }
    private fun failed(text: String) {
        message(text)
        if (!disposed) CustomDialog.error().header("送信予約を完了できませんでした").content(text).owner(owner()).show()
    }

    private fun reload() {
        if (busy || disposed) return
        refreshPending = false
        setBusy(true)
        target.submitIo {
            val left = entries(target.localService)
            Listing(left, runCatching { entries(target) }, plans.read(), target.documents.read())
        }.whenComplete { result, error -> Platform.runLater {
            setBusy(false)
            if (disposed) return@runLater
            if (error != null) { message("一覧を読み込めません。既存予約は保持しています。"); return@runLater }
            local.root = tree(openEntries(result.local, EditorDocumentSource.Local))
            result.server.getOrNull()?.let { server.root = tree(openEntries(it, target.documentSource)) }
            when (val loaded = result.requests) {
                is StoreResult.Success -> { requests = loaded.value.uploads.associateBy { it.id }; target.updateUploadState(loaded.value) }
                is StoreResult.Failure -> {
                    requests = emptyMap()
                    (result.plan as? StoreResult.Success)?.value?.groups?.filter { it.operationId != null }?.forEach { group ->
                        target.unresolvedUploadDocuments.addAll(group.documents.flatMap { listOf(it.key,
                            it.key.copy(source = target.documentSource, id = it.targetId)) })
                    }
                }
            }
            when (val loaded = result.plan) {
                is StoreResult.Success -> displayPlan(loaded.value)
                is StoreResult.Failure -> failed(loaded.error.detail ?: loaded.error.code.name)
            }
            if (result.server.isFailure) message("宛先一覧を取得できません。ローカル編集と保存済み予約は利用できます。")
        } }
    }

    private fun entries(service: EditorDataService): List<Entry> = buildList {
        fun <T : ManagedData<T, *>> addType(descriptor: EditorDataDescriptor<T>) {
            val category = descriptor.dataType.categoryDirName
            val files = service.store.list(descriptor); val dirs = service.store.listDirectories(descriptor)
            check(files is StoreResult.Success && dirs is StoreResult.Success) { "一覧を読み込めません: " + category }
            add(Entry(EditorDocumentKey(service.documentSource, category, ""), true, descriptor.displayName))
            dirs.value.forEach { add(Entry(EditorDocumentKey(service.documentSource, category, it), true, it.substringAfterLast('.'))) }
            files.value.forEach { add(Entry(EditorDocumentKey(service.documentSource, category, it.id), false, it.id.substringAfterLast('.'))) }
        }
        addType(service.items.descriptor); addType(service.ores.descriptor); addType(service.shops.descriptor); addType(service.recipes.descriptor)
    }

    private fun openEntries(entries: List<Entry>, source: EditorDocumentSource): List<Entry> {
        val current = entries.toMutableList()
        entries.filter { it.directory && it.key.id.isEmpty() }.forEach { category ->
            EditorWindowManager.openDocumentIds(source, category.key.category).forEach { id ->
                val key = category.key.copy(id = id)
                if (current.none { it.key == key && !it.directory }) current += Entry(key, false, id.substringAfterLast('.'))
            }
        }
        return current
    }

    private fun tree(entries: List<Entry>): TreeItem<Entry> {
        val hierarchy = entries.toMutableList()
        entries.filterNot { it.directory }.forEach { file ->
            val parts = file.key.id.split('.').dropLast(1)
            parts.indices.forEach { index ->
                val id = parts.take(index + 1).joinToString("."); val key = file.key.copy(id = id)
                if (hierarchy.none { it.key == key && it.directory }) hierarchy += Entry(key, true, parts[index])
            }
        }
        val root = TreeItem<Entry>()
        val nodes = mutableMapOf<Pair<String, String>, TreeItem<Entry>>()
        hierarchy.sortedWith(compareBy<Entry> { it.key.id.count { c -> c == '.' } }.thenBy { !it.directory }.thenBy { it.key.id }).forEach { entry ->
            val node = TreeItem(entry).apply { isExpanded = entry.key.id.isEmpty() }
            val parent = if (entry.key.id.isEmpty()) root else nodes[entry.key.category to entry.key.id.substringBeforeLast('.', "")]
            (parent ?: root).children.add(node)
            if (entry.directory) nodes[entry.key.category to entry.key.id] = node
        }
        return root
    }

    private val held = mutableSetOf<EditorDocumentKey>()
    private fun freeze(keys: Set<EditorDocumentKey>): Boolean {
        val added = keys - held
        if (added.any { it in target.uploadingDocuments || it in target.unresolvedUploadDocuments }) return false
        held += added; target.uploadingDocuments.addAll(added)
        return true
    }

    private fun executeAll() {
        val selected = plan?.groups?.filter { it.operationId == null }.orEmpty()
        if (operationRunning || selected.isEmpty()) return
        val keys = selected.flatMap { group -> group.documents.flatMap { listOf(it.key,
            it.key.copy(source = target.documentSource, id = it.targetId)) } }.toSet()
        if (!freeze(keys)) { message("別の操作または結果不明の対象があります。同じ要求を確認してください。"); return }
        setBusy(true)
        val planner = SavedUploadPlanner(target.localService, target)
        target.submitIo { selected.map { group ->
            when (val selections = plans.selections(group)) {
                is StoreResult.Failure -> error(selections.error.detail ?: selections.error.code.name)
                is StoreResult.Success -> when (val preview = planner.preview(selections.value)) {
                    is StoreResult.Failure -> error(preview.error.detail ?: preview.error.code.name)
                    is StoreResult.Success -> group to preview.value
                }
            }
        } }.whenComplete { previews, error -> Platform.runLater {
            if (error != null || disposed) { finish("確認を準備できません。予約を保持しています。"); return@runLater }
            val confirmed = mutableListOf<Triple<UploadPlanGroup, SavedUploadPreview, SavedUploadConfirmation>>()
            for ((group, preview) in previews) {
                val choice = SavedUploadDialog.show(owner(), target.currentProfileName.orEmpty() + " / " + group.targetDirectory, preview)
                if (choice == null || disposed) { finish("確認を取り消しました。通信作用はありません。"); return@runLater }
                confirmed += Triple(group, preview, choice)
            }
            val progress = SavedUploadProgress(owner()).also { it.show() }
            val observations = previews.flatMap { it.second.versions }.associate { it.key to it.revision }
            val requests = mutableSetOf<String>()
            target.submitIo {
                val completed = mutableListOf<SavedUploadOperation>()
                for ((group, preview, choice) in confirmed) {
                    if (progress.cancelled) break
                    val loaded = plans.read()
                    if (loaded !is StoreResult.Success || loaded.value.groups.singleOrNull { it.id == group.id } != group)
                        error("確認後に送信予約が変わりました。残りの予約を保持します。")
                    val built = planner.build(preview, choice.localPaths, choice.confirmUnknownBaseline)
                    if (built !is StoreResult.Success) error((built as StoreResult.Failure).error.detail ?: "計画を固定できません。")
                    requests += built.value.id
                    when (val result = UploadPlanExecution(target).execute(loaded.value, group.id, built.value,
                        cancelled = { progress.cancelled }, progress = { current -> Platform.runLater { progress.update(current) } })) {
                        is StoreResult.Failure -> error(result.error.detail ?: result.error.code.name)
                        is StoreResult.Success -> { completed += result.value; if (!result.value.complete) break }
                    }
                }
                completed
            }.whenComplete { completed, failure ->
                target.submitIo { target.documents.read() }.whenComplete { snapshot, readError -> Platform.runLater {
                    progress.finish()
                    if (snapshot is StoreResult.Success) {
                        target.updateUploadState(snapshot.value)
                        snapshot.value.uploads.filter { operation -> operation.id in requests }.forEach {
                            target.publishUploadResult(SavedUploadPublication(it, snapshot.value, observations))
                        }
                    } else target.unresolvedUploadDocuments.addAll(held)
                    finish(if (failure != null || readError != null)
                        "完了を確認できません。固定要求と予約を保持します。同じ要求を読戻してください。"
                        else "実行結果を記録しました。未完了の要求は同じ要求を確認してください。")
                    reload()
                } }
            }
        } }
    }

    private fun readback() {
        val group = groups.selectionModel.selectedItem ?: return
        if (operationRunning || group.operationId == null) return
        val action = SavedUploadAction(target.localService, target, owner(), { !disposed }, { it(true) }, {
            readbackAction = null; reload()
        })
        readbackAction = action
        action.readback(group.documents.map { SavedUploadSelection(it.key, it.targetId) }, group.operationId)
    }

    private fun finish(text: String) {
        target.uploadingDocuments.removeAll(held); held.clear(); setBusy(false); message(text)
    }
    fun dispose() { disposed = true }
}
