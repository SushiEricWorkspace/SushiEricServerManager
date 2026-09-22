package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.common.data.core.ManagedData

interface EditorDataStore {
    val kind: EditorDataStoreKind
    val identity: String
    val isAvailable: Boolean

    fun <T : ManagedData<T, *>> list(
        descriptor: EditorDataDescriptor<T>
    ): StoreResult<List<StoreResource>>

    fun <T : ManagedData<T, *>> load(
        descriptor: EditorDataDescriptor<T>,
        id: String
    ): StoreResult<T>

    fun <T : ManagedData<T, *>> save(
        descriptor: EditorDataDescriptor<T>,
        id: String,
        data: T
    ): StoreResult<Unit>

    /** 完全IDを維持したまま、同じディレクトリ内で葉名だけを変更します。 */
    fun <T : ManagedData<T, *>> rename(
        descriptor: EditorDataDescriptor<T>,
        oldId: String,
        newName: String
    ): StoreResult<Unit>

    fun <T : ManagedData<T, *>> delete(
        descriptor: EditorDataDescriptor<T>,
        id: String
    ): StoreResult<Unit>

    /** 空ディレクトリを含め、指定したディレクトリと不足している親を作成します。 */
    fun <T : ManagedData<T, *>> createDirectory(
        descriptor: EditorDataDescriptor<T>,
        directory: String
    ): StoreResult<Unit>

    /** 指定ディレクトリと配下のデータ・子ディレクトリを削除します。 */
    fun <T : ManagedData<T, *>> deleteDirectory(
        descriptor: EditorDataDescriptor<T>,
        directory: String
    ): StoreResult<Unit>

    /** 直下を表す空文字を除き、存在するディレクトリを完全ディレクトリIDで返します。 */
    fun <T : ManagedData<T, *>> listDirectories(
        descriptor: EditorDataDescriptor<T>
    ): StoreResult<List<String>>

    /** データの葉名を維持したまま別ディレクトリへ移動し、変更後の完全IDを返します。 */
    fun <T : ManagedData<T, *>> move(
        descriptor: EditorDataDescriptor<T>,
        id: String,
        targetDirectory: String
    ): StoreResult<String>
}
