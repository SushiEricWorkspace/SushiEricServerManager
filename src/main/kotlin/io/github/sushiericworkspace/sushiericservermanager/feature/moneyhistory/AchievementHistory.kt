package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.common.stats.player.AchievementCategory
import io.github.sushiericworkspace.common.stats.player.AchievementType
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.time.Instant
import java.time.ZoneId
import java.util.Date

/** 実績の達成履歴1件です。 */
internal data class AchievementHistoryEntry(val type: AchievementType, val achievedAt: Instant)

/** 実績の分類を画面に表示する名前です。 */
internal val AchievementCategory.displayName: String
    get() = when (this) {
        AchievementCategory.Combat -> "戦闘"
        AchievementCategory.Mining -> "採掘"
        AchievementCategory.Other -> "その他"
    }

/**
 * `achievements.yml`の`achievements`セクションを、達成時刻の新しい順の履歴へ変換します。
 *
 * 達成時刻はISO-8601の文字列のほか、YAMLの時刻型として読まれた値も受け付けます。
 * 未知の実績IDと読めない時刻は[onSkipped]へ通知して読み飛ばします。
 * `statistics`セクションは扱いません。YAML全体が不正な場合は例外を送出します。
 */
internal fun parseAchievementHistory(text: String, onSkipped: (String) -> Unit = {}): List<AchievementHistoryEntry> {
    val document = Yaml(SafeConstructor(LoaderOptions())).load<Any?>(text) as? Map<*, *> ?: return emptyList()
    val achievements = document["achievements"] as? Map<*, *> ?: return emptyList()
    return achievements.mapNotNull { (key, value) ->
        val id = key.toString()
        val type = AchievementType.fromIdOrNull(id)
        if (type == null) {
            onSkipped("未知の実績を読み飛ばしました: $id")
            return@mapNotNull null
        }
        val achievedAt = when (value) {
            is Date -> value.toInstant()
            else -> runCatching { Instant.parse(value.toString()) }.getOrNull()
        }
        if (achievedAt == null) {
            onSkipped("${id}の達成時刻を読み込めません: $value")
            return@mapNotNull null
        }
        AchievementHistoryEntry(type, achievedAt)
    }.sortedWith(compareByDescending<AchievementHistoryEntry> { it.achievedAt }.thenBy { it.type.ordinal })
}

/** 達成時刻が[range]に含まれ、分類が[categories]に含まれる履歴だけを返します。 */
internal fun filterAchievementHistory(
    entries: List<AchievementHistoryEntry>,
    range: HistoryTimeRange,
    categories: Set<AchievementCategory>,
    zone: ZoneId = ZoneId.systemDefault()
): List<AchievementHistoryEntry> = entries.filter { entry ->
    entry.type.category in categories && range.contains(entry.achievedAt.atZone(zone).toLocalDateTime())
}
