package io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice

/**
 * リモート設定ファイルの削除結果を表す状態列挙型
 */
enum class DeleteResult {
    /** リモートファイルの削除に成功した場合 */
    SUCCESS,

    /** SSH/SFTPセッションが有効でない場合 */
    SFTP_INACTIVE,

    /** 現在アクティブな接続プロファイルが存在しない場合 */
    PROFILE_NOT_SELECTED,

    /** 削除対象のファイルがリモートサーバー上にそもそも存在しない場合 */
    FILE_NOT_FOUND,

    /** 権限不足、ネットワーク遮断、またはその他の例外が発生した場合 */
    FAILED
}