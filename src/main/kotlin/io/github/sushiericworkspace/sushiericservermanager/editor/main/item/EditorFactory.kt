package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.common.data.core.identity.VanillaItemId
import io.github.sushiericworkspace.common.value.SushiEricHexColor
import io.github.sushiericworkspace.common.data.item.model.SushiEricRarity
import io.github.sushiericworkspace.common.data.item.model.HeadSkinSource
import io.github.sushiericworkspace.common.stats.player.StatsType
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableArmorTrimData
import io.github.sushiericworkspace.common.data.item.model.ArmorTrimRegistry
import io.github.sushiericworkspace.common.data.item.LoreLineEditor
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableCustomComponentLoreSection
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.data.item.model.ItemType
import io.github.sushiericworkspace.common.data.item.model.ItemType.*
import io.github.sushiericworkspace.common.data.item.model.LoreSectionType
import io.github.sushiericworkspace.common.data.item.model.mutable.MutablePlainTextLoreSection
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableStatLoreSection
import io.github.sushiericworkspace.common.data.item.model.mutable.detail.MutableArmorContent
import io.github.sushiericworkspace.common.data.item.model.mutable.detail.MutableBowContent
import io.github.sushiericworkspace.common.data.item.model.mutable.detail.MutableCrossbowData
import io.github.sushiericworkspace.common.data.item.model.mutable.detail.MutableLongSwordData
import io.github.sushiericworkspace.common.data.item.model.mutable.detail.MutablePickaxeData
import io.github.sushiericworkspace.common.data.item.model.mutable.detail.MutablePotionData
import io.github.sushiericworkspace.common.data.item.model.mutable.detail.MutableShieldData
import io.github.sushiericworkspace.common.data.item.model.mutable.detail.MutableShortBowData
import io.github.sushiericworkspace.common.registry.ItemIdGroups
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.component.ColorPickerDialog
import io.github.sushiericworkspace.sushiericservermanager.editor.component.EditorSpinnerFactory
import io.github.sushiericworkspace.sushiericservermanager.editor.component.PotionEffectEditorDialog
import io.github.sushiericworkspace.sushiericservermanager.editor.component.SearchableComboBox
import io.github.sushiericworkspace.sushiericservermanager.editor.tree.EditorGraphicFactory
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.tree.TreeRow
import io.github.sushiericworkspace.sushiericservermanager.ui.AppTooltip
import io.github.sushiericworkspace.sushiericservermanager.util.NumericSpinnerFactory
import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import io.github.sushiericworkspace.common.data.item.model.ItemStatMultiplier
import io.github.sushiericworkspace.common.stats.player.StatsPart
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.CheckBox
import javafx.scene.control.ColorPicker
import javafx.scene.control.ComboBox
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.control.TextFormatter
import javafx.application.Platform
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import javafx.util.Callback
import javafx.util.StringConverter
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import org.controlsfx.control.ToggleSwitch

internal fun isMaxStackSizeEditable(itemType: ItemType): Boolean = itemType == OTHER

/**
 * ステータス追加ダイアログで確定できる組み合わせかを判定します。
 *
 * 種類が未選択、値が未確定、または0（未設定）の場合は確定できません。
 * OKボタンとEnterの両方がこの判定を使います。
 */
internal fun resolveStatToAdd(type: StatsType?, value: Double?): Pair<StatsType, Double>? {
    if (type == null || value == null || value.isNaN() || value == 0.0) {
        return null
    }

    return type to value
}

private data class HeadSkinSourceOption(val source: HeadSkinSource?) {
    override fun toString(): String = when (source) {
        null -> "未指定"
        HeadSkinSource.PLAYER_NAME -> "プレイヤー名"
        HeadSkinSource.PLAYER_UUID -> "プレイヤーUUID"
        HeadSkinSource.TEXTURE -> "テクスチャ"
    }
}

/**
 * Itemエディタ専用のEditor行Graphic生成クラス。
 *
 * 共通TreeCellから呼び出され、MutableItemBaseDataに応じた入力UIを生成する。
 *
 * @property itemData 編集対象のItem定義。
 * @property refreshButtonVisual 変更を保存ボタンの表示へ反映する処理。
 * @property headSkinTextureLookup ヘッドスキンのテクスチャ値取得。
 * 既定はMojang APIを使用する実装。
 */
