package io.github.sushiericworkspace.sushiericservermanager.editor.shop

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericdatamodel.database.run.PostgresRunBinding
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javafx.application.Platform
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.util.UUID

/** 通常HostとManagement所有Modに共通の商品操作口。実装は同期のためI/O workerから呼びます。 */
interface ShopTransport : AutoCloseable {
    fun databaseBinding(): PostgresRunBinding? = null
    fun worldUuid(): String
    /** PG writerのSAVEDは完成確認のEditedへ対応付けます。即時応答にない取引IDは空とし、必要時はreconcileで照合します。 */
    fun execute(request: HostAdapterRequest): HostAdapterResponse
    /** PG HostはEditを再送せず、元ID/hashのread-only観測だけを行います。 */
    fun reconcile(request: HostAdapterRequest): HostAdapterResponse = execute(request)
    override fun close() = Unit
}

@Serializable
private data class PostgresPendingRequest(@Required val storageContractVersion: Int = 2,
    val binding: PostgresRunBinding, val request: HostAdapterRequest)

/**
 * 送信前に元要求を永続化します。UNKNOWN・切断・不正応答では新規操作を拒否し、
 * [reconcile]は保存した同じIDと本文を照合へ渡します。PGではEditを再送しません。編集backupは呼出元が維持します。
 * 同一接続のI/Oキューから直列に使用してください。
 */
class ShopSaveSession(private val transport: ShopTransport, private val pendingFile: Path) {
    private val pendingJson = Json { encodeDefaults = true }
    fun close() = transport.close()
    internal fun databaseBinding(): PostgresRunBinding? {
        requireIoThread()
        return transport.databaseBinding()
    }
    private var boundWorld: String? = null

    fun read(ids: List<String>): HostAdapterResponse {
        requireIoThread()
        val request = HostAdapterRequest(requestId = UUID.randomUUID().toString(),
            worldUuid = currentWorld(), action = HostAdapterAction.Products(ids))
        return transport.execute(request).also { require(it.requestId == request.requestId && it.version == 1) }
    }

    internal fun prepare(edit: HostAdapterAction.Edit): HostAdapterRequest = locked {
        requireIoThread()
        check(pending() == null) { "結果未確認の商品保存があります。元要求を照合してください。" }
        HostAdapterRequest(requestId = UUID.randomUUID().toString(), worldUuid = currentWorld(), action = edit)
    }

    fun execute(edit: HostAdapterAction.Edit): HostAdapterResponse = execute(prepare(edit))

    internal fun execute(request: HostAdapterRequest): HostAdapterResponse = locked { executeLocked(request) }

    private fun executeLocked(request: HostAdapterRequest, reconcile: Boolean = false): HostAdapterResponse {
        requireIoThread()
        check(!Files.exists(pendingFile)) { "結果未確認の商品保存があります。元要求を照合してください。" }
        require(request.worldUuid == currentWorld() && request.action is HostAdapterAction.Edit)
        val binding = transport.databaseBinding()
        val bytes = if (binding == null) HostAdapterCodec.encodeRequest(request)
            else pendingJson.encodeToString(PostgresPendingRequest(binding = binding, request = request)).encodeToByteArray()
        Files.createDirectories(pendingFile.parent)
        val temporary = Files.createTempFile(pendingFile.parent, ".shop-request-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, pendingFile, ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return send(request, reconcile)
    }

    /** 接続し直した場合も保存済みのworldと要求を維持し、別worldへの適用を拒否します。 */
    fun reconcile(): HostAdapterResponse = locked {
        requireIoThread()
        val request = requireNotNull(pending())
        require(request.worldUuid == currentWorld()) { "保存待ち要求のworldが接続先と一致しません。" }
        send(request, reconcile = true)
    }

    /** 台帳と同じ要求だけを照合します。成功後に台帳更新が失敗した場合もIDを変えません。 */
    internal fun reconcile(request: HostAdapterRequest): HostAdapterResponse = locked {
        val current = pending()
        require(current == null || current == request) { "別の商品要求が保留されています。" }
        require(request.worldUuid == currentWorld() && request.action is HostAdapterAction.Edit)
        if (current == null) {
            executeLocked(request, reconcile = true)
        } else send(request, reconcile = true)
    }

    fun pending(): HostAdapterRequest? {
        requireIoThread()
        if (!Files.exists(pendingFile)) return null
        val bytes = Files.readAllBytes(pendingFile)
        val binding = transport.databaseBinding()
        if (binding == null) return HostAdapterCodec.decodeRequest(bytes)
        val saved = pendingJson.decodeFromString<PostgresPendingRequest>(bytes.decodeToString(throwOnInvalidSequence = true))
        require(saved.storageContractVersion == 2 && saved.binding == binding) {
            "保存待ち要求のDB/world/generationが接続先と一致しません。元要求を保全しました。"
        }
        return PostgresOfflineLookupCodec.decodeRequest(HostAdapterCodec.encodeRequest(saved.request))
    }

    private fun currentWorld(): String = transport.worldUuid().also {
        require(boundWorld == null || boundWorld == it) { "接続先worldが変わりました。編集接続を作り直してください。" }
        boundWorld = it
    }

    private fun requireIoThread() {
        check(!Platform.isFxApplicationThread()) { "商品DB操作はI/O workerで実行してください。" }
    }

    private fun <T> locked(operation: () -> T): T {
        requireIoThread()
        Files.createDirectories(pendingFile.parent)
        return FileChannel.open(pendingFile.resolveSibling(pendingFile.fileName.toString() + ".lock"), CREATE, WRITE).use { channel ->
            checkNotNull(channel.tryLock()) { "別の保存処理がこの接続の保存待ち記録を使用しています。" }.use { operation() }
        }
    }

    private fun send(request: HostAdapterRequest, reconcile: Boolean = false): HostAdapterResponse {
        val response = try {
            (if (reconcile) transport.reconcile(request) else transport.execute(request)).also {
                require(it.requestId == request.requestId && it.version == 1)
                require(it.status != HostAdapterStatus.SUCCESS || it.result is HostAdapterResult.Edited)
                require(it.status != HostAdapterStatus.SUCCESS || !it.rejectedBeforeEffect)
            }
        } catch (failure: Exception) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            return HostAdapterResponse(requestId = request.requestId, status = HostAdapterStatus.UNKNOWN,
                code = "PENDING_REVIEW", message = "保存結果を確認できません。元要求とバックアップを保持しています。")
        }
        if (response.status == HostAdapterStatus.SUCCESS ||
            response.status == HostAdapterStatus.REJECTED && response.rejectedBeforeEffect) {
            try { Files.delete(pendingFile) } catch (failure: Exception) {
                return HostAdapterResponse(requestId = request.requestId, status = HostAdapterStatus.UNKNOWN,
                    code = "PENDING_REVIEW", message = "応答後の保存待ち記録を整理できません。元要求を照合してください。")
            }
        }
        return if (response.status == HostAdapterStatus.REJECTED && !response.rejectedBeforeEffect)
            response.copy(status = HostAdapterStatus.UNKNOWN, code = "PENDING_REVIEW") else response
    }
}
