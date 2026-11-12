package io.github.sushiericworkspace.sushiericservermanager.editor.main.ore

import io.github.sushiericworkspace.sushiericdatamodel.data.ore.OreMiningDuration
import io.github.sushiericworkspace.sushiericservermanager.ui.AppTooltip
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.Region
import javafx.scene.layout.GridPane
import javafx.scene.layout.VBox

internal fun miningTimeText(hardnessText: String, requiredTierText: String, speedText: String, tierText: String): String {
    val hardness = hardnessText.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    val requiredTier = requiredTierText.toIntOrNull()?.takeIf { it >= 0 }
    val speed = speedText.toDoubleOrNull()?.takeIf(Double::isFinite)
    val tier = tierText.toIntOrNull()?.takeIf { it >= 0 }
    if (hardness == null || requiredTier == null || speed == null || tier == null) {
        return "未算出：硬度・要求Tier・最終採掘速度・ツルハシTierに有効な値を入力してください。"
    }
    if (speed <= 0.0) return "採掘不可：最終採掘速度は0より大きい値が必要です。"
    if (tier < requiredTier) return "採掘不可：ツルハシTierが要求Tierを満たしていません。"
    val ticks = checkNotNull(OreMiningDuration.calculate(hardness, speed))
    return "必要時間：${ticks}tick / ${ticks / 20.0}秒（20TPS換算）"
}

/** 編集データを変更せず、入力中の硬度・Tierと一時条件から採掘時間を表示します。 */
internal class OreMiningPreview(hardness: TextField, requiredTier: TextField) : VBox(8.0) {
    private val speed = TextField().apply { id = "mining-speed"; promptText = "装備などを反映した最終値"; prefWidth = 180.0 }
    private val tier = TextField("0").apply { id = "mining-tier"; prefWidth = 180.0 }
    private val result = Label().apply { id = "mining-time"; isWrapText = true; minHeight = Region.USE_PREF_SIZE }

    init {
        id = "mining-preview"
        val inputs = GridPane().apply {
            hgap = 12.0; vgap = 8.0
            add(Label("最終採掘速度：").apply { id = "mining-speed-label"; minWidth = Region.USE_PREF_SIZE }, 0, 0); add(speed, 1, 0)
            add(Label("ツルハシTier：").apply { id = "mining-tier-label"; minWidth = Region.USE_PREF_SIZE }, 0, 1); add(tier, 1, 1)
        }
        val formula = Label("必要tick = 硬度 × 30 ÷ 最終採掘速度を切り上げ（最低1tick）\n秒 = 必要tick ÷ 20").apply {
            id = "mining-formula"
            isWrapText = true
            minHeight = Region.USE_PREF_SIZE
            tooltip = AppTooltip.create("必要tickの上限は${OreMiningDuration.MAX_TICKS}です。計算途中で上限を超える場合も上限値を使用します。")
        }
        children.addAll(Label("採掘時間の目安"), inputs, result, formula,
            Label("技能条件などを満たす独自ツルハシで独自鉱石を採掘する目安です。装備などを反映した最終採掘速度を入力してください。TPS低下による遅延は含みません。").apply { id = "mining-conditions"; isWrapText = true; minHeight = Region.USE_PREF_SIZE })
        fun refresh() { result.text = miningTimeText(hardness.text, requiredTier.text, speed.text, tier.text) }
        listOf(hardness, requiredTier, speed, tier).forEach { field -> field.textProperty().addListener { _, _, _ -> refresh() } }
        refresh()
    }
}
