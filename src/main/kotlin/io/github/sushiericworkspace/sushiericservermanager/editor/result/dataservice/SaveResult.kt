package io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice

enum class SaveResult {
    /** 保存処理がすべて正常に完了した場合 */
    SUCCESS,
    /** SSH/SFTPセッションが有効でない、またはプロファイルが未選択の場合 */
    SFTP_INACTIVE,
    /** YAMLの生成、アップロード、またはその他の例外が発生した場合 */
    FAILED
}