package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.data.ore.model.mutable.MutableOreBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.ConflictValueFormatter

/** 保存確認に表示する、操作と項目単位の変更内容を組み立てます。 */
internal fun saveChangeDetails(
    dataId: String,
    operation: PendingStoreOperation?,
    original: ManagedData<*, *>,
    current: ManagedData<*, *>,
    automaticallyMerged: ManagedData<*, *>? = null,
    finalData: ManagedData<*, *>? = null
): List<String> = buildList {
    add("【ストア操作】")
    val contentChanged = original != current ||
        (automaticallyMerged != null && current != automaticallyMerged) ||
        (finalData != null && automaticallyMerged != null && finalData != automaticallyMerged)
    addAll(saveChangeSummary(dataId, operation, contentChanged).map { "・$it" })

    if (operation is PendingStoreOperation.Delete) return@buildList

    val changes = changesBetween(original, current, operation is PendingStoreOperation.Create)
    add("")
    add("【内容の変更】")
    appendChanges(changes)

    if (automaticallyMerged != null) {
        val mergedChanges = changesBetween(current, automaticallyMerged, created = false)
        if (mergedChanges.isNotEmpty()) {
            add("")
            add("【自動マージ内容】")
            appendChanges(mergedChanges)
        }
    }

    if (finalData != null && automaticallyMerged != null) {
        val resolutionChanges = changesBetween(automaticallyMerged, finalData, created = false)
        if (resolutionChanges.isNotEmpty()) {
            add("")
            add("【競合の解決内容】")
            appendChanges(resolutionChanges)
        }
    }
}

private fun MutableList<String>.appendChanges(changes: List<Change>) {
    if (changes.isEmpty()) {
        add("・なし")
    } else {
        changes.forEach { change ->
            add("・${change.label}")
            if (change.created) {
                add("  値: ${formatValue(change.after)}")
            } else {
                add("  変更前: ${formatValue(change.before)}")
                add("  変更後: ${formatValue(change.after)}")
            }
        }
    }
}

private fun changesBetween(
    before: ManagedData<*, *>,
    after: ManagedData<*, *>,
    created: Boolean
): List<Change> = when {
    before is MutableItemBaseData && after is MutableItemBaseData ->
        itemChanges(before, after, created)
    before is MutableOreBaseData && after is MutableOreBaseData ->
        oreChanges(before, after, created)
    before != after -> listOf(Change("データ全体", before, after, created))
    else -> emptyList()
}

/** 複数データの保存内容を、データIDごとの見出し付きで1つの確認表示へまとめます。 */
internal fun combinedSaveChangeDetails(entries: List<Pair<String, List<String>>>): List<String> = buildList {
    entries.forEachIndexed { index, (dataId, details) ->
        if (index > 0) add("")
        add("【$dataId】")
        addAll(details)
    }
}

private data class Change(
    val label: String,
    val before: Any?,
    val after: Any?,
    val created: Boolean = false
)

private fun itemChanges(
    original: MutableItemBaseData,
    current: MutableItemBaseData,
    created: Boolean
): List<Change> = buildList {
    addValue("レアリティ", original.rarity, current.rarity, created)
    addValue("バニラID", original.itemDetail.vanillaId, current.itemDetail.vanillaId, created)
    addValue("最大スタック数", original.itemDetail.maxStackSize, current.itemDetail.maxStackSize, created)
    addValue("エンチャント表示", original.itemDetail.enchantAura, current.itemDetail.enchantAura, created)
    addValue("ヘッドスキン", original.itemDetail.mutableHeadSkin, current.itemDetail.mutableHeadSkin, created)
    addValue("種別固有データ", original.itemDetail.content, current.itemDetail.content, created)
    addValue("表示名", original.display.displayName, current.display.displayName, created)
    addList("Lore", original.display.mutableLore, current.display.mutableLore, created)
    addMap("ステータス", original.stats, current.stats, created)
    addMap("ステータス倍率", original.statMultipliers, current.statMultipliers, created)
    addList("エディターコメント", original.editorMeta.comment, current.editorMeta.comment, created)
}

private fun oreChanges(
    original: MutableOreBaseData,
    current: MutableOreBaseData,
    created: Boolean
): List<Change> = buildList {
    addValue("ブロックID", original.blockId, current.blockId, created)
    addValue("硬度", original.hardness, current.hardness, created)
    addValue("要求Tier", original.requiredTier, current.requiredTier, created)
    addList("ドロップアイテム", original.mutableDropItems, current.mutableDropItems, created)
    addMap("スキル経験値", original.skillExperienceMap, current.skillExperienceMap, created)
    addValue("魔力量", original.manaAmount, current.manaAmount, created)
    addList("エディターコメント", original.editorMeta.comment, current.editorMeta.comment, created)
}

private fun MutableList<Change>.addValue(label: String, before: Any?, after: Any?, created: Boolean) {
    if (created || before != after) add(Change(label, before, after, created))
}

private fun MutableList<Change>.addList(
    label: String,
    before: List<*>,
    after: List<*>,
    created: Boolean
) {
    val indexes = if (created) after.indices else 0 until maxOf(before.size, after.size)
    indexes.forEach { index ->
        addValue("$label / ${index + 1}番目", before.getOrNull(index), after.getOrNull(index), created)
    }
}

private fun MutableList<Change>.addMap(
    label: String,
    before: Map<*, *>,
    after: Map<*, *>,
    created: Boolean
) {
    val keys = (before.keys + after.keys).distinct().sortedBy(Any?::toString)
    keys.forEach { key ->
        addValue("$label / ${ConflictValueFormatter.format(key)}", before[key], after[key], created)
    }
}

private fun formatValue(value: Any?): String {
    val normalized = ConflictValueFormatter.format(value)
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (normalized.length <= 180) normalized else normalized.take(177) + "..."
}
