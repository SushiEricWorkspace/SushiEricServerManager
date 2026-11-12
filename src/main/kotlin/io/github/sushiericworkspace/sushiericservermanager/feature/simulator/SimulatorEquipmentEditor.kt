package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.PreviewCanvas
import javafx.scene.image.ImageView
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.LevelledItemUpgradeDefinition
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.enchantment.ArmorCustomEnchantment
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.enchantment.MeleeSushiEricEnchantment
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.honing.Honing
import io.github.sushiericworkspace.combatcore.item.upgrade.definition.reforge.*
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.*
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.detail.*
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.*
import javafx.collections.FXCollections
import javafx.scene.control.*
import javafx.scene.layout.VBox

/** 保存先を参照しない手動装備指定。型/技能/強化の適用判定はEquipmentAssemblerだけが行います。 */
internal class SimulatorEquipmentEditor : VBox(8.0) {
    var onSelectionChange: () -> Unit = {}
    internal val slots = StatsPart.entries.filter { it.isEquipment }.associateWith { EquipmentSlot(it) { onSelectionChange() } }
    init { children.addAll(slots.values.map { it.pane }) }
    fun readInput() = SimulatorEquipmentInput(slots.mapNotNull { (part, slot) -> slot.readItem()?.let { part to it } }.toMap())
    fun without(part: StatsPart) = SimulatorEquipmentInput(slots.filterKeys { it != part }.mapNotNull { (key, slot) -> slot.readItem()?.let { key to it } }.toMap())
    fun withImported(part: StatsPart, value: SimulatorImportedItem): SimulatorEquipmentInput = SimulatorEquipmentInput(
        slots.mapNotNull { (key, slot) -> slot.readItem(if (key == part) value else null)?.let { key to it } }.toMap())
}

internal class EquipmentSlot(private val part: StatsPart, private val onSelectionChange: () -> Unit = {}) {
    val enabled = CheckBox("装備を使用")
    val itemId = TextField("manual_${part.name.lowercase()}").apply { tooltip = Tooltip("履歴や発射時の記録と装備IDを合わせて再現するときだけ変更します。保存済み装備は上の検索から選べます。") }
    val type = ComboBox(FXCollections.observableArrayList(ItemType.entries)).apply {
        value = part.supportType.firstOrNull() ?: ItemType.SWORD
        converter = simulatorConverter { it.display }
    }
    val stats = SimulatorNumberGrid(StatsType.entries, { it.display }, { 0.0 })
    val requirements = SimulatorNumberGrid(SkillType.entries, { it.display }, { 0 })
    private val upgrades: List<LevelledItemUpgradeDefinition> = MeleeSushiEricEnchantment.entries + ArmorCustomEnchantment.entries
    val levels = SimulatorNumberGrid(upgrades, { it.displayName }, { 0 })
    val honing = CheckBox("刃研ぎ")
    val multi = numberField(1)
    val arrowCount = numberField(1)
    val arrowEfficiency = numberField(1.0)
    private val reforges: List<ReforgeDefinition> = MeleeReforge.entries + BowReforge.entries + CrossbowReforge.entries + ArmorReforge.entries
    val reforge = ComboBox(FXCollections.observableArrayList(reforges)).apply { promptText = "リフォージなし"; converter = simulatorConverter { it.displayName } }
    private val blockCd = numberField(20)
    private val parryCd = numberField(20)
    private val multipliers = VBox(6.0)
    private val multiplierRows = mutableListOf<MultiplierRow>()
    internal fun addMultiplier(): MultiplierRow = MultiplierRow().also { row -> multiplierRows += row; multipliers.children += row }
    val pane = TitledPane(part.display, VBox(8.0,
        fieldRow("手動装備の種類", enabled, type),
        TitledPane("再現用の識別子（通常は変更不要）", fieldRow("手動装備ID", itemId)).apply { isExpanded = false },
        fieldRow("盾の待ち時間(tick)：防御／パリィ", blockCd, parryCd),
        fieldRow("弓の発射数／クロスボウの本数／矢消費効率", multi, arrowCount, arrowEfficiency),
        fieldRow("リフォージ", reforge, Button("リフォージ解除").apply { setOnAction { reforge.value = null } }),
        TitledPane("装備の固定値（基礎能力には加算しない）", stats).apply { isExpanded = false },
        TitledPane("必要技能レベル", requirements).apply { isExpanded = false },
        TitledPane("強化レベル", levels).apply { isExpanded = false }, honing,
        TitledPane("倍率（この装備／全能力部位／指定部位）", VBox(6.0, multipliers, Button("倍率を追加").apply {
            setOnAction { addMultiplier() }
        })).apply { isExpanded = false }
    )).apply { isExpanded = false }

