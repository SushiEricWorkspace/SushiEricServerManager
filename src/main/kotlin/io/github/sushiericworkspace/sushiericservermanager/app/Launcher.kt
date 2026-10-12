package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.common.registry.VanillaIdRegistry
import io.github.sushiericworkspace.sushiericservermanager.config.OS
import javafx.application.Application
import kotlin.system.exitProcess

/**
 * アプリケーションの起動を管理するエントリポイント用オブジェクト。
 *
 * 実行可能JARとしてパッケージングした際に、JavaFXランタイムの依存関係チェックを
 * 回避するためのメインクラスとして機能します。
 */
object Launcher {

    /**
     * アプリケーションを起動するためのメインメソッド。
     * [javafx.application.Application.launch] を呼び出して、JavaFXのライフサイクルを開始します。
     *
     * @param args コマンドライン引数
     */
    @JvmStatic
    fun main(args: Array<String>) = launch(MainApp::class.java, args)

    /** OSとCommonの初期化を確認して指定したJavaFX入口を起動します。 */
    fun launch(application: Class<out Application>, args: Array<String>) {
        // GUI起動前のOSチェック
        if (!OS.isSupportedOs()) {
            println("エラー: このソフトはWindowsまたはmacOSでのみ動作します。")
            exitProcess(1)
        }

        if (VanillaIdRegistry.allItems.isEmpty()) {
            println("エラー: アイテムリストを読み込めませんでした")
            exitProcess(1)
        }

        // 💡 3. JavaFX アプリケーションを起動
        Application.launch(application, *args)
    }
}