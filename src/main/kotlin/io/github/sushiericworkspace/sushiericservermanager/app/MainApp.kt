package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.UpdateDialog
import io.github.sushiericworkspace.sushiericservermanager.update.UpdateCheckResult
import javafx.application.Application
import javafx.application.Platform
import javafx.stage.Stage

/**
 * JavaFX アプリケーションのメインライフサイクルを管理するクラス。
 * ウィンドウの初期化、シーンのロード、およびテーマの適用を行います。
 */
class MainApp : Application() {

    /**
     * JavaFX アプリケーションの開始点。
     * サーバー選択画面をロードし、テーマを適用してメインステージを表示します。
     *
     * @param stage アプリケーションのプライマリステージ
     */
    override fun start(stage: Stage) {

        if (!SingleAppLock.tryLock()) {
            CustomDialog
                .error()
                .title("起動エラー")
                .header("すでに起動しています")
                .content("このアプリはすでに起動中です。")
                .show()

            Platform.exit()
            return
        }

        ApplicationFlow.showUpdate = ::showUpdateDialog
        ApplicationFlow.showModeSelection(stage)
    }

    override fun stop() {
        io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession.disconnect()
        SingleAppLock.release()
    }

    private fun showUpdateDialog(update: UpdateCheckResult.Update) {
        UpdateDialog.show(update, openUrl = hostServices::showDocument)
    }
}

/**
 * アプリケーションの起動用エントリーポイント。
 * 実行環境のOS互換性をチェックし、問題がなければ JavaFX ランタイムを起動します。
 */
fun main(args: Array<String>) {
    Launcher.main(args)
}
