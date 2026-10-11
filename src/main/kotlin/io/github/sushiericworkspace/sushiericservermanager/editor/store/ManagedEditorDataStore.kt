package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.common.data.core.identity.PublicId
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.sushiericservermanager.communication.managed.ManagedSession
import io.github.sushiericworkspace.sushiericservermanager.communication.managed.SupervisorFailure
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import kotlinx.serialization.json.*
import java.nio.file.Files

/** 管理rootのhandleを持たず、監督経由のUTF-8本文をCommonの既存Serializerへ渡します。 */
class ManagedEditorDataStore(val session: ManagedSession, private val compatibility: ManagerCompatibility) : EditorDataStore {
    override val kind = EditorDataStoreKind.REMOTE
    override val identity = "managed-${session.identity}"
    override val isAvailable get() = session.state == ManagedSession.State.OPEN

    private fun request(action: String, path: String, extra: Map<String, JsonElement> = emptyMap()): JsonObject =
        session.io(JsonObject(mapOf("action" to JsonPrimitive(action), "path" to JsonPrimitive(path)) + extra))

    private fun <T> result(path: String, write: Boolean = false, block: () -> T): StoreResult<T> {
        if (write && !compatibility.writable) return StoreResult.Failure(StoreError(StoreErrorCode.PERMISSION_DENIED))
        if (!StorePathValidator.isValidRelativePath(path)) return StoreResult.Failure(StoreError(StoreErrorCode.INVALID_ID, path))
        return try { StoreResult.Success(block()) } catch (error: Exception) {
            val code = when ((error as? SupervisorFailure)?.code) {
                "FILE_NOT_FOUND" -> StoreErrorCode.FILE_NOT_FOUND
                "ALREADY_EXISTS" -> StoreErrorCode.ALREADY_EXISTS
                "PATH_DENIED", "PERMISSION_DENIED" -> StoreErrorCode.PERMISSION_DENIED
                else -> StoreErrorCode.STORE_UNAVAILABLE
            }
            StoreResult.Failure(StoreError(code, path, cause = error))
        }
    }

    override fun readText(relativePath: String): StoreResult<String> = result(relativePath) {
        request("read", relativePath).getValue("text").jsonPrimitive.content
    }
    override fun readServerText(relativePath: String): StoreResult<String> = result(relativePath) {
        request("server-read", relativePath).getValue("text").jsonPrimitive.content
    }
    override fun writeText(relativePath: String, text: String): StoreResult<Unit> = result(relativePath, true) {
        request("write", relativePath, mapOf("text" to JsonPrimitive(text))); Unit
    }
    override fun listPath(relativePath: String): StoreResult<List<StorePathEntry>> {
        fun entries() = request("list", relativePath).getValue("entries").jsonArray.map {
            val entry = it.jsonObject
            StorePathEntry(entry.getValue("name").jsonPrimitive.content, entry.getValue("isDirectory").jsonPrimitive.boolean)
        }
        return if (relativePath.isNotEmpty()) result(relativePath) { entries() }
        else try { StoreResult.Success(entries()) } catch (error: Exception) {
            StoreResult.Failure(StoreError(StoreErrorCode.STORE_UNAVAILABLE, cause = error))
        }
    }

    private fun <T : ManagedData<T, *>> path(descriptor: EditorDataDescriptor<T>, id: String) =
        "${descriptor.relativeDirectory}/${PublicId.relativePathOf(id)}.yml"

    private fun <T : ManagedData<T, *>> walk(descriptor: EditorDataDescriptor<T>): StoreResult<List<Pair<String, Boolean>>> {
        val found = mutableListOf<Pair<String, Boolean>>()
        fun visit(directory: String): StoreResult.Failure? {
            when (val entries = listPath(directory)) {
                is StoreResult.Failure -> return entries
                is StoreResult.Success -> for (entry in entries.value) {
                    val child = "$directory/${entry.name}"
                    found += child to entry.isDirectory
                    if (entry.isDirectory) visit(child)?.let { return it }
                }
            }
            return null
        }
        return visit(descriptor.relativeDirectory) ?: StoreResult.Success(found)
    }

    override fun <T : ManagedData<T, *>> list(descriptor: EditorDataDescriptor<T>): StoreResult<List<StoreResource>> =
        when (val entries = walk(descriptor)) {
            is StoreResult.Failure -> entries
            is StoreResult.Success -> StoreResult.Success(entries.value.filter { !it.second && it.first.endsWith(".yml") }.mapNotNull {
                val id = it.first.removePrefix(descriptor.relativeDirectory + "/").removeSuffix(".yml").replace('/', '.')
                if (StorePathValidator.isValidId(id) && descriptor.isValidId(id)) StoreResource(id, it.first.substringAfterLast('/'), it.first) else null
            }.sortedBy { it.id })
        }

