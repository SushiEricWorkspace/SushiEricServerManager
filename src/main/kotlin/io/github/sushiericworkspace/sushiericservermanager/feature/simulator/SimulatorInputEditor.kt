package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.breaking.BreakRuntime
import io.github.sushiericworkspace.combatcore.combat.breaking.BreakState
import io.github.sushiericworkspace.combatcore.combat.breaking.BreakStateRule
import io.github.sushiericworkspace.combatcore.combat.attack.snapshot.AttackSnapshot
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.*
import io.github.sushiericworkspace.combatcore.combat.damage.state.DamageResources
import io.github.sushiericworkspace.combatcore.combat.guard.MobParryInput
import io.github.sushiericworkspace.combatcore.entity.definition.MobAttackMeans
import io.github.sushiericworkspace.combatcore.player.skill.effect.SkillLevelSnapshot
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.detail.BowContent
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.detail.CrossbowData
import io.github.sushiericworkspace.sushiericdatamodel.stats.entity.EntityStatsType
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.*
import javafx.collections.FXCollections
import javafx.scene.control.*
import javafx.scene.layout.VBox
import java.util.UUID

/** 手動入力専用のNode。現在の文字列を固定入力へ変換し、保存/取込/計算は行いません。 */
internal class SimulatorInputEditor(private val onInputError: (IllegalArgumentException) -> Unit = { throw it }) : VBox(10.0) {
    val playerIsSource = CheckBox("プレイヤー → Mob（解除でMob → プレイヤー）").apply { isSelected = true }
    val sample = CheckBox("seed付きサンプル（解除で厳密期待値）")
    val seed = numberField(1)
    val tick = numberField(100)
    val baseStats = SimulatorNumberGrid(StatsType.entries, { it.display }, {
        when (it) { StatsType.MAX_HEALTH -> 1000.0; StatsType.PHYSICS_DAMAGE -> 95.0; else -> it.default }
    })
    val skills = SimulatorNumberGrid(SkillType.entries, { it.name }, { 0 })
    val mobStats = SimulatorNumberGrid(EntityStatsType.entries, { it.display }, {
        if (it == EntityStatsType.MAX_HEALTH) 1000.0 else it.default
    })
    val mobAttacks = SimulatorNumberGrid(MobAttackMeans.entries, { it.name }, { 100.0 })
    val equipmentEditor = SimulatorEquipmentEditor()
    val hits = mutableListOf<SimulatorHitEditor>()
    private val hitContainer = VBox(12.0)
    private val playerId = UUID.randomUUID()
    private val mobId = UUID.randomUUID()
    private val playerName = TextField("Player")
    private val mobName = TextField("Mob")
    private val playerX = numberField(0)
    private val playerZ = numberField(0)
    private val playerYaw = numberField(0)
    private val mobX = numberField(1)
    private val mobZ = numberField(0)
    private val mobYaw = numberField(0)
    val playerState = SimulatorActorEditor()
    val mobState = SimulatorActorEditor(allowOverHeal = false)
    val historyEditor = SimulatorStateEditor()
    private val breakValue = numberField(0)
    private val breakActive = CheckBox("対象BREAK有効")
    private val breakRuntime = CheckBox("対象BREAK runtimeあり")
    private val breakStart = numberField(100)
    private val breakHits = numberField(0)
    private val parryEnabled = CheckBox("Mobパリィ設定を使う")
    private val parryProbability = numberField(0.0)
    private val parryRate = numberField(0.0)
    private val parryCooldown = numberField(20)
    private val directTick = TextField().apply { promptText = "直接間隔なし=空白" }
    private val projectileTick = TextField().apply { promptText = "投射間隔なし=空白" }
    internal val shotProfile = ComboBox(FXCollections.observableArrayList("なし", "BOW", "STANDARD_CROSSBOW", "MANUAL")).apply { value = "なし" }
    private val arrowsConsumed = numberField(1)
    private val shotCondition = TextField("手動指定の成立済み1射")

