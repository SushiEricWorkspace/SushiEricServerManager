package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.sushiericdatamodel.stats.player.StatsPart

/** 固定Coreの直接攻撃6経路を説明します。数値の再計算や途中状態の推定は行いません。 */
internal val simulatorFormulaHelp = """
    計算の順序と用語
    式計算結果 → 命中時の補正・拒否 → 算出 → HP・追加HP(OH)への適用。
    防御前は通常会心まで反映した値です。式計算結果は防御・BREAK・距離・バニラ会心等も反映し、命中時の補正前の値です。
    補正記録にある会心倍率1・防御0は式の計算後の記録用です。式で使った会心倍率や対象防御を表す値ではありません。補正の順序は結果詳細で確認できます。
    算出は適用前の計算値、純HP減少は実際に減った通常HP、追加HP(OH)吸収はOHが吸収した量、実適用＝純HP減少＋追加HP(OH)吸収です。残量や拒否により算出と一致しないことがあります。追加HPを貫通する行はOHを消費しません。
    同tickの命中は上から逐次処理し、後続は変更後のHP・リソース・BREAK・履歴等を参照します。未命中・拒否・死亡後は結果の状態に従い、式が評価されていない行に式結果はありません。
    厳密期待値＝各枝の確率×結果の合計です。平均能力を式へ代入しません。行の平均・状態確率・終端枝を表示し、途中の枝の処理記録や平均状態は作りません。取消・上限超過・対応外にはダメージ値がありません。

    Player → Mob 近接
    防御前＝(5＋物理ダメージ)×(1＋筋力/100)×通常会心倍率。
    実効防御D′＝対象防御×(1－防御貫通×0.001)、防御後＝防御前×100/(100＋D′)。分母が非有限または0以下なら軽減しません。
    BREAK中はD′＝0、その後×1.2。主手が大剣でバニラ会心指定ならさらに×1.2（通常会心とは別判定）。

    Player → Mob 投射物
    防御前＝(5＋物理ダメージ)×(1＋筋力/100)×通常会心倍率。
    実効防御D′＝対象防御。貫徹矢だけD′＝対象防御×(1－有効rank×0.015)。未指定rankは最小rank、範囲外はCoreの有効範囲へ補正します。通常の防御貫通能力は使いません。
    防御後＝防御前×100/(100＋D′)（近接・魔法の分母除外条件はありません）。距離指定時は距離倍率を掛け、最後にBREAK倍率を掛けます。
    距離dが3以下なら倍率1、3超なら1－0.11×(d－3)^1.6を0.1～1の範囲に制限した値。距離未指定は減衰なし。BREAK中はD′＝0、最後に×1.2。

    Player → Mob 魔法
    防御前＝魔法ダメージ×(1＋知力/100)×魔法の通常会心倍率。
    実効防御D′＝対象防御×(1－防御貫通×0.001)。防御後＝防御前×100/(100＋D′)。分母が非有限または0以下なら軽減しません。BREAK中はD′＝0、その後×1.2。
    この画面の魔法行は通常の直接魔法です。固定加算・会心無効・防御貫通無視を指定する入力はありません。

    Playerの能力と会心
    通常会心倍率は非会心なら1、会心なら1＋会心ダメージ/100。近接・投射物は物理会心率/物理会心ダメージ、魔法は魔法会心率/魔法会心ダメージを使います。
    近接は主手が近接武器、投射物は主手が投射物武器のときだけ主手由来の能力を含めます。不適合でも主手以外の部位は含めます。魔法は主手を除外しません。
    Coreのバニラ会心補正は大剣主手＋指定時に通常計算後へ掛かります。この画面で指定できるのはPlayer近接行です。防御前値にはバニラ会心倍率を含みません。

    Mob → Player 近接 / 投射物 / 魔法
    各経路の防御前＝それぞれの攻撃手段の指定攻撃力（未指定ならMob基礎攻撃力）。非有限または0以下の攻撃力では式結果0です。
    3経路とも式計算結果＝防御前×100/(100＋Player防御)。防御が非有限なら0として扱い、分母が非有限または0以下なら軽減しません。通常会心、Playerの矢補正・距離減衰・BREAK倍率はありません。
    Mobからの近接・投射物はPlayerのブロック/パリィの対象です。構え中はブロック、それ以外はパリィを判定します。魔法はこの判定の対象外です。式の防御とガードを混同せず、成立・拒否・補正は結果詳細を参照してください。

    対応する要求
    下の対応説明は最後に計算した要求の記録です。編集中の入力ではありません。式には要求の保存したPlayer/Mob能力を使い、発射時の記録で能力全体を置き換えません。
    この説明は実際に組み込んだ計算規則に対応しています。依存更新時の照合方法と手計算例は利用文書に記載しています。
""".trimIndent()

