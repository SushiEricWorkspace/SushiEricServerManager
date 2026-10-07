package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.common.stats.player.AchievementCategory
import io.github.sushiericworkspace.common.stats.player.AchievementType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AchievementHistoryTest {
    private val utc = ZoneOffset.UTC

    @Test
    fun `達成時刻の新しい順に履歴へ変換し統計セクションは無視する`() {
        val entries = parseAchievementHistory(
            """
            statistics:
              enemy_kills: 120
            achievements:
              monster_hunter_1: 2026-10-06T10:15:30Z
              mining_beginner_1: 2026-10-07T01:00:00Z
            """.trimIndent()
        )
        assertEquals(listOf(AchievementType.MINING_BEGINNER_1, AchievementType.MONSTER_HUNTER_1), entries.map { it.type })
        assertEquals(Instant.parse("2026-10-07T01:00:00Z"), entries.first().achievedAt)
    }

    @Test
    fun `未知のIDと読めない時刻は読み飛ばして通知する`() {
        val skipped = mutableListOf<String>()
        val entries = parseAchievementHistory(
            """
            achievements:
              unknown_id: 2026-10-06T10:15:30Z
              monster_hunter_1: invalid
              monster_hunter_2: 2026-10-06T10:15:30Z
            """.trimIndent(),
            skipped::add
        )
        assertEquals(listOf(AchievementType.MONSTER_HUNTER_2), entries.map { it.type })
        assertEquals(2, skipped.size)
    }

    @Test
    fun `実績セクションがない文書は空の履歴になる`() {
        assertTrue(parseAchievementHistory("").isEmpty())
        assertTrue(parseAchievementHistory("statistics:\n  enemy_kills: 1").isEmpty())
    }

    @Test
    fun `達成時刻の範囲と分類で絞り込む`() {
        val entries = listOf(
            AchievementHistoryEntry(AchievementType.MONSTER_HUNTER_1, Instant.parse("2026-10-06T10:00:00Z")),
            AchievementHistoryEntry(AchievementType.MINING_BEGINNER_1, Instant.parse("2026-10-07T10:00:00Z")),
            AchievementHistoryEntry(AchievementType.MONSTER_HUNTER_2, Instant.parse("2026-10-08T10:00:00Z"))
        )
        val day = historyTimeRange(LocalDate.of(2026, 10, 7), "", LocalDate.of(2026, 10, 8), "")
        val all = AchievementCategory.entries.toSet()

        assertEquals(
            listOf(AchievementType.MINING_BEGINNER_1, AchievementType.MONSTER_HUNTER_2),
            filterAchievementHistory(entries, day, all, utc).map { it.type }
        )
        assertEquals(
            listOf(AchievementType.MONSTER_HUNTER_2),
            filterAchievementHistory(entries, day, setOf(AchievementCategory.Combat), utc).map { it.type }
        )
        assertEquals(3, filterAchievementHistory(entries, historyTimeRange(null, "", null, ""), all, utc).size)
        assertTrue(filterAchievementHistory(entries, day, emptySet(), utc).isEmpty())
    }

    @Test
    fun `分類の表示名を日本語で返す`() {
        assertEquals(listOf("戦闘", "採掘", "その他"), AchievementCategory.entries.map { it.displayName })
    }
}
