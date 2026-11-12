package io.github.sushiericworkspace.sushiericservermanager.feature.dashboard

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceManager
import io.github.sushiericworkspace.sushiericservermanager.config.WorkspaceFeature

/** Dashboard画面をworkspace内の単一領域として管理します。 */
object DashboardWindowManager {
    /** 同じ機能が開いている場合は、そのworkspace領域へフォーカスします。 */
    fun open() {
        val workspace = WorkspaceManager.requireWorkspace()
        if (workspace.focus(WorkspaceFeature.DASHBOARD)) return
        val loader = FXMLLoader(javaClass.getResource(AppScreen.DASHBOARD.fxml!!))
        val root = loader.load<Parent>()
        val controller = loader.getController<DashboardController>()
        workspace.show(WorkspaceFeature.DASHBOARD, root, dispose = { controller.dispose() })
    }

    /** 表示中の機能を、workspaceの終了経路で閉じます。 */
    fun close() { WorkspaceManager.close(WorkspaceFeature.DASHBOARD) }
}
