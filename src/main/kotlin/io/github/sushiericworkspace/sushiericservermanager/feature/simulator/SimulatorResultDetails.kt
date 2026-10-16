package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.damage.simulation.SameTickDamageState
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.SameTickDamageHitResult
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.SameTickDamageRowExpectation
import io.github.sushiericworkspace.combatcore.item.custom.base.itemtype.sword.SwordAttackCycle

/** 完了結果だけを展開します。期待値の状態は平均状態を捏造せず、終端枝の重み付き状態として示します。 */
private const val DETAIL_PAGE_SIZE = 50

internal fun SimulatorMultipleHitResult.detailPages(): Int {
    val size = when (this) {
        is SimulatorMultipleHitResult.Sample -> result.rows.size
        is SimulatorMultipleHitResult.Expected -> maxOf(result.rows.size, result.branches.size)
        else -> 0
    }
    return maxOf(1, (size + DETAIL_PAGE_SIZE - 1) / DETAIL_PAGE_SIZE)
}

internal fun SimulatorMultipleHitResult.details(page: Int = 0): String = buildString {
    require(page in 0 until this@details.detailPages())
    val start = page * DETAIL_PAGE_SIZE
    appendLine("詳細ページ ${page + 1}/${this@details.detailPages()}（各最大${DETAIL_PAGE_SIZE}攻撃・最終状態）")
    appendLine(summary())
    when (val value = this@details) {
        is SimulatorMultipleHitResult.Sample -> {
            appendLine("乱数方式=${value.randomVersion} / 乱数の固定番号(seed)=${value.seed}")
            value.shotPlan?.let { appendLine("射撃の記録: ${simulatorLabel(it.profile)} / 本数=${it.projectileCount} / 矢消費=${it.arrowConsumption} / 消費記録=${costDetails(it.resourceCosts)} / 条件=${it.condition}") }
            value.result.rows.asSequence().drop(start).take(DETAIL_PAGE_SIZE).forEachIndexed { offset, row ->
                val index = start + offset
                val t = row.transition
                appendLine("行${index + 1} 命中ID=${row.hitId} / ${simulatorLabel(t.status)} / HP適用=${simulatorLabel(t.application)} / 拒否=${simulatorLabel(t.rejectionReason)}")
                appendLine("算出=${t.calculatedDamage} / 純HP減少=${t.healthDamage} / 追加HP(OH)吸収=${t.overHealDamage} / 実適用=${t.appliedDamage}")
                t.simulation?.let { simulation ->
                    appendLine("計算式の結果=${simulation.formula.finalDamage}, 防御前=${simulation.formula.preDefenceDamage}, 通常会心=${yesNo(simulation.formula.isCrit)} / 補正の順序=${phaseDetails(simulation.preparation.phaseTrace)}")
                    appendLine("判定中に成立した効果=${simulation.preparation.preparationEffects.joinToString(" / ", transform = ::preparationEffectDetails).ifEmpty { "なし" }} / 成功後の消費=${costDetails(t.appliedEffects.resourceCosts)}, 履歴=${recordDetails(t.appliedEffects.attackRecord)}, 剣の連続攻撃=${cycleDetails(t.appliedEffects.swordCycle)}, BREAK=${t.appliedEffects.breakChange?.let { "蓄積値=${it.state.value}, 開始時刻=${it.state.runtime?.startTick}, 記録回数=${it.state.runtime?.hitCount}, 今回回数=${it.hitCount}, 解除=${yesNo(it.ended)}" } ?: "変更なし"}")
                }
                appendLine("前: ${stateDetails(t.before)}")
                appendLine("後: ${stateDetails(t.after)}")
                row.trace.records.forEach { appendLine("抽選 ${drawDetails(it)}") }
            }
        }
        is SimulatorMultipleHitResult.Expected -> {
            value.shotPlan?.let { appendLine("射撃の記録: ${simulatorLabel(it.profile)} / 本数=${it.projectileCount} / 矢消費=${it.arrowConsumption} / 消費記録=${costDetails(it.resourceCosts)} / 条件=${it.condition}") }
            appendLine("途中の状態や抽選値を見るには「1回の結果」を選んでください。平均結果では各状態の確率を表示します。")
            appendLine("計算に使った命中評価回数=${value.result.evaluatedHits}")
            value.result.rows.asSequence().drop(start).take(DETAIL_PAGE_SIZE).forEachIndexed { offset, row ->
                val index = start + offset
                appendLine("行${index + 1} 命中ID=${row.hitId} / 算出=${row.meanCalculatedDamage} / 純HP減少=${row.meanHealthDamage} / 追加HP(OH)吸収=${row.meanOverHealDamage} / 実適用=${row.meanAppliedDamage}")
                appendLine("状態確率=${probabilityDetails(row.statusProbabilities)} / 拒否理由確率=${probabilityDetails(row.rejectionProbabilities)} / 評価=${row.evaluatedHits}")
            }
            value.result.branches.asSequence().drop(start).take(DETAIL_PAGE_SIZE).forEachIndexed { offset, branch ->
                val index = start + offset
                appendLine("最終状態${index + 1} 確率=${branch.probability}: ${stateDetails(branch.finalState)}")
            }
        }
        else -> Unit
    }
}

