package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.attack.sequence.AttackRecord
import io.github.sushiericworkspace.combatcore.combat.attack.sequence.AttackRecordCategory
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.DamageEffectIntervalKey
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.DamageItemCooldownKey
import io.github.sushiericworkspace.combatcore.item.effect.ItemSpecialEffectRole
import javafx.collections.FXCollections
import javafx.scene.control.*
import javafx.scene.layout.VBox
import java.util.UUID

/** Coreの初期履歴とCDを手入力します。履歴の集計やsword-cycle判定は行いません。 */
internal class SimulatorStateEditor : VBox(8.0) {
    val history = mutableListOf<SimulatorHistoryRow>()
    val intervals = mutableListOf<SimulatorIntervalRow>()
    val cooldowns = mutableListOf<SimulatorCooldownRow>()
    val nextRecordId = TextField().apply { promptText = "空白=履歴最大IDの次" }
    private val historyBox = VBox(6.0)
    private val intervalBox = VBox(6.0)
    private val cooldownBox = VBox(6.0)
    init {
        children.addAll(Label("攻撃者の履歴は古い順。同tickでは適用順です。対象UUIDの空白は今回の対象です。"),
            historyBox, Button("履歴を追加").apply { setOnAction { addHistory() } }, fieldRow("次の履歴ID", nextRecordId),
            Label("固有間隔のKind/Effect型IDと、特殊効果の役割/提供元/effect IDはCoreの識別子を指定します。"),
            intervalBox, Button("固有間隔を追加").apply { setOnAction { addInterval() } },
            cooldownBox, Button("特殊効果CDを追加").apply { setOnAction { addCooldown() } })
    }
    fun addHistory(): SimulatorHistoryRow = SimulatorHistoryRow((history.mapNotNull { it.id.text.toLongOrNull()?.takeIf { value -> value >= 0L } }.maxOrNull() ?: 0L) + 1L).also { row ->
        history += row; historyBox.children += row
        row.remove.setOnAction { history.remove(row); historyBox.children.remove(row) }
    }
    fun addInterval(): SimulatorIntervalRow = SimulatorIntervalRow().also { row ->
        intervals += row; intervalBox.children += row
        row.remove.setOnAction { intervals.remove(row); intervalBox.children.remove(row) }
    }
    fun addCooldown(): SimulatorCooldownRow = SimulatorCooldownRow().also { row ->
        cooldowns += row; cooldownBox.children += row
        row.remove.setOnAction { cooldowns.remove(row); cooldownBox.children.remove(row) }
    }
    fun readHistory(targetId: UUID): List<AttackRecord> = history.map { it.read(targetId) }
    fun readIntervals() = intervals.associate { DamageEffectIntervalKey(it.kind.text, it.effect.text) to it.tick.tick("固有間隔tick") }
    fun readCooldowns() = cooldowns.associate { DamageItemCooldownKey(it.role.value, it.provider.text, it.effect.text) to it.tick.tick("効果CD期限tick") }
}

internal class SimulatorHistoryRow(recordId: Long) : VBox(5.0) {
    val target = TextField().apply { promptText = "対象UUID（空白=今回）" }
    val item = TextField().apply { promptText = "Item ID（空白=null）" }
    val tick = numberField(99)
    val id = numberField(recordId)
    val category = ComboBox(FXCollections.observableArrayList(AttackRecordCategory.entries)).apply { value = AttackRecordCategory.DIRECT_MELEE }
    val remove = Button("履歴削除")
    init { children.addAll(fieldRow("対象/Item ID", target, item), fieldRow("適用tick/履歴ID/分類", tick, id, category, remove)) }
    fun read(targetId: UUID) = AttackRecord(target.text.takeIf(String::isNotBlank)?.let(UUID::fromString) ?: targetId,
        item.text.takeIf(String::isNotBlank), tick.tick("履歴tick"), category.value, id.tick("履歴ID"))
}

internal class SimulatorIntervalRow : VBox(5.0) {
    val kind = TextField().apply { promptText = "Kind型ID" }
    val effect = TextField().apply { promptText = "Effect型ID" }
    val tick = numberField(99)
    val remove = Button("間隔削除")
    init { children.add(fieldRow("固有間隔", kind, effect, tick, remove)) }
}

internal class SimulatorCooldownRow : VBox(5.0) {
    val role = ComboBox(FXCollections.observableArrayList(ItemSpecialEffectRole.entries)).apply { value = ItemSpecialEffectRole.SOURCE }
    val provider = TextField().apply { promptText = "提供元key" }
    val effect = TextField().apply { promptText = "effect ID" }
    val tick = numberField(120)
    val remove = Button("CD削除")
    init { children.add(fieldRow("特殊効果CD", role, provider, effect, tick, remove)) }
}
