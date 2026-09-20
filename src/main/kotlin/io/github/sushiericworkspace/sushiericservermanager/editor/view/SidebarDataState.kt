package io.github.sushiericworkspace.sushiericservermanager.editor.view

/**
 * サイドバーに表示するデータの状態を、互いに独立したフラグとして保持します。
 *
 * @property localOnly サーバーへ未保存で、ローカルの編集キャッシュにだけ存在する状態。
 */
internal data class SidebarDataState(
    val selected: Boolean,
    val modified: Boolean,
    val hasWarnings: Boolean,
    val hasErrors: Boolean,
    val localOnly: Boolean = false
) {
    val styleClasses: List<String>
        get() = buildList {
            if (selected) add(SELECTED_STYLE_CLASS)
            if (modified) add(MODIFIED_STYLE_CLASS)
            if (hasWarnings) add(WARNING_STYLE_CLASS)
            if (hasErrors) add(ERROR_STYLE_CLASS)
            if (localOnly) add(LOCAL_ONLY_STYLE_CLASS)
        }

    fun displayText(name: String): String = buildString {
        when {
            hasErrors -> append("⛔ ")
            hasWarnings -> append("⚠ ")
        }
        if (localOnly) append("＋ ")
        append(name)
        if (modified) append("  ●")
    }

    fun description(): String? {
        val states = buildList {
            if (selected) add("選択中")
            if (localOnly) add("サーバー未保存")
            if (modified) add("未保存の変更あり")
            if (hasWarnings) add("警告あり")
            if (hasErrors) add("エラーあり")
        }
        return states.takeIf { it.isNotEmpty() }?.joinToString(" / ")
    }

    companion object {
        val STYLE_CLASSES = listOf(
            SELECTED_STYLE_CLASS,
            MODIFIED_STYLE_CLASS,
            WARNING_STYLE_CLASS,
            ERROR_STYLE_CLASS,
            LOCAL_ONLY_STYLE_CLASS
        )

        private const val SELECTED_STYLE_CLASS = "button-selected"
        private const val MODIFIED_STYLE_CLASS = "button-modified"
        private const val WARNING_STYLE_CLASS = "button-warning"
        private const val ERROR_STYLE_CLASS = "button-invalid"
        private const val LOCAL_ONLY_STYLE_CLASS = "button-local-only"
    }
}
