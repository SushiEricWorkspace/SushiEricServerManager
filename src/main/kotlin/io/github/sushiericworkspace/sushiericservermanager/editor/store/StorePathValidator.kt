package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.common.data.core.identity.PublicId

object StorePathValidator {
    fun isValidId(id: String): Boolean = PublicId.isValidFull(id)

    fun isValidName(name: String): Boolean = PublicId.isValid(name)

    fun isValidDirectory(directory: String, allowRoot: Boolean = true): Boolean =
        (allowRoot && directory.isEmpty()) || PublicId.isValidFull(directory)

    /**
     * 基準ディレクトリからの相対パスとして扱えるかどうかを返します。
     *
     * 基準ディレクトリの外を指す指定と、絶対パスは扱いません。
     *
     * @param relativePath 区切りが`/`の相対パス。
     */
    fun isValidRelativePath(relativePath: String): Boolean {
        if (relativePath.isBlank()) return false
        if (relativePath.startsWith("/") || relativePath.contains(":")) return false
        if (relativePath.contains("\\")) return false

        return relativePath.split("/").all { segment ->
            segment.isNotBlank() && segment != "." && segment != ".."
        }
    }
}