    override fun <T : ManagedData<T, *>> load(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<T> {
        if (!StorePathValidator.isValidId(id) || !descriptor.isValidId(id)) return StoreResult.Failure(StoreError(StoreErrorCode.INVALID_ID, id))
        return when (val text = readText(path(descriptor, id))) {
            is StoreResult.Failure -> text
            is StoreResult.Success -> result(path(descriptor, id)) {
                val file = session.cacheDirectory.resolve(path(descriptor, id))
                file.parentFile.mkdirs()
                file.writeText(text.value, Charsets.UTF_8)
                descriptor.load(file, session.cacheDirectory.resolve(descriptor.relativeDirectory)) ?: error("YAMLを読み込めません。")
            }
        }
    }

    override fun <T : ManagedData<T, *>> save(descriptor: EditorDataDescriptor<T>, id: String, data: T): StoreResult<Unit> {
        return when (val text = serialize(descriptor, id, data)) {
            is StoreResult.Failure -> text
            is StoreResult.Success -> writeText(path(descriptor, id), text.value)
        }
    }

    private fun <T : ManagedData<T, *>> serialize(descriptor: EditorDataDescriptor<T>, id: String, data: T): StoreResult<String> {
        if (!StorePathValidator.isValidId(id) || !descriptor.isValidId(id)) return StoreResult.Failure(StoreError(StoreErrorCode.INVALID_ID, id))
        val itemIds = mutableSetOf<ItemInternalId>()
        if (descriptor.validateInStore == null && descriptor != EditorDataDescriptors.item) {
            when (val items = list(EditorDataDescriptors.item)) {
                is StoreResult.Failure -> return items
                is StoreResult.Success -> for (item in items.value) {
                    when (val loaded = load(EditorDataDescriptors.item, item.id)) {
                        is StoreResult.Failure -> return loaded
                        is StoreResult.Success -> itemIds += loaded.value.internalId
                    }
                }
            }
        }
        val validation = descriptor.validateInStore?.invoke(data, this)
        if (validation is StoreResult.Failure) return validation
        val errors = data.refreshCompleted((validation as? StoreResult.Success)?.value ?: descriptor.validate(data, itemIds)).filter { it.isError }
        if (errors.isNotEmpty()) return StoreResult.Failure(StoreError(StoreErrorCode.VALIDATION_FAILED, id, errors.joinToString("\n") { it.message }))
        return result(path(descriptor, id), true) {
            session.cacheDirectory.mkdirs()
            val temporary = Files.createTempFile(session.cacheDirectory.toPath(), "serialize-", ".yml").toFile()
            try {
                descriptor.save(temporary, data, itemIds)
                temporary.readText(Charsets.UTF_8)
            } finally { temporary.delete() }
        }
    }

    override fun <T : ManagedData<T, *>> rename(descriptor: EditorDataDescriptor<T>, oldId: String, newName: String): StoreResult<Unit> {
        if (!StorePathValidator.isValidName(newName)) return StoreResult.Failure(StoreError(StoreErrorCode.INVALID_ID, newName))
        return when (val moved = relocate(descriptor, oldId, PublicId.join(PublicId.directoryOf(oldId), newName))) {
            is StoreResult.Failure -> moved
            is StoreResult.Success -> StoreResult.Success(Unit)
        }
    }
    override fun <T : ManagedData<T, *>> move(descriptor: EditorDataDescriptor<T>, id: String, targetDirectory: String): StoreResult<String> {
        if (!StorePathValidator.isValidDirectory(targetDirectory)) return StoreResult.Failure(StoreError(StoreErrorCode.INVALID_ID, targetDirectory))
        return relocate(descriptor, id, PublicId.join(targetDirectory.split('.').filter { it.isNotEmpty() }, PublicId.nameOf(id)))
    }
    private fun <T : ManagedData<T, *>> relocate(descriptor: EditorDataDescriptor<T>, id: String, targetId: String): StoreResult<String> {
        if (!StorePathValidator.isValidId(id) || !descriptor.isValidId(id) || !descriptor.isValidId(targetId)) return StoreResult.Failure(StoreError(StoreErrorCode.INVALID_ID, id))
        if (id == targetId) return StoreResult.Success(id)
        val extra = mutableMapOf<String, JsonElement>("destination" to JsonPrimitive(path(descriptor, targetId)))
        if (descriptor.storesIdInFile) {
            when (val loaded = load(descriptor, id)) {
                is StoreResult.Failure -> return loaded
                is StoreResult.Success -> when (val text = serialize(descriptor, targetId, descriptor.deepCopy(loaded.value).apply { this.id = targetId })) {
                    is StoreResult.Failure -> return text
                    is StoreResult.Success -> extra["text"] = JsonPrimitive(text.value)
                }
            }
        }
        return result(path(descriptor, id), true) {
            request("move", path(descriptor, id), extra); targetId
        }
    }
    override fun <T : ManagedData<T, *>> delete(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<Unit> {
        if (!StorePathValidator.isValidId(id) || !descriptor.isValidId(id)) return StoreResult.Failure(StoreError(StoreErrorCode.INVALID_ID, id))
        return result(path(descriptor, id), true) { request("delete", path(descriptor, id)); Unit }
    }
    override fun <T : ManagedData<T, *>> createDirectory(descriptor: EditorDataDescriptor<T>, directory: String): StoreResult<Unit> = directoryOperation(descriptor, directory, "mkdir")
    override fun <T : ManagedData<T, *>> deleteDirectory(descriptor: EditorDataDescriptor<T>, directory: String): StoreResult<Unit> = directoryOperation(descriptor, directory, "delete-directory")
    private fun <T : ManagedData<T, *>> directoryOperation(descriptor: EditorDataDescriptor<T>, directory: String, action: String): StoreResult<Unit> {
        if (!StorePathValidator.isValidDirectory(directory, false)) return StoreResult.Failure(StoreError(StoreErrorCode.INVALID_ID, directory))
        val path = "${descriptor.relativeDirectory}/${PublicId.relativePathOf(directory)}"
        return result(path, true) { request(action, path); Unit }
    }
    override fun <T : ManagedData<T, *>> listDirectories(descriptor: EditorDataDescriptor<T>): StoreResult<List<String>> = when (val entries = walk(descriptor)) {
        is StoreResult.Failure -> entries
        is StoreResult.Success -> StoreResult.Success(entries.value.filter { it.second }.map { it.first.removePrefix(descriptor.relativeDirectory + "/").replace('/', '.') })
    }
}
