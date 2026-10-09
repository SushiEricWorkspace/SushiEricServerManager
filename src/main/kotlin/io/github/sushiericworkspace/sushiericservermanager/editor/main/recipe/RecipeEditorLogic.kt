package io.github.sushiericworkspace.sushiericservermanager.editor.main.recipe

import io.github.sushiericworkspace.common.data.core.validation.SushiEricValidationError
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.recipe.model.RecipeIngredients
import io.github.sushiericworkspace.common.data.recipe.model.RecipeSize
import io.github.sushiericworkspace.common.data.recipe.model.mutable.MutableRecipeData
import io.github.sushiericworkspace.common.stats.player.AchievementType
import io.github.sushiericworkspace.common.stats.player.SkillType
import io.github.sushiericworkspace.sushiericservermanager.editor.component.SearchableComboBox
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.service.RecipeEditorCatalog
import io.github.sushiericworkspace.sushiericservermanager.editor.service.validateRecipeForEditor
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.view.ManagedDataEditorView
import io.github.sushiericworkspace.sushiericservermanager.editor.view.createPublicIdDisplay
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.MergeConflictDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.RecipeSaveProgressDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.ItemTreeSelectionDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.ItemTreeSelection
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.AppTooltip
import javafx.concurrent.Task
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.TextFormatter
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.util.StringConverter