private fun stateDetails(value: SameTickDamageState): String =
    "攻撃者: ${actorDetails(value.source)} / 対象: ${actorDetails(value.target)} / BREAK中=${yesNo(value.targetBreakActive)}, 蓄積値=${value.targetBreak.value}, 開始記録=${value.targetBreak.runtime?.let { "時刻=${it.startTick}, 被命中数=${it.hitCount}" } ?: "なし"} / 効果の再使用時刻=${value.itemCooldowns.entries.joinToString { (key, tick) -> "${simulatorLabel(key.role)}:${key.providerKey}:${key.effectId}=$tick" }} / 固有間隔の最終適用時刻=${value.intervals.effectTicks.entries.joinToString { (key, tick) -> "${key.kindId}:${key.effectId}=$tick" }}, 直接=${value.intervals.directTick ?: "なし"}, 投射物=${value.intervals.projectileTick ?: "なし"} / 履歴=${value.sourceAttackRecords.joinToString(transform = ::recordDetails)}, 次の履歴ID=${value.nextRecordId}"

private fun probabilityDetails(values: Map<out Enum<*>, Double>) = values.entries.joinToString { "${simulatorLabel(it.key)}=${it.value}" }.ifEmpty { "なし" }
private fun cycleDetails(value: SwordAttackCycle?): String = when (value) {
    null -> "なし"
    is SwordAttackCycle.Normal -> "通常（前回履歴ID=${value.previousRecordId}）"
    is SwordAttackCycle.Heavy -> "重攻撃（前回履歴ID=${value.previousRecordId}, 追加消費=${costDetails(listOf(value.additionalCost))}）"
}

internal fun SimulatorComparisonResult.detailPages(): Int = when (this) {
    is SimulatorComparisonResult.Expected -> maxOf(1, (rows.size + DETAIL_PAGE_SIZE - 1) / DETAIL_PAGE_SIZE)
    is SimulatorComparisonResult.Sample -> maxOf(1, (rows.size + DETAIL_PAGE_SIZE - 1) / DETAIL_PAGE_SIZE)
    else -> 1
}

internal fun SimulatorComparisonResult.details(page: Int = 0): String {
    require(page in 0 until detailPages())
    val label = "比較ページ ${page + 1}/${detailPages()}\n"
    return label + when (this) {
        SimulatorComparisonResult.NoBaseline -> "比較基準がありません。"
        is SimulatorComparisonResult.Stale -> "古い要求のため比較しません。"
        is SimulatorComparisonResult.Incompatible -> "比較不可: $reason"
        is SimulatorComparisonResult.Incomplete -> "未完了結果のため数値差はありません。"
        is SimulatorComparisonResult.Expected -> "平均ダメージの比較（厳密期待値）\n${changes(totals)}\n${rowsDetails(rows, page, ::expectedRowDetails)}"
        is SimulatorComparisonResult.Sample -> {
            val pageDraws = rows.asSequence().drop(page * DETAIL_PAGE_SIZE).take(DETAIL_PAGE_SIZE)
                .mapNotNull { row -> draws[row.hitId]?.let { row.hitId to it } }.joinToString("\n") { (id, values) -> "$id: ${values.joinToString { "${drawDetails(it.baseline)} → ${drawDetails(it.current)}" }}" }
            "1回の結果の比較 乱数の固定番号(seed)=${(baseline.mode as SimulatorMultipleHitMode.Sample).seed} → ${(current.mode as SimulatorMultipleHitMode.Sample).seed}\n${changes(totals)}\n${rowsDetails(rows, page, ::sampleRowDetails)}\n抽選差（確率0..1、抽選値0以上1未満）=$pageDraws"
        }
    }
}

private fun changes(values: SimulatorDamageChanges): String = listOf("算出" to values.calculated,
    "純HP減少" to values.health, "追加HP(OH)吸収" to values.overHeal, "実適用" to values.applied).joinToString(" / ") { (label, v) ->
    "$label: ${v.baseline} → ${v.current}, 差=${v.difference}, 増減率=${v.changePercent?.let { "$it%" } ?: "基準0のため未定義"}"
}

private fun <T> rowsDetails(rows: List<SimulatorRowComparison<T>>, page: Int, render: (T) -> String): String =
    rows.asSequence().drop(page * DETAIL_PAGE_SIZE).take(DETAIL_PAGE_SIZE).joinToString("\n") { row -> when (row) {
        is SimulatorRowComparison.Added -> "追加 命中ID=${row.hitId} / 順序=${row.currentIndex + 1}（基準なし）\n現在: ${render(row.current)}"
        is SimulatorRowComparison.Removed -> "削除 命中ID=${row.hitId} / 元順序=${row.baselineIndex + 1}（現在なし）\n基準: ${render(row.baseline)}"
        is SimulatorRowComparison.Matched -> "命中ID=${row.hitId} / 順序=${row.baselineIndex + 1}→${row.currentIndex + 1}: ${changes(row.changes)}\n基準: ${render(row.baseline)}\n現在: ${render(row.current)}"
    } }

private fun expectedRowDetails(row: SameTickDamageRowExpectation): String =
    "算出=${row.meanCalculatedDamage} / 純HP減少=${row.meanHealthDamage} / 追加HP(OH)吸収=${row.meanOverHealDamage} / 実適用=${row.meanAppliedDamage} / 状態確率=${probabilityDetails(row.statusProbabilities)} / 拒否確率=${probabilityDetails(row.rejectionProbabilities)}"

private fun sampleRowDetails(row: SameTickDamageHitResult): String = row.transition.let { value ->
    "${simulatorLabel(value.status)} / HP適用=${simulatorLabel(value.application)} / 拒否=${simulatorLabel(value.rejectionReason)} / 算出=${value.calculatedDamage} / 純HP減少=${value.healthDamage} / 追加HP(OH)吸収=${value.overHealDamage} / 実適用=${value.appliedDamage}\n前: ${stateDetails(value.before)}\n後: ${stateDetails(value.after)}\n補正の順序=${value.simulation?.preparation?.phaseTrace?.let(::phaseDetails) ?: "なし"} / 抽選記録(確率0..1)=${row.trace.records.joinToString(transform = ::drawDetails)}"
}
