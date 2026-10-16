package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

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
    internal val slots = StatsPart.entries.filter { it.isEquipment }.associateWith { EquipmentSlot(it) }
    init { children.addAll(slots.values.map { it.pane }) }
    fun readInput() = SimulatorEquipmentInput(slots.mapNotNull { (part, slot) -> slot.readItem()?.let { part to it } }.toMap())
}

internal class EquipmentSlot(part: StatsPart) {
    val enabled = CheckBox("装備を使用")
    val itemId = TextField("manual_${part.name.lowercase()}")
    val type = ComboBox(FXCollections.observableArrayList(ItemType.entries)).apply {
        value = part.supportType.firstOrNull() ?: ItemType.SWORD
    }
    val stats = SimulatorNumberGrid(StatsType.entries, { it.display }, { 0.0 })
    val requirements = SimulatorNumberGrid(SkillType.entries, { it.name }, { 0 })
    private val upgrades: List<LevelledItemUpgradeDefinition> = MeleeSushiEricEnchantment.entries + ArmorCustomEnchantment.entries
    val levels = SimulatorNumberGrid(upgrades, { it.displayName }, { 0 })
    val honing = CheckBox("刃研ぎ")
    val multi = numberField(1)
    val arrowCount = numberField(1)
    val arrowEfficiency = numberField(1.0)
    private val reforges: List<ReforgeDefinition> = MeleeReforge.entries + BowReforge.entries + CrossbowReforge.entries + ArmorReforge.entries
    val reforge = ComboBox(FXCollections.observableArrayList(reforges)).apply { promptText = "リフォージなし" }
    private val blockCd = numberField(20)
    private val parryCd = numberField(20)
    private val multipliers = VBox(6.0)
    private val multiplierRows = mutableListOf<MultiplierRow>()
    internal fun addMultiplier(): MultiplierRow = MultiplierRow().also { row -> multiplierRows += row; multipliers.children += row }
    val pane = TitledPane(part.display, VBox(8.0,
        fieldRow("種類/ID", enabled, type, itemId),
        fieldRow("盾 Block/Parry CD", blockCd, parryCd),
        fieldRow("弓multi / クロスボウ本数/矢消費効率", multi, arrowCount, arrowEfficiency),
        fieldRow("リフォージ", reforge, Button("リフォージ解除").apply { setOnAction { reforge.value = null } }),
        TitledPane("装備の固定値（基礎能力には加算しない）", stats).apply { isExpanded = false },
        TitledPane("必要技能レベル", requirements).apply { isExpanded = false },
        TitledPane("強化レベル", levels).apply { isExpanded = false }, honing,
        TitledPane("倍率（Self/All/指定部位）", VBox(6.0, multipliers, Button("倍率を追加").apply {
            setOnAction { addMultiplier() }
        })).apply { isExpanded = false }
    )).apply { isExpanded = false }

    fun readItem(): SimulatorEquippedItem? {
        if (!enabled.isSelected) return null
        val content = when (type.value) {
            ItemType.SHIELD -> ShieldData(blockCooldown = blockCd.tick("盾Block CD"), parryCooldown = parryCd.tick("盾Parry CD"))
            ItemType.BOW -> BowData(multi = multi.integer("弓multi"))
            ItemType.SHORT_BOW -> ShortBowData(multi = multi.integer("弓multi"))
            ItemType.CROSSBOW -> CrossbowData(arrowCount = arrowCount.integer("クロスボウ本数"), arrowEfficiency = arrowEfficiency.number("矢消費効率"))
            else -> type.value.createContent()
        }
        val item = ItemBaseData(id = itemId.text, itemDetail = ItemDetail(content = content),
            stats = stats.values(), skillLevelRequirements = requirements.fields.mapValues { (key, field) -> field.integer(key.name) },
            statMultipliers = multiplierRows.filter { it.enabled.isSelected }.groupBy({ it.stat.value }, { it.read() }))
        return SimulatorEquippedItem(item, levels.fields.mapValues { (key, field) -> field.integer(key.displayName) }.filterValues { it != 0 },
            buildMap {
                if (honing.isSelected) put(Honing.category, Honing)
                reforge.value?.let { put(it.category, it) }
            })
    }
}

internal class MultiplierRow : VBox(5.0) {
    val enabled = CheckBox("有効").apply { isSelected = true }
    val stat = ComboBox(FXCollections.observableArrayList(StatsType.entries)).apply { value = StatsType.PHYSICS_DAMAGE }
    val target = ComboBox(FXCollections.observableArrayList("Self", "All", "Parts")).apply { value = "Self" }
    val factor = numberField(1.0)
    val parts = StatsPart.entries.associateWith { CheckBox(it.display) }
    init {
        children.addAll(fieldRow("倍率", enabled, stat, target, factor), fieldRow("Parts対象", *parts.values.toTypedArray()))
    }
    fun read() = ItemStatMultiplier(when (target.value) {
        "Self" -> StatsPartTarget.Self
        "All" -> StatsPartTarget.All
        else -> StatsPartTarget.Parts(parts.filterValues { it.isSelected }.keys)
    }, factor.number("倍率"))
}