/** 材料盤と解放条件の入力を担当し、階層・履歴・自動保存は共通エディターへ委譲します。 */
internal class RecipeEditorLogic(main: MainController, dataService: EditorDataService) :
    ManagedDataEditorView<MutableRecipeData>(main, dataService, dataService.recipes) {
    private var catalog: RecipeEditorCatalog? = null
    private var catalogTask: Task<RecipeEditorCatalog>? = null
    private var catalogFailed = false
    private var disposed = false
    private var refreshContent: (() -> Unit)? = null
    private val focusTargets = mutableMapOf<String, Node>()
    private val ingredientFields = mutableMapOf<Int, Button>()
    private val itemButtons = mutableMapOf<Button, () -> ItemInternalId?>()

    override fun setupSidebar(container: VBox, selectId: String?) {
        super.setupSidebar(container, selectId)
        if (catalog == null && catalogTask == null) loadCatalog()
    }

    override fun setupMainContent(selectData: MutableRecipeData) {
        val warnings = VBox(5.0)
        val refreshButton = Button("参照アイテム・レシピを更新")
        fun refresh() {
            warnings.children.clear()
            if (catalog == null || catalogTask != null || catalogFailed) {
                warnings.children += Label(if (catalogFailed) "参照一覧を取得できませんでした。更新を押して再試行してください。"
                    else "参照アイテム・レシピを読み込み中...").apply { styleClass.add("warning-label"); isWrapText = true }
            }
            validationErrors(selectData).forEach { error ->
                warnings.children += Label(error.message).apply {
                    styleClass.add(if (error.isWarning) "warning-label" else "error-label")
                    isWrapText = true
                }
            }
            refreshButton.isDisable = catalogTask != null
            refreshItemButtons()
            sidebarButtons.keys.forEach(::refreshButtonVisual)
        }
        refreshContent = ::refresh
        refreshButton.setOnAction { loadCatalog() }
        focusTargets.clear()
        ingredientFields.clear()
        itemButtons.clear()
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
                setupMainContent(selectData)
            }
        }
        val shaped = CheckBox("材料の配置を指定する（形状あり）").apply {
            id = "recipe-shaped"
            isSelected = selectData.shaped
            setOnAction {
                changeRecipeShape(selectData, isSelected)
                setupMainContent(selectData)
            }
        }
        val result = itemButton({ selectData.resultItemInternalId }) { selectData.resultItemInternalId = it; refresh() }.apply { id = "recipe-result" }
        val count = integerField(selectData.resultCount) { selectData.resultCount = it ?: 0; refresh() }.apply { id = "recipe-count" }
        val header = GridPane().apply {
            hgap = 12.0; vgap = 12.0
            add(Label("レシピID:"), 0, 0)
            add(createPublicIdDisplay(selectData.id), 1, 0)
            add(Label("盤サイズ:"), 0, 1); add(HBox(12.0, size, shaped).apply { alignment = Pos.CENTER_LEFT }, 1, 1)
            add(Label("完成品:"), 0, 2); add(result, 1, 2)
            add(Label("完成数:"), 0, 3); add(count, 1, 3)
        }
        focusTargets.putAll(mapOf("size" to size, "resultItemInternalId" to result, "resultCount" to count))
        val slots = recipeSlots(selectData.ingredients).toMutableMap()
        fun editSlot(slot: Int, id: ItemInternalId?) {
            if (id == null) slots.remove(slot) else slots[slot] = id
            selectData.ingredients = if (selectData.shaped) RecipeIngredients.Shaped(slots)
                else RecipeIngredients.Shapeless(slots.toSortedMap().values.toList())
            refresh()
        }
        fun cell(slot: Int): VBox {
            val field = itemButton({ slots[slot] }) { editSlot(slot, it) }.apply {
                id = "recipe-ingredient-$slot"
                prefWidth = 160.0; minWidth = 80.0; maxWidth = Double.MAX_VALUE
            }
            ingredientFields[slot] = field
            return VBox(4.0, Label("${slot + 1}番目"), field).apply { maxWidth = Double.MAX_VALUE }
        }
        val board = GridPane().apply {
            hgap = 8.0; vgap = 8.0; maxWidth = Double.MAX_VALUE
            selectData.size.slots.forEach { slot ->
                add(cell(slot), slot % selectData.size.sideLength, slot / selectData.size.sideLength)
            }
            children.forEach { GridPane.setHgrow(it, Priority.ALWAYS) }
        }
        focusTargets["ingredients"] = board
        val extra = VBox(8.0)
        slots.keys.filter { it !in selectData.size.slots }.sorted().forEach { slot -> extra.children += cell(slot) }
        if (extra.children.isNotEmpty()) {
            extra.children.add(0, Label("盤の範囲外の材料（選択を解除すると削除）").apply { styleClass.add("error-label") })
        }
        val skillFields = GridPane().apply {
            hgap = 12.0; vgap = 8.0
            SkillType.entries.forEachIndexed { index, skill ->
                val field = integerField(selectData.skillLevelRequirements[skill]) { level ->
                    if (level == null) selectData.skillLevelRequirements.remove(skill) else selectData.skillLevelRequirements[skill] = level
                    refresh()
                }.apply { promptText = "空欄: 条件なし" }
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
        main.mainContentContainer.children.setAll(VBox(16.0,
            header, Label("材料（ボタンからアイテムを選択、未選択は空きマス）"),
            Label("形状なしでは配置を問わず、同じアイテムの選択回数が必要個数になります。サイズを縮小しても材料は削除されません。" ).apply { isWrapText = true },
            board, extra, Label("要求スキルレベル"), skillFields, Label("要求実績（すべて達成が必要）"),
            HBox(10.0, achievementSelector, addAchievement).apply { alignment = Pos.CENTER_LEFT }, achievements,
            refreshButton, warnings
        ).apply { padding = Insets(20.0) })
        refresh()
    }

    private fun itemButton(current: () -> ItemInternalId?, changed: (ItemInternalId?) -> Unit): Button = Button().apply {
        isWrapText = true
        itemButtons[this] = current
        setOnAction {
            val button = this
            loadCatalog {
                if (button.scene == null) return@loadCatalog
                when (val selection = ItemTreeSelectionDialog.show(main.currentStage, catalog?.items.orEmpty(), current())) {
                    is ItemTreeSelection.Selected -> changed(selection.id)
                    ItemTreeSelection.Cleared -> changed(null)
                    null -> Unit
                }
            }
        }
    }

    private fun refreshItemButtons() {
        itemButtons.forEach { (button, current) ->
            button.isDisable = catalogTask != null
            val id = current()
            button.text = if (id == null) "アイテムを選択..." else catalog?.items?.firstOrNull { it.internalId == id }?.id
                ?: if (catalog == null) "参照確認中: ${id.value}" else "参照先不明: ${id.value}"
            button.tooltip = AppTooltip.create(button.text)
        }
    }

    private fun integerField(initial: Int?, changed: (Int?) -> Unit): TextField = TextField(initial?.toString().orEmpty()).apply {
        textFormatter = TextFormatter<String> { change ->
            change.takeIf { it.controlNewText.isEmpty() || it.controlNewText.toIntOrNull() != null }
        }
        textProperty().addListener { _, _, value -> changed(value.toIntOrNull()) }
    }

    override fun validationErrors(data: MutableRecipeData): List<SushiEricValidationError> {
        val currentCatalog = catalog
        val stored = currentCatalog?.recipes.orEmpty().associateBy { it.id }
        val ids = mergeSidebarIdsWithPending(remoteDataIds)
        val candidates = ids.filterNot(::isPendingStoreDeletion).mapNotNull { editingDataMap[it] ?: stored[it] }
        return validateRecipeForEditor(data, currentCatalog?.items.orEmpty(), candidates)
            .filterNot { currentCatalog == null && it.isWarning }
    }

    override fun availableItemInternalIds(): Set<ItemInternalId> = catalog?.items?.mapTo(mutableSetOf()) { it.internalId }.orEmpty()

    override fun itemDisplayText(id: ItemInternalId): String = catalog?.items?.firstOrNull { it.internalId == id }?.id ?: id.value

    override fun focusValidationError(error: SushiEricValidationError): Boolean {
        val target = if (error.property.name == "ingredients") ingredientFields[error.key as? Int] ?: focusTargets["ingredients"]
            else focusTargets[error.property.name]
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

    override fun saveStoreData(dataId: String, data: MutableRecipeData): StoreResult<Unit> =
        RecipeSaveProgressDialog.save(main.currentStage, dataId) { dataAccess.saveStore(dataId, data) }

    private fun loadCatalog(onLoaded: (() -> Unit)? = null) {
        if (catalogTask != null || disposed) return
        catalogFailed = false
        val task = object : Task<RecipeEditorCatalog>() {
            override fun call(): RecipeEditorCatalog = when (val result = RecipeEditorCatalog.load(dataService.store)) {
                is StoreResult.Success -> result.value
                is StoreResult.Failure -> error("参照一覧を読み込めません: ${result.error.code} ${result.error.dataId}")
            }
        }
        catalogTask = task
        refreshContent?.invoke()
        task.setOnSucceeded {
            catalogTask = null
            if (!disposed) { catalog = task.value; refreshContent?.invoke(); onLoaded?.invoke() }
        }
        task.setOnFailed {
            catalogTask = null
            if (!disposed) {
                catalogFailed = true
                logger.warn("レシピ検証用の参照一覧を取得できませんでした。", task.exception)
                refreshContent?.invoke()
                if (onLoaded != null) {
                    CustomDialog.error().title("アイテム一覧の読込エラー")
                        .header("アイテムを選択できません")
                        .content("一覧を読み込めませんでした。接続状態とデータを確認してください。")
                        .owner(main.currentStage).show()
                }
            }
        }
        Thread(task, "recipe-editor-catalog").apply { isDaemon = true; start() }
    }

    override fun onClose(): Boolean {
        if (!super.onClose()) return false
        disposed = true
        catalogTask?.cancel()
        return true
    }
}
