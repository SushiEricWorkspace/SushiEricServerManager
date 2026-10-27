package io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe

import io.github.sushiericworkspace.sushiericdatamodel.data.core.validation.SushiEricValidationError
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.RecipeSize
import io.github.sushiericworkspace.sushiericdatamodel.data.recipe.model.mutable.MutableRecipeData
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.AchievementType
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.SkillType
import io.github.sushiericworkspace.sushiericservermanager.editor.component.ItemReferenceBinding
import io.github.sushiericworkspace.sushiericservermanager.editor.component.SearchableComboBox
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.ItemReferenceStatus
import io.github.sushiericworkspace.sushiericservermanager.editor.service.RecipeEditorCatalog
import io.github.sushiericworkspace.sushiericservermanager.editor.service.validateRecipeForEditor
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.view.ManagedDataEditorView
import io.github.sushiericworkspace.sushiericservermanager.editor.view.createPublicIdDisplay
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.MergeConflictDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import javafx.application.Platform
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.RecipeSaveProgressDialog
import javafx.concurrent.Task
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.util.StringConverter

/** 材料盤と解放条件の入力を担当し、階層・履歴・自動保存は共通エディターへ委譲します。 */
internal class RecipeEditorLogic(main: MainController, service: EditorDataService) :
    ManagedDataEditorView<MutableRecipeData>(main, service, service.recipes) {
    private var catalog: RecipeEditorCatalog? = null
    private var catalogTask: Task<RecipeEditorCatalog>? = null
    private var catalogFailed = false
    private var disposed = false
    private var refreshContent: (() -> Unit)? = null
    private val focusTargets = mutableMapOf<String, Node>()
    private var board: RecipeBoardView? = null

    private val availableItems get() = dataService.itemReferences.snapshot.items
    private val itemsLoaded get() = dataService.itemReferences.snapshot.status == ItemReferenceStatus.READY
    private var itemBinding = createItemBinding()

    private fun createItemBinding() = ItemReferenceBinding(dataService.itemReferences, {
        refreshItemButtons()
        registeredSidebarIds.forEach(::refreshSidebarState)
        refreshContent?.invoke()
    }, { error ->
        logger.warn("レシピ用のアイテム一覧を取得できませんでした。", error.cause)
        CustomDialog.error().title("アイテム一覧の読込エラー")
            .header("アイテムを選択できません")
            .content("一覧を読み込めませんでした。接続状態とデータを確認してください。")
            .owner(main.currentStage).show()
    })

    override fun onSourceChanged() {
        itemBinding.close()
        itemBinding = createItemBinding()
        catalogTask?.cancel()
        catalogTask = null
        catalog = null
        catalogFailed = false
        refreshContent = null
        board?.close()
        board = null
    }

    override fun loadEditorReferences() {
        if (catalog == null && catalogTask == null) loadCatalog()
    }

    override fun setupMainContent(selectData: MutableRecipeData) {
        val warnings = VBox(5.0)
        val refreshButton = Button("参照アイテム・レシピを更新")
        fun refresh() {
            warnings.children.clear()
            if (!itemsLoaded || catalog == null || catalogTask != null || catalogFailed) {
                warnings.children += Label(if (catalogFailed || dataService.itemReferences.snapshot.status == ItemReferenceStatus.FAILED) "参照一覧を取得できませんでした。更新を押して再試行してください。"
                    else "参照アイテム・レシピを読み込み中... アイテム ${dataService.itemReferences.snapshot.loadedCount} / ${dataService.itemReferences.snapshot.totalCount} 件").apply { styleClass.add("warning-label"); isWrapText = true }
            }
            validationErrors(selectData).forEach { error ->
                warnings.children += Label(error.message).apply {
                    styleClass.add(if (error.isWarning) "warning-label" else "error-label")
                    isWrapText = true
                }
            }
            refreshButton.isDisable = catalogTask != null || dataService.itemReferences.snapshot.status == ItemReferenceStatus.LOADING
            refreshItemButtons()
            registeredSidebarIds.forEach(::refreshSidebarState)
        }
        refreshContent = ::refresh
        refreshButton.setOnAction { loadCatalog(force = true) }
        focusTargets.clear()
        board?.close()
        board = null
        val size = ComboBox<RecipeSize>().apply {
            id = "recipe-size"
            items.setAll(RecipeSize.entries)
            converter = object : StringConverter<RecipeSize>() {
                override fun toString(value: RecipeSize?): String = value?.let { "${it.sideLength} × ${it.sideLength}" }.orEmpty()
                override fun fromString(value: String?): RecipeSize? = null
            }
            value = selectData.size
            setOnAction {
                value?.let { changeRecipeSize(selectData, it) }
                recordHistorySnapshot(selectData.id)
                setupMainContent(selectData)
            }
        }
        val shaped = CheckBox("材料の配置を指定する（形状あり）").apply {
            id = "recipe-shaped"
            isSelected = selectData.shaped
            setOnAction {
                changeRecipeShape(selectData, isSelected)
                recordHistorySnapshot(selectData.id)
                setupMainContent(selectData)
            }
        }
        val count = integerField(selectData.resultCount) { selectData.resultCount = it ?: 0; refresh() }.apply { id = "recipe-count" }
        retainInputDraft(selectData.id, "resultCount", count) { it.isEmpty() || it.toIntOrNull() != null }
        val header = GridPane().apply {
            hgap = 12.0; vgap = 12.0
            add(Label("レシピID:"), 0, 0)
            add(createPublicIdDisplay(selectData.id), 1, 0)
            add(Label("盤サイズ:"), 0, 1); add(HBox(12.0, size, shaped).apply { alignment = Pos.CENTER_LEFT }, 1, 1)
            add(Label("完成数:"), 0, 2); add(count, 1, 2)
        }
        val recipeBoard = RecipeBoardView(selectData, referenceItems(), dataService.itemPreviews, "editing", {
            recordHistorySnapshot(selectData.id)
            refresh()
        }, { count.requestFocus(); count.selectAll() })
        board = recipeBoard
        focusTargets.putAll(mapOf("size" to size, "resultCount" to count, "ingredients" to recipeBoard))
        val skillFields = GridPane().apply {
            hgap = 12.0; vgap = 8.0
            SkillType.entries.forEachIndexed { index, skill ->
                val field = integerField(selectData.skillLevelRequirements[skill]) { level ->
                    if (level == null) selectData.skillLevelRequirements.remove(skill) else selectData.skillLevelRequirements[skill] = level
                    refresh()
                }.apply { promptText = "空欄: 条件なし" }
                retainInputDraft(selectData.id, "skillLevelRequirements." + skill.name, field) { it.isEmpty() || it.toIntOrNull() != null }
                add(Label(skill.display), 0, index); add(field, 1, index)
            }
        }
        focusTargets["skillLevelRequirements"] = skillFields
        val achievements = VBox(8.0)
        fun refreshAchievements() {
            achievements.children.setAll(selectData.achievementRequirements.sortedBy { it.id }.map { achievement ->
                HBox(10.0, Label(achievement.display), Button("削除").apply {
                    setOnAction {
                        selectData.achievementRequirements.remove(achievement)
                        setupMainContent(selectData)
                    }
                }).apply { alignment = Pos.CENTER_LEFT }
            })
        }
        val achievementSelector = SearchableComboBox(AchievementType.entries.toList(), null, "実績を検索", { it.display }, onSelected = {})
        val addAchievement = Button("追加").apply {
            setOnAction {
                achievementSelector.comboBox.value?.let {
                    selectData.achievementRequirements.add(it)
                    refreshAchievements(); refresh()
                }
            }
        }
        refreshAchievements()
        focusTargets["achievementRequirements"] = achievementSelector
        val form = VBox(16.0,
            header, Label("材料（1枠1個）"),
            Label("パレットから配置し、右クリックでコピー・削除できます。サイズを縮小しても盤外の材料を保持します。").apply { isWrapText = true },
            recipeBoard, Label("要求スキルレベル"), skillFields, Label("要求実績（すべて達成が必要）"),
            HBox(10.0, achievementSelector, addAchievement).apply { alignment = Pos.CENTER_LEFT }, achievements,
            refreshButton, warnings
        ).apply { padding = Insets(20.0) }
        val scroll = ScrollPane(form).apply { isFitToWidth = true; minWidth = 0.0; minHeight = 0.0 }
        main.mainContentContainer.children.setAll(HBox(16.0, scroll, recipeBoard.palette).apply {
            minWidth = 0.0; minHeight = 0.0; maxWidth = Double.MAX_VALUE
            HBox.setHgrow(scroll, Priority.ALWAYS)
            HBox.setHgrow(this, Priority.ALWAYS)
        })
        refresh()
    }

    private fun referenceItems() = dataService.itemReferences.snapshot
        .takeIf { it.status == ItemReferenceStatus.READY }?.byInternalId

    private fun refreshItemButtons() { board?.refresh(referenceItems()) }

    private fun integerField(initial: Int?, changed: (Int?) -> Unit): TextField = TextField(initial?.toString().orEmpty()).apply {
        textProperty().addListener { _, _, value -> if (value.isEmpty() || value.toIntOrNull() != null) changed(value.toIntOrNull()) }
    }

    override fun validationErrors(data: MutableRecipeData): List<SushiEricValidationError> {
        val currentCatalog = catalog
        val stored = currentCatalog?.recipes.orEmpty().associateBy { it.id }
        val ids = mergeSidebarIdsWithPending(remoteDataIds)
        val candidates = ids.filterNot(::isPendingStoreDeletion).mapNotNull { editingDataMap[it] ?: stored[it] }
        return validateRecipeForEditor(data, availableItems, candidates)
            .filterNot { !itemsLoaded && it.isWarning }
    }

    override fun availableItemInternalIds(): Set<ItemInternalId> = dataService.itemReferences.snapshot.byInternalId.keys

    override fun itemDisplayText(id: ItemInternalId): String = dataService.itemReferences.snapshot.byInternalId[id]?.id ?: id.value

    override fun focusValidationError(error: SushiEricValidationError): Boolean {
        if (error.property.name == "resultItemInternalId") return board?.let { it.focusResult(); true } ?: false
        if (error.property.name == "ingredients" && (error.key as? Int)?.let { board?.focusIngredient(it) } == true) return true
        val target = focusTargets[error.property.name]
        return target?.let { it.requestFocus(); true } ?: false
    }

    override fun resolveSaveConflict(dataId: String, originalData: MutableRecipeData, currentData: MutableRecipeData, serverData: MutableRecipeData): MutableRecipeData? {
        val result = dataAccess.merge(originalData, currentData, serverData)
        if (result.conflicts.isEmpty()) return result.merged
        val choices = MergeConflictDialog.show(main.currentStage, dataId, result.conflicts) { value ->
            io.github.sushiericworkspace.sushiericservermanager.editor.merge.ConflictValueFormatter.format(value, ::itemDisplayText)
        } ?: return null
        return result.resolveWithLocal(choices)
    }


    internal override val sourceSwitchBusy: Boolean get() = super.sourceSwitchBusy || catalogTask != null

    private fun loadCatalog(force: Boolean = false) {
        if (catalogTask != null || disposed || (!force && catalogFailed)) return
        catalogFailed = false
        val service = dataService
        val items = service.itemReferences.load(force)
        val task = object : Task<RecipeEditorCatalog>() {
            override fun call(): RecipeEditorCatalog {
                val cachedItems = when (val result = items.get()) {
                    is StoreResult.Success -> result.value
                    is StoreResult.Failure -> error("参照アイテムを読み込めません: ${result.error.code}")
                }
                return when (val result = service.submitIo { RecipeEditorCatalog.load(service.store, cachedItems) }.get()) {
                    is StoreResult.Success -> result.value
                    is StoreResult.Failure -> error("参照一覧を読み込めません: ${result.error.code} ${result.error.dataId}")
                }
            }
        }
        catalogTask = task
        refreshContent?.invoke()
        task.setOnSucceeded {
            if (catalogTask !== task || dataService !== service) return@setOnSucceeded
            catalogTask = null
            if (!disposed) { catalog = task.value; refreshContent?.invoke() }
        }
        task.setOnFailed {
            if (catalogTask !== task || dataService !== service) return@setOnFailed
            catalogTask = null
            if (!disposed) {
                catalogFailed = true
                logger.warn("レシピ検証用の参照一覧を取得できませんでした。", task.exception)
                refreshContent?.invoke()
            }
        }
        Thread(task, "recipe-editor-catalog").apply { isDaemon = true; start() }
    }

    override fun afterClose() {
        disposed = true
        board?.close()
        board = null
        itemBinding.close()
        catalogTask?.cancel()
    }
}