internal fun SimulatorRunState.Finished.formulaExplanation(page: Int = 0): String = buildString {
    appendLine("最後の計算要求 #${request.generation} / ${if (request.mode is SimulatorMultipleHitMode.Expected) "厳密期待値" else "1回の結果"}")
    appendLine(result.summary())
    val input = request.input as? SimulatorMultipleHitInput.Ready ?: return@buildString
    if (result !is SimulatorMultipleHitResult.Sample && result !is SimulatorMultipleHitResult.Expected) return@buildString
    appendLine("式の入力記録（結果詳細と同じページ、最大50行）。能力は計算要求に保存した値、発射時の記録は特殊効果の判定用です。")
    input.hits.asSequence().drop(page * 50).take(50).forEachIndexed { offset, hit ->
        val index = page * 50 + offset
        val fixed = input.preparedInputs[index]
        val route = when (hit.attack) {
            is SimulatorAttack.PlayerMelee -> "Player → Mob 近接"
            is SimulatorAttack.PlayerProjectile -> "Player → Mob 投射物"
            is SimulatorAttack.PlayerMagic -> "Player → Mob 魔法"
            SimulatorAttack.MobMelee -> "Mob → Player 近接"
            SimulatorAttack.MobProjectile -> "Mob → Player 投射物"
            SimulatorAttack.MobMagic -> "Mob → Player 魔法"
        }
        appendLine("行${index + 1} 命中ID=${hit.hitId} / $route / 命中指定=${yesNo(hit.matched)} / 追加HP貫通=${yesNo(hit.pierceOverHeal)}")
        appendLine("Player=${fixed.player.snapshot.displayName} / Mob=${fixed.mob.snapshot.displayName} / 発射時の記録=${if (hit.launchSnapshot == null) "なし" else "あり（式の能力とは別）"}")
        when (val attack = hit.attack) {
            is SimulatorAttack.PlayerMelee -> appendLine("バニラ会心指定=${yesNo(attack.vanillaCritical)} / 主手=${fixed.player.itemTypes[StatsPart.MAIN_HAND]?.display ?: "なし"}")
            is SimulatorAttack.PlayerProjectile -> appendLine("矢=${attack.arrowType?.display ?: "指定なし"} / rank=${attack.rank ?: "未指定"} / 距離=${attack.distance ?: "未指定"}")
            else -> Unit
        }
        when (val value = result) {
            is SimulatorMultipleHitResult.Sample -> value.result.rows[index].transition.let { transition ->
                appendLine("状態=${simulatorLabel(transition.status)} / 拒否=${simulatorLabel(transition.rejectionReason)}")
                val formula = transition.simulation?.formula
                if (formula == null) appendLine("式結果なし（この行では式の評価記録がありません）")
                else appendLine("防御前=${formula.preDefenceDamage} → 式計算結果=${formula.finalDamage} / 通常会心=${yesNo(formula.isCrit)}")
                appendLine("算出=${transition.calculatedDamage} → 純HP減少=${transition.healthDamage} / 追加HP(OH)吸収=${transition.overHealDamage} / 実適用=${transition.appliedDamage}")
            }
            is SimulatorMultipleHitResult.Expected -> appendLine("厳密期待値: この行の平均・状態確率は結果詳細へ対応します。各枝で評価するため、単一の式結果・中間値は表示しません。")
            else -> Unit
        }
    }
}
