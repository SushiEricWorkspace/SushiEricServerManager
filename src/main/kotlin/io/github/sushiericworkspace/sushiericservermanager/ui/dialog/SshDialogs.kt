package io.github.sushiericworkspace.sushiericservermanager.ui.dialog

import io.github.sushiericworkspace.sushiericservermanager.communication.HostKeyPrompt
import io.github.sushiericworkspace.sushiericservermanager.communication.SshFailure
import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.layout.VBox
import javafx.stage.Stage

object SshHostKeyDialog {
    fun confirm(prompt: HostKeyPrompt, owner: Stage? = null): Boolean {
        return CustomDialog.confirmation()
            .owner(owner)
            .title("SSHホスト鍵の確認")
            .header("この接続先のホスト鍵を信頼しますか？")
            .content(
                listOf(
                    "ホスト: ${prompt.host}:${prompt.port}",
                    "鍵の種類: ${prompt.algorithm}",
                    "フィンガープリント: ${prompt.fingerprint}",
                    "",
                    "接続先OSの管理者が提示したフィンガープリントと一致することを確認してください。",
                    "承認すると、このホスト名とポートの組み合わせに対して保存されます。"
                )
            )
            .okButton("承認して接続")
            .cancelButton("接続を中止")
            .show()
    }
}

object SshPasswordDialog {
    fun show(owner: Stage?, remoteOperatingSystem: RemoteOperatingSystem): CharArray? {
        val passwordField = PasswordField().apply {
            promptText = "接続先OSユーザーのパスワード"
        }

        val okButtonType = ButtonType("公開鍵を登録", ButtonBar.ButtonData.OK_DONE)
        val dialog = Dialog<CharArray>().apply {
            title = "初回SSHパスワード認証"
            headerText = "初回公開鍵登録にだけ${remoteOperatingSystem.displayName}のパスワードを使用します"
            if (owner != null) initOwner(owner)

            dialogPane.content = VBox(
                10.0,
                Label("パスワードは設定ファイルへ保存されず、処理終了後に入力欄を消去します。"),
                passwordField
            )
            dialogPane.buttonTypes.setAll(okButtonType, ButtonType.CANCEL)
            AppScreen::class.java.getResource(AppScreen.WIDGETS_ONLY.css)
                ?.toExternalForm()
                ?.let(dialogPane.stylesheets::add)

            val okButton = dialogPane.lookupButton(okButtonType)
            okButton.disableProperty().bind(passwordField.textProperty().isEmpty)

            setResultConverter { buttonType ->
                if (buttonType == okButtonType) passwordField.text.toCharArray() else null
            }
        }

        return try {
            dialog.showAndWait().orElse(null)
        } finally {
            passwordField.clear()
        }
    }
}

object SshFailureDialog {
    fun show(failure: SshFailure, owner: Stage? = null) {
        CustomDialog.error()
            .owner(owner)
            .title(failure.title)
            .header("SSH処理を完了できませんでした")
            .content(failure.userMessage())
            .show()
    }
}
