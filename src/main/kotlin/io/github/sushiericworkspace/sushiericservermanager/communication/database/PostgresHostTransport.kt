package io.github.sushiericworkspace.sushiericservermanager.communication.database

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericservermanager.editor.shop.ShopTransport
import javafx.application.Platform
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 同じH本人をOpenからCloseまで保持します。失敗後は再起動・Edit再送せずread-only照合だけを許可します。 */
internal class PostgresHostTransport(
    private val reference: PostgresHostReference,
    private val start: (Boolean) -> HostChannel,
    private val current: () -> Unit = {},
    private val readRunState: () -> ByteArray = { reference.runState.encodeToByteArray() },
    private val timeoutMillis: Long = 30_000
) : ShopTransport {
    override fun databaseBinding() = reference.status.state.binding
    private var channel: HostChannel? = null
    private var session: String? = null
    private var failed = false
    private var closed = false
    private var cleanupComplete = false
    private var runSha256 = reference.status.sha256
    private var revision = reference.status.state.stateRevision
    private val workers = Executors.newFixedThreadPool(3) { Thread(it, "postgres-host-io").apply { isDaemon = true } }
    private var stderr: java.util.concurrent.Future<*>? = null

    override fun worldUuid(): String {
        requireWorker()
        reference.validate()
        current()
        return reference.status.state.binding.worldUuid
    }

    override fun execute(request: HostAdapterRequest): HostAdapterResponse {
        requireWorker()
        require(request.worldUuid == worldUuid())
        check(!closed && !failed) { "Host sessionの結果が未確認です。元要求を照合してください。" }
        try {
            if (channel == null) {
                val observed = reference.observe(readRunState())
                runSha256 = observed.sha256
                revision = observed.state.stateRevision
                val openedChannel = start(false)
                channel = openedChannel
                stderr = workers.submit { openedChannel.error.use { it.transferTo(OutputStream.nullOutputStream()) } }
                session = UUID.randomUUID().toString()
                val opened = exchange(PostgresWriterCommand.Open(runSha256, requireNotNull(session)))
                require(opened.status == PostgresWriterStatus.OPENED && opened.sessionUuid == session &&
                    opened.runSha256 == runSha256 && opened.stateRevision == revision)
            }
            val edit = request.action is HostAdapterAction.Edit
            val result = exchange(if (edit) PostgresWriterCommand.Edit(request) else PostgresWriterCommand.Read(request))
            require(result.sessionUuid == session && result.requestId == request.requestId)
            if (result.status == PostgresWriterStatus.REJECTED) {
                failed = true
                close()
                return HostAdapterResponse(requestId = request.requestId, status = HostAdapterStatus.REJECTED,
                    code = "HOST_REJECTED")
            }
            require(result.status == if (edit) PostgresWriterStatus.SAVED else PostgresWriterStatus.READ)
            val hash = requireNotNull(result.runSha256)
            val nextRevision = requireNotNull(result.stateRevision)
            require(hash.matches(Regex("[0-9a-f]{64}")) && nextRevision >= revision)
            if (!edit) require(hash == runSha256 && nextRevision == revision)
            else require(nextRevision > revision)
            runSha256 = hash
            revision = nextRevision
            return HostAdapterResponse(requestId = request.requestId, status = HostAdapterStatus.SUCCESS,
                result = if (edit) HostAdapterResult.Edited(emptyList()) else requireNotNull(result.result))
        } catch (failure: Exception) {
            failed = true
            try { close() } catch (closing: Exception) { failure.addSuppressed(closing) }
            throw failure
        }
    }

    override fun reconcile(request: HostAdapterRequest): HostAdapterResponse {
        requireWorker()
        require(request.worldUuid == worldUuid() && request.action is HostAdapterAction.Edit)
        // writerの終了を確認してから、別processのread-only入口だけへ元要求を渡します。
        close()
        current()
        val observed = reference.observe(readRunState())
        val lookup = start(true)
        val errors = workersForLookup()
        try {
            val drain = errors.submit { lookup.error.use { it.transferTo(OutputStream.nullOutputStream()) } }
            val work = errors.submit<PostgresOfflineLookupResponse> {
                lookup.output.use { it.write(HostAdapterCodec.encodeRequest(request)) }
                val bytes = lookup.input.readNBytes(PostgresOfflineLookupCodec.MAX_BYTES + 1)
                PostgresOfflineLookupCodec.decodeResponse(bytes)
            }
            val response = work.get(timeoutMillis, TimeUnit.MILLISECONDS)
            drain.get(timeoutMillis, TimeUnit.MILLISECONDS)
            val exit = lookup.awaitExit(timeoutMillis)
            require(response.requestId == request.requestId &&
                response.requestSha256 == PostgresOfflineLookupCodec.requestHash(request))
            current()
            val after = reference.observe(readRunState())
            require(observed == after && response.runSha256 == observed.sha256)
            val expectedExit = when (response.outcome) {
                PostgresOfflineLookupOutcome.COMPLETE -> 0
                PostgresOfflineLookupOutcome.NOT_FOUND, PostgresOfflineLookupOutcome.REJECTED -> 2
                PostgresOfflineLookupOutcome.CONFLICT -> 4
                else -> 3
            }
            require(exit == expectedExit)
            return if (response.outcome == PostgresOfflineLookupOutcome.COMPLETE)
                HostAdapterResponse(requestId = request.requestId, status = HostAdapterStatus.SUCCESS,
                    result = HostAdapterResult.Edited(response.transactionIds))
            else HostAdapterResponse(requestId = request.requestId, status = HostAdapterStatus.UNKNOWN,
                code = response.outcome.name)
        } finally {
            try { lookup.close() } finally {
                errors.shutdownNow()
                check(errors.awaitTermination(5, TimeUnit.SECONDS)) { "照合workerの終了を確認できません。" }
            }
        }
    }

    private fun workersForLookup() = Executors.newFixedThreadPool(2) {
        Thread(it, "postgres-host-lookup").apply { isDaemon = true }
    }

    private fun exchange(command: PostgresWriterCommand): PostgresWriterResponse {
        current()
        val active = requireNotNull(channel)
        val work = workers.submit<PostgresWriterResponse> {
            PostgresWriterCodec.writeFrame(active.output,
                PostgresWriterCodec.encodeRequest(PostgresWriterRequest(command = command)))
            PostgresWriterCodec.decodeResponse(requireNotNull(PostgresWriterCodec.readFrame(active.input)))
        }
        return try { work.get(timeoutMillis, TimeUnit.MILLISECONDS).also { current() } }
        finally { work.cancel(true) }
    }

    override fun close() {
        check(!Platform.isFxApplicationThread()) { "DB操作はI/O workerで実行してください。" }
        if (cleanupComplete) return
        val sendClose = !closed && !failed
        closed = true
        val interrupted = Thread.interrupted()
        try {
            channel?.let { active ->
                try {
                    if (sendClose) {
                        val response = exchange(PostgresWriterCommand.Close)
                        require(response.status == PostgresWriterStatus.CLOSED && response.sessionUuid == session)
                    }
                    active.output.close()
                    val exit = active.awaitExit(timeoutMillis)
                    require(if (failed) exit in setOf(0, 2, 3) else exit == 0)
                } finally { active.close() }
            }
            stderr?.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } finally {
            workers.shutdownNow()
            try { check(workers.awaitTermination(5, TimeUnit.SECONDS)) { "Host workerの終了を確認できません。" } }
            finally { if (interrupted) Thread.currentThread().interrupt() }
        }
        cleanupComplete = true
    }

    private fun requireWorker() {
        check(!Platform.isFxApplicationThread()) { "DB操作はI/O workerで実行してください。" }
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }

}
