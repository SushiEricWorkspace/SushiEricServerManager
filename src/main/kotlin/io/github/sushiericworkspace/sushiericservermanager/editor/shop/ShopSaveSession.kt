package io.github.sushiericworkspace.sushiericservermanager.editor.shop

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import javafx.application.Platform
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.*
import java.util.UUID

/** 通常HostとManagement所有Modに共通の商品操作口。実装は同期のためI/O workerから呼びます。 */
interface ShopTransport {
    fun worldUuid(): String
    fun execute(request: HostAdapterRequest): HostAdapterResponse
}

/**
 * 送信前に元要求を永続化します。UNKNOWN・切断・不正応答では新規操作を拒否し、
 * [reconcile]だけが保存した同じIDと本文を再送します。編集backupは呼出元が維持します。
 * 同一接続のI/Oキューから直列に使用してください。
 */
class ShopSaveSession(private val transport: ShopTransport, private val pendingFile: Path) {
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

    private fun executeLocked(request: HostAdapterRequest): HostAdapterResponse {
        requireIoThread()
        check(!Files.exists(pendingFile)) { "結果未確認の商品保存があります。元要求を照合してください。" }
        require(request.worldUuid == currentWorld() && request.action is HostAdapterAction.Edit)
        val bytes = HostAdapterCodec.encodeRequest(request)
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
        return send(request)
    }

    /** 接続し直した場合も保存済みのworldと要求を維持し、別worldへの適用を拒否します。 */
    fun reconcile(): HostAdapterResponse = locked {
        requireIoThread()
        val request = HostAdapterCodec.decodeRequest(Files.readAllBytes(pendingFile))
        require(request.worldUuid == currentWorld()) { "保存待ち要求のworldが接続先と一致しません。" }
        send(request)
    }

    /** 台帳と同じ要求だけを照合します。成功後に台帳更新が失敗した場合もIDを変えません。 */
    internal fun reconcile(request: HostAdapterRequest): HostAdapterResponse = locked {
        val current = pending()
        require(current == null || current == request) { "別の商品要求が保留されています。" }
        require(request.worldUuid == currentWorld() && request.action is HostAdapterAction.Edit)
        if (current == null) executeLocked(request) else send(request)
    }

    fun pending(): HostAdapterRequest? {
        requireIoThread()
        return if (Files.exists(pendingFile)) HostAdapterCodec.decodeRequest(Files.readAllBytes(pendingFile)) else null
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

    private fun send(request: HostAdapterRequest): HostAdapterResponse {
        val response = try {
            transport.execute(request).also {
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
