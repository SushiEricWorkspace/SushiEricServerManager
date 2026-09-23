package io.github.sushiericworkspace.sushiericservermanager.editor.main.ore

import io.github.sushiericworkspace.common.data.core.identity.VanillaBlockId
import io.github.sushiericworkspace.common.data.core.validation.SushiEricValidationError
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.data.ore.model.mutable.MutableOreBaseData
import io.github.sushiericworkspace.common.registry.VanillaIdRegistry
import io.github.sushiericworkspace.sushiericservermanager.editor.component.DropItemEditorDialog
import io.github.sushiericworkspace.sushiericservermanager.editor.component.EditorSpinnerFactory
import io.github.sushiericworkspace.sushiericservermanager.editor.component.SearchableComboBox
import io.github.sushiericworkspace.sushiericservermanager.editor.controller.MainController
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.view.ManagedDataEditorView
import io.github.sushiericworkspace.sushiericservermanager.editor.view.createPublicIdDisplay
import io.github.sushiericworkspace.sushiericservermanager.editor.validation.ValidationRepairRegistry
import io.github.sushiericworkspace.sushiericservermanager.editor.validation.ValidationRepairResult
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.MergeConflictDialog
import javafx.concurrent.Task
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.control.TextFormatter
import javafx.scene.input.KeyCode
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox

internal fun parseHardnessInput(text: String): Double? =
    text.toDoubleOrNull()?.takeIf(Double::isFinite)

internal fun formatDropItemCount(count: Int): String = "ドロップアイテムを編集（${count}件）"

internal fun createOreValidationRepairRegistry(): ValidationRepairRegistry<MutableOreBaseData> =
    ValidationRepairRegistry<MutableOreBaseData>().apply {
        register(
            matches = { it.property.name == "dropItems" && it.key is Int },
            description = { error ->
                val index = error.key as Int
                "ドロップアイテム ${index + 1} 番目を削除"
            },
            repair = { data, error ->
                val index = error.key as? Int
                if (index == null || index !in data.mutableDropItems.indices) {
                    ValidationRepairResult.Failed("削除対象のドロップ行が見つかりません。")
                } else {
                    data.mutableDropItems.removeAt(index)
                    ValidationRepairResult.Applied("ドロップアイテム ${index + 1} 番目を削除")
                }
            }
        )
    }

