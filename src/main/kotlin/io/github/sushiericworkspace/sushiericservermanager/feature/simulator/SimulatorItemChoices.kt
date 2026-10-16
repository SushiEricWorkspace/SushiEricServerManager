package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.sushiericservermanager.editor.store.*
import java.io.File

/** 検索欄の文字列と装備解除を、保存済みItemの公開IDから区別します。 */
internal sealed interface SimulatorItemChoice {
    data object None : SimulatorItemChoice
    data class Saved(val id: String, val name: String?) : SimulatorItemChoice
}

/** ディレクトリを作成せず、明示localのItemだけを列挙します。本文の固定取込は適用時に行います。 */
internal fun listSimulatorItems(root: File): StoreResult<List<SimulatorItemChoice.Saved>> {
    val store = LocalEditorDataStore(root)
    val descriptor = EditorDataDescriptors.item
    val result = mutableListOf<SimulatorItemChoice.Saved>()
    fun visit(path: String): StoreResult<Unit> {
        return when (val entries = store.listPath(path)) {
            is StoreResult.Failure -> entries
            is StoreResult.Success -> {
                for (entry in entries.value) {
                    val child = "$path/${entry.name}"
                    if (entry.isDirectory) {
                        val nested = visit(child)
                        if (nested is StoreResult.Failure) return nested
                    } else if (entry.name.endsWith(".yml")) {
                        val id = child.removePrefix(descriptor.relativeDirectory + "/").removeSuffix(".yml").replace('/', '.')
                        if (!descriptor.isValidId(id)) continue
                        val name = (store.load(descriptor, id) as? StoreResult.Success)?.value?.display?.displayName
                        result += SimulatorItemChoice.Saved(id, name?.takeIf(String::isNotBlank))
                    }
                }
                StoreResult.Success(Unit)
            }
        }
    }
    return when (val listed = visit(descriptor.relativeDirectory)) {
        is StoreResult.Failure -> listed
        is StoreResult.Success -> StoreResult.Success(result.sortedBy { it.id })
    }
}
