package io.github.sushiericworkspace.sushiericservermanager.editor.main.item.diff

import io.github.sushiericworkspace.common.stats.player.StatsType

/**
 * 差分を検出する対象フィールドの定義
 */
enum class ItemDiffField(val categoryName: String) {
    RARITY("レアリティ"),
    DISPLAY_NAME("表示名"),
    LORE("Lore"),
    STATS("ステータス"),
    STAT_MULTIPLIER("ステータス倍率"),
    COMMENT("コメントアウト"),
    HEAD_SKIN("ヘッドスキン"),
    DETAIL("詳細データ")
}

/**
 * どのフィールドの、具体的にどの要素（行数やStatsのキー）に差分があるかを一意に特定するための識別子
 */
data class ItemDiffId(
    val field: ItemDiffField,
    val index: Int? = null,       // Lore や説明文の「行インデックス (0始まり)」用
    val statsType: StatsType? = null // Stats と StatMultiplier の「キー」用
)
