package io.github.sushiericworkspace.sushiericservermanager.editor.main.item.tree

import java.util.UUID

/**
 * ItemエディタのLore Tree用UI IDをメモリ上で管理するクラス。
 *
 * 保存データには含めず、TreeViewの開閉状態などを
 * indexではなく「行・セクションそのもの」に紐づけるために使用する。
 */
class LoreTreeUiIdMemory {

    private data class ItemLoreIds(
        val lineIds: MutableList<String> = mutableListOf(),
        val sectionIdsByLineId: MutableMap<String, MutableList<String>> = mutableMapOf()
    )

    private val idsByItemId = mutableMapOf<String, ItemLoreIds>()

    /**
     * 指定アイテムのLore行数に合わせて、行UI ID一覧を取得する。
     */
    fun getLineIds(
        itemId: String,
        lineSize: Int
    ): List<String> {
        val itemIds = idsByItemId.getOrPut(itemId) {
            ItemLoreIds()
        }

        while (itemIds.lineIds.size < lineSize) {
            itemIds.lineIds.add(newId())
        }

        while (itemIds.lineIds.size > lineSize) {
            val removedLineId = itemIds.lineIds.removeAt(itemIds.lineIds.lastIndex)
            itemIds.sectionIdsByLineId.remove(removedLineId)
        }

        return itemIds.lineIds
    }

    /**
     * 指定Lore行のセクション数に合わせて、セクションUI ID一覧を取得する。
     */
    fun getSectionIds(
        itemId: String,
        lineUiId: String,
        sectionSize: Int
    ): List<String> {
        val itemIds = idsByItemId.getOrPut(itemId) {
            ItemLoreIds()
        }

        val sectionIds = itemIds.sectionIdsByLineId.getOrPut(lineUiId) {
            mutableListOf()
        }

        while (sectionIds.size < sectionSize) {
            sectionIds.add(newId())
        }

        while (sectionIds.size > sectionSize) {
            sectionIds.removeAt(sectionIds.lastIndex)
        }

        return sectionIds
    }

    /**
     * Lore行が追加されたとき、同じ位置に行UI IDを追加する。
     */
    fun lineInserted(
        itemId: String,
        index: Int
    ) {
        val itemIds = idsByItemId.getOrPut(itemId) {
            ItemLoreIds()
        }

        val safeIndex = index.coerceIn(0, itemIds.lineIds.size)
        itemIds.lineIds.add(safeIndex, newId())
    }

    /**
     * Lore行が移動されたとき、行UI IDとその行に紐づくセクションUI IDも一緒に移動する。
     */
    fun lineMoved(
        itemId: String,
        fromIndex: Int,
        toIndex: Int
    ) {
        val itemIds = idsByItemId[itemId] ?: return
        if (fromIndex !in itemIds.lineIds.indices) return

        val safeToIndex = toIndex.coerceIn(0, itemIds.lineIds.lastIndex)
        if (fromIndex == safeToIndex) return

        val movedLineId = itemIds.lineIds.removeAt(fromIndex)
        itemIds.lineIds.add(safeToIndex, movedLineId)
    }

    /**
     * Lore行が削除されたとき、その行UI IDと配下のセクションUI IDを削除する。
     */
    fun lineRemoved(
        itemId: String,
        index: Int
    ) {
        val itemIds = idsByItemId[itemId] ?: return
        if (index !in itemIds.lineIds.indices) return

        val removedLineId = itemIds.lineIds.removeAt(index)
        itemIds.sectionIdsByLineId.remove(removedLineId)
    }

    /**
     * セクションが追加されたとき、同じ位置にセクションUI IDを追加する。
     */
    fun sectionInserted(
        itemId: String,
        lineUiId: String,
        sectionIndex: Int
    ) {
        val itemIds = idsByItemId.getOrPut(itemId) {
            ItemLoreIds()
        }

        val sectionIds = itemIds.sectionIdsByLineId.getOrPut(lineUiId) {
            mutableListOf()
        }

        val safeIndex = sectionIndex.coerceIn(0, sectionIds.size)
        sectionIds.add(safeIndex, newId())
    }

    /**
     * セクションが移動されたとき、セクションUI IDも同じように移動する。
     */
    fun sectionMoved(
        itemId: String,
        lineUiId: String,
        fromSectionIndex: Int,
        toSectionIndex: Int
    ) {
        val itemIds = idsByItemId[itemId] ?: return
        val sectionIds = itemIds.sectionIdsByLineId[lineUiId] ?: return
        if (fromSectionIndex !in sectionIds.indices) return

        val safeToIndex = toSectionIndex.coerceIn(0, sectionIds.lastIndex)
        if (fromSectionIndex == safeToIndex) return

        val movedSectionId = sectionIds.removeAt(fromSectionIndex)
        sectionIds.add(safeToIndex, movedSectionId)
    }

    /**
     * セクションが削除されたとき、同じ位置のセクションUI IDを削除する。
     */
    fun sectionRemoved(
        itemId: String,
        lineUiId: String,
        sectionIndex: Int
    ) {
        val itemIds = idsByItemId[itemId] ?: return
        val sectionIds = itemIds.sectionIdsByLineId[lineUiId] ?: return

        if (sectionIndex in sectionIds.indices) {
            sectionIds.removeAt(sectionIndex)
        }
    }

    /**
     * アイテムIDが変更されたとき、UI ID情報を新しいIDへ移す。
     */
    fun renameItem(
        oldItemId: String,
        newItemId: String
    ) {
        if (oldItemId == newItemId) return
        val ids = idsByItemId.remove(oldItemId) ?: return
        idsByItemId[newItemId] = ids
    }

    /**
     * アイテム削除・エディタ破棄時に、そのアイテムのUI ID情報を破棄する。
     */
    fun clearItem(itemId: String) {
        idsByItemId.remove(itemId)
    }

    private fun newId(): String {
        return UUID.randomUUID().toString()
    }
}