    private var imported: SimulatorImportedItem? = null
    private val importedLabel = Label().apply { isWrapText = true }
    private val importedPreview = ImageView().apply { isPreserveRatio = true; fitWidth = 200.0 }

    init {
        enabled.selectedProperty().addListener { _, _, _ -> onSelectionChange() }
        (pane.content as VBox).children.addAll(importedLabel, importedPreview, Button("手動装備へ戻す").apply {
            setOnAction { onSelectionChange(); clearImported(); importedLabel.text = "手動指定" }
        })
    }

    private fun clearImported() {
        imported = null
        importedLabel.text = ""
        importedPreview.image = null
    }

    fun unequip() {
        clearImported()
        enabled.isSelected = false
    }

    fun importItem(value: SimulatorImportedItem) {
        val item = value.item
        require(item.itemDetail.itemType in part.supportType || (part == StatsPart.OFF_HAND && item.itemDetail.itemType == ItemType.SHORT_SWORD)) { "この部位には${item.itemDetail.itemType}を装備できません。" }
        require(item.validate().none { it.isError }) { "Itemに検証エラーがあります。" }
        val preview = ImageView()
        PreviewCanvas(item, preview).refreshPreview()
        imported = value
        enabled.isSelected = true
        importedLabel.text = "${value.source} / 取得版=${value.revision} / ${item.id}（再読込まで固定。取込装備に下の強化指定を追加します）"
        importedPreview.image = preview.image
        importedPreview.fitWidth = preview.fitWidth
        importedPreview.fitHeight = preview.fitHeight
    }

    fun readItem(candidate: SimulatorImportedItem? = null): SimulatorEquippedItem? {
        if (candidate == null && !enabled.isSelected) return null
        val item = candidate?.item ?: imported?.item ?: run {
            val content = when (type.value) {
                ItemType.SHIELD -> ShieldData(blockCooldown = blockCd.tick("盾Block CD"), parryCooldown = parryCd.tick("盾Parry CD"))
                ItemType.BOW -> BowData(multi = multi.integer("弓multi"))
                ItemType.SHORT_BOW -> ShortBowData(multi = multi.integer("弓multi"))
                ItemType.CROSSBOW -> CrossbowData(arrowCount = arrowCount.integer("クロスボウ本数"), arrowEfficiency = arrowEfficiency.number("矢消費効率"))
                else -> type.value.createContent()
            }
            ItemBaseData(id = itemId.text, itemDetail = ItemDetail(content = content),
                stats = stats.values(), skillLevelRequirements = requirements.fields.mapValues { (key, field) -> field.integer(key.name) },
                statMultipliers = multiplierRows.filter { it.enabled.isSelected }.groupBy({ it.stat.value }, { it.read() }))
        }
        return SimulatorEquippedItem(item, levels.fields.mapValues { (key, field) -> field.integer(key.displayName) }.filterValues { it != 0 },
            buildMap {
                if (honing.isSelected) put(Honing.category, Honing)
                reforge.value?.let { put(it.category, it) }
            })
    }
}

internal class MultiplierRow : VBox(5.0) {
    val enabled = CheckBox("有効").apply { isSelected = true }
    val stat = ComboBox(FXCollections.observableArrayList(StatsType.entries)).apply { value = StatsType.PHYSICS_DAMAGE; converter = simulatorConverter { it.display } }
    val target = ComboBox(FXCollections.observableArrayList("Self", "All", "Parts")).apply { value = "Self"; converter = simulatorConverter { when (it) { "Self" -> "この装備"; "All" -> "全能力部位（基礎・技能を含む）"; else -> "指定部位" } } }
    val factor = numberField(1.0)
    val parts = StatsPart.entries.associateWith { CheckBox(it.display) }
    init {
        children.addAll(fieldRow("倍率", enabled, stat, target, factor), fieldRow("対象部位", *parts.values.toTypedArray()))
    }
    fun read() = ItemStatMultiplier(when (target.value) {
        "Self" -> StatsPartTarget.Self
        "All" -> StatsPartTarget.All
        else -> StatsPartTarget.Parts(parts.filterValues { it.isSelected }.keys)
    }, factor.number("倍率"))
}
