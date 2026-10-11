package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.communication.managed.ManagedProfile
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.TextField
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import java.nio.file.Path

/** 接続時の貸出入力だけを受け取り、SSH設定や秘密を保存しません。 */
internal object ManagedConnectionDialog {
    fun show(owner: Stage): ManagedProfile? {
        val root = TextField()
        val python = TextField()
        val cli = TextField()
        val instance = TextField()
        val lease = PasswordField()
        val epoch = TextField()
        val error = Label().apply { styleClass.add("error-label"); isWrapText = true }
        var result: ManagedProfile? = null
        val stage = Stage().apply { initOwner(owner); initModality(Modality.APPLICATION_MODAL); title = "管理テスト環境へ接続" }
        val form = VBox(8.0, Label("管理root（絶対パス）"), root, Label("Python 3.12（絶対パス）"), python,
            Label("dev-server.py（絶対パス）"), cli, Label("instanceId"), instance, Label("取得済みleaseId"), lease,
            Label("取得済みepoch"), epoch, Label("貸出の自動取得・更新は行いません。管理接続は既存SSH設定と別です。"), error).apply {
            styleClass.add("common-root")
            padding = Insets(16.0)
        }
        val connect = Button("接続").apply {
            styleClass.add("btn-primary")
            setOnAction {
                try {
                    result = ManagedProfile(Path.of(root.text), Path.of(python.text), Path.of(cli.text), instance.text, lease.text, epoch.text.toLong())
                    stage.close()
                } catch (_: Exception) { error.text = "絶対パスと取得済みinstanceId・leaseId・正のepochを確認してください。" }
            }
        }
        val cancel = Button("キャンセル").apply { setOnAction { stage.close() } }
        form.children.addAll(connect, cancel)
        stage.scene = Utility.createScene(AppScreen.WIDGETS_ONLY, 620.0, 640.0, customRoot = form)
        stage.showAndWait()
        return result
    }
}
