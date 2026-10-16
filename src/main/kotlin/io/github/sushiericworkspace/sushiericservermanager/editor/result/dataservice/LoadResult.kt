package io.github.sushiericworkspace.sushiericservermanager.editor.result.dataservice

/**
 * リモート設定ファイルの読み込み結果を表す状態列挙型
 */
enum class LoadResult {
    /** リモートからの取得・解析にすべて成功した場合 */
    SUCCESS,
    /** SSH/SFTPセッションが有効でない場合 */
    SFTP_INACTIVE,
    /** 現在アクティブな接続プロファイルが存在しない場合 */
    PROFILE_NOT_SELECTED,
    /** 指定されたファイルがリモートサーバー上に存在しない場合 */
    FILE_NOT_FOUND,
    /** YAMLファイルの構文が不正、またはマッピングに失敗した場合 */
    INVALID_YAML,
    /** その他ネットワークエラーやI/O例外などが発生した場合 */
    FAILED
}