    init {
        styleClass.add("simulator-form")
        children.addAll(fieldRow("評価方法", playerIsSource, sample), fieldRow("seed / 現在tick", seed, tick),
            TitledPane("プレイヤー基礎能力（技能・装備補正を含めない）", baseStats).apply { isExpanded = false },
            TitledPane("技能レベル", skills).apply { isExpanded = false },
            TitledPane("Mob能力/攻撃手段", VBox(8.0, mobStats, mobAttacks)).apply { isExpanded = false },
            TitledPane("固定装備（手動指定・取込）", equipmentEditor).apply { isExpanded = false },
            TitledPane("当事者と現在HP/リソース/構え/CD", VBox(8.0,
                fieldRow("Player 名前/X/Z/yaw", playerName, playerX, playerZ, playerYaw), playerState,
                fieldRow("Mob 名前/X/Z/yaw", mobName, mobX, mobZ, mobYaw), mobState)).apply { isExpanded = false },
            TitledPane("対象BREAK / Mobパリィ / 間隔 / 射撃", VBox(8.0,
                fieldRow("BREAK値/runtime開始tick/被命中数", breakValue, breakRuntime, breakStart, breakHits, breakActive),
                fieldRow("Mobパリィ 確率/軽減率/CD tick", parryEnabled, parryProbability, parryRate, parryCooldown),
                fieldRow("最終間隔tick", directTick, projectileTick),
                fieldRow("射撃profile/矢消費数(手動)/条件(手動)", shotProfile, arrowsConsumed, shotCondition),
                Label("射撃profileは成立済み1射のmetadataです。矢種/rank/発射地点からの距離は各行へ指定します。"))).apply { isExpanded = false },
            TitledPane("攻撃者履歴 / 固有間隔 / 特殊効果CD", historyEditor).apply { isExpanded = false },
            Label("命中行（上から同tickの処理順、単発は1行）"), hitContainer,
            Button("命中行を追加").apply { setOnAction { addHit() } })
        addHit()
    }

    internal fun addHit(): SimulatorHitEditor = SimulatorHitEditor().also { row ->
        hits += row
        row.up.setOnAction { move(row, -1) }
        row.down.setOnAction { move(row, 1) }
        row.remove.setOnAction { hits.remove(row); showHits() }
        row.captureLaunch.setOnAction {
            try { captureLaunch(row) } catch (error: IllegalArgumentException) { onInputError(error) }
        }
        row.clearLaunch.setOnAction { row.setLaunch(null) }
        showHits()
    }

    internal fun move(row: SimulatorHitEditor, offset: Int) {
        val index = hits.indexOf(row)
        val next = index + offset
        if (index >= 0 && next in hits.indices) { hits.removeAt(index); hits.add(next, row); showHits() }
    }
    internal fun captureLaunch(row: SimulatorHitEditor) {
        val input = readInput() as? SimulatorMultipleHitInput.Ready
            ?: throw IllegalArgumentException("発射状態の固定には対応済みの入力を指定してください。")
        val prepared = input.preparedInputs[hits.indexOf(row)]
        val source = if (playerIsSource.isSelected) prepared.player.snapshot else prepared.mob.snapshot
        val target = if (playerIsSource.isSelected) prepared.mob.snapshot else prepared.player.snapshot
        row.setLaunch(AttackSnapshot(source, target, prepared.preparation.sourceItemData?.id,
            row.launchBaseDamage.number("発射基本値"), input.currentTick, row.vanillaCritical.isSelected))
    }
    private fun showHits() = hitContainer.children.setAll(hits)

    fun readMode(): SimulatorMultipleHitMode = if (sample.isSelected) SimulatorMultipleHitMode.Sample(seed.longValue("seed"))
        else SimulatorMultipleHitMode.Expected

