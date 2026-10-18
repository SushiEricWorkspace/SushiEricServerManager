package io.github.sushiericworkspace.sushiericservermanager.feature.console

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceManager
import io.github.sushiericworkspace.sushiericservermanager.config.WorkspaceFeature

/** コンソール画面をworkspace内の単一領域として管理します。 */
object ConsoleWindowManager {
    /** 同じ機能が開いている場合は、そのworkspace領域へフォーカスします。 */
    fun open() {
        val workspace = WorkspaceManager.requireWorkspace()
        if (workspace.focus(WorkspaceFeature.CONSOLE)) return
        val loader = FXMLLoader(javaClass.getResource(AppScreen.CONSOLE.fxml!!))
        val root = loader.load<Parent>()
        val controller = loader.getController<ConsoleController>()
        workspace.show(WorkspaceFeature.CONSOLE, root, dispose = { controller.dispose() })
    }

    /** 表示中の機能を、workspaceの終了経路で閉じます。 */
    fun close() { WorkspaceManager.close(WorkspaceFeature.CONSOLE) }
}