class ItemEditorFactory(
    private val itemData: MutableItemBaseData,
    private val refreshButtonVisual: (String) -> Unit,
    private val headSkinTextureLookup: HeadSkinTextureLookup =
        MojangHeadSkinTextureLookup()
) : EditorGraphicFactory<TreeRow> {

    private val decorationDisplay = linkedMapOf(
        TextDecoration.OBFUSCATED to "難読化",
        TextDecoration.BOLD to  "太字",
        TextDecoration.STRIKETHROUGH to "取り消し線",
        TextDecoration.UNDERLINED to "下線",
        TextDecoration.ITALIC to "斜体"
    )

    private val textColorDisplay = linkedMapOf<TextColor, String>(
        NamedTextColor.WHITE to "white",
        NamedTextColor.BLACK to "black",
        NamedTextColor.DARK_BLUE to "dark_blue",
        NamedTextColor.DARK_GREEN to "dark_green",
        NamedTextColor.DARK_AQUA to "dark_aqua",
        NamedTextColor.DARK_RED to "dark_red",
        NamedTextColor.DARK_PURPLE to "dark_purple",
        NamedTextColor.GOLD to "gold",
        NamedTextColor.GRAY to "gray",
        NamedTextColor.DARK_GRAY to "dark_gray",
        NamedTextColor.BLUE to "blue",
        NamedTextColor.GREEN to "green",
        NamedTextColor.AQUA to "aqua",
        NamedTextColor.RED to "red",
        NamedTextColor.LIGHT_PURPLE to "light_purple",
        NamedTextColor.YELLOW to "yellow"
    )

    private fun editorRow(label: String, control: Node): HBox {
        return HBox(
            Label(label).apply {
                minWidth = Region.USE_PREF_SIZE
                styleClass.add("editor-label")
            },
            control
        ).apply {
            alignment = Pos.CENTER_LEFT
            styleClass.add("editor-row-hbox")
        }
    }

    private fun intSpinnerRow(
        label: String,
        initialValue: Int,
        min: Int,
        max: Int,
        step: Int,
        onChanged: (Int) -> Unit
    ): HBox {
        return editorRow(
            label,
            EditorSpinnerFactory.intSpinner(
                initialValue = initialValue,
                min = min,
                max = max,
                step = step,
                onChanged = onChanged
            )
        )
    }

    private fun doubleSpinnerRow(
        label: String,
        initialValue: Double,
        min: Double,
        max: Double,
        step: Double,
        decimalPlaces: Int,
        onChanged: (Double) -> Unit
    ): HBox {
        return editorRow(
            label,
            EditorSpinnerFactory.doubleSpinner(
                initialValue = initialValue,
                min = min,
                max = max,
                step = step,
                decimalPlaces = decimalPlaces,
                onChanged = onChanged
            )
        )
    }

    private fun longSpinnerRow(
        label: String,
        initialValue: Long,
        min: Long,
        max: Long,
        step: Long,
        onChanged: (Long) -> Unit
    ): HBox {
        return editorRow(
            label,
            EditorSpinnerFactory.longSpinner(
                initialValue = initialValue,
                min = min,
                max = max,
                step = step,
                onChanged = onChanged
            )
        )
    }

    private fun toggleRow(
        label: String,
        selected: Boolean,
        onChanged: (Boolean) -> Unit
    ): HBox {
        return editorRow(
            label,
            ToggleSwitch().apply {
                isSelected = selected
                selectedProperty().addListener { _, _, value ->
                    if (value != null) onChanged(value)
                }
            }
        )
    }

    /**
     * 装飾が有効かどうかを返します。
     *
     * Mutableセクションは装飾ごとの判定APIだけを公開しているため、
     * 画面側で使用する[TextDecoration]から対応するAPIへ振り分けます。
     */
    private fun decorationEnabled(
        section: MutableCustomComponentLoreSection,
        decoration: TextDecoration
    ): Boolean {
        return when (decoration) {
            TextDecoration.BOLD -> section.isBold()
            TextDecoration.ITALIC -> section.isItalic()
            TextDecoration.UNDERLINED -> section.isUnderlined()
            TextDecoration.STRIKETHROUGH -> section.isStrikethrough()
            TextDecoration.OBFUSCATED -> section.isObfuscated()
        }
    }

    private fun createLongSwordRows(content: MutableLongSwordData): List<Node> {
        return listOf(
            doubleSpinnerRow("クールダウン:", content.cooldown, 0.0, 999.0, 0.1, 1) { value ->
                content.cooldown = value
                refreshButtonVisual(itemData.id)
            },
            doubleSpinnerRow("レンジ:", content.range, 0.0, 999.0, 0.1, 1) { value ->
                content.range = value
                refreshButtonVisual(itemData.id)
            }
        )
    }

    private fun createPickaxeRows(content: MutablePickaxeData): List<Node> {
        return listOf(
            intSpinnerRow("Tier:", content.tier, 0, Int.MAX_VALUE, 1) { value ->
                content.tier = value
                refreshButtonVisual(itemData.id)
            }
        )
    }

    private fun createBowRows(content: MutableBowContent): List<Node> {
        val angleRow = doubleSpinnerRow(
            "マルチショット角度:",
            content.angle,
            0.0,
            360.0,
            0.1,
            1
        ) { value ->
            content.angle = value
            refreshButtonVisual(itemData.id)
        }.apply {
            isVisible = content.multi > 0
            isManaged = content.multi > 0
        }

        return buildList {
            add(
                intSpinnerRow("マルチショット:", content.multi, 0, 999, 1) { value ->
                    content.multi = value
                    angleRow.isVisible = value > 0
                    angleRow.isManaged = value > 0
                    refreshButtonVisual(itemData.id)
                }
            )
            add(angleRow)
            add(
                intSpinnerRow("貫通:", content.pierce, 0, 999, 1) { value ->
                    content.pierce = value
                    refreshButtonVisual(itemData.id)
                }
            )
            if (content is MutableShortBowData) {
                add(
                    doubleSpinnerRow("連射速度:", content.shortInterval, 0.0, 999.0, 0.1, 1) { value ->
                        content.shortInterval = value
                        refreshButtonVisual(itemData.id)
                    }
                )
            }
        }
    }

    private fun createCrossbowRows(content: MutableCrossbowData): List<Node> {
        return listOf(
            doubleSpinnerRow("チャージ時間:", content.chargeSecond, 0.0, 999.0, 0.1, 1) { value ->
                content.chargeSecond = value
                refreshButtonVisual(itemData.id)
            },
            doubleSpinnerRow("矢消費効率:", content.arrowEfficiency, 0.0, 1.0, 0.1, 1) { value ->
                content.arrowEfficiency = value
                refreshButtonVisual(itemData.id)
            },
            intSpinnerRow("矢本数:", content.arrowCount, 0, 999, 1) { value ->
                content.arrowCount = value
                refreshButtonVisual(itemData.id)
            },
            doubleSpinnerRow("拡散率:", content.diffusionRate, 0.0, 1.0, 0.1, 1) { value ->
                content.diffusionRate = value
                refreshButtonVisual(itemData.id)
            }
        )
    }

    private fun createPotionRows(content: MutablePotionData): List<Node> {
        val effectCountLabel = Label("効果数: ${content.effects.size}").apply {
            styleClass.add("editor-label")
        }
        return listOf(
            editorRow(
                "色:",
                ColorPicker(ColorPickerDialog.toFxColor(content.color)).apply {
                    valueProperty().addListener { _, _, newColor ->
                        if (newColor == null) return@addListener
                        content.color = ColorPickerDialog.toHexColor(newColor)
                        refreshButtonVisual(itemData.id)
                    }
                }
            ),
            HBox(
                effectCountLabel,
                Button("効果を編集").apply {
                    isFocusTraversable = false
                    setOnAction {
                        PotionEffectEditorDialog.show(content) {
                            refreshButtonVisual(itemData.id)
                        }
                        effectCountLabel.text = "効果数: ${content.effects.size}"
                    }
                }
            ).apply {
                alignment = Pos.CENTER_LEFT
                styleClass.add("editor-row-hbox")
            }
        )
    }

    private fun createShieldRows(content: MutableShieldData): List<Node> {
        return listOf(
            longSpinnerRow(
                "Blockクールダウン (tick):",
                content.blockCooldown,
                0L,
                Long.MAX_VALUE,
                1L
            ) { value ->
                content.blockCooldown = value
                refreshButtonVisual(itemData.id)
            },
            longSpinnerRow(
                "Parryクールダウン (tick):",
                content.parryCooldown,
                0L,
                Long.MAX_VALUE,
                1L
            ) { value ->
                content.parryCooldown = value
                refreshButtonVisual(itemData.id)
            }
        )
    }

    private fun createArmorRows(content: MutableArmorContent): List<Node> {
        val vanillaId = itemData.itemDetail.vanillaId
        val isOtherArmor = vanillaId !in ItemIdGroups.notTurtleArmors
        val isLeather = ItemIdGroups.isLeather(vanillaId)

        if (isOtherArmor) {
            content.color = null
            content.mutableTrimData = null
            return emptyList()
        }
        if (!isLeather) {
            content.color = null
        }

        val nodes = mutableListOf<Node>()
        val colorPicker = ColorPicker(
            ColorPickerDialog.toFxColor(content.color ?: SushiEricHexColor.of("#FFFFFF"))
        ).apply {
            valueProperty().addListener { _, _, newColor ->
                if (newColor == null) return@addListener
                content.color = ColorPickerDialog.toHexColor(newColor)
                refreshButtonVisual(itemData.id)
            }
        }
        val colorRow = editorRow("色:", colorPicker).apply {
            isVisible = content.color != null
            isManaged = content.color != null
        }

        if (isLeather) {
            nodes += toggleRow("着色:", content.color != null) { enabled ->
                if (enabled) {
                    val defaultColor = SushiEricHexColor.of("#FFFFFF")
                    content.color = defaultColor
                    colorPicker.value = ColorPickerDialog.toFxColor(defaultColor)
                } else {
                    content.color = null
                }
                colorRow.isVisible = enabled
                colorRow.isManaged = enabled
                refreshButtonVisual(itemData.id)
            }
            nodes += colorRow
        }

        val patternCombo = ComboBox<ArmorTrimRegistry.Pattern>().apply {
            items.addAll(ArmorTrimRegistry.Pattern.entries)
            value = content.trimData?.pattern ?: ArmorTrimRegistry.Pattern.COAST
            valueProperty().addListener { _, oldPattern, newPattern ->
                if (newPattern == null || newPattern == oldPattern) return@addListener
                val trimData = content.mutableTrimData
                    ?: MutableArmorTrimData().also { content.mutableTrimData = it }
                trimData.pattern = newPattern
                refreshButtonVisual(itemData.id)
            }
        }
        val materialCombo = ComboBox<ArmorTrimRegistry.Material>().apply {
            items.addAll(ArmorTrimRegistry.Material.entries)
            value = content.trimData?.material ?: ArmorTrimRegistry.Material.IRON
            valueProperty().addListener { _, oldMaterial, newMaterial ->
                if (newMaterial == null || newMaterial == oldMaterial) return@addListener
                val trimData = content.mutableTrimData
                    ?: MutableArmorTrimData().also { content.mutableTrimData = it }
                trimData.material = newMaterial
                refreshButtonVisual(itemData.id)
            }
        }
        val trimRows = VBox(
            editorRow("模様:", patternCombo),
            editorRow("素材:", materialCombo)
        ).apply {
            styleClass.add("editor-row-vbox")
            isVisible = content.trimData != null
            isManaged = content.trimData != null
        }

        nodes += toggleRow("装飾:", content.trimData != null) { enabled ->
            if (enabled) {
                val defaultTrimData = MutableArmorTrimData()
                content.mutableTrimData = defaultTrimData
                patternCombo.value = defaultTrimData.pattern
                materialCombo.value = defaultTrimData.material
            } else {
                content.mutableTrimData = null
            }
            trimRows.isVisible = enabled
            trimRows.isManaged = enabled
            refreshButtonVisual(itemData.id)
        }
        nodes += trimRows
        return nodes
    }

    override fun createGraphic(row: TreeRow): Node {
        val editorRow = row as? TreeRow.Editor
            ?: return Label(row.label)

        return VBox(5.0).apply {
            children.add(
                when (editorRow) {
                    is TreeRow.Editor.DisplayName -> HBox(5.0).apply {
                        alignment = Pos.CENTER_LEFT
                        styleClass.add("editor-row-hbox")

                        val errorLabel = Label().apply {
                            styleClass.add("error-label")
                        }

                        fun errorView() {
                            val errorMessage = itemData.display.validator().validateDisplayName().getOrNull(0)?.message

                            val isNotNull = errorMessage != null

                            if (isNotNull) {
                                errorLabel.text = errorMessage
                            } else {
                                errorLabel.text = ""
                            }

                            errorLabel.isVisible = isNotNull
                            errorLabel.isManaged = isNotNull
                        }

                        errorView()

                        children.addAll(
                            Label("表示名:").apply {
                                minWidth = Region.USE_PREF_SIZE
                                styleClass.add("editor-label")
                            },
                            TextField(itemData.display.displayName).apply {
                                textProperty().addListener { _, _, n ->
                                    itemData.display.displayName = n
                                    errorView()
                                    refreshButtonVisual(itemData.id)
                                }
                            },
                            errorLabel
                        )
                    }

                    is TreeRow.Editor.SushiEricRarity -> HBox(5.0).apply {
                        alignment = Pos.CENTER_LEFT
                        styleClass.add("editor-row-hbox")

                        children.addAll(
                            Label("レアリティ:").apply {
                                minWidth = Region.USE_PREF_SIZE
                                styleClass.add("editor-label")
                            },
                            ComboBox<SushiEricRarity>().apply {
                                items.addAll(SushiEricRarity.entries)
                                value = itemData.rarity
                                valueProperty().addListener { _, _, n ->
                                    if (n != null) {
                                        itemData.rarity = n
                                        refreshButtonVisual(itemData.id)
                                    }
                                }
                            }
                        )
                    }

                    is TreeRow.Editor.DetailContent -> HBox(5.0).apply {
                        alignment = Pos.CENTER_LEFT
                        styleClass.add("editor-row-hbox")

                        fun contentDisplay(type: ItemType) {
                            children.clear()

                            val contentController = VBox(6.0).apply {
                                styleClass.add("editor-row-vbox")
                            }

                            fun rebuildContentController() {
                                val content = itemData.itemDetail.content

                                contentController.children.clear()

                                val addList: List<Node> = when (type) {
                                    SWORD -> emptyList()

                                    SHORT_SWORD -> emptyList()

                                    LONG_SWORD -> {
                                        content as MutableLongSwordData
                                        createLongSwordRows(content)
                                    }

                                    AXE -> emptyList()

                                    PICKAXE -> {
                                        content as MutablePickaxeData
                                        createPickaxeRows(content)
                                    }

                                    BOW, SHORT_BOW -> {
                                        content as MutableBowContent
                                        createBowRows(content)
                                    }

                                    CROSSBOW -> {
                                        content as MutableCrossbowData
                                        createCrossbowRows(content)
                                    }

                                    OTHER_WEAPON -> emptyList()

                                    POTION -> {
                                        content as MutablePotionData
                                        createPotionRows(content)
                                    }

                                    SHIELD -> {
                                        content as MutableShieldData
                                        createShieldRows(content)
                                    }

                                    HELMET, CHESTPLATE, LEGGINGS, BOOTS -> {
                                        content as MutableArmorContent
                                        createArmorRows(content)
                                    }

                                    OTHER -> emptyList()
                                }

                                contentController.children.addAll(addList)
                            }

                            rebuildContentController()

                            children.addAll(
                                VBox(6.0).apply {
                                    styleClass.add("editor-row-vbox")

                                    val allItems = itemData
                                        .itemDetail
                                        .content
                                        .vanillaIdConstraint
                                        .choices()
                                        .map(VanillaItemId::value)

                                    val errorLabel = Label().apply {
                                        styleClass.add("error-label")
                                        isVisible = false
                                        isManaged = false
                                    }

                                    val headSkin = itemData.itemDetail.mutableHeadSkin
                                    val headSkinValueField = TextField(headSkin?.value.orEmpty()).apply {
                                        promptText = "スキンの値"
                                        prefColumnCount = 21
                                        minWidth = 160.0
                                        HBox.setHgrow(this, Priority.ALWAYS)
                                    }
                                    val headSkinMultilineArea = TextArea(headSkin?.value.orEmpty()).apply {
                                        promptText = "テクスチャの値"
                                        isWrapText = true
                                        prefRowCount = 5
                                        maxWidth = Double.MAX_VALUE
                                        textFormatter = TextFormatter<String> { change ->
                                            change.text = normalizeHeadSkinInput(change.text)
                                            change
                                        }
                                        isVisible = false
                                        isManaged = false
                                    }
                                    val headSkinErrorLabel = Label().apply {
                                        styleClass.add("error-label")
                                        isVisible = false
                                        isManaged = false
                                    }
                                    val headSkinWarningLabel = Label().apply {
                                        styleClass.add("warning-label")
                                        isWrapText = true
                                        maxWidth = Double.MAX_VALUE
                                        isVisible = false
                                        isManaged = false
                                    }
                                    val headSkinLookupLabel = Label().apply {
                                        styleClass.add("error-label")
                                        isWrapText = true
                                        maxWidth = Double.MAX_VALUE
                                        isVisible = false
                                        isManaged = false
                                    }
                                    val headSkinLookupButton = Button("テクスチャ値を取得").apply {
                                        isVisible = false
                                        isManaged = false
                                    }
                                    val headSkinSourceOptions = listOf(HeadSkinSourceOption(null)) +
                                        HeadSkinSource.entries.map(::HeadSkinSourceOption)
                                    val headSkinSourceComboBox = ComboBox<HeadSkinSourceOption>().apply {
                                        items.addAll(headSkinSourceOptions)
                                        value = headSkinSourceOptions.first { it.source == headSkin?.source }
                                    }
                                    val headSkinEditor = VBox(5.0).apply {
                                        styleClass.add("editor-row-vbox")
                                        children.addAll(
                                            Label("ヘッドスキン:"),
                                            HBox(5.0).apply {
                                                alignment = Pos.CENTER_LEFT
                                                styleClass.add("editor-row-hbox")
                                                children.addAll(
                                                    headSkinSourceComboBox,
                                                    headSkinValueField,
                                                    headSkinLookupButton
                                                )
                                            },
                                            headSkinMultilineArea,
                                            headSkinWarningLabel,
                                            headSkinLookupLabel,
                                            headSkinErrorLabel
                                        )
                                    }

                                    fun refreshHeadSkinEditor() {
                                        val editable = isHeadSkinEditableVanillaId(itemData.itemDetail.vanillaId)
                                        headSkinEditor.isVisible = editable
                                        headSkinEditor.isManaged = editable

                                        val source = headSkinSourceComboBox.value?.source
                                        val specified = source != null
                                        val texture = source == HeadSkinSource.TEXTURE

                                        headSkinValueField.isVisible = specified && !texture
                                        headSkinValueField.isManaged = specified && !texture
                                        headSkinMultilineArea.isVisible = texture
                                        headSkinMultilineArea.isManaged = texture

                                        /*
                                         * テクスチャ値の取得はプレイヤー名とUUIDだけが対象になる。
                                         */
                                        val lookupAvailable = specified && !texture
                                        headSkinLookupButton.isVisible = lookupAvailable
                                        headSkinLookupButton.isManaged = lookupAvailable

                                        val warningMessage = headSkinWarning(source)
                                        headSkinWarningLabel.text = warningMessage.orEmpty()
                                        headSkinWarningLabel.isVisible = warningMessage != null
                                        headSkinWarningLabel.isManaged = warningMessage != null

                                        val errorMessage = if (editable && specified) {
                                            itemData.itemDetail.validator().validateHeadSkin().firstOrNull()?.message
                                        } else {
                                            null
                                        }
                                        headSkinErrorLabel.text = errorMessage.orEmpty()
                                        headSkinErrorLabel.isVisible = errorMessage != null
                                        headSkinErrorLabel.isManaged = errorMessage != null
                                    }

                                    headSkinSourceComboBox.valueProperty().addListener { _, _, option ->
                                        val source = option?.source
                                        itemData.itemDetail.mutableHeadSkin = applyHeadSkinEditorValue(
                                            current = itemData.itemDetail.mutableHeadSkin,
                                            source = source,
                                            value = headSkinValueField.text
                                        )
                                        refreshHeadSkinEditor()
                                        refreshButtonVisual(itemData.id)
                                    }
                                    headSkinValueField.textProperty().addListener { _, _, value ->
                                        if (headSkinMultilineArea.text != value) {
                                            headSkinMultilineArea.text = value
                                        }
                                        val source = headSkinSourceComboBox.value?.source
                                        if (source != null) {
                                            itemData.itemDetail.mutableHeadSkin = applyHeadSkinEditorValue(
                                                current = itemData.itemDetail.mutableHeadSkin,
                                                source = source,
                                                value = value
                                            )
                                            refreshHeadSkinEditor()
                                            refreshButtonVisual(itemData.id)
                                        }
                                    }
                                    headSkinMultilineArea.textProperty().addListener { _, _, value ->
                                        if (headSkinValueField.text != value) {
                                            headSkinValueField.text = value
                                        }
                                    }

                                    fun showHeadSkinLookupMessage(message: String?) {
                                        headSkinLookupLabel.text = message.orEmpty()
                                        headSkinLookupLabel.isVisible = message != null
                                        headSkinLookupLabel.isManaged = message != null
                                    }

                                    headSkinLookupButton.setOnAction {
                                        val source = headSkinSourceComboBox.value?.source
                                            ?: return@setOnAction
                                        val input = headSkinValueField.text

                                        showHeadSkinLookupMessage(null)
                                        headSkinLookupButton.isDisable = true

                                        /*
                                         * 通信でUIを止めないため別スレッドで取得し、
                                         * 反映だけをJavaFXスレッドへ戻す。
                                         */
                                        Thread {
                                            val result = headSkinTextureLookup.lookup(source, input)

                                            Platform.runLater {
                                                headSkinLookupButton.isDisable = false

                                                when (result) {
                                                    is HeadSkinTextureLookupResult.Success -> {
                                                        headSkinSourceComboBox.value = headSkinSourceOptions
                                                            .first { it.source == HeadSkinSource.TEXTURE }
                                                        headSkinValueField.text = result.texture
                                                        showHeadSkinLookupMessage(null)
                                                    }

                                                    is HeadSkinTextureLookupResult.Failure ->
                                                        showHeadSkinLookupMessage(result.message)
                                                }
                                            }
                                        }.apply {
                                            isDaemon = true
                                            name = "head-skin-texture-lookup"
                                        }.start()
                                    }

                                    val fixedValue = itemData.itemDetail.vanillaId.value
                                        .takeIf { it in allItems }
                                        ?: allItems.firstOrNull()
                                    if (fixedValue != null) {
                                        itemData.itemDetail.vanillaId = VanillaItemId(fixedValue)
                                    }
                                    val vanillaIdSelector = SearchableComboBox(
                                        allChoices = allItems,
                                        initialValue = fixedValue,
                                        promptText = "アイテムIDを検索",
                                        displayText = { it },
                                        onSelected = { selected ->
                                            itemData.itemDetail.vanillaId = VanillaItemId(selected)
                                            rebuildContentController()
                                            refreshHeadSkinEditor()
                                            refreshButtonVisual(itemData.id)
                                            errorLabel.isVisible = false
                                            errorLabel.isManaged = false
                                            errorLabel.text = ""
                                        },
                                        onSearchMatchChanged = { hasMatches ->
                                            if (!hasMatches) {
                                                errorLabel.text = "検索に一致するアイテムIDがありません"
                                                errorLabel.isVisible = true
                                                errorLabel.isManaged = true
                                            } else {
                                                errorLabel.isVisible = false
                                                errorLabel.isManaged = false
                                                errorLabel.text = ""
                                            }
                                        }
                                    )

                                    val maxStackSizeRow = HBox(5.0).apply {
                                        alignment = Pos.CENTER_LEFT
                                        styleClass.add("editor-row-hbox")
                                    }

                                    fun refreshMaxStackSizeRow(itemType: ItemType) {
                                        maxStackSizeRow.children.setAll(
                                            Label("最大スタック数:"),
                                            if (isMaxStackSizeEditable(itemType)) {
                                                NumericSpinnerFactory.intSpinner(
                                                    getter = { itemData.itemDetail.maxStackSize },
                                                    setter = { value ->
                                                        itemData.itemDetail.maxStackSize = value
                                                        refreshButtonVisual(itemData.id)
                                                    },
                                                    min = 1,
                                                    max = 99,
                                                    step = 1,
                                                    allowNegative = false,
                                                    allowPlus = true
                                                )
                                            } else {
                                                Label("1（固定）")
                                            }
                                        )
                                    }

                                    refreshMaxStackSizeRow(itemData.itemDetail.itemType)
                                    refreshHeadSkinEditor()

                                    children.addAll(
                                        Label("バニラID:"),
                                        vanillaIdSelector,
                                        errorLabel,
                                        headSkinEditor,
                                        HBox(5.0).apply {
                                            alignment = Pos.CENTER_LEFT
                                            styleClass.add("editor-row-hbox")

                                            children.addAll(
                                                Label("エンチャントオーラ:"),
                                                ToggleSwitch().apply {
                                                    isSelected = itemData.itemDetail.enchantAura

                                                    selectedProperty().addListener { _, _, value ->
                                                        if (value == null) return@addListener
                                                        itemData.itemDetail.enchantAura = value
                                                        refreshButtonVisual(itemData.id)
                                                    }
                                                }
                                            )
                                        },
                                        maxStackSizeRow,
                                        ComboBox<ItemType>().apply {
                                            items.addAll(ItemType.entries)
                                            value = itemData.itemDetail.content.itemType

                                            valueProperty().addListener { _, oldType, newType ->
                                                if (newType == null || newType == oldType) return@addListener

                                                itemData.itemDetail.content = newType.createMutableContent()
                                                itemData.itemDetail.normalizeVanillaIdByContent()
                                                refreshMaxStackSizeRow(newType)
                                                contentDisplay(newType)
                                                refreshButtonVisual(itemData.id)
                                            }
                                        }
                                    )
                                },
                                contentController
                            )
                        }

                        contentDisplay(itemData.itemDetail.itemType)
                    }

                    is TreeRow.Editor.StatsContent -> VBox(8.0).apply {
                        styleClass.add("editor-row-vbox")

                        /*
                         * 初期値、補正、表示の規則はItemStatValueRulesへ集約する。
                         * 追加時と追加済みの編集で同じ規則を使う。
                         */
                        fun formatStatValue(value: Double): String =
                            formatItemStatValue(value)

                        fun nonZeroInitial(type: StatsType): Double =
                            initialItemStatValue(type)

                        fun fixZero(type: StatsType, value: Double): Double =
                            normalizeItemStatValue(type, value)

                        fun commitSpinnerValue(spinner: Spinner<Double>) {
                            val converter = spinner.valueFactory.converter
                            spinner.valueFactory.value = converter.fromString(spinner.editor.text)
                        }

                        fun createDoubleSpinner(
                            type: StatsType,
                            initialValue: Double
                        ): Spinner<Double> {
                            return Spinner<Double>().apply {
                                valueFactory = SpinnerValueFactory.DoubleSpinnerValueFactory(
                                    type.min,
                                    type.max,
                                    fixZero(type, initialValue),
                                    1.0
                                )

                                isEditable = true
                                prefWidth = 90.0

                                valueFactory.converter = object : StringConverter<Double>() {
                                    override fun toString(value: Double?): String {
                                        return value?.let { formatStatValue(it) } ?: ""
                                    }

                                    override fun fromString(string: String?): Double =
                                        parseItemStatValue(type, string, valueFactory.value)
                                }

                                editor.setOnAction {
                                    valueFactory.value = valueFactory.converter.fromString(editor.text)
                                }

                                focusedProperty().addListener { _, _, focused ->
                                    if (!focused) {
                                        valueFactory.value = valueFactory.converter.fromString(editor.text)
                                    }
                                }
                            }
                        }

                        /*
                         * 倍率はStatsTypeごとの対象Part付きリストで、規則はItemStatValueRulesへ集約する。
                         * 要素は不変なので、変更時はリストの同じ位置を置き換える。
                         */
                        fun multipliersOf(type: StatsType): MutableList<ItemStatMultiplier> =
                            itemData.statMultipliers.getOrPut(type) { mutableListOf() }

                        fun updateMultiplier(
                            type: StatsType,
                            entry: ItemStatMultiplier,
                            updated: ItemStatMultiplier
                        ) {
                            val multipliers = multipliersOf(type)
                            val index = multipliers.indexOf(entry)

                            if (index >= 0) {
                                multipliers[index] = updated
                                refreshButtonVisual(itemData.id)
                            }
                        }

                        fun createMultiplierSpinner(
                            initialValue: Double,
                            onChanged: (Double) -> Unit
                        ): Spinner<Double> {
                            return Spinner<Double>().apply {
                                valueFactory = SpinnerValueFactory.DoubleSpinnerValueFactory(
                                    ITEM_STAT_MULTIPLIER_MIN,
                                    ITEM_STAT_MULTIPLIER_MAX,
                                    normalizeItemStatMultiplier(initialValue),
                                    ITEM_STAT_MULTIPLIER_STEP
                                )

                                isEditable = true
                                prefWidth = 90.0

                                valueFactory.converter = object : StringConverter<Double>() {
                                    override fun toString(value: Double?): String {
                                        return value?.let { formatItemStatMultiplier(it) } ?: ""
                                    }

                                    override fun fromString(string: String?): Double =
                                        parseItemStatMultiplier(string, valueFactory.value)
                                }

                                editor.setOnAction {
                                    valueFactory.value = valueFactory.converter.fromString(editor.text)
                                }

                                focusedProperty().addListener { _, _, focused ->
                                    if (!focused) {
                                        valueFactory.value = valueFactory.converter.fromString(editor.text)
                                    }
                                }

                                valueProperty().addListener { _, _, newValue ->
                                    if (newValue != null) {
                                        onChanged(newValue)
                                    }
                                }
                            }
                        }

                        /**
                         * 倍率1件の行を作る。対象の選択、値、削除を1行に置き、対象が部位のときだけ
                         * その下に部位のチェックを3列で表示する。
                         * 部位で1つも選んでいない状態は対象を作れないため、部位へ切り替えたときは
                         * アイテムの種類に合う部位を先に選び、最後の1つは外せない。
                         */
                        fun createMultiplierRow(
                            type: StatsType,
                            initialEntry: ItemStatMultiplier,
                            onRemoved: () -> Unit
                        ): VBox {
                            var entry = initialEntry
                            val selectedParts = selectedItemStatMultiplierParts(entry.target).toMutableSet()
                            val partCheckBoxes = linkedMapOf<StatsPart, CheckBox>()

                            val targetComboBox = ComboBox<ItemStatMultiplierTargetOption>().apply {
                                items.addAll(ItemStatMultiplierTargetOption.entries)
                                value = ItemStatMultiplierTargetOption.of(entry.target)
                                prefWidth = 80.0
                                tooltip = AppTooltip.create(ITEM_STAT_MULTIPLIER_TARGET_TOOLTIP)
                            }

                            fun applyTarget() {
                                val target = resolveItemStatMultiplierTarget(targetComboBox.value, selectedParts)
                                    ?: return
                                val updated = entry.copy(target = target)

                                if (updated != entry) {
                                    updateMultiplier(type, entry, updated)
                                    entry = updated
                                }
                            }

                            val partsGrid = GridPane().apply {
                                hgap = 12.0
                                vgap = 4.0
                                padding = Insets(0.0, 0.0, 0.0, 128.0)

                                StatsPart.equipmentParts.forEachIndexed { index, part ->
                                    val checkBox = CheckBox(part.display).apply {
                                        styleClass.add("editor-label")
                                        isSelected = part in selectedParts
                                        tooltip = AppTooltip.create("${part.display}の部位のステータスに倍率を掛けます")

                                        selectedProperty().addListener { _, _, selected ->
                                            if (selected) {
                                                selectedParts.add(part)
                                            } else if (selectedParts.size > 1) {
                                                selectedParts.remove(part)
                                            } else {
                                                isSelected = true
                                                return@addListener
                                            }

                                            applyTarget()
                                        }
                                    }

                                    partCheckBoxes[part] = checkBox
                                    add(checkBox, index % 3, index / 3)
                                }
                            }

                            fun syncPartsVisibility() {
                                val visible = targetComboBox.value == ItemStatMultiplierTargetOption.PARTS
                                partsGrid.isVisible = visible
                                partsGrid.isManaged = visible
                            }

                            syncPartsVisibility()

                            targetComboBox.valueProperty().addListener { _, _, option ->
                                if (option == ItemStatMultiplierTargetOption.PARTS && selectedParts.isEmpty()) {
                                    selectedParts.addAll(
                                        defaultItemStatMultiplierParts(itemData.itemDetail.itemType)
                                    )
                                    selectedParts.forEach { part ->
                                        partCheckBoxes[part]?.isSelected = true
                                    }
                                }

                                syncPartsVisibility()
                                applyTarget()
                            }

                            val valueSpinner = createMultiplierSpinner(entry.value) { newValue ->
                                val updated = entry.copy(value = newValue)

                                if (updated != entry) {
                                    updateMultiplier(type, entry, updated)
                                    entry = updated
                                }
                            }.apply {
                                tooltip = AppTooltip.create(ITEM_STAT_MULTIPLIER_VALUE_TOOLTIP)
                            }

                            val row = HBox(8.0).apply {
                                alignment = Pos.CENTER_LEFT
                                styleClass.add("editor-row-hbox")

                                children.addAll(
                                    Label("倍率:").apply {
                                        styleClass.add("editor-label")
                                        minWidth = 120.0
                                        alignment = Pos.CENTER_RIGHT
                                    },
                                    targetComboBox,
                                    Label("×").apply {
                                        styleClass.add("editor-label")
                                    },
                                    valueSpinner,
                                    Button("削除").apply {
                                        styleClass.add("btn-danger")
                                        minWidth = 56.0
                                        prefWidth = 56.0
                                        maxWidth = 56.0
                                        minHeight = 26.0
                                        prefHeight = 26.0
                                        maxHeight = 26.0
                                        styleClass.add("editor-small-button")

                                        setOnAction {
                                            multipliersOf(type).remove(entry)

                                            if (multipliersOf(type).isEmpty()) {
                                                itemData.statMultipliers.remove(type)
                                            }

                                            refreshButtonVisual(itemData.id)
                                            onRemoved()
                                        }
                                    }
                                )
                            }

                            return VBox(4.0).apply {
                                children.addAll(row, partsGrid)
                            }
                        }

                        fun applySmallButtonSize(button: Button) {
                            button.minWidth = 56.0
                            button.prefWidth = 56.0
                            button.maxWidth = 56.0

                            button.minHeight = 26.0
                            button.prefHeight = 26.0
                            button.maxHeight = 26.0
                            button.styleClass.add("editor-small-button")
                        }

                        fun rebuildStatsList(container: VBox) {
                            container.children.clear()

                            itemData.stats.entries
                                .forEach { (type, value) ->
                                    val spinner = createDoubleSpinner(type, value)

                                    spinner.valueProperty().addListener { _, _, newValue ->
                                        if (newValue != null && newValue != 0.0) {
                                            itemData.stats[type] = newValue
                                            refreshButtonVisual(itemData.id)
                                        }
                                    }

                                    val multiplierRows = VBox(4.0).apply {
                                        styleClass.add("editor-row-vbox")
                                    }

                                    fun rebuildMultiplierRows() {
                                        multiplierRows.children.clear()

                                        itemData.statMultipliers[type].orEmpty().forEach { entry ->
                                            multiplierRows.children.add(
                                                createMultiplierRow(type, entry) {
                                                    rebuildMultiplierRows()
                                                }
                                            )
                                        }
                                    }

                                    rebuildMultiplierRows()

                                    container.children.add(
                                        VBox(4.0).apply {
                                            styleClass.add("editor-row-vbox")

                                            children.addAll(
                                                HBox(8.0).apply {
                                                    alignment = Pos.CENTER_LEFT
                                                    styleClass.add("editor-row-hbox")

                                                    children.addAll(
                                                        Label("${type.display}:").apply {
                                                            styleClass.add("editor-label")
                                                            minWidth = 120.0
                                                        },

                                                        spinner,

                                                        Button("倍率追加").apply {
                                                            styleClass.add("btn-primary")
                                                            applySmallButtonSize(this)
                                                            minWidth = 72.0
                                                            prefWidth = 72.0
                                                            maxWidth = 72.0

                                                            setOnAction {
                                                                multipliersOf(type).add(initialItemStatMultiplier())
                                                                refreshButtonVisual(itemData.id)
                                                                rebuildMultiplierRows()
                                                            }
                                                        },

                                                        Button("削除").apply {
                                                            styleClass.add("btn-danger")
                                                            applySmallButtonSize(this)

                                                            setOnAction {
                                                                itemData.stats.remove(type)
                                                                itemData.statMultipliers.remove(type)
                                                                refreshButtonVisual(itemData.id)
                                                                rebuildStatsList(container)
                                                            }
                                                        }
                                                    )
                                                },
                                                multiplierRows
                                            )
                                        }
                                    )
                                }
                        }

                        val statsListBox = VBox(6.0).apply {
                            styleClass.add("editor-row-vbox")
                        }

                        fun showAddStatsDialog() {
                            val typeComboBox = ComboBox<StatsType>().apply {
                                items.addAll(
                                    StatsType.entries.filterNot { it in itemData.stats.keys }
                                )

                                cellFactory = Callback {
                                    object : ListCell<StatsType>() {
                                        override fun updateItem(item: StatsType?, empty: Boolean) {
                                            super.updateItem(item, empty)
                                            text = if (empty || item == null) null else item.display
                                        }
                                    }
                                }

                                buttonCell = object : ListCell<StatsType>() {
                                    override fun updateItem(item: StatsType?, empty: Boolean) {
                                        super.updateItem(item, empty)
                                        text = if (empty || item == null) null else item.display
                                    }
                                }

                                value = items.firstOrNull()
                            }

                            val valueSpinner = Spinner<Double>().apply {
                                isEditable = true
                                prefWidth = 90.0
                            }

                            fun updateSpinnerForType(type: StatsType?) {
                                if (type == null) return

                                valueSpinner.valueFactory = SpinnerValueFactory.DoubleSpinnerValueFactory(
                                    type.min,
                                    type.max,
                                    nonZeroInitial(type),
                                    1.0
                                )

                                valueSpinner.valueFactory.converter = object : StringConverter<Double>() {
                                    override fun toString(value: Double?): String {
                                        return value?.let { formatStatValue(it) } ?: ""
                                    }

                                    override fun fromString(string: String?): Double =
                                        parseItemStatValue(type, string, valueSpinner.valueFactory.value)
                                }
                            }

                            updateSpinnerForType(typeComboBox.value)

                            typeComboBox.valueProperty().addListener { _, _, type ->
                                updateSpinnerForType(type)
                            }

                            val errorLabel = Label().apply {
                                styleClass.add("error-label")
                                isVisible = false
                                isManaged = false
                            }

                            val content = VBox(8.0).apply {
                                styleClass.add("editor-row-vbox")

                                children.addAll(
                                    Label("追加するステータス:").apply {
                                        styleClass.add("editor-label")
                                    },
                                    typeComboBox,
                                    Label("値:").apply {
                                        styleClass.add("editor-label")
                                    },
                                    valueSpinner,
                                    errorLabel
                                )
                            }

                            val dialog = Dialog<Pair<StatsType, Double>>().apply {
                                title = "ステータスを追加"
                                dialogPane.content = content
                                dialogPane.buttonTypes.addAll(ButtonType.OK, ButtonType.CANCEL)

                                dialogPane.stylesheets.add(
                                    ItemEditorFactory::class.java
                                        .getResource(AppScreen.WIDGETS_ONLY.css)!!
                                        .toExternalForm()
                                )

                                dialogPane.styleClass.add("custom-dialog")

                                setOnShown {
                                    dialogPane.scene?.root?.styleClass?.add("common-root")
                                }

                                setResultConverter { buttonType ->
                                    if (buttonType != ButtonType.OK) {
                                        null
                                    } else {
                                        commitSpinnerValue(valueSpinner)

                                        resolveStatToAdd(typeComboBox.value, valueSpinner.value)
                                            ?.let { (type, value) -> type to fixZero(type, value) }
                                    }
                                }
                            }

                            val okButton = dialog.dialogPane.lookupButton(ButtonType.OK) as Button

                            /*
                             * 確定できない場合はダイアログを閉じない。
                             * 入力中の文字列を先にSpinnerへ反映してから判定する。
                             */
                            okButton.addEventFilter(ActionEvent.ACTION) { event ->
                                commitSpinnerValue(valueSpinner)

                                if (resolveStatToAdd(typeComboBox.value, valueSpinner.value) == null) {
                                    errorLabel.text = "ステータスと0以外の値を指定してください"
                                    errorLabel.isVisible = true
                                    errorLabel.isManaged = true
                                    event.consume()
                                }
                            }

                            /*
                             * EnterはOKボタンと同じ経路で確定する。入力中の文字列はOKボタン側で
                             * Spinnerへ反映されるため、ここでは値を読まない。
                             * ステータスの更新と画面の再構築はshowAndWaitの結果で1回だけ行う。
                             */
                            valueSpinner.editor.setOnAction { event ->
                                event.consume()
                                okButton.fire()
                            }

                            dialog.showAndWait().ifPresent { (type, value) ->
                                itemData.stats[type] = value
                                refreshButtonVisual(itemData.id)
                                rebuildStatsList(statsListBox)
                            }
                        }

                        val addButton = Button("追加").apply {
                            styleClass.add("btn-primary")
                            applySmallButtonSize(this)

                            setOnAction {
                                showAddStatsDialog()
                            }
                        }

                        children.addAll(
                            HBox(8.0).apply {
                                alignment = Pos.CENTER_LEFT
                                styleClass.add("editor-row-hbox")

                                children.addAll(
                                    Label("ステータス:").apply {
                                        styleClass.add("editor-label")
                                    },
                                    addButton
                                )
                            },

                            statsListBox
                        )

                        rebuildStatsList(statsListBox)
                    }

                    is TreeRow.Editor.LoreContent -> VBox(6.0).apply {
                        styleClass.add("editor-row-vbox")

                        val sectionEditor = LoreLineEditor(itemData.display, editorRow.lineIndex).section(editorRow.sectionIndex)

                        fun rebuildContent() {
                            children.clear()

                            val section = sectionEditor.currentSectionOrNull()

                            children.add(
                                HBox(5.0).apply {
                                    alignment = Pos.CENTER_LEFT
                                    styleClass.add("editor-row-hbox")

                                    children.addAll(
                                        Label("テキストタイプ:").apply {
                                            minWidth = Region.USE_PREF_SIZE
                                        },
                                        ComboBox<LoreSectionType>().apply {
                                            items.addAll(LoreSectionType.entries)
                                            value = section?.type

                                            cellFactory = Callback {
                                                object : ListCell<LoreSectionType>() {
                                                    override fun updateItem(item: LoreSectionType?, empty: Boolean) {
                                                        super.updateItem(item, empty)
                                                        text = if (empty || item == null) null else item.display
                                                    }
                                                }
                                            }

                                            buttonCell = object : ListCell<LoreSectionType>() {
                                                override fun updateItem(item: LoreSectionType?, empty: Boolean) {
                                                    super.updateItem(item, empty)
                                                    text = if (empty || item == null) null else item.display
                                                }
                                            }

                                            valueProperty().addListener { _, oldType, newType ->
                                                if (newType == null || newType == oldType) return@addListener

                                                sectionEditor.replaceCurrentSection(newType)

                                                refreshButtonVisual(itemData.id)
                                                rebuildContent()
                                            }
                                        }
                                    )
                                }
                            )

                            when (section) {
                                is MutablePlainTextLoreSection -> children.addAll(
                                    HBox(5.0).apply {
                                        alignment = Pos.CENTER_LEFT
                                        styleClass.add("editor-row-hbox")

                                        children.addAll(
                                            Label("テキスト:").apply {
                                                minWidth = Region.USE_PREF_SIZE
                                            },
                                            TextField(section.text).apply {
                                                HBox.setHgrow(this, Priority.ALWAYS)
                                                maxWidth = Double.MAX_VALUE

                                                textProperty().addListener { _, _, text ->
                                                    if (text == null) return@addListener
                                                    section.text = text
                                                    refreshButtonVisual(itemData.id)
                                                }
                                            }
                                        )
                                    },
                                    HBox(5.0).apply {
                                        alignment = Pos.CENTER_LEFT
                                        styleClass.add("editor-row-hbox")

                                        children.addAll(
                                            Label("シークレット:"),
                                            ToggleSwitch().apply {
                                                isSelected = section.secret

                                                selectedProperty().addListener { _, _, value ->
                                                    if (value == null) return@addListener
                                                    section.secret = value
                                                    refreshButtonVisual(itemData.id)
                                                }
                                            }
                                        )
                                    }
                                )
                                is MutableStatLoreSection -> children.add(
                                    HBox(5.0).apply {
                                        alignment = Pos.CENTER_LEFT
                                        styleClass.add("editor-row-hbox")

                                        children.addAll(
                                            Label("ステータス:"),
                                            ComboBox<StatsType>().apply {
                                                items.addAll(StatsType.entries)

                                                cellFactory = Callback {
                                                    object : ListCell<StatsType>() {
                                                        override fun updateItem(item: StatsType?, empty: Boolean) {
                                                            super.updateItem(item, empty)
                                                            text = if (empty || item == null) null else item.display
                                                        }
                                                    }
                                                }

                                                buttonCell = object : ListCell<StatsType>() {
                                                    override fun updateItem(item: StatsType?, empty: Boolean) {
                                                        super.updateItem(item, empty)
                                                        text = if (empty || item == null) null else item.display
                                                    }
                                                }

                                                value = section.stat
                                                valueProperty().addListener { _, _, stat ->
                                                    if (stat == null) return@addListener
                                                    section.stat = stat

                                                    refreshButtonVisual(itemData.id)
                                                }
                                            }
                                        )
                                    }
                                )
                                is MutableCustomComponentLoreSection -> children.addAll(
                                    HBox(5.0).apply {
                                        alignment = Pos.CENTER_LEFT
                                        styleClass.add("editor-row-hbox")

                                        children.addAll(
                                            Label("テキスト:").apply {
                                                minWidth = Region.USE_PREF_SIZE
                                            },
                                            TextField(section.getText()).apply {
                                                HBox.setHgrow(this, Priority.ALWAYS)
                                                maxWidth = Double.MAX_VALUE

                                                textProperty().addListener { _, _, text ->
                                                    if (text == null) return@addListener
                                                    section.editText(text)
                                                    refreshButtonVisual(itemData.id)
                                                }
                                            }
                                        )
                                    },
                                    HBox(5.0).apply {
                                        alignment = Pos.CENTER_LEFT
                                        styleClass.add("editor-row-hbox")

                                        children.addAll(
                                            GridPane().apply {
                                                hgap = 12.0
                                                vgap = 8.0

                                                alignment = Pos.CENTER_LEFT

                                                decorationDisplay.entries.forEachIndexed { index, entry ->
                                                    val decoration = entry.key
                                                    val label = entry.value

                                                    val splitIndex = 3

                                                    val row = if (index < splitIndex) {
                                                        index
                                                    } else {
                                                        index - splitIndex
                                                    }

                                                    val colBase = if (index < splitIndex) {
                                                        0
                                                    } else {
                                                        2
                                                    }

                                                    add(Label("$label:"), colBase, row)

                                                    add(ToggleSwitch().apply {
                                                        isSelected = decorationEnabled(section, decoration)

                                                        selectedProperty().addListener { _, _, value ->
                                                            if (value == null) return@addListener
                                                            section.editDecoration(decoration, value)
                                                            refreshButtonVisual(itemData.id)
                                                        }
                                                    }, colBase + 1, row)
                                                }
                                            },
                                            VBox(4.0).apply {
                                                styleClass.add("editor-row-vbox")

                                                fun updateColorButtonStyle(button: Button, hexColor: SushiEricHexColor) {
                                                    button.minWidth = 80.0
                                                    button.prefWidth = 80.0
                                                    button.maxWidth = 80.0

                                                    button.minHeight = 20.0
                                                    button.prefHeight = 20.0
                                                    button.maxHeight = 20.0
                                                    if ("editor-color-button" !in button.styleClass) {
                                                        button.styleClass.add("editor-color-button")
                                                    }

                                                    button.style = """
                                                        -fx-background-color: ${hexColor.value};
                                                        -fx-text-fill: ${if (ColorPickerDialog.isBrightColor(hexColor)) "#000000" else "#ffffff"};
                                                    """.trimIndent()
                                                }

                                                val hexCodeLabel = "HexCode"

                                                val colorComboBox = ComboBox<String>().apply {
                                                    items.addAll(textColorDisplay.values)
                                                    items.add(hexCodeLabel)

                                                    value = textColorDisplay.entries
                                                        .firstOrNull { (color, _) -> color == section.getColor() }
                                                        ?.value
                                                        ?: hexCodeLabel
                                                }

                                                var initialized = false

                                                val initialHexColor = SushiEricHexColor.orNull(section.getColorHex() ?: "#ffffff")
                                                    ?: SushiEricHexColor.of("#ffffff")

                                                val hexColorButton = Button(initialHexColor.value).apply {
                                                    isVisible = colorComboBox.value == hexCodeLabel
                                                    isManaged = isVisible

                                                    updateColorButtonStyle(this, initialHexColor)

                                                    setOnAction {
                                                        val currentHexColor = SushiEricHexColor.orNull(section.getColorHex() ?: "#ffffff")
                                                            ?: SushiEricHexColor.of("#ffffff")

                                                        val selectedColor = ColorPickerDialog.show(
                                                            initialColor = currentHexColor,
                                                            owner = scene?.window
                                                        ) ?: return@setOnAction

                                                        section.editColor(selectedColor.value)

                                                        text = selectedColor.value
                                                        updateColorButtonStyle(this, selectedColor)

                                                        refreshButtonVisual(itemData.id)
                                                    }
                                                }

                                                colorComboBox.valueProperty().addListener { _, _, selectedLabel ->
                                                    if (selectedLabel == hexCodeLabel) {
                                                        hexColorButton.isVisible = true
                                                        hexColorButton.isManaged = true

                                                        val hexColor = if (initialized) {
                                                            SushiEricHexColor.of("#ffffff")
                                                        } else {
                                                            SushiEricHexColor.orNull(section.getColorHex() ?: "#ffffff")
                                                                ?: SushiEricHexColor.of("#ffffff")
                                                        }

                                                        section.editColor(hexColor.value)

                                                        hexColorButton.text = hexColor.value
                                                        updateColorButtonStyle(hexColorButton, hexColor)

                                                        refreshButtonVisual(itemData.id)
                                                    } else {
                                                        hexColorButton.isVisible = false
                                                        hexColorButton.isManaged = false

                                                        val selectedColor = textColorDisplay.entries
                                                            .firstOrNull { (_, label) -> label == selectedLabel }
                                                            ?.key

                                                        selectedColor?.let {
                                                            section.editColor(it)
                                                            refreshButtonVisual(itemData.id)
                                                        }
                                                    }
                                                }

                                                initialized = true

                                                children.addAll(
                                                    colorComboBox,
                                                    hexColorButton
                                                )
                                            }
                                        )
                                    }
                                )
                                null -> {}
                            }
                        }

                        rebuildContent()
                    }
                    is TreeRow.Editor.Comment -> VBox(6.0).apply {
                        styleClass.add("editor-row-vbox")

                        children.addAll(
                            Label("コメントアウト").apply {
                                styleClass.add("editor-label")
                            },

                            TextArea(itemData.editorMeta.comment.joinToString("\n")).apply {
                                // 自動折り返ししない
                                isWrapText = false

                                // 12行分を表示
                                prefRowCount = 12

                                // 横幅
                                prefColumnCount = 40

                                // 12行以上はTextArea内で縦スクロール
                                minHeight = 220.0
                                prefHeight = 220.0
                                maxHeight = 220.0

                                // 横に長い場合は横スクロール
                                prefWidth = 520.0

                                textProperty().addListener { _, _, text ->
                                    itemData.editorMeta.comment.clear()
                                    itemData.editorMeta.comment.addAll(text.lines())
                                    refreshButtonVisual(itemData.id)
                                }
                            }
                        )
                    }
                }
            )
        }
    }
}
