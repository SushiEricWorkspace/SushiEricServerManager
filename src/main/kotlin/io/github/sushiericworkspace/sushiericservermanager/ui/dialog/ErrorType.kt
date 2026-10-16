package io.github.sushiericworkspace.sushiericservermanager.ui.dialog

/**
 * アプリケーション内で発生するエラーの種類を定義する列挙型。
 * 各エラータイプは、ダイアログ表示時にデフォルトで使用されるタイトルとヘッダーを持ちます。
 */
enum class ErrorType(val defaultTitle: String, val defaultHeader: String) {
    // --- 接続・ネットワーク関連 ---
    CONNECTION_FAILED("接続エラー", "サーバーとの通信に失敗しました。"),
    SFTP_ERROR("通信エラー", "ファイルの送受信（SFTP）中にエラーが発生しました。"),
    AUTHENTICATION_FAILED("認証失敗", "秘密鍵又はユーザー名に誤りがあります。"),
    NETWORK_ERROR("接続失敗", "ネットワークまたはその他のエラーが発生しました。"),

    // --- ファイル・データ入出力関連 ---
    DIRECTORY_NOT_FOUND("ディレクトリ不在", "ディレクトリが存在しないか、アクセス権限がありません"),
    INVALID_DIRECTORY("ディレクトリエラー", "指定されたパスはディレクトリではありません。"),
    FILE_NOT_FOUND("ファイル不在", "指定されたファイルが見つかりません。"),
    INVALID_YAML("データ破損", "YAMLデータの構文が不正、または破損しています。"),
    SAVE_FAILED("保存失敗", "データの保存処理に失敗しました。"),

    // --- アプリケーション内部・その他 ---
    INTERNAL_ERROR("システムエラー", "予期しない内部エラーが発生しました。"),
    UNKNOWN("詳細不明なエラー", "原因特定ができないエラーが発生しました。");
}