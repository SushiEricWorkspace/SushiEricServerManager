package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.common.data.core.identity.PublicId

/** サイドバーへ表示するディレクトリまたはデータを表します。 */
internal sealed interface SidebarTreeNode {
    val name: String

    /** ディレクトリを表します。ルート自体はノードへ含めません。 */
    data class Directory(
        override val name: String,
        val fullPath: String,
        val children: List<SidebarTreeNode>
    ) : SidebarTreeNode

    /** 完全IDを持つ編集対象データを表します。 */
    data class Data(
        override val name: String,
        val fullId: String
    ) : SidebarTreeNode
}

/**
 * 完全IDと明示的に存在するディレクトリから、サイドバー用ツリーを構築します。
 *
 * 検索中は一致するデータとその親ディレクトリだけを返します。検索していない場合は、
 * データを持たない空ディレクトリも保持します。
 */
internal fun buildSidebarTree(
    ids: Collection<String>,
    directories: Collection<String> = emptyList(),
    query: String = ""
): List<SidebarTreeNode> {
    val normalizedQuery = query.trim()
    val visibleIds = ids
        .map(PublicId::normalizeForLoad)
        .filter { normalizedQuery.isEmpty() || it.contains(normalizedQuery, ignoreCase = true) }
        .toSortedSet()

    val root = MutableSidebarDirectory("", "")
    if (normalizedQuery.isEmpty()) {
        directories.map(PublicId::normalizeForLoad).sorted().forEach(root::ensureDirectory)
    }
    visibleIds.forEach(root::addData)
    return root.toNodes()
}

/** 指定ディレクトリを削除したときに同時に削除される完全IDを返します。 */
internal fun idsInSidebarDirectory(ids: Collection<String>, directory: String): List<String> =
    ids.filter { it.startsWith("$directory.") }.sorted()

/** データが既にドロップ先ディレクトリにあるかを返します。 */
internal fun isSameSidebarDirectory(id: String, targetDirectory: String): Boolean =
    PublicId.directoryOf(id).joinToString(".") == targetDirectory

private class MutableSidebarDirectory(
    private val name: String,
    private val fullPath: String
) {
    private val directories = sortedMapOf<String, MutableSidebarDirectory>()
    private val data = sortedMapOf<String, String>()

    fun ensureDirectory(path: String): MutableSidebarDirectory {
        var current = this
        var currentPath = ""
        path.split('.').filter(String::isNotEmpty).forEach { segment ->
            currentPath = if (currentPath.isEmpty()) segment else "$currentPath.$segment"
            current = current.directories.getOrPut(segment) {
                MutableSidebarDirectory(segment, currentPath)
            }
        }
        return current
    }

    fun addData(id: String) {
        val directory = ensureDirectory(PublicId.directoryOf(id).joinToString("."))
        val leaf = PublicId.nameOf(id)
        directory.data[leaf] = id
    }

    fun toNodes(): List<SidebarTreeNode> = buildList {
        directories.values.forEach { directory ->
            add(
                SidebarTreeNode.Directory(
                    name = directory.name,
                    fullPath = directory.fullPath,
                    children = directory.toNodes()
                )
            )
        }
        data.forEach { (name, id) -> add(SidebarTreeNode.Data(name, id)) }
    }
}

/** エディターが選択できるサイドバー表示方式です。 */
enum class SidebarDisplayMode { TREE, FLAT }

/** 仮想化された一覧に表示する行です。深さはルート直下を0とします。 */
internal sealed interface SidebarRow {
    val name: String
    val depth: Int

    data class Data(val fullId: String, override val name: String, override val depth: Int) : SidebarRow
    data class Directory(val fullPath: String, override val name: String, override val depth: Int, val expanded: Boolean) : SidebarRow
}

internal fun buildSidebarRows(
    ids: Collection<String>,
    directories: Collection<String>,
    query: String,
    mode: SidebarDisplayMode,
    expandedState: Map<String, Boolean>
): List<SidebarRow> {
    if (mode == SidebarDisplayMode.FLAT) {
        return filterManagedDataIds(ids.sorted(), query).map { SidebarRow.Data(it, PublicId.normalizeForLoad(it), 0) }
    }
    return buildList {
        fun addNodes(nodes: List<SidebarTreeNode>, depth: Int) {
            nodes.forEach { node ->
                when (node) {
                    is SidebarTreeNode.Data -> add(SidebarRow.Data(node.fullId, node.name, depth))
                    is SidebarTreeNode.Directory -> {
                        val expanded = expandedState[node.fullPath] ?: true
                        add(SidebarRow.Directory(node.fullPath, node.name, depth, expanded))
                        if (expanded) addNodes(node.children, depth + 1)
                    }
                }
            }
        }
        addNodes(buildSidebarTree(ids, directories, query), 0)
    }
}

internal fun sidebarDisclosureSymbol(expanded: Boolean): String = if (expanded) "▾" else "▸"
