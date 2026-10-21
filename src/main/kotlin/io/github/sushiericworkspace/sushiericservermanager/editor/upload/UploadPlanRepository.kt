package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericdatamodel.data.core.identity.PublicId
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDocumentSource
import io.github.sushiericworkspace.sushiericservermanager.editor.service.LocalDocumentRepository
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 宛先別の可変予約だけをatomic保存します。古い画面からの保存や壊れた原本を上書きしません。 */
internal class UploadPlanRepository(root: File, private val target: EditorDocumentSource.Server) {
    private val directory = root.resolve(target.directory)
    private val file = directory.resolve("plan.json")
    private val json = Json { prettyPrint = true; encodeDefaults = true }

    private companion object {
        val publishers = ConcurrentHashMap<Path, Any>()
    }

    fun read(): StoreResult<UploadPlan> = result { readCurrent() }

    fun save(plan: UploadPlan): StoreResult<UploadPlan> = result { publish(plan.snapshot()) }

    /** 固定台帳を実際に読み、完了または確定停止した要求だけ明示的に予定から外せます。台帳自体は残します。 */
    fun removeResolved(plan: UploadPlan, id: String, documents: LocalDocumentRepository): StoreResult<UploadPlan> = result {
        require(documents.source == target) { "要求を確認する保存元が一致しません。" }
        val fixed = plan.snapshot()
        val group = fixed.groups.first { it.id == id }
        val request = requireNotNull(group.operationId) { "実行要求へ結び付いていません。" }
        val loaded = documents.read()
        check(loaded is StoreResult.Success) { "固定要求の台帳を読めないため予定を保持します。" }
        val operation = loaded.value.uploads.firstOrNull { it.id == request }
        val stopped = operation != null && !operation.needsReadback &&
            (operation.complete || operation.steps.any { it.state in setOf(SavedUploadState.FAILED, SavedUploadState.CANCELLED) })
        check(stopped) { "実行中または結果不明の要求は予定を保持します。同じ要求の結果を確認してください。" }
        publish(fixed.copy(groups = fixed.groups.filterNot { it.id == id }), id to request)
    }

    private fun publish(plan: UploadPlan, released: Pair<String, String>? = null): UploadPlan {
        require(plan.target == target) { "予約の宛先が一致しません。" }
        Files.createDirectories(directory.toPath())
        val canonical = directory.toPath().toRealPath()
        return synchronized(publishers.computeIfAbsent(canonical) { Any() }) {
            FileChannel.open(directory.resolve("publisher.lock").toPath(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { lockChannel ->
                lockChannel.lock().use {
                    val current = readCurrent()
                    check(current.revision == plan.revision) { "送信予約が別の画面で更新されています。" }
                    current.groups.filter { it.operationId != null }.forEach { previous ->
                        val nextGroup = plan.groups.firstOrNull { it.id == previous.id }
                        check(nextGroup == previous || (nextGroup == null && released == (previous.id to previous.operationId))) {
                            "固定要求に結び付いた予約は結果を確認するまで変更・解除できません。"
                        }
                    }
                    val next = plan.copy(revision = current.revision + 1)
                    validate(next)
                    val temporary = directory.resolve(".plan-" + UUID.randomUUID())
                    FileChannel.open(temporary.toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                        val bytes = ByteBuffer.wrap(json.encodeToString(next).toByteArray(Charsets.UTF_8))
                        while (bytes.hasRemaining()) channel.write(bytes)
                        channel.force(true)
                    }
                    Files.move(temporary.toPath(), file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    next
                }
            }
        }
    }

    private fun readCurrent(): UploadPlan {
        if (!file.exists()) return UploadPlan(target)
        return json.decodeFromString<UploadPlan>(file.readText(Charsets.UTF_8)).also(::validate)
    }

    private fun validate(plan: UploadPlan) {
        check(plan.version == 1 && plan.target == target) { "送信予約の形式または宛先が一致しません。" }
        check(plan.revision >= 0 && plan.groups.map { it.id }.toSet().size == plan.groups.size) { "送信予約の識別子が不正です。" }
        val destinations = mutableSetOf<Pair<String, String>>()
        plan.groups.forEach { group ->
            check(group.id.isNotBlank() && group.documents.isNotEmpty() && isValidUploadDestination(group.targetDirectory)) {
                "送信予約の割当てが不正です。"
            }
            check(group.documents.map { it.key }.toSet().size == group.documents.size) { "保存元文書が重複しています。" }
            group.documents.forEach { document ->
                check(document.key.source == EditorDocumentSource.Local && document.savedRevision >= 0 &&
                    document.savedHash.matches(Regex("[0-9a-f]{64}")) &&
                    PublicId.isValidFull(document.key.id) && PublicId.isValidFull(document.targetId) &&
                    (group.targetDirectory.isEmpty() || document.targetId.startsWith(group.targetDirectory + ".")) &&
                    destinations.add(document.key.category to document.targetId)) { "送信予約の文書が不正または重複しています。" }
            }
            check(group.itemIds.values.toSet().size == group.itemIds.size &&
                group.oreIds.values.toSet().size == group.oreIds.size) { "複製先の内部IDが重複しています。" }
        }
    }

    private fun <T> result(action: () -> T): StoreResult<T> = try {
        StoreResult.Success(action())
    } catch (error: Exception) {
        StoreResult.Failure(StoreError(StoreErrorCode.IO_ERROR, detail = error.message, cause = error))
    }
}
