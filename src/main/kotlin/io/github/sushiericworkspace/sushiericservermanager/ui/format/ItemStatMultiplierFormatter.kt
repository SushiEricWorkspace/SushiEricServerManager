package io.github.sushiericworkspace.sushiericservermanager.ui.format

import io.github.sushiericworkspace.common.data.item.model.ItemStatMultiplier
import io.github.sushiericworkspace.common.stats.player.StatsPart
import io.github.sushiericworkspace.common.stats.player.StatsPartTarget
import io.github.sushiericworkspace.sushiericservermanager.editor.main.item.formatItemStatMultiplier

/**
 * ステータス倍率を差分表示や競合解決ダイアログ向けの1行文字列へ整形します。
 *
 * 例: `自分 ×1.5`、`全体 ×1.1`、`部位(頭, 胴) ×1.2`。複数は` | `で区切ります。
 */
object ItemStatMultiplierFormatter {

    /** 倍率が1件もないことを表す表示文字列。 */
    const val NONE = "(なし)"

    fun format(multiplier: ItemStatMultiplier): String {
        val target = when (val target = multiplier.target) {
            StatsPartTarget.Self -> "自分"
            StatsPartTarget.All -> "全体"
            is StatsPartTarget.Parts -> "部位(${formatParts(target.parts)})"
        }

        return "$target ×${formatItemStatMultiplier(multiplier.value)}"
    }

    fun format(multipliers: List<ItemStatMultiplier>?): String {
        if (multipliers.isNullOrEmpty()) return NONE

        return multipliers.joinToString(" | ", transform = ::format)
    }

    private fun formatParts(parts: Set<StatsPart>): String {
        return StatsPart.entries
            .filter { it in parts }
            .joinToString(", ") { it.display }
    }
}
