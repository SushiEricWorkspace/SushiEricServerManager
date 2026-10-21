package io.github.sushiericworkspace.sushiericservermanager.app

/** 起動中だけ保持する通知です。画面の寿命と切り離し、旧接続・旧操作の更新を拒否します。 */
internal class NotificationState(private val clock: () -> Long = System::currentTimeMillis) {
    data class Scope(val session: Long, val connection: String, val source: String, val feature: String, val target: String)
    data class Operation(val scope: Scope, val channel: String, val id: Long)
    enum class Severity { INFO, SUCCESS, WARNING, ERROR }
    data class Notice(
        val scope: Scope, val key: String, val text: String, val severity: Severity,
        val expiresAt: Long? = null, val progress: Double? = null,
        val operation: Operation? = null
    )
    private var session = 0L
    private var connection = "未接続"
    private var sequence = 0L
    private val notices = linkedMapOf<Pair<Scope, String>, Notice>()
    private val operations = mutableMapOf<Pair<Scope, String>, Operation>()
    private val latest = mutableMapOf<Pair<Scope, String>, Operation>()
    private val listeners = mutableSetOf<() -> Unit>()

    @Synchronized fun scope(source: String, feature: String, target: String = ""): Scope =
        Scope(session, connection, source, feature, target)

    @Synchronized fun beginSession(name: String) {
        endSession()
        connection = name
    }

    @Synchronized fun endSession() {
        operations.values.toList().forEach { interrupt(it, "処理は中断されました。結果を確認してください。") }
        notices.entries.removeIf { it.value.scope.feature == "接続状態" }
        latest.clear()
        session++
        changed()
    }

    @Synchronized fun observe(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { synchronized(this) { listeners -= listener } }
    }

    @Synchronized fun snapshot(): List<Notice> = notices.values.toList()

    @Synchronized fun expire() {
        if (notices.entries.removeIf { it.value.expiresAt?.let { end -> end <= clock() } == true }) changed()
    }

    @Synchronized fun toast(scope: Scope, text: String, severity: Severity = Severity.INFO, seconds: Double = 3.0) {
        if (scope.session != session || text.isBlank()) return
        val key = "toast:$text"
        notices[scope to key] = Notice(scope, key, text, severity, clock() + (seconds * 1000).toLong())
        changed()
    }

    /** nullはこのscopeの当該原因が解消した場合だけ渡します。別対象の通知には作用しません。 */
    @Synchronized fun condition(scope: Scope, key: String, text: String?, severity: Severity = Severity.WARNING) {
        if (scope.session != session) return
        val identity = scope to key
        if (text == null) {
            if (notices.remove(identity) != null) changed()
        } else {
            val next = Notice(scope, key, text, severity)
            if (notices[identity] != next) { notices[identity] = next; changed() }
        }
    }

    /** 現在の要求を読戻しで解決したときだけ、同じ保存元・要求の旧接続のBannerも解消します。 */
    @Synchronized fun resolve(scope: Scope, key: String) {
        if (scope.session != session) return
        if (notices.entries.removeIf { (_, notice) ->
            notice.key == key && notice.scope.source == scope.source &&
                notice.scope.feature == scope.feature && notice.scope.target == scope.target
        }) changed()
    }

    /** 一括処理の対象別結果を予約します。進捗行は一括操作側で表示します。 */
    @Synchronized fun prepare(scope: Scope, channel: String): Operation {
        val operation = Operation(scope, channel, ++sequence)
        if (scope.session != session) return operation
        operations[scope to channel]?.let { interrupt(it, "別の処理へ切り替わりました。結果を確認してください。") }
        latest[scope to channel] = operation
        return operation
    }

    @Synchronized fun start(scope: Scope, channel: String, text: String): Operation {
        val operation = prepare(scope, channel)
        if (scope.session != session) return operation
        operations[scope to channel] = operation
        notices[scope to operation.key] = Notice(scope, operation.key, text, Severity.INFO, progress = -1.0, operation = operation)
        changed()
        return operation
    }

    /** この操作が最新のときだけ結果を反映します。後発の失敗を旧成功で消しません。 */
    @Synchronized fun result(operation: Operation, key: String, error: String?, severity: Severity = Severity.WARNING): Boolean {
        if (operation.scope.session != session || latest[operation.scope to operation.channel] != operation) return false
        finish(operation)
        latest.remove(operation.scope to operation.channel, operation)
        if (error == null) resolve(operation.scope, key)
        else condition(operation.scope, key, error, severity)
        return true
    }

    @Synchronized fun update(operation: Operation, text: String, completed: Int? = null, total: Int? = null) {
        if (!active(operation)) return
        val count = if (completed != null && total != null && total > 0) " $completed / $total" else ""
        val progress = if (completed != null && total != null && total > 0) (completed.toDouble() / total).coerceIn(0.0, 1.0) else -1.0
        notices[operation.scope to operation.key] = Notice(operation.scope, operation.key, text + count, Severity.INFO,
            progress = progress, operation = operation)
        changed()
    }

    @Synchronized fun fraction(operation: Operation, text: String, value: Double) {
        if (!active(operation)) return
        notices[operation.scope to operation.key] = Notice(operation.scope, operation.key, text, Severity.INFO,
            progress = if (value.isFinite() && value in 0.0..1.0) value else -1.0, operation = operation)
        changed()
    }

    @Synchronized fun finish(operation: Operation) {
        if (!active(operation)) return
        operations.remove(operation.scope to operation.channel)
        notices.remove(operation.scope to operation.key)
        changed()
    }

    /** 接続が失われた通信操作だけを止めます。ローカル保存の通知は消しません。 */
    @Synchronized fun interruptRemote() {
        latest.values.filter { it.channel.startsWith("upload:") }.toList().forEach {
            interrupt(it, "通信が切断されました。未反映または結果不明の要求を読戻しで確認してください。")
        }
    }

    private fun interrupt(operation: Operation, text: String) {
        operations.remove(operation.scope to operation.channel)
        if (latest[operation.scope to operation.channel] == operation) latest.remove(operation.scope to operation.channel)
        notices.remove(operation.scope to operation.key)
        if (operation.channel.startsWith("upload:")) {
            notices[operation.scope to operation.channel] = Notice(operation.scope, operation.channel, text, Severity.WARNING)
        } else {
            notices[operation.scope to operation.key] = Notice(operation.scope, operation.key, text, Severity.WARNING, clock() + 5000)
        }
        changed()
    }

    private fun active(operation: Operation) = operation.scope.session == session && operations[operation.scope to operation.channel] == operation
    private val Operation.key: String get() = "operation:$id"
    private fun changed() { listeners.toList().forEach { it() } }
}
