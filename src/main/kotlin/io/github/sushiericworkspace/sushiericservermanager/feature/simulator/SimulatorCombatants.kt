package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.attack.snapshot.*
import io.github.sushiericworkspace.combatcore.combat.damage.stats.PlayerDamageStatsSnapshot
import io.github.sushiericworkspace.combatcore.entity.definition.MobAttackMeans
import io.github.sushiericworkspace.combatcore.player.skill.SkillStatsSource
import io.github.sushiericworkspace.combatcore.player.skill.effect.SkillLevelSnapshot
import io.github.sushiericworkspace.combatcore.stats.*
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemType
import io.github.sushiericworkspace.sushiericdatamodel.stats.entity.EntityStatsType
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.*
import java.util.Collections
import java.util.UUID

private fun <K, V> fixedMap(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(values.toMap())

private fun StatModifier<StatsPart>.fixed(): StatModifier<StatsPart> = copy(
    multipliers = Collections.unmodifiableList(multipliers.map { multiplier ->
        multiplier.copy(target = when (val target = multiplier.target) {
            StatsPartTarget.Self -> StatsPartTarget.Self
            StatsPartTarget.All -> StatsPartTarget.All
            is StatsPartTarget.Parts -> StatsPartTarget.Parts(Collections.unmodifiableSet(target.parts.toSet()))
        })
    })
)

private fun StatsPartSource<StatsPart, StatsType>.fixed() = StatsPartSource(
    allStats = allStats.fixed(), stats = fixedMap(stats.mapValues { (_, modifier) -> modifier.fixed() })
)

internal fun copyEquipmentSources(
    sources: Map<StatsPart, Map<String, StatsPartSource<StatsPart, StatsType>>>
): Map<StatsPart, Map<String, StatsPartSource<StatsPart, StatsType>>> =
    fixedMap(sources.mapValues { (_, entries) -> fixedMap(entries.mapValues { (_, source) -> source.fixed() }) })

/** 計算対象の識別子と姿勢。実Entityや画面への参照を持ちません。 */
data class SimulatorPosition(
    val id: UUID,
    val name: String,
    val worldId: String,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0,
    val yaw: Float = 0f,
    val pitch: Float = 0f
) {
    internal fun snapshot(
        player: PlayerStatsSnapshot? = null,
        damage: PlayerDamageStatsSnapshot? = null,
        dynamic: DynamicStatsSnapshot? = null,
        entity: EntityStatsSnapshot? = null,
        attacks: Map<MobAttackMeans, Double> = emptyMap(),
        skills: SkillLevelSnapshot? = null
    ) = CombatantSnapshot(id, name, worldId, x, y, z, yaw, pitch, player, damage, dynamic, entity, attacks, skills)
}

/**
 * スキル・装備を含まない基礎固定値と、部位寄与へ未集計の装備sourceから生成する固定入力です。
 * [equipmentSources]にはItemStatsCalculationのSelf適用済み固定値と、All/Parts未適用の倍率を渡します。
 * StatsPartCalculatorの集計済み取り分を渡さないでください。BASE/SKILLのsourceは受け付けません。
 * 基礎値は全StatsTypeを明示し、未指定値の補完や補正済み最終値の入力経路は設けません。
 * 生成時に全入力を複製し、比較用の入力記録とSnapshotを固定します。元Map・source・倍率Listの変更を参照しません。
 * [snapshot]のPart別取り分と合計は表示用にも利用できます。
 */
class SimulatorPlayerInput(
    position: SimulatorPosition,
    baseStats: Map<StatsType, Double>,
    skillLevels: Map<SkillType, Int>,
    equipmentSources: Map<StatsPart, Map<String, StatsPartSource<StatsPart, StatsType>>>,
    itemTypes: Map<StatsPart, ItemType?>,
    dynamicStats: Map<DynamicStatsType, Double>
) {
    val baseStats = fixedMap(baseStats)
    val skillLevels = fixedMap(skillLevels)
    private val fixedEquipmentSources = copyEquipmentSources(equipmentSources)
    /** 公開sourceのMap変更で固定入力の説明値とSnapshotを食い違わせない値コピーです。 */
    val equipmentSources get() = copyEquipmentSources(fixedEquipmentSources)
    val itemTypes = fixedMap(itemTypes)
    val dynamicStats = fixedMap(dynamicStats)
    val snapshot: CombatantSnapshot

    init {
        require(baseStats.keys.containsAll(StatsType.entries)) { "基礎能力は全StatsTypeを指定してください。" }
        require(dynamicStats.keys.containsAll(DynamicStatsType.entries)) { "現在値は全DynamicStatsTypeを指定してください。" }
        require(equipmentSources.keys.all { it.isEquipment }) { "装備sourceは装備Partだけを指定してください。" }
        require(itemTypes.keys.all { it.isEquipment }) { "ItemTypeは装備Partだけを指定してください。" }
        val skills = SkillLevelSnapshot(this.skillLevels)
        val parts = fixedEquipmentSources.toMutableMap()
        parts[StatsPart.SKILL] = buildMap {
            SkillType.entries.forEach { skill ->
                SkillStatsSource.sourceOf(skill, skills.level(skill))?.let { put(SkillStatsSource.sourceId(skill), it) }
            }
        }
        val calculations = StatsType.entries.associateWith { type ->
            StatsPartCalculator.calculate(parts, type, initialFlats = mapOf(StatsPart.BASE to this.baseStats.getValue(type)))
        }
        val damage = PlayerDamageStatsSnapshot(
            StatsPart.entries.associateWith { part -> calculations.mapValues { (_, value) -> value.valueOf(part) } }, this.itemTypes
        )
        snapshot = position.snapshot(
            player = PlayerStatsSnapshot.create(damage::getTotalStat), damage = damage,
            dynamic = DynamicStatsSnapshot.create(this.dynamicStats::getValue), skills = skills
        )
    }
}

/** Mobの全能力・攻撃手段と現在HPを手入力し、生成時に複製して固定します。 */
class SimulatorMobInput(
    position: SimulatorPosition,
    stats: Map<EntityStatsType, Double>,
    attackDamages: Map<MobAttackMeans, Double>,
    val currentHp: Double
) {
    val stats = fixedMap(stats)
    val attackDamages = fixedMap(attackDamages)
    val snapshot: CombatantSnapshot

    init {
        require(stats.keys.containsAll(EntityStatsType.entries)) { "Mob能力は全EntityStatsTypeを指定してください。" }
        snapshot = position.snapshot(entity = EntityStatsSnapshot.create(this.stats::getValue),
            attacks = this.attackDamages)
    }
}
