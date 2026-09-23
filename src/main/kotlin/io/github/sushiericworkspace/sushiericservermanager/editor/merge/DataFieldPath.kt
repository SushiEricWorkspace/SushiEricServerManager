package io.github.sushiericworkspace.sushiericservermanager.editor.merge

sealed interface DataFieldSegment {
    val displayName: String

    data class Property(
        val id: String,
        override val displayName: String
    ) : DataFieldSegment

    data class Key(
        val value: String,
        override val displayName: String = value
    ) : DataFieldSegment

    data class Index(
        val value: Int,
        override val displayName: String = "${value + 1}番目"
    ) : DataFieldSegment {
        init {
            require(displayName.isNotBlank()) { "表示名は空にできません" }
        }
    }
}

data class DataFieldPath(
    val segments: List<DataFieldSegment>
) {
    val displayName: String
        get() = segments.joinToString(" / ") { it.displayName }

    fun key(value: Any, displayName: String = value.toString()): DataFieldPath {
        return copy(segments = segments + DataFieldSegment.Key(value.toString(), displayName))
    }

    fun index(value: Int, displayName: String = "${value + 1}番目"): DataFieldPath {
        return copy(segments = segments + DataFieldSegment.Index(value, displayName))
    }

    companion object {
        fun property(id: String, displayName: String): DataFieldPath {
            return DataFieldPath(listOf(DataFieldSegment.Property(id, displayName)))
        }
    }
}

object DataFields {
    val rarity = DataFieldPath.property("rarity", "レアリティ")
    val itemDetail = DataFieldPath.property("item-detail", "詳細データ")
    val vanillaId = DataFieldPath.property("vanilla-id", "バニラID")
    val maxStackSize = DataFieldPath.property("max-stack-size", "最大スタック数")
    val enchantAura = DataFieldPath.property("enchant-aura", "エンチャント表示")
    val headSkin = DataFieldPath.property("head-skin", "ヘッドスキン")
    val detailContent = DataFieldPath.property("detail-content", "種別固有データ")
    val displayName = DataFieldPath.property("display-name", "表示名")
    val lore = DataFieldPath.property("lore", "Lore")
    val stats = DataFieldPath.property("stats", "ステータス")
    val statMultipliers = DataFieldPath.property("stat-multipliers", "ステータス倍率")
    val comments = DataFieldPath.property("editor-comment", "エディターコメント")
    val blockId = DataFieldPath.property("block-id", "ブロックID")
    val hardness = DataFieldPath.property("hardness", "硬度")
    val requiredTier = DataFieldPath.property("required-tier", "要求Tier")
    val dropItems = DataFieldPath.property("drop-items", "ドロップアイテム")
    val entityData = DataFieldPath.property("entity-data", "エンティティデータ")
    val equipment = DataFieldPath.property("equipment", "装備")
}
