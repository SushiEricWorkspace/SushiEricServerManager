package io.github.sushiericworkspace.sushiericservermanager.util

import io.github.sushiericworkspace.sushiericdatamodel.path.SushiEricDataDirectory
import io.github.sushiericworkspace.sushiericdatamodel.path.SushiEricPath
import io.github.sushiericworkspace.sushiericservermanager.app.WindowSizing
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.app.ApplicationFlow
import io.github.sushiericworkspace.sushiericservermanager.app.appWindowTitle
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfile
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.stage.Stage
import org.slf4j.LoggerFactory

object Utility {
    private val logger = LoggerFactory.getLogger(Utility::class.java)

    private class CSSResourceContext

    fun getFullRemotePath(profile: ServerProfile, path: SushiEricPath): String {
        // 1. サーバーのベース（例: /home/minecraft/server）
        val base = profile.path.trimEnd('/')

        // 2. プラグインのデータフォルダまでの固定階層（plugins/SushiEricServerPlugin21）
        val dataRoot = SushiEricDataDirectory.BASE_ROOT.trim('/')

        // 3. SushiEricDataModelから取得した相対パス（例: item_data/stats/sword.yml）
        val relative = path.getRawPath().trimStart('/')

        // 4. すべてを結合して「サーバー上の絶対パス」にする
        return "$base/$dataRoot/$relative"
    }

    /** 退避と接続終了を待ち、通常接続のサーバー選択画面へ遷移します。管理接続ではモード選択へ戻ります。 */
    fun navigateToServerSelect() {
        if (EditorSession.connection != null) {
            navigateToModeSelect()
            return
        }
        closeConnection {
            EditorSession.prepareOnlineMode()
            Stage().apply {
                title = appWindowTitle("サーバー選択")
                scene = createScene(AppScreen.SERVER_SELECT, 600.0, 400.0)
                isResizable = false
                show()
            }
        }
    }

    fun navigateToModeSelect() {
        closeConnection { ApplicationFlow.showModeSelection(Stage()) }
    }

    /** 全画面の操作・退避と接続終了を非同期で待ち、成功後だけJavaFXスレッドで[afterClose]を実行します。 */
    fun closeConnection(afterClose: () -> Unit) = ApplicationFlow.finishConnection(afterClose)

    fun navigateToHome(contextName: String) {
        val scene = createScene(AppScreen.HOME)
        Stage().apply {
            title = appWindowTitle(contextName)
            this.scene = scene
            show()
        }
    }

    /**
     * 指定された画面（AppScreen）の定義に基づき、FXMLの読み込みとスタイルシートの適用を行います。
     *
     * この関数を使用することで、FXMLパスとCSSパスの管理を一元化でき、
     * 画面ごとに必要な初期化ロジックをカプセル化できます。
     *
     * ### 使用例1: 通常の画面生成
     * ```kotlin
     * // AppScreen で定義された画面を生成
     * val scene = createScene(AppScreen.SERVER_CREATE, 400.0, 300.0)
     *
     * stage.scene = scene
     * stage.show()
     * ```
     *
     * ### 使用例2: FXMLを持たない動的画面の生成
     * ```kotlin
     * // コードで直接生成したノードを渡す（WIDGETS_ONLYなど）
     * val myRoot = VBox(Label("ダイアログ"))
     * val scene = createScene(AppScreen.WIDGETS_ONLY, 300.0, 200.0, customRoot = myRoot)
     * ```
     *
     * @param screen 生成したい画面のEnum定義
     * @param width ウインドウの幅 `null` 又は指定しない場合指定したFXMLの推奨値が適用
     * @param height ウインドウの高さ `null` 又は指定しない場合指定したFXMLの推奨値が適用
     * @param customRoot FXMLを持たない画面を生成する際に渡すルートノード
     * @return CSSが適用済みの [Scene] インスタンス
     * @throws IllegalArgumentException FXMLファイルが見つからない、またはFXMLなし画面で customRoot が未指定の場合
     */
    fun createScene(
        screen: AppScreen,
        width: Double? = null,
        height: Double? = null,
        customRoot: Parent? = null
    ): Scene {
        // customRoot が渡された場合は最優先で使う
        val root: Parent = if (customRoot != null) {
            customRoot
        } else {
            val fxmlPath = screen.fxml ?: throw IllegalArgumentException(
                "${screen.name} はFXML定義を持たないため、createSceneの引数に customRoot を指定する必要があります。"
            )

            val fxmlUrl = AppScreen::class.java.getResource(fxmlPath)
                ?: throw IllegalArgumentException("FXMLファイルが見つかりません: $fxmlPath")

            FXMLLoader.load<Parent>(fxmlUrl)
        }

        val w = width ?: root.prefWidth(-1.0)
        val h = height ?: root.prefHeight(-1.0)

        val scene = if (width != null || height != null) {
            Scene(root, w, h)
        } else {
            Scene(root)
        }

        val cssPath = if (screen.css.endsWith(".css")) {
            screen.css
        } else {
            "${screen.css}.css"
        }

        try {
            val cssUrl = CSSResourceContext::class.java.getResource(cssPath)?.toExternalForm()

            if (cssUrl != null) {
                if (!scene.stylesheets.contains(cssUrl)) {
                    scene.stylesheets.add(cssUrl)
                    logger.info("Scene: スタイルシートを適用しました ($cssPath)")
                }
            } else {
                logger.warn("Scene: スタイルシートが見つかりません ($cssPath)")
            }
        } catch (e: Exception) {
            logger.error("Scene: スタイルシートの適用中にエラーが発生しました ($cssPath)", e)
        }

        WindowSizing.install(scene)
        return scene
    }

    /**
     * Alertに共通のスタイルを適用します。
     * アプリ内のデザイン統一のため、すべてのAlertでこれを呼び出してください。
     */
    fun Alert.applyCommonStyle() {
        WindowSizing.install(this)
        val cssPath = AppScreen.WIDGETS_ONLY.css
        val cssUrl = CSSResourceContext::class.java.getResource(cssPath)?.toExternalForm()

        if (cssUrl != null) {
            this.dialogPane.stylesheets.add(cssUrl)
        }
    }
}
