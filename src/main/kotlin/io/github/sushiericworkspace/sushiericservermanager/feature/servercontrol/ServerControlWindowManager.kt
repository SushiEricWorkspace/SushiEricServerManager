package io.github.sushiericworkspace.sushiericservermanager.feature.servercontrol

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceManager
import io.github.sushiericworkspace.sushiericservermanager.config.WorkspaceFeature

/** Server Control画面をworkspace内の単一領域として管理します。 */
object ServerControlWindowManager {
    /** 同じ機能が開いている場合は、そのworkspace領域へフォーカスします。 */
    fun open() {
        val workspace = WorkspaceManager.requireWorkspace()
        if (workspace.focus(WorkspaceFeature.SERVER_CONTROL)) return
        val loader = FXMLLoader(javaClass.getResource(AppScreen.SERVER_CONTROL.fxml!!))
        val root = loader.load<Parent>()
        workspace.show(WorkspaceFeature.SERVER_CONTROL, root)
    }

    /** 表示中の機能を、workspaceの終了経路で閉じます。 */
    fun close() { WorkspaceManager.close(WorkspaceFeature.SERVER_CONTROL) }
}
