package io.github.sushiericworkspace.sushiericservermanager.editor.result

sealed class ValidationResult {
    object Success : ValidationResult()
    data class Error(val message: String) : ValidationResult()
}