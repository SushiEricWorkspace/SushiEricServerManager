package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.attack.snapshot.AttackSnapshot
import io.github.sushiericworkspace.combatcore.combat.damage.preparation.DamagePreparationValues
import io.github.sushiericworkspace.combatcore.combat.damage.resource.DamageResourceSource
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.*
import io.github.sushiericworkspace.combatcore.combat.guard.MobParryInput
import io.github.sushiericworkspace.combatcore.item.effect.ResolvedPreparationEffect
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.DynamicStatsType
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.StatsType
import java.util.Collections
import java.util.UUID

/**
 * 行作成時だけIDを生成します。並べ替え・再計算・copy・比較では[hitId]を保持し、削除IDを再利用しません。
 * [cooldownTicks]は利用側で解決した固有間隔です。通常の直接投射物は0です。
 * 矢種・rank・発射地点からの距離は[attack]、発射時の記録は[launchSnapshot]へ指定します。
 */
data class SimulatorHit(
    val attack: SimulatorAttack,
    val matched: Boolean = true,
    val cooldownTicks: Int = 0,
    val launchSnapshot: AttackSnapshot? = null,
    val hitId: String = UUID.randomUUID().toString(),
    /** trueではOverHealを消費せずHPへ適用します。 */
    val pierceOverHeal: Boolean = false
)

/** 組立不能な入力ではCore用の部分入力を公開しません。 */
sealed interface SimulatorMultipleHitInput {
    class Ready internal constructor(
        val equipment: SimulatorEquipmentResult.Ready,
        val position: SimulatorPosition,
        baseStats: Map<StatsType, Double>,
        val mob: SimulatorMobInput,
        hits: List<SimulatorHit>,
        preparedInputs: List<SimulatorInput>,
        val currentTick: Long,
        val targetX: Double,
        val targetZ: Double,
        val shotPlan: SimulatorShotPlan?,
        private val coreTemplate: SameTickDamageInput
    ) : SimulatorMultipleHitInput {
        val baseStats: Map<StatsType, Double> = Collections.unmodifiableMap(baseStats.toMap())
        val hits: List<SimulatorHit> = Collections.unmodifiableList(hits.toList())
        val preparedInputs: List<SimulatorInput> = Collections.unmodifiableList(preparedInputs.toList())
        val matchedCount: Int = hits.count { it.matched }
        /** Core型のcollectionは値コピーで返し、変更しても要求の固定値に反映しません。 */
        val initialState: SameTickDamageState get() = coreTemplate.initialState.valueCopy()

        /** 同moduleの比較・実行側にも保存中の参照を渡さず、実行ごとに隔離します。 */
        internal fun coreInput(): SameTickDamageInput = SameTickDamageInput(currentTick, coreTemplate.initialState.valueCopy(),
            coreTemplate.hits.map { hit -> SameTickDamageHit(hit.hitId, hit.attack, hit.cooldownTicks, hit.matched,
                hit.pierceOverHeal, hit.attackSnapshot, hit.sourceItemData?.deepCopy(), hit.specialEffects,
                hit.sourceUpgrades, hit.targetUpgrades) }, targetX, targetZ, coreTemplate.playerGuard,
            coreTemplate.mobParry, coreTemplate.sourceAvailable)
    }

    data class Unsupported(val reason: String) : SimulatorMultipleHitInput
}

/**
 * 同じ技能・装備判定で全行を固定します。baseStatsと装備は指定時点の固定能力です。
 * 現在HP/OverHeal/ST/MP・構え/CD・履歴/BREAKは[initialState]だけを正本とします。
 * mob.currentHpは単発用の記録であり、この経路の適用量はinitialStateから計算します。
 * launchSnapshotは別に保持し、現在値や式の能力を暗黙に置き換えません。
 * [extraEffects]は装備由来を含めない追加効果です。装備はactiveUpgrades経由でCoreへ一度だけ渡します。
 * [shotPlan]指定時は未命中も含む全発射行を渡し、行数と発射本数を一致させてください。
 */
