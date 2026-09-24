package io.github.sushiericworkspace.sushiericservermanager.app

/**
 * アプリケーション内のすべての画面（FXMLとCSSのパス）を一元管理するEnum。
 * フォルダ構造が変更された場合は、このクラスのパスを修正するだけで全画面に反映されます。
 */
enum class AppScreen(val fxml: String?, val css: String) {

    /** オンライン／オフライン選択 */
    MODE_SELECT("/fxml/startup/mode-select.fxml", "/css/startup/mode-select.css"),
    /** エディターのメインGUIのベース */
    BASE("/fxml/main/base.fxml", "/css/main/base.css"),
    /** ホーム画面 */
    HOME("/fxml/main/home.fxml", "/css/main/home.css"),
    /** Minecraftコンソール */
    CONSOLE("/fxml/console/console.fxml", "/css/console/console.css"),
    /** サーバー状態のダッシュボード */
    DASHBOARD("/fxml/dashboard/dashboard.fxml", "/css/dashboard/dashboard.css"),
    /** サーバープロセスの操作 */
    SERVER_CONTROL("/fxml/servercontrol/server-control.fxml", "/css/servercontrol/server-control.css"),

    /** Mod共通設定（config.yml）の編集画面。 */
    MOD_CONFIG("/fxml/modconfig/mod-config.fxml", "/css/modconfig/mod-config.css"),
    /** ウィジェットのみ */
    WIDGETS_ONLY(null, "/css/common/widgets.css"),

    /** サーバー選択 */
    SERVER_SELECT("/fxml/serverselect/select.fxml", "/css/serverselect/select.css"),
    /** サーバー作成 */
    SERVER_CREATE("/fxml/serverselect/create.fxml", "/css/serverselect/create.css"),
    /** サーバー編集 */
    SERVER_EDIT("/fxml/serverselect/edit.fxml", "/css/serverselect/edit.css");
}
