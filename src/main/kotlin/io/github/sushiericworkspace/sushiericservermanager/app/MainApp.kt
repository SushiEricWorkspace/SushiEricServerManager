package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.UpdateDialog
import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.update.UpdateCheckResult
import io.github.sushiericworkspace.sushiericservermanager.update.UpdateResultStore
import io.github.sushiericworkspace.sushiericservermanager.update.pruneOldUpdates
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
        reportPreviousUpdate()
        ApplicationFlow.showModeSelection(stage)
    }

    override fun stop() {
        io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession.disconnect()
        SingleAppLock.release()
    }

    private fun showUpdateDialog(update: UpdateCheckResult.Update): UpdateDialog.Outcome =
        UpdateDialog.show(update, openUrl = hostServices::showDocument)

    /**
     * 前回の更新の結果があれば表示し、不要になったダウンロード済みの成果物を削除する。
     *
     * 更新で再起動した直後の起動で、Updaterが書いた結果を利用者へ知らせるために呼ぶ。
     */
    private fun reportPreviousUpdate() {
        val store = UpdateResultStore(FilePath.UPDATE_RESULT.toFile())
        val result = store.read()
        store.delete()
        pruneOldUpdates(FilePath.UPDATES_DIR.toFile())
        result ?: return

        if (result.success) {
            CustomDialog.information()
                .title("アップデート")
                .header("${result.version}へ更新しました")
                .content("Managerを更新しました。設定やデータは引き継がれています。")
                .show()
        } else {
            CustomDialog.error()
                .title("アップデートエラー")
                .header("${result.version}への更新に失敗しました")
                .content(
                    "${result.message ?: "原因不明のエラーです。"}\n\n" +
                        "現在のバージョンのまま起動しました。時間をおいて、もう一度更新してください。"
                )
                .show()
        }
    }
}

/**
 * アプリケーションの起動用エントリーポイント。
 * 実行環境のOS互換性をチェックし、問題がなければ JavaFX ランタイムを起動します。
 */
fun main(args: Array<String>) {
    Launcher.main(args)
}