/** 共通エディタ基盤上で鉱石固有の入力項目を提供します。 */
internal class OreEditorLogic(
    main: MainController,
    dataService: EditorDataService
) : ManagedDataEditorView<MutableOreBaseData>(
    main = main,
    dataService = dataService,
    dataAccess = dataService.ores
) {
    override val validationRepairRegistry = createOreValidationRepairRegistry()

    private var availableItems: List<MutableItemBaseData> = emptyList()
    private var itemCatalogLoading = false
    private var itemCatalogLoaded = false
    private var refreshCurrentValidation: (() -> Unit)? = null
    private var dropItemEditorButton: Button? = null
    private val validationFocusTargets = mutableMapOf<String, Node>()

    override fun setupSidebar(container: VBox, selectId: String?) {
        if (!itemCatalogLoaded && !itemCatalogLoading) itemCatalogLoading = true
        super.setupSidebar(container, selectId)
        if (itemCatalogLoading && !itemCatalogLoaded) loadAvailableItems()
    }

    override fun setupMainContent(selectData: MutableOreBaseData) {
        val validationBox = VBox(4.0).apply {
            styleClass.add("ore-validation-box")
        }
        val dropItemButton = Button(formatDropItemCount(selectData.mutableDropItems.size)).apply {
            isFocusTraversable = false
            styleClass.add("btn-secondary")
        }
        dropItemEditorButton = dropItemButton

        fun refreshValidation() {
            dropItemButton.text = formatDropItemCount(selectData.mutableDropItems.size)
            dropItemButton.isDisable = itemCatalogLoading
            validationBox.children.setAll(
                dataAccess.validationErrors(selectData, availableItemInternalIds()).map { result ->
                    Label(result.message).apply {
                        styleClass.add(if (result.isWarning) "warning-label" else "error-label")
                        isWrapText = true
                    }
                }
            )
            refreshButtonVisual(selectData.id)
        }
        refreshCurrentValidation = ::refreshValidation

        val blockIdSelector = createBlockIdSelector(selectData) {
            refreshValidation()
        }
        val hardnessField = createHardnessField(selectData) {
            refreshValidation()
        }
        val requiredTierSpinner = EditorSpinnerFactory.intSpinner(
            initialValue = selectData.requiredTier,
            min = 0,
            max = Int.MAX_VALUE,
            step = 1,
            prefWidth = 180.0
        ) { value ->
            selectData.requiredTier = value
            refreshValidation()
        }
        validationFocusTargets.clear()
        validationFocusTargets["blockId"] = blockIdSelector.children.first()
        validationFocusTargets["hardness"] = hardnessField
        validationFocusTargets["requiredTier"] = requiredTierSpinner
        validationFocusTargets["dropItems"] = dropItemButton
        dropItemButton.onAction = EventHandler {
            val owner = main.currentStage ?: return@EventHandler
            itemCatalogLoading = true
            refreshValidation()
            loadAvailableItems {
                DropItemEditorDialog.show(
                    owner = owner,
                    dropItems = selectData.mutableDropItems,
                    items = availableItems
                ) {
                    refreshValidation()
                }
            }
        }

        val inputGrid = GridPane().apply {
            styleClass.add("ore-editor-grid")
            hgap = 12.0
            vgap = 12.0
            maxWidth = Double.MAX_VALUE
            add(Label("公開ID:"), 0, 0)
            add(createPublicIdDisplay(selectData.id).apply { styleClass.add("editor-identity-value") }, 1, 0)
            add(Label("ブロックID:"), 0, 1)
            add(blockIdSelector, 1, 1)
            add(Label("硬度:"), 0, 2)
            add(hardnessField, 1, 2)
            add(Label("要求Tier:"), 0, 3)
            add(requiredTierSpinner, 1, 3)
            add(Label("ドロップアイテム:"), 0, 4)
            add(dropItemButton, 1, 4)
        }

        val content = VBox(16.0, inputGrid, validationBox).apply {
            styleClass.add("ore-editor-content")
            padding = Insets(20.0)
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
        }
        HBox.setHgrow(content, Priority.ALWAYS)
        main.mainContentContainer.children.setAll(content)
        refreshValidation()
    }

    override fun focusValidationError(error: SushiEricValidationError): Boolean {
        val target = validationFocusTargets[error.property.name] ?: return false
        target.requestFocus()
        return true
    }

    override fun availableItemInternalIds(): Set<ItemInternalId> =
        availableItems.mapTo(mutableSetOf()) { it.internalId }

    override fun prepareNewData(data: MutableOreBaseData): MutableOreBaseData = data.apply {
        blockId = VanillaIdRegistry.defaultBlock
    }

    override fun resolveSaveConflict(
        dataId: String,
        originalData: MutableOreBaseData,
        currentData: MutableOreBaseData,
        serverData: MutableOreBaseData
    ): MutableOreBaseData? {
        val merge = dataAccess.merge(originalData, currentData, serverData)
        if (merge.conflicts.isEmpty()) return merge.merged
        val localPaths = MergeConflictDialog.show(
            owner = main.currentStage,
            dataId = dataId,
            conflicts = merge.conflicts
        ) ?: return null
        return merge.resolveWithLocal(localPaths)
    }

    private fun createBlockIdSelector(
        ore: MutableOreBaseData,
        onChanged: () -> Unit
    ): VBox {
        val registryIds = VanillaIdRegistry.allBlocks.map(VanillaBlockId::value)
        val allIds = (listOf(ore.blockId.value) + registryIds).filter(String::isNotBlank).distinct()
        return SearchableComboBox(
            allChoices = allIds,
            initialValue = ore.blockId.value,
            promptText = "ブロックIDを検索",
            displayText = { it },
            onSelected = { selected ->
                ore.blockId = VanillaBlockId(selected)
                onChanged()
            }
        ).apply {
            comboBox.prefWidth = 360.0
            maxWidth = Double.MAX_VALUE
        }
    }

    private fun createHardnessField(
        ore: MutableOreBaseData,
        onChanged: () -> Unit
    ): TextField {
        return TextField(ore.hardness.toString()).apply {
            prefWidth = 180.0
            maxWidth = 180.0
            textFormatter = TextFormatter<String> { change ->
                if (change.controlNewText.matches(Regex("-?\\d*(\\.\\d*)?"))) change else null
            }

            fun commitValue() {
                val parsed = parseHardnessInput(text)
                if (parsed == null) {
                    text = ore.hardness.toString()
                    return
                }
                ore.hardness = parsed
                text = parsed.toString()
                onChanged()
            }

            focusedProperty().addListener { _, _, focused ->
                if (!focused) commitValue()
            }
            setOnKeyPressed { event ->
                if (event.code == KeyCode.ENTER) {
                    commitValue()
                    event.consume()
                }
            }
        }
    }

    private fun loadAvailableItems(onLoaded: (() -> Unit)? = null) {
        val task = object : Task<List<MutableItemBaseData>>() {
            override fun call(): List<MutableItemBaseData> {
                return when (val listed = dataService.items.listStoreResources()) {
                    is StoreResult.Failure -> throw IllegalStateException(
                        listed.error.detail ?: listed.error.code.name,
                        listed.error.cause
                    )
                    is StoreResult.Success -> listed.value.mapNotNull { resource ->
                        when (val loaded = dataService.items.loadStore(resource.id)) {
                            is StoreResult.Success -> loaded.value
                            is StoreResult.Failure -> null
                        }
                    }
                }
            }
        }
        task.setOnSucceeded {
            availableItems = task.value
            itemCatalogLoading = false
            itemCatalogLoaded = true
            sidebarButtons.keys.forEach(::refreshButtonVisual)
            refreshCurrentValidation?.invoke()
            onLoaded?.invoke()
        }
        task.setOnFailed {
            itemCatalogLoading = false
            itemCatalogLoaded = false
            refreshCurrentValidation?.invoke()
            logger.error("ドロップ参照用のアイテム一覧を読み込めませんでした", task.exception)
            CustomDialog.error()
                .title("アイテム一覧の読込エラー")
                .header("ドロップアイテムを編集できません")
                .content("アイテム一覧を読み込めませんでした。接続状態とデータを確認してください。")
                .owner(main.currentStage)
                .show()
        }
        Thread(task, "ore-editor-item-catalog").apply {
            isDaemon = true
            start()
        }
    }
}
