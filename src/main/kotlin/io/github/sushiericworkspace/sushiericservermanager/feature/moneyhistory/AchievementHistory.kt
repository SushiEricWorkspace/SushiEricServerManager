package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericdatamodel.stats.player.AchievementCategory
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.AchievementType
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.time.Instant
import java.time.ZoneId
import java.util.Date

/** 実績の達成履歴1件です。 */
internal enum class AchievementSource(val displayName: String) {
    Natural("自然達成"),
    Admin("管理者による付与")
}

internal data class AchievementHistoryEntry(
    val type: AchievementType,
    val achievedAt: Instant,
    val source: AchievementSource,
    val executorName: String? = null
)

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
 * 達成経路は`natural`と`admin`を受け付け、管理者付与には実行者名を含めます。
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
        val record = value as? Map<*, *>
        if (record == null) {
            onSkipped("${id}の達成履歴を読み込めません: $value")
            return@mapNotNull null
        }
        val achievedAt = when (val at = record["at"]) {
            is Date -> at.toInstant()
            null -> null
            else -> runCatching { Instant.parse(at.toString()) }.getOrNull()
        }
        if (achievedAt == null) {
            onSkipped("${id}の達成時刻を読み込めません: $value")
            return@mapNotNull null
        }
        val source = when (record["source"]?.toString()) {
            "natural" -> AchievementSource.Natural
            "admin" -> AchievementSource.Admin
            else -> {
                onSkipped("${id}の達成経路を読み込めません: ${record["source"]}")
                return@mapNotNull null
            }
        }
        val executor = record["executor"] as? Map<*, *>
        val executorName = if (source == AchievementSource.Admin) executor?.get("name")?.toString() else null
        if (source == AchievementSource.Admin && executorName.isNullOrBlank()) {
            onSkipped("${id}の付与実行者を読み込めません")
            return@mapNotNull null
        }
        AchievementHistoryEntry(type, achievedAt, source, executorName)
    }.sortedWith(compareByDescending<AchievementHistoryEntry> { it.achievedAt }.thenBy { it.type.ordinal })
}

/** 達成時刻が[range]に含まれ、分類が[categories]に含まれる履歴だけを返します。 */
internal fun filterAchievementHistory(
    entries: List<AchievementHistoryEntry>,
    range: HistoryTimeRange,
    categories: Set<AchievementCategory>,
    sources: Set<AchievementSource>,
    zone: ZoneId = ZoneId.systemDefault()
): List<AchievementHistoryEntry> = entries.filter { entry ->
    entry.type.category in categories && entry.source in sources && range.contains(entry.achievedAt.atZone(zone).toLocalDateTime())
}
