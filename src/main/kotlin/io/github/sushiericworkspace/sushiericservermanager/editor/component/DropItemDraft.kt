package io.github.sushiericworkspace.sushiericservermanager.editor.component

import io.github.sushiericworkspace.sushiericdatamodel.data.drop.model.mutable.MutableDropItemData

/** モーダル内だけで保持し、親リストとは共有しない入力・順序履歴です。 */
internal class DropItemDraft(initial: List<MutableDropItemData>) {
    val items = initial.mapTo(mutableListOf()) { it.deepCopy() }
    private var current = copy()
    private val undo = ArrayDeque<List<MutableDropItemData>>()
    private val redo = ArrayDeque<List<MutableDropItemData>>()
    private fun copy() = items.map { it.deepCopy() }

    fun record() {
        val next = copy()
        if (current == next) return
        undo.addLast(current.map { it.deepCopy() })
        current = next
        redo.clear()
    }

    fun move(from: Int, to: Int): Boolean {
        record()
        if (!moveDropItem(items, from, to)) return false
        record()
        return true
    }

    fun undo(): Boolean {
        record()
        val previous = undo.removeLastOrNull() ?: return false
        redo.addLast(copy())
        restore(previous)
        return true
    }

    fun redo(): Boolean {
        record()
        val next = redo.removeLastOrNull() ?: return false
        undo.addLast(copy())
        restore(next)
        return true
    }

    private fun restore(value: List<MutableDropItemData>) {
        items.clear()
        items.addAll(value.map { it.deepCopy() })
        current = copy()
    }
}
