package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.common.data.core.identity.PublicId

sealed interface StoreResult<out T> {
    data class Success<T>(val value: T) : StoreResult<T>
    data class Failure(val error: StoreError) : StoreResult<Nothing>
}

data class StoreError(
    val code: StoreErrorCode,
    val dataId: String? = null,
    val detail: String? = null,
    val cause: Throwable? = null
)

enum class StoreErrorCode {
    STORE_UNAVAILABLE,
    PROFILE_NOT_SELECTED,
    FILE_NOT_FOUND,
    ALREADY_EXISTS,
    INVALID_ID,
    INVALID_YAML,
    VALIDATION_FAILED,
    PERMISSION_DENIED,
    UNSUPPORTED_FORMAT,
    MANIFEST_INVALID,
    IO_ERROR
}

data class StoreResource(
    val id: String,
    val fileName: String,
    val location: String,
    val directory: String = PublicId.directoryOf(id).joinToString("."),
    val name: String = PublicId.nameOf(id)
)

enum class EditorDataStoreKind {
    REMOTE,
    LOCAL,
    IN_MEMORY
}
