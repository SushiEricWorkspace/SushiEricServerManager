package io.github.sushiericworkspace.sushiericservermanager.editor.main.shop

internal data class ShopOrderValue(val order: Int, val raw: String)

/** 表示順だけの複数文書コマンドと、通常文書履歴との操作順を保持します。 */
internal class ShopOrderHistory {
    private sealed interface Entry {
        val ids: Set<String>
        data class Document(val id: String) : Entry { override val ids = setOf(id) }
        data class Order(val before: Map<String, ShopOrderValue>, val after: Map<String, ShopOrderValue>, val allSiblings: Boolean) : Entry {
            override val ids = before.keys
        }
    }
    sealed interface Step {
        data object Document : Step
        data object Rejected : Step
        data class Order(val values: Map<String, ShopOrderValue>) : Step
    }
    private val undo = mutableListOf<Entry>()
    private val redo = mutableListOf<Entry>()

    fun recordDocument(id: String) { undo += Entry.Document(id); redo.clear() }

    fun recordOrder(before: Map<String, ShopOrderValue>, after: Map<String, ShopOrderValue>, allSiblings: Boolean = true) {
        require(before.keys == after.keys)
        if (before == after) return
        undo += Entry.Order(before.toMap(), after.toMap(), allSiblings)
        redo.clear()
    }

    fun hasOrder(id: String, forward: Boolean): Boolean =
        (if (forward) redo else undo).lastOrNull { id in it.ids } is Entry.Order

    /** 不一致では履歴位置を動かさず、通常Undoへのフォールスルーも許しません。 */
    fun peek(id: String, forward: Boolean, current: (Set<String>, Boolean) -> Map<String, ShopOrderValue>?): Step {
        val entries = if (forward) redo else undo
        val index = entries.indexOfLast { id in it.ids }
        val entry = entries.getOrNull(index) as? Entry.Order ?: return Step.Document
        if (entries.drop(index + 1).any { it is Entry.Order && it.ids.any(entry.ids::contains) }) return Step.Rejected
        val expected = if (forward) entry.before else entry.after
        if (current(entry.ids, entry.allSiblings) != expected) return Step.Rejected
        return Step.Order(if (forward) entry.after else entry.before)
    }

    fun traversed(id: String, forward: Boolean) {
        val from = if (forward) redo else undo
        val to = if (forward) undo else redo
        val index = from.indexOfLast { id in it.ids }
        if (index >= 0) to += from.removeAt(index)
    }

    /** 一件の正本保存で、関係する順序コマンドを全メンバー同時に確定します。 */
    fun saved(id: String) {
        val related = mutableSetOf(id)
        var changed: Boolean
        do {
            changed = false
            (undo + redo).filterIsInstance<Entry.Order>().forEach {
                if (it.ids.any(related::contains) && related.addAll(it.ids)) changed = true
            }
        } while (changed)
        undo.removeAll { it is Entry.Order && it.ids.any(related::contains) }
        redo.removeAll { it is Entry.Order && it.ids.any(related::contains) }
    }

    fun clear() { undo.clear(); redo.clear() }
}

internal fun shopOrderIds(values: Map<String, ShopOrderValue>): List<String> =
    values.keys.sortedWith(compareBy({ values.getValue(it).order }, { it }))

internal fun moveShopOrder(values: Map<String, ShopOrderValue>, from: String, target: String, below: Boolean): Map<String, ShopOrderValue>? {
    if (from == target || from !in values || target !in values) return null
    val ordered = shopOrderIds(values).toMutableList()
    ordered.remove(from)
    ordered.add(ordered.indexOf(target) + if (below) 1 else 0, from)
    return ordered.mapIndexed { index, id -> id to ShopOrderValue(index, index.toString()) }.toMap()
}
