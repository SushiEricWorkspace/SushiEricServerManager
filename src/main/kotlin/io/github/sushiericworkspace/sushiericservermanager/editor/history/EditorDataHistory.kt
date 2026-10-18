package io.github.sushiericworkspace.sushiericservermanager.editor.history

import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentKey

/** 保存元・種別・公開IDごとに、起動中だけ保持する元に戻す・やり直し履歴です。 */
internal class EditorDataHistory<T>(
    private val copy: (T) -> T,
    private val maxEntries: Int = 100
) {
    private data class State<T>(
        val undo: ArrayDeque<T> = ArrayDeque(),
        var current: T,
        val redo: ArrayDeque<T> = ArrayDeque()
    )

    private val states = mutableMapOf<EditorDocumentKey, State<T>>()

    fun initialize(id: EditorDocumentKey, value: T) {
        states.putIfAbsent(id, State(current = copy(value)))
    }

    fun record(id: EditorDocumentKey, value: T) {
        val state = states[id]
        if (state == null) {
            initialize(id, value)
            return
        }
        if (state.current == value) return
        state.undo.addLast(copy(state.current))
        while (state.undo.size > maxEntries) state.undo.removeFirst()
        state.current = copy(value)
        state.redo.clear()
    }

    fun undo(id: EditorDocumentKey, current: T): T? {
        val state = states[id] ?: return null
        val previous = state.undo.removeLastOrNull() ?: return null
        state.redo.addLast(copy(current))
        state.current = copy(previous)
        return copy(previous)
    }

    fun redo(id: EditorDocumentKey, current: T): T? {
        val state = states[id] ?: return null
        val next = state.redo.removeLastOrNull() ?: return null
        state.undo.addLast(copy(current))
        state.current = copy(next)
        return copy(next)
    }

    fun canUndo(id: EditorDocumentKey): Boolean = states[id]?.undo?.isNotEmpty() == true

    fun canRedo(id: EditorDocumentKey): Boolean = states[id]?.redo?.isNotEmpty() == true

    fun reset(id: EditorDocumentKey, value: T) {
        states[id] = State(current = copy(value))
    }

    fun rename(oldId: EditorDocumentKey, newId: EditorDocumentKey) {
        states.remove(oldId)?.let { states[newId] = it }
    }

    fun remove(id: EditorDocumentKey) {
        states.remove(id)
    }

    fun clear() = states.clear()
}
