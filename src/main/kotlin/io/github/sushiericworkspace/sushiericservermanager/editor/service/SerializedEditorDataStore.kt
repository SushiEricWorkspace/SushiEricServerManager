package io.github.sushiericworkspace.sushiericservermanager.editor.service

import io.github.sushiericworkspace.sushiericdatamodel.data.core.ManagedData
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataDescriptor
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResource
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StorePathEntry

/** 同期呼び出し元を含め、同じ接続へのI/Oと複合工程の割り込みを防ぎます。 */
internal class SerializedEditorDataStore(
    private val delegate: EditorDataStore,
    private val lock: Any
) : EditorDataStore by delegate {
    override fun <T : ManagedData<T, *>> list(descriptor: EditorDataDescriptor<T>): StoreResult<List<StoreResource>> =
        synchronized(lock) { delegate.list(descriptor) }

    override fun <T : ManagedData<T, *>> load(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<T> =
        synchronized(lock) { delegate.load(descriptor, id) }

    override fun <T : ManagedData<T, *>> save(descriptor: EditorDataDescriptor<T>, id: String, data: T): StoreResult<Unit> =
        synchronized(lock) { delegate.save(descriptor, id, data) }

    override fun <T : ManagedData<T, *>> rename(descriptor: EditorDataDescriptor<T>, oldId: String, newName: String): StoreResult<Unit> =
        synchronized(lock) { delegate.rename(descriptor, oldId, newName) }

    override fun <T : ManagedData<T, *>> delete(descriptor: EditorDataDescriptor<T>, id: String): StoreResult<Unit> =
        synchronized(lock) { delegate.delete(descriptor, id) }

    override fun <T : ManagedData<T, *>> createDirectory(descriptor: EditorDataDescriptor<T>, directory: String): StoreResult<Unit> =
        synchronized(lock) { delegate.createDirectory(descriptor, directory) }

    override fun <T : ManagedData<T, *>> deleteDirectory(descriptor: EditorDataDescriptor<T>, directory: String): StoreResult<Unit> =
        synchronized(lock) { delegate.deleteDirectory(descriptor, directory) }

    override fun <T : ManagedData<T, *>> listDirectories(descriptor: EditorDataDescriptor<T>): StoreResult<List<String>> =
        synchronized(lock) { delegate.listDirectories(descriptor) }

    override fun <T : ManagedData<T, *>> move(descriptor: EditorDataDescriptor<T>, id: String, targetDirectory: String): StoreResult<String> =
        synchronized(lock) { delegate.move(descriptor, id, targetDirectory) }

    override fun readText(relativePath: String): StoreResult<String> =
        synchronized(lock) { delegate.readText(relativePath) }

    override fun readServerText(relativePath: String): StoreResult<String> =
        synchronized(lock) { delegate.readServerText(relativePath) }

    override fun writeText(relativePath: String, text: String): StoreResult<Unit> =
        synchronized(lock) { delegate.writeText(relativePath, text) }

    override fun listPath(relativePath: String): StoreResult<List<StorePathEntry>> =
        synchronized(lock) { delegate.listPath(relativePath) }

}
