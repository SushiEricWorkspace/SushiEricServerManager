package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.common.data.core.identity.PublicId

object StorePathValidator {
    fun isValidId(id: String): Boolean = PublicId.isValidFull(id)

    fun isValidName(name: String): Boolean = PublicId.isValid(name)

    fun isValidDirectory(directory: String, allowRoot: Boolean = true): Boolean =
        (allowRoot && directory.isEmpty()) || PublicId.isValidFull(directory)
}
