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
              monster_hunter_1:
                at: 2026-10-06T10:15:30Z
                source: natural
              mining_beginner_1:
                at: 2026-10-07T01:00:00Z
                source: admin
                executor:
                  uuid: 11111111-1111-1111-1111-111111111111
                  name: 管理者
            """.trimIndent()
        )
        assertEquals(listOf(AchievementType.MINING_BEGINNER_1, AchievementType.MONSTER_HUNTER_1), entries.map { it.type })
        assertEquals(Instant.parse("2026-10-07T01:00:00Z"), entries.first().achievedAt)
        assertEquals(AchievementSource.Admin, entries.first().source)
        assertEquals("管理者", entries.first().executorName)
    }

    @Test
    fun `未知のIDと読めない時刻は読み飛ばして通知する`() {
        val skipped = mutableListOf<String>()
        val entries = parseAchievementHistory(
            """
            achievements:
              unknown_id:
                at: 2026-10-06T10:15:30Z
                source: natural
              monster_hunter_1:
                at: invalid
                source: natural
              monster_hunter_2:
                at: 2026-10-06T10:15:30Z
                source: natural
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
            AchievementHistoryEntry(AchievementType.MONSTER_HUNTER_1, Instant.parse("2026-10-06T10:00:00Z"), AchievementSource.Natural),
            AchievementHistoryEntry(AchievementType.MINING_BEGINNER_1, Instant.parse("2026-10-07T10:00:00Z"), AchievementSource.Admin, "管理者"),
            AchievementHistoryEntry(AchievementType.MONSTER_HUNTER_2, Instant.parse("2026-10-08T10:00:00Z"), AchievementSource.Natural)
        )
        val day = historyTimeRange(LocalDate.of(2026, 10, 7), "", LocalDate.of(2026, 10, 8), "")
        val all = AchievementCategory.entries.toSet()

        assertEquals(
            listOf(AchievementType.MINING_BEGINNER_1, AchievementType.MONSTER_HUNTER_2),
            filterAchievementHistory(entries, day, all, AchievementSource.entries.toSet(), utc).map { it.type }
        )
        assertEquals(
            listOf(AchievementType.MONSTER_HUNTER_2),
            filterAchievementHistory(entries, day, setOf(AchievementCategory.Combat), AchievementSource.entries.toSet(), utc).map { it.type }
        )
        assertEquals(3, filterAchievementHistory(entries, historyTimeRange(null, "", null, ""), all, AchievementSource.entries.toSet(), utc).size)
        assertTrue(filterAchievementHistory(entries, day, emptySet(), AchievementSource.entries.toSet(), utc).isEmpty())
        assertEquals(
            listOf(AchievementType.MINING_BEGINNER_1),
            filterAchievementHistory(entries, day, all, setOf(AchievementSource.Admin), utc).map { it.type }
        )
    }

    @Test
    fun `分類の表示名を日本語で返す`() {
        assertEquals(listOf("戦闘", "採掘", "その他"), AchievementCategory.entries.map { it.displayName })
    }
}
