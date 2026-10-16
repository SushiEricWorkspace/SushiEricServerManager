package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.attack.sequence.AttackRecord
import io.github.sushiericworkspace.combatcore.combat.damage.preparation.*
import io.github.sushiericworkspace.combatcore.combat.damage.resource.DamageResourceCost
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.SameTickCombatantState
import io.github.sushiericworkspace.combatcore.combat.random.DamageRollRecord

private val resultLabels = mapOf(
    "APPLIED" to "適用済み", "REJECTED" to "条件不成立", "MISSED" to "未命中", "TARGET_DEAD" to "対象は死亡済み",
    "NOT_APPLIED" to "未適用", "DEAD" to "死亡", "INVALID_ENTITY" to "当事者の状態が無効",
    "INVALID_CONTEXT" to "攻撃条件の組合せが無効", "INVALID_DAMAGE" to "ダメージ値が無効",
    "INVALID_RESOURCE_POLICY" to "消費条件が無効", "MISSING_REQUIRED_DATA" to "必要なデータがない",
    "COOLDOWN" to "再使用待ち", "EFFECT_CONDITION_FAILED" to "効果の条件を満たさない", "GUARDED" to "完全防御",
    "INSUFFICIENT_RESOURCE" to "スタミナ・マナ不足", "UNSUPPORTED_CONTEXT" to "未対応の攻撃条件",
    "BEFORE_RESOURCE" to "消費判定前", "AFTER_RESOURCE" to "消費判定後", "BEFORE_CRITICAL" to "会心判定前",
    "AFTER_CRITICAL" to "会心判定後", "BEFORE_DEFENCE" to "防御計算前", "AFTER_DEFENCE" to "防御計算後",
    "BEFORE_COMPLETE" to "最終補正", "CRITICAL" to "会心", "MOB_PARRY" to "Mobパリィ", "PLAYER_BLOCK" to "盾防御",
    "PLAYER_PARRY" to "プレイヤーパリィ", "STAMINA" to "スタミナ", "MANA" to "マナ",
    "SOURCE" to "攻撃者", "TARGET" to "対象", "DIRECT_MELEE" to "直接近接", "DERIVED_MELEE" to "派生近接",
    "MELEE" to "近接", "PROJECTILE" to "投射物", "MAGIC" to "魔法", "AILMENT" to "状態異常", "OTHER" to "その他",
    "BOW" to "弓", "STANDARD_CROSSBOW" to "通常クロスボウ", "MANUAL" to "手動"
)

internal fun simulatorLabel(value: Enum<*>?): String = value?.let { resultLabels.getValue(it.name) } ?: "なし"
internal fun yesNo(value: Boolean) = if (value) "はい" else "いいえ"
internal fun phaseDetails(values: List<DamagePreparationPhaseTrace>) = values.joinToString(" / ") {
    "${simulatorLabel(it.phase)}: ${it.damageBefore} → ${it.damageAfter}"
}.ifEmpty { "なし" }
internal fun costDetails(values: List<DamageResourceCost>) = values.joinToString(" / ") {
    "${simulatorLabel(it.type)} ${it.amount}"
}.ifEmpty { "なし" }
internal fun recordDetails(value: AttackRecord?): String = value?.let {
    "履歴ID=${it.recordId}, 対象=${it.targetUuid}, 装備ID=${it.itemDataId ?: "なし"}, 適用時刻=${it.appliedTick}, ${simulatorLabel(it.category)}"
} ?: "なし"
internal fun actorDetails(value: SameTickCombatantState) =
    "HP=${value.health}, 追加HP(OH)=${value.overHeal}, スタミナ=${value.resources.stamina}, マナ=${value.resources.mana}, 盾の構え=${yesNo(value.raising)}, 防御再使用時刻=${value.blockUntilTick}, パリィ再使用時刻=${value.parryUntilTick}"
internal fun drawDetails(record: DamageRollRecord?): String = when (record) {
    null -> "対応する抽選なし"
    is DamageRollRecord.NotReached -> "${simulatorLabel(record.kind)}: 判定未到達"
    is DamageRollRecord.Reached -> "${simulatorLabel(record.kind)} #${record.occurrence}: 確率(0..1)=${record.probability} / 抽選値(0以上1未満)=${record.unitValue} / 成立=${yesNo(record.succeeded)}"
}
internal fun preparationEffectDetails(value: DamagePreparationEffect): String = when (value) {
    is DamagePreparationEffect.ItemCooldown -> "効果の再使用待ち: ${simulatorLabel(value.role)}, 提供元=${value.providerKey}, 効果ID=${value.effectId}, ${value.part.display}, 待ち時間=${value.cooldownSeconds}秒"
    is DamagePreparationEffect.Guard -> "${simulatorLabel(value.kind)}: 防御割合=${value.outcome.rate}, 判定元=${value.outcome.originalDamage}, 残り=${value.outcome.remainingDamage}, スタミナ消費=${value.outcome.consumedStamina}, 待ち時間=${value.cooldownTicks}tick, 再使用時刻=${value.cooldownUntilTick}, " + when (val movement = value.knockback) {
        GuardKnockback.None -> "ノックバックなし"
        is GuardKnockback.Apply -> "ノックバック強度=${movement.strength}"
        is GuardKnockback.ReserveMultiplier -> "次回ノックバック倍率=${movement.multiplier}"
    }
}
