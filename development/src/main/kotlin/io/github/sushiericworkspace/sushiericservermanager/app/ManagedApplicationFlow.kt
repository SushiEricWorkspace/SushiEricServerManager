package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.communication.managed.ManagedProfile
import io.github.sushiericworkspace.sushiericservermanager.communication.managed.ManagedEditorConnection
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.application.Platform
import javafx.stage.Stage
import kotlin.concurrent.thread

internal object ManagedApplicationFlow {
    /** 登録・IPC・socketはバックグラウンドで準備し、失敗をSSH接続へ変換しません。 */
    fun prepareManaged(stage: Stage, selection: ManagedConnectionDialog.Selection) {
        val profile = selection.profile
        ApplicationFlow.showPreparing(stage, "管理writerと起動識別を確認しています...")
        thread(isDaemon = true, name = "managed-connect") {
            try {
                EditorSession.startConnection(ManagedEditorConnection(profile, selection.lease))
                Platform.runLater { stage.close(); Utility.navigateToHome(profile.instanceId) }
            } catch (_: Exception) {
                Platform.runLater {
                    CustomDialog.error().header("管理接続に失敗しました")
                        .content("writer登録が残っている可能性があります。監督statusと診断を確認してください。自動解除・別profileへのfallbackは行いません。")
                        .show()
                    stage.close()
                    ApplicationFlow.showModeSelection()
                }
            }
        }
    }

}
