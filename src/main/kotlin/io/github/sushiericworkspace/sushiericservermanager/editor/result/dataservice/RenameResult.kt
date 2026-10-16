package io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice

enum class RenameResult {
    SUCCESS,
    SFTP_INACTIVE,
    PROFILE_NOT_SELECTED,
    FILE_NOT_FOUND,         // 変更前のファイルが存在しない
    ALREADY_EXISTS,         // 変更後のファイル名が既に存在する
    FAILED
}