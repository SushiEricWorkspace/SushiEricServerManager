package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.damage.resource.DamageResourceCost
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.detail.BowContent
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.detail.CrossbowData
import java.util.Collections
import kotlin.math.ceil

/** 標準仕様を明示した計画と、Mod固有条件を手入力した計画を区別します。 */
enum class SimulatorShotProfile { BOW, STANDARD_CROSSBOW, MANUAL }

/**
 * 射撃成立済みとして一度だけ保持する発射・消費予定です。実在庫やリソースを変更しません。
 * 各命中のダメージ・成功後costとは別で、命中行へ分配・重複計上しません。
 * profileは利用者の指定であり、ModのItem IDや遅延volleyを自動解決した結果ではありません。
 */
class SimulatorShotPlan private constructor(
    val profile: SimulatorShotProfile,
    val projectileCount: Int,
    val arrowConsumption: Int,
    resourceCosts: List<DamageResourceCost>,
    val condition: String
) {
    val resourceCosts: List<DamageResourceCost> = Collections.unmodifiableList(resourceCosts.toList())

    companion object {
        /** 固定ModのBowContent.multiによる発射本数。成立した1射で矢を1本消費します。 */
        fun bow(data: BowContent): SimulatorShotPlan = SimulatorShotPlan(SimulatorShotProfile.BOW,
            Math.addExact(Math.multiplyExact(data.multi.coerceAtLeast(0), 2), 1), 1, emptyList(), "標準弓の成立済み1射")

        /** 標準profileを明示して使います。矢はチャージ完了時に一度消費し、着弾では消費しません。 */
        fun standardCrossbow(data: CrossbowData): SimulatorShotPlan {
            val count = data.arrowCount.coerceAtLeast(0)
            val consumption = ceil(count * (1.0 - data.arrowEfficiency.coerceIn(0.0, 1.0))).toInt().coerceIn(0, count)
            return SimulatorShotPlan(SimulatorShotProfile.STANDARD_CROSSBOW, count, consumption, emptyList(), "標準クロスボウの成立済み1射")
        }

        /** Mod固有profile等の本数と消費を明示します。飛翔・遅延volleyは実行しません。 */
        fun manual(projectileCount: Int, arrowConsumption: Int, condition: String,
            resourceCosts: List<DamageResourceCost> = emptyList()): SimulatorShotPlan {
            require(projectileCount >= 0 && arrowConsumption >= 0 && condition.isNotBlank()) { "本数・消費は0以上、手動条件は空白以外で指定してください。" }
            return SimulatorShotPlan(SimulatorShotProfile.MANUAL, projectileCount, arrowConsumption, resourceCosts, condition)
        }
    }
}
