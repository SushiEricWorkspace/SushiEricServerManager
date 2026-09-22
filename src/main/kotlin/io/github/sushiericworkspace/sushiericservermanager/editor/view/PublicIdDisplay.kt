package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.common.data.core.identity.PublicId
import javafx.scene.control.Label
import javafx.scene.layout.HBox

internal data class PublicIdDisplayParts(val directory: String, val name: String)

internal fun splitPublicIdForDisplay(id: String): PublicIdDisplayParts = PublicIdDisplayParts(
    directory = PublicId.directoryOf(id).takeIf(List<String>::isNotEmpty)?.joinToString(".", postfix = ".").orEmpty(),
    name = PublicId.nameOf(id)
)

/** ディレクトリを薄く、葉名を通常色で表示する完全IDノードを作成します。 */
internal fun createPublicIdDisplay(id: String): HBox {
    val parts = splitPublicIdForDisplay(id)
    return HBox().apply {
        if (parts.directory.isNotEmpty()) children += Label(parts.directory).apply {
            styleClass.add("public-id-directory")
        }
        children += Label(parts.name).apply { styleClass.add("public-id-name") }
    }
}