    fun readInput(equipmentInput: SimulatorEquipmentInput = equipmentEditor.readInput()): SimulatorMultipleHitInput {
        require(hits.isNotEmpty()) { "命中行を1件以上指定してください。" }
        val skillLevels = SkillLevelSnapshot(skills.fields.mapValues { (key, field) -> field.integer(key.name) })
        val assembled = SimulatorEquipmentAssembler.resolve(equipmentInput, skillLevels)
        val equipment = when (assembled) {
            is SimulatorEquipmentResult.Unsupported -> return SimulatorMultipleHitInput.Unsupported(assembled.problems.joinToString(" / ") { it.reason })
            is SimulatorEquipmentResult.Ready -> assembled
        }
        val player = SimulatorPosition(playerId, playerName.text, "simulator", playerX.number("Player X"), z = playerZ.number("Player Z"), yaw = playerYaw.number("Player yaw").toFloat())
        val mobPosition = SimulatorPosition(mobId, mobName.text, "simulator", mobX.number("Mob X"), z = mobZ.number("Mob Z"), yaw = mobYaw.number("Mob yaw").toFloat())
        val playerCurrent = playerState.read()
        val mobCurrent = mobState.read()
        val mob = SimulatorMobInput(mobPosition, mobStats.values(), mobAttacks.values(), mobCurrent.health)
        val targetId = if (playerIsSource.isSelected) mobId else playerId
        val records = historyEditor.readHistory(targetId)
        require(records.map { it.recordId }.distinct().size == records.size &&
            records.zipWithNext().all { (before, after) -> before.appliedTick <= after.appliedTick }) {
            "攻撃履歴はIDを重複させず、適用tickの古い順に指定してください。"
        }
        val nextRecordId = if (historyEditor.nextRecordId.text.isBlank()) (records.maxOfOrNull { it.recordId } ?: 0L) + 1L
            else historyEditor.nextRecordId.tick("次の履歴ID")
        require(records.all { it.recordId < nextRecordId }) { "次の履歴IDは既存の履歴IDより大きい値を指定してください。" }
        val state = SameTickDamageState(if (playerIsSource.isSelected) playerCurrent else mobCurrent,
            if (playerIsSource.isSelected) mobCurrent else playerCurrent,
            BreakState(BreakStateRule.clamp(breakValue.number("BREAK値")), if (breakRuntime.isSelected) BreakRuntime(breakStart.tick("BREAK開始tick"), breakHits.integer("BREAK被命中数")) else null),
            SameTickDamageIntervals(historyEditor.readIntervals(), directTick = directTick.text.takeIf(String::isNotBlank)?.let { directTick.tick("直接間隔tick") },
                projectileTick = projectileTick.text.takeIf(String::isNotBlank)?.let { projectileTick.tick("投射間隔tick") }),
            itemCooldowns = historyEditor.readCooldowns(), sourceAttackRecords = records, nextRecordId = nextRecordId,
            targetBreakActive = breakActive.isSelected)
        val rows = hits.map { it.read(playerIsSource.isSelected) }
        val content = equipment.input.items[StatsPart.MAIN_HAND]?.baseData?.itemDetail?.content
        val plan = when (shotProfile.value) {
            "BOW" -> SimulatorShotPlan.bow(content as? BowContent ?: throw IllegalArgumentException("BOW profileはメインハンドの弓を指定してください。"))
            "STANDARD_CROSSBOW" -> SimulatorShotPlan.standardCrossbow(content as? CrossbowData ?: throw IllegalArgumentException("STANDARD_CROSSBOW profileはメインハンドのクロスボウを指定してください。"))
            "MANUAL" -> SimulatorShotPlan.manual(rows.size, arrowsConsumed.integer("矢消費数"), shotCondition.text)
            else -> null
        }
        val parry = if (parryEnabled.isSelected) MobParryInput(parryProbability.number("パリィ確率"), parryRate.number("パリィ率"),
            parryCooldown.tick("MobパリィCD"), mobCurrent.parryUntilTick) else null
        val target = if (playerIsSource.isSelected) mobPosition else player
        return equipment.multipleHitInput(player, baseStats.values(), mob, tick.tick("現在tick"), state, rows, target.x, target.z, parry, shotPlan = plan)
    }
}

internal class SimulatorActorEditor(private val allowOverHeal: Boolean = true) : VBox(6.0) {
    val health = numberField(1000)
    val overHeal = numberField(0).apply { isDisable = !allowOverHeal }
    val stamina = numberField(100)
    val mana = numberField(100)
    val raising = CheckBox("盾を構える")
    val blockUntil = numberField(0)
    val parryUntil = numberField(0)
    init { children.addAll(fieldRow("HP / OH / ST / MP", health, overHeal, stamina, mana), fieldRow("Block/Parry期限tick", raising, blockUntil, parryUntil)) }
    fun read(): SameTickCombatantState {
        val oh = overHeal.currentValue("OH")
        require(allowOverHeal || oh == 0.0) { "MobのOHは0だけ指定できます。" }
        return SameTickCombatantState(health.currentValue("HP"), oh,
            DamageResources(stamina.currentValue("ST"), mana.currentValue("MP")), raising.isSelected,
            blockUntil.tick("Block期限tick"), parryUntil.tick("Parry期限tick"))
    }
}
