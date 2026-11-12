package io.github.sushiericworkspace.sushiericservermanager.editor.service

/** 編集画面が観測した文書version。自身の成功した書込みだけで前進させます。 */
internal class LocalDocumentVersion(val key: EditorDocumentKey, revision: Long, backupOwned: Boolean = false) {
    @Volatile var revision: Long = revision
        private set

    /** この編集画面が引き継いだ、または正常退避した未保存内容だけを解除対象にします。 */
    @Volatile var backupOwned: Boolean = backupOwned
        private set

    fun accepted(record: LocalDocumentRecord) {
        check(record.key == key) { "異なる保存元・文書のversionは更新できません。" }
        revision = record.revision
        backupOwned = record.unsaved
    }
}

/** UIスレッドで複製した退避内容と、その画面が観測した文書versionです。 */
internal data class LocalBackupSnapshot<T>(
    val generation: Long,
    val entries: Map<String, Pair<T, T>>,
    val removedIds: Set<String>,
    val pending: List<PendingStoreOperationRecord>,
    val raw: Map<String, Map<String, String>> = emptyMap(),
    val versions: Map<String, LocalDocumentVersion> = emptyMap()
)
