package io.github.sushiericworkspace.sushiericservermanager.editor.service

/** UIスレッドで複製した、1世代のローカル退避内容。 */
internal data class LocalBackupSnapshot<T>(
    val generation: Long,
    val entries: Map<String, Pair<T, T>>,
    val removedIds: Set<String>,
    val pending: List<PendingStoreOperationRecord>
)

/** 接続のローカルI/Oキューだけから使用し、成功したペアと保留操作を記録します。 */
internal class LocalBackupState<T>(
    private val write: (String, String, T) -> Boolean,
    private val delete: (String) -> Boolean,
    private val writePending: (List<PendingStoreOperationRecord>) -> Boolean
) {
    private val completed = mutableMapOf<String, Pair<T, T>>()
    private val deleted = mutableSetOf<String>()
    private var completedPending: List<PendingStoreOperationRecord>? = null

    fun persist(snapshot: LocalBackupSnapshot<T>): Boolean {
        var success = true
        snapshot.entries.forEach { (id, pair) ->
            if (completed[id] != pair) {
                completed.remove(id)
                deleted.remove(id)
                val editingSaved = write(id, "editing", pair.first)
                val originalSaved = write(id, "original", pair.second)
                if (editingSaved && originalSaved) completed[id] = pair
                else success = false
            }
        }
        if (!success) return false
        if (completedPending != snapshot.pending) {
            if (!writePending(snapshot.pending)) return false
            completedPending = snapshot.pending
        }
        val removed = (snapshot.removedIds + completed.keys) - snapshot.entries.keys
        removed.forEach { id ->
            if (id !in deleted) {
                if (delete(id)) {
                    completed.remove(id)
                    deleted.add(id)
                } else success = false
            }
        }
        return success
    }
}
