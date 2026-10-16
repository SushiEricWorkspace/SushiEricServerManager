package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.damage.simulation.SameTickDamageState
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.SameTickDamageHitResult
import io.github.sushiericworkspace.combatcore.combat.damage.simulation.SameTickDamageRowExpectation
import io.github.sushiericworkspace.combatcore.combat.random.DamageRollRecord

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
    appendLine("詳細ページ ${page + 1}/${this@details.detailPages()}（各最大${DETAIL_PAGE_SIZE}行・終端枝）")
    appendLine(summary())
    when (val value = this@details) {
        is SimulatorMultipleHitResult.Sample -> {
            appendLine("乱数版=${value.randomVersion} / seed=${value.seed}")
            value.shotPlan?.let { appendLine("成立済み1射: ${it.profile} / 本数=${it.projectileCount} / 矢消費=${it.arrowConsumption} / cost=${it.resourceCosts} / 条件=${it.condition}") }
            value.result.rows.asSequence().drop(start).take(DETAIL_PAGE_SIZE).forEachIndexed { offset, row ->
                val index = start + offset
                val t = row.transition
                appendLine("行${index + 1} hitId=${row.hitId} / ${t.status} / HP適用=${t.application} / 拒否=${t.rejectionReason ?: "なし"}")
                appendLine("算出=${t.calculatedDamage} / 純HP=${t.healthDamage} / OH=${t.overHealDamage} / 適用=${t.appliedDamage}")
                t.simulation?.let { simulation ->
                    appendLine("式=${simulation.formula} / phaseTrace=${simulation.preparation.phaseTrace}")
                    appendLine("準備中効果=${simulation.preparation.preparationEffects} / 成功後cost=${t.appliedEffects.resourceCosts},履歴=${t.appliedEffects.attackRecord},周期=${t.appliedEffects.swordCycle},BREAK=${t.appliedEffects.breakChange}")
                }
                appendLine("前: ${stateDetails(t.before)}")
                appendLine("後: ${stateDetails(t.after)}")
                row.trace.records.forEach { record -> appendLine(when (record) {
                    is DamageRollRecord.Reached -> "抽選 ${record.kind} #${record.occurrence}: 確率(0..1)=${record.probability} / 抽選値(0以上1未満)=${record.unitValue} / 成立=${record.succeeded}"
                    else -> "抽選 $record"
                }) }
            }
        }
        is SimulatorMultipleHitResult.Expected -> {
            value.shotPlan?.let { appendLine("成立済み1射: ${it.profile} / 本数=${it.projectileCount} / 矢消費=${it.arrowConsumption} / cost=${it.resourceCosts} / 条件=${it.condition}") }
            appendLine("中間状態・抽選値のtraceはサンプルで表示します。期待値は周辺確率と各終端枝を示します。")
            appendLine("厳密評価回数=${value.result.evaluatedHits}")
            value.result.rows.asSequence().drop(start).take(DETAIL_PAGE_SIZE).forEachIndexed { offset, row ->
                val index = start + offset
                appendLine("行${index + 1} hitId=${row.hitId} / 算出=${row.meanCalculatedDamage} / 純HP=${row.meanHealthDamage} / OH=${row.meanOverHealDamage} / 適用=${row.meanAppliedDamage}")
                appendLine("状態確率=${row.statusProbabilities} / 拒否理由確率=${row.rejectionProbabilities} / 評価=${row.evaluatedHits}")
            }
            value.result.branches.asSequence().drop(start).take(DETAIL_PAGE_SIZE).forEachIndexed { offset, branch ->
                val index = start + offset
                appendLine("終端枝${index + 1} 重み=${branch.probability}: ${stateDetails(branch.finalState)}")
            }
        }
        else -> Unit
    }
}

private fun stateDetails(value: SameTickDamageState): String =
    "攻撃者=${value.source} / 対象=${value.target} / BREAK有効=${value.targetBreakActive},値=${value.targetBreak} / ItemCD=${value.itemCooldowns} / 間隔=${value.intervals.effectTicks},直接=${value.intervals.directTick},投射=${value.intervals.projectileTick} / 履歴=${value.sourceAttackRecords},次ID=${value.nextRecordId}"

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
        is SimulatorComparisonResult.Expected -> "厳密期待値比較\n${changes(totals)}\n${rowsDetails(rows, page, ::expectedRowDetails)}"
        is SimulatorComparisonResult.Sample -> {
            val pageDraws = rows.asSequence().drop(page * DETAIL_PAGE_SIZE).take(DETAIL_PAGE_SIZE)
                .mapNotNull { row -> draws[row.hitId]?.let { row.hitId to it } }.joinToString("\n") { (id, values) -> "$id: $values" }
            "サンプル比較 seed=${baseline.mode} → ${current.mode}\n${changes(totals)}\n${rowsDetails(rows, page, ::sampleRowDetails)}\n抽選差（確率0..1、抽選値0以上1未満）=$pageDraws"
        }
    }
}

private fun changes(values: SimulatorDamageChanges): String = listOf("算出" to values.calculated,
    "純HP" to values.health, "OH" to values.overHeal, "適用" to values.applied).joinToString(" / ") { (label, v) ->
    "$label: ${v.baseline} → ${v.current}, 差=${v.difference}, 増減率=${v.changePercent?.let { "$it%" } ?: "基準0のため未定義"}"
}

private fun <T> rowsDetails(rows: List<SimulatorRowComparison<T>>, page: Int, render: (T) -> String): String =
    rows.asSequence().drop(page * DETAIL_PAGE_SIZE).take(DETAIL_PAGE_SIZE).joinToString("\n") { row -> when (row) {
        is SimulatorRowComparison.Added -> "追加 hitId=${row.hitId} / 順序=${row.currentIndex + 1}（基準なし）\n現在: ${render(row.current)}"
        is SimulatorRowComparison.Removed -> "削除 hitId=${row.hitId} / 元順序=${row.baselineIndex + 1}（現在なし）\n基準: ${render(row.baseline)}"
        is SimulatorRowComparison.Matched -> "hitId=${row.hitId} / 順序=${row.baselineIndex + 1}→${row.currentIndex + 1}: ${changes(row.changes)}\n基準: ${render(row.baseline)}\n現在: ${render(row.current)}"
    } }

private fun expectedRowDetails(row: SameTickDamageRowExpectation): String =
    "算出=${row.meanCalculatedDamage} / 純HP=${row.meanHealthDamage} / OH=${row.meanOverHealDamage} / 適用=${row.meanAppliedDamage} / 状態確率=${row.statusProbabilities} / 拒否確率=${row.rejectionProbabilities}"

private fun sampleRowDetails(row: SameTickDamageHitResult): String = row.transition.let { value ->
    "${value.status} / HP適用=${value.application} / 拒否=${value.rejectionReason} / 算出=${value.calculatedDamage} / 純HP=${value.healthDamage} / OH=${value.overHealDamage} / 適用=${value.appliedDamage}\n前: ${stateDetails(value.before)}\n後: ${stateDetails(value.after)}\nphaseTrace=${value.simulation?.preparation?.phaseTrace} / 抽選記録(確率0..1)=${row.trace.records}"
}
