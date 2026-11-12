package io.github.sushiericworkspace.sushiericservermanager.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 同じworkspaceで重複して開かない機能の識別子です。 */
@Serializable
enum class WorkspaceFeature(val title: String, val editor: Boolean = false) {
    HOME("ホーム"), ITEM_EDITOR("アイテム", true), ORE_EDITOR("鉱石", true),
    SHOP_EDITOR("ショップ", true), RECIPE_EDITOR("レシピ", true),
    DASHBOARD("Dashboard"), CONSOLE("コンソール"), SERVER_CONTROL("サーバー操作"),
    MOD_CONFIG("Mod設定"), MONEY_HISTORY("履歴")
}

@Serializable
enum class SplitDirection { HORIZONTAL, VERTICAL }

internal enum class DockSide { LEFT, RIGHT, TOP, BOTTOM }

/** Nodeそのものを含めず、表示機能・分割方向・比率だけを保存します。 */
@Serializable
sealed class WorkspaceNode {
    @Serializable @SerialName("feature")
    data class Feature(val feature: WorkspaceFeature) : WorkspaceNode()

    @Serializable @SerialName("split")
    data class Split(
        val direction: SplitDirection,
        val first: WorkspaceNode,
        val second: WorkspaceNode,
        val ratio: Double = 0.5
    ) : WorkspaceNode()

    internal fun features(): List<WorkspaceFeature> = when (this) {
        is Feature -> listOf(feature)
        is Split -> first.features() + second.features()
    }

    internal fun remove(feature: WorkspaceFeature): WorkspaceNode? = when (this) {
        is Feature -> takeUnless { it.feature == feature }
        is Split -> {
            val left = first.remove(feature)
            val right = second.remove(feature)
            when { left == null -> right; right == null -> left; else -> copy(first = left, second = right) }
        }
    }

    internal fun dock(feature: WorkspaceFeature, target: WorkspaceFeature, side: DockSide): WorkspaceNode {
        if (feature == target || target !in features()) return this
        val remaining = remove(feature) ?: return Feature(feature)
        fun insert(node: WorkspaceNode): WorkspaceNode = when (node) {
            is Feature -> if (node.feature != target) node else {
                val before = side == DockSide.LEFT || side == DockSide.TOP
                Split(if (side == DockSide.LEFT || side == DockSide.RIGHT) SplitDirection.HORIZONTAL else SplitDirection.VERTICAL,
                    if (before) Feature(feature) else node, if (before) node else Feature(feature))
            }
            is Split -> node.copy(first = insert(node.first), second = insert(node.second))
        }
        return insert(remaining)
    }
}

/** 開いているIDの表示順と選択です。編集データ・保存状態・履歴はEditorViewが保持します。 */
@Serializable
data class IdTabsState(val ids: List<String> = emptyList(), val selected: String? = null) {
    internal fun open(id: String) = copy(ids = if (id in ids) ids else ids + id, selected = id)
    internal fun close(id: String): IdTabsState {
        val next = ids - id
        return copy(ids = next, selected = if (selected != id) selected else next.getOrNull(ids.indexOf(id).coerceAtMost(next.lastIndex)))
    }
    internal fun move(id: String, destination: Int): IdTabsState {
        if (id !in ids) return this
        val next = ids.toMutableList().apply { remove(id); add(destination.coerceIn(0, size), id) }
        return copy(ids = next)
    }
}

/** 接続profileごとに保持する表示配置です。保存データや接続先を変更しません。 */
@Serializable
data class WorkspaceLayout(
    val root: WorkspaceNode? = null,
    val active: WorkspaceFeature? = null,
    val documents: Map<WorkspaceFeature, IdTabsState> = emptyMap(),
    val sourceDocuments: Map<String, IdTabsState> = emptyMap(),
    val documentSources: Map<WorkspaceFeature, String> = emptyMap()
) {
    internal fun open(feature: WorkspaceFeature, target: WorkspaceFeature? = active, side: DockSide = DockSide.RIGHT): WorkspaceLayout {
        val next = when {
            root == null -> WorkspaceNode.Feature(feature)
            feature in root.features() -> root
            else -> root.dock(feature, target?.takeIf { it in root.features() } ?: root.features().last(), side)
        }
        return copy(root = next, active = feature)
    }
    internal fun close(feature: WorkspaceFeature): WorkspaceLayout {
        val next = root?.remove(feature)
        return copy(root = next, active = active.takeUnless { it == feature } ?: next?.features()?.lastOrNull())
    }
}
