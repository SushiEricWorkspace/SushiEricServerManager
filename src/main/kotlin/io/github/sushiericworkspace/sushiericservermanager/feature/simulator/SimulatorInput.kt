package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.attack.snapshot.AttackSnapshot
import io.github.sushiericworkspace.combatcore.combat.damage.kind.*
import io.github.sushiericworkspace.combatcore.combat.damage.kind.effect.*
import io.github.sushiericworkspace.combatcore.combat.damage.preparation.DamagePreparationValues
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.DamageSimulationAttack
import io.github.sushiericworkspace.combatcore.item.custom.base.itemtype.arrow.ArrowType
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.DynamicStatsType
import java.util.Collections

/** 両方向の直接攻撃だけを受け付け、KindとEffectの対応を構築時に固定します。 */
sealed class SimulatorAttack {
    internal abstract fun create(player: SimulatorPlayerInput, mob: SimulatorMobInput): DamageSimulationAttack
    internal abstract val playerIsSource: Boolean

    data class PlayerMelee(val effect: PlayerToMobMeleeEffects.Direct, val vanillaCritical: Boolean = false) : SimulatorAttack() {
        override val playerIsSource = true
        override fun create(player: SimulatorPlayerInput, mob: SimulatorMobInput) = DamageSimulationAttack.PlayerToMob(
            player.snapshot, mob.snapshot, PlayerToMobDamageKind.Melee, effect, isVanillaCritical = vanillaCritical)
    }

    data class PlayerProjectile(val arrowType: ArrowType?, val rank: Int?, val distance: Double?) : SimulatorAttack() {
        override val playerIsSource = true
        override fun create(player: SimulatorPlayerInput, mob: SimulatorMobInput) = DamageSimulationAttack.PlayerToMob(
            player.snapshot, mob.snapshot, PlayerToMobDamageKind.Projectile, PlayerToMobProjectileEffects.Direct,
            projectileFalloffDistance = distance, arrowType = arrowType, arrowRank = rank)
    }

    data class PlayerMagic(val effect: PlayerToMobMagicEffects.Direct = PlayerToMobMagicEffects.Direct()) : SimulatorAttack() {
        override val playerIsSource = true
        override fun create(player: SimulatorPlayerInput, mob: SimulatorMobInput) = DamageSimulationAttack.PlayerToMob(
            player.snapshot, mob.snapshot, PlayerToMobDamageKind.Magic, effect)
    }

    data object MobMelee : SimulatorAttack() {
        override val playerIsSource = false
        override fun create(player: SimulatorPlayerInput, mob: SimulatorMobInput) = DamageSimulationAttack.MobToPlayer(
            mob.snapshot, player.snapshot, MobToPlayerDamageKind.Melee, MobToPlayerMeleeEffects.Direct)
    }

    data object MobProjectile : SimulatorAttack() {
        override val playerIsSource = false
        override fun create(player: SimulatorPlayerInput, mob: SimulatorMobInput) = DamageSimulationAttack.MobToPlayer(
            mob.snapshot, player.snapshot, MobToPlayerDamageKind.Projectile, MobToPlayerProjectileEffects.Direct)
    }

    data object MobMagic : SimulatorAttack() {
        override val playerIsSource = false
        override fun create(player: SimulatorPlayerInput, mob: SimulatorMobInput) = DamageSimulationAttack.MobToPlayer(
            mob.snapshot, player.snapshot, MobToPlayerDamageKind.Magic, MobToPlayerMagicEffects.Direct)
    }
}

/**
 * 単発要求の固定入力です。[preparation]は現在tick・リソース・BREAK・ガード/パリィ/CD・履歴を持ちます。
 * [launchSnapshot]は発射時の情報だけを固定し、式には[player]/[mob]の指定時点のSnapshotを使用します。
 * 発射時能力で式を評価する場合は、その時点で生成した[player]/[mob]を明示してください。
 * ItemBaseDataはdeepCopyし、履歴・効果List・発射時Mob攻撃Mapも複製します。
 * specialEffectsの効果定義はCoreと同じ純粋かつ不変の定義を渡してください。実環境への効果は適用せず要求として結果に残します。
 */
class SimulatorInput(
    val player: SimulatorPlayerInput,
    val mob: SimulatorMobInput,
    attack: SimulatorAttack,
    preparation: DamagePreparationValues,
    launchSnapshot: AttackSnapshot? = null
) {
    val attackSpec = attack
    internal val attack = attack.create(player, mob)
    internal val preparation = DamagePreparationValues(
        preparation.currentTick, preparation.sourceResource, preparation.targetX, preparation.targetZ,
        preparation.sourceItemData?.deepCopy(), preparation.sourceAttackRecords, preparation.targetIsBreaking,
        preparation.playerGuard?.let { it.copy(shield = it.shield.copy()) }, preparation.mobParry,
        preparation.offhandFollowUp, preparation.specialEffects
    )
    internal val launchSnapshot = launchSnapshot?.copy(
        attacker = launchSnapshot.attacker?.let { it.copy(attackDamages = Collections.unmodifiableMap(it.attackDamages.toMap())) },
        target = launchSnapshot.target.copy(attackDamages = Collections.unmodifiableMap(launchSnapshot.target.attackDamages.toMap()))
    )
    val sourceHp = if (attack.playerIsSource) player.snapshot.requireDynamicStats()[
        DynamicStatsType.HEALTH] else mob.currentHp
    val targetHp = if (attack.playerIsSource) mob.currentHp else player.snapshot.requireDynamicStats()[
        DynamicStatsType.HEALTH]
}