fun SimulatorEquipmentResult.Ready.multipleHitInput(
    position: SimulatorPosition,
    baseStats: Map<StatsType, Double>,
    mob: SimulatorMobInput,
    currentTick: Long,
    initialState: SameTickDamageState,
    hits: List<SimulatorHit>,
    targetX: Double,
    targetZ: Double,
    mobParry: MobParryInput? = null,
    extraEffects: List<ResolvedPreparationEffect> = emptyList(),
    shotPlan: SimulatorShotPlan? = null,
    sourceAvailable: Boolean = true
): SimulatorMultipleHitInput {
    fun unsupported(reason: String) = SimulatorMultipleHitInput.Unsupported(reason)
    if (hits.map { it.hitId }.distinct().size != hits.size || hits.any { it.hitId.isBlank() })
        return unsupported("命中IDは空白にせず、行ごとに一意にしてください。")
    if (hits.map { it.attack.playerIsSource }.distinct().size > 1)
        return unsupported("同じ攻撃方向・攻撃者・対象の命中だけを指定してください。")
    if (hits.isNotEmpty() && position.worldId != mob.snapshot.worldId)
        return unsupported("攻撃者と対象のworldを一致させてください。")
    if (shotPlan != null && (shotPlan.projectileCount != hits.size || hits.any {
            it.attack !is SimulatorAttack.PlayerProjectile && it.attack != SimulatorAttack.MobProjectile }))
        return unsupported("射撃計画の発射本数と、未命中を含む投射物の行数を一致させてください。")
    for (hit in hits) {
        val sourceId = if (hit.attack.playerIsSource) position.id else mob.snapshot.entityUuid
        val targetId = if (hit.attack.playerIsSource) mob.snapshot.entityUuid else position.id
        if (hit.launchSnapshot?.let { it.attacker?.entityUuid != sourceId || it.target.entityUuid != targetId } == true)
            return unsupported("発射Snapshotの当事者が命中入力と一致しません。")
    }
    if (offhandFollowUp != null && hits.any { it.attack is SimulatorAttack.PlayerMelee })
        return unsupported("オフハンド短剣の追撃予約は未対応です。")
    val state = initialState.valueCopy()
    val prepared = hits.map { hit ->
        val player = if (hit.attack.playerIsSource) state.source else state.target
        val dynamic = mapOf(DynamicStatsType.HEALTH to player.health, DynamicStatsType.OVER_HEAL to player.overHeal,
            DynamicStatsType.STAMINA to player.resources.stamina, DynamicStatsType.MANA to player.resources.mana)
        val resource = if (hit.attack.playerIsSource) {
            if (sourceAvailable) DamageResourceSource.Player(state.source.resources.stamina, state.source.resources.mana)
            else DamageResourceSource.UnavailablePlayer
        } else DamageResourceSource.NonPlayer
        val values = DamagePreparationValues(currentTick, resource, targetX, targetZ,
            sourceAttackRecords = state.sourceAttackRecords, targetIsBreaking = state.targetBreakActive,
            mobParry = mobParry, specialEffects = extraEffects)
        singleHitInput(position, baseStats, dynamic, mob, hit.attack, values,
            SimulatorGuardState(player.raising, player.blockUntilTick, player.parryUntilTick), hit.launchSnapshot)
    }
    val fixedHits = hits.mapIndexed { index, hit -> hit.copy(launchSnapshot = prepared[index].launchSnapshot) }
    val coreHits = fixedHits.mapIndexed { index, hit ->
        val input = prepared[index]
        SameTickDamageHit(hit.hitId, input.attack, hit.cooldownTicks, hit.matched,
            pierceOverHeal = hit.pierceOverHeal, attackSnapshot = input.launchSnapshot, sourceItemData = input.preparation.sourceItemData?.deepCopy(),
            specialEffects = extraEffects,
            sourceUpgrades = if (hit.attack.playerIsSource) activeUpgrades else emptyList(),
            targetUpgrades = if (hit.attack.playerIsSource) emptyList() else activeUpgrades)
    }
    val core = SameTickDamageInput(currentTick, state, coreHits, targetX, targetZ,
        prepared.firstOrNull()?.preparation?.playerGuard, mobParry, sourceAvailable)
    return SimulatorMultipleHitInput.Ready(this, position, baseStats, mob, fixedHits, prepared,
        currentTick, targetX, targetZ, shotPlan, core)
}

private fun SameTickDamageState.valueCopy() = SameTickDamageState(source, target, targetBreak,
    SameTickDamageIntervals(intervals.effectTicks, intervals.directTick, intervals.projectileTick),
    itemCooldowns, sourceAttackRecords, nextRecordId, targetBreakActive)
