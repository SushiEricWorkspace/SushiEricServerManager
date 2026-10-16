package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.damage.kind.effect.PlayerToMobMeleeEffects
import io.github.sushiericworkspace.combatcore.combat.attack.snapshot.AttackSnapshot
import io.github.sushiericworkspace.combatcore.item.custom.base.itemtype.arrow.ArrowType
import io.github.sushiericworkspace.combatcore.item.effect.cost.ItemResourceCostCatalog
import javafx.collections.FXCollections
import javafx.scene.control.*
import javafx.scene.layout.VBox
import java.util.UUID

internal enum class SimulatorAttackKind(private val label: String) {
    MELEE("近接"), PROJECTILE("投射物"), MAGIC("魔法");
    override fun toString(): String = label
}

/** 行自身がhitIdを保持し、移動や再実行では作り直しません。 */
internal class SimulatorHitEditor : VBox(6.0) {
    val hitId: String = UUID.randomUUID().toString()
    val kind = ComboBox(FXCollections.observableArrayList(SimulatorAttackKind.entries)).apply { value = SimulatorAttackKind.PROJECTILE }
    val matched = CheckBox("命中").apply { isSelected = true }
    val vanillaCritical = CheckBox("バニラ会心")
    val pierceOverHeal = CheckBox("OH貫通")
    val arrow = ComboBox(FXCollections.observableArrayList(listOf("指定なし") + ArrowType.entries.map { it.id })).apply { value = "指定なし" }
    val rank = TextField().apply { promptText = "rankなし=空白"; prefColumnCount = 7 }
    val distance = TextField().apply { promptText = "距離なし=空白"; prefColumnCount = 9 }
    val cooldown = numberField(0)
    val up = Button("上へ")
    val down = Button("下へ")
    val remove = Button("削除")
    val captureLaunch = Button("現在入力で発射状態を固定")
    val clearLaunch = Button("発射状態を解除")
    val launchBaseDamage = numberField(0)
    private val launchStatus = Label("発射Snapshotなし")
    var launchSnapshot: AttackSnapshot? = null
        private set
    fun setLaunch(snapshot: AttackSnapshot?) {
        launchSnapshot = snapshot
        launchStatus.text = snapshot?.let { "発射tick=${it.createdTick}（現在能力と独立）" } ?: "発射Snapshotなし"
    }
    init {
        children.addAll(fieldRow("hitId=$hitId", up, down, remove),
            fieldRow("直接攻撃", kind, matched, vanillaCritical, pierceOverHeal),
            fieldRow("矢種/rank/距離/固有間隔tick", arrow, rank, distance, cooldown),
            fieldRow("発射基本値", launchBaseDamage, captureLaunch, clearLaunch), launchStatus)
    }
    fun read(playerIsSource: Boolean): SimulatorHit {
        val attack = if (playerIsSource) when (kind.value) {
            SimulatorAttackKind.MELEE -> SimulatorAttack.PlayerMelee(PlayerToMobMeleeEffects.Direct(null, ItemResourceCostCatalog.normalDirectMelee(null)), vanillaCritical.isSelected)
            SimulatorAttackKind.PROJECTILE -> SimulatorAttack.PlayerProjectile(ArrowType.entries.firstOrNull { it.id == arrow.value },
                rank.text.takeIf { it.isNotBlank() }?.let { rank.integer("矢rank") },
                distance.text.takeIf { it.isNotBlank() }?.let { distance.number("投射距離") })
            SimulatorAttackKind.MAGIC -> SimulatorAttack.PlayerMagic()
        } else when (kind.value) {
            SimulatorAttackKind.MELEE -> SimulatorAttack.MobMelee
            SimulatorAttackKind.PROJECTILE -> SimulatorAttack.MobProjectile
            SimulatorAttackKind.MAGIC -> SimulatorAttack.MobMagic
        }
        val interval = cooldown.integer("固有間隔tick")
        require(interval >= 0) { "固有間隔tickは0以上を入力してください。" }
        return SimulatorHit(attack, matched.isSelected, interval, launchSnapshot, hitId, pierceOverHeal.isSelected)
    }
}
