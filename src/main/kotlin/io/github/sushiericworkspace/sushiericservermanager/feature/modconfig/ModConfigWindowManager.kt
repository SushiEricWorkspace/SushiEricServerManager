package io.github.sushiericworkspace.sushiericservermanager.feature.modconfig

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceManager
import io.github.sushiericworkspace.sushiericservermanager.config.WorkspaceFeature

/** Mod共通設定（config.yml）の編集画面をworkspace内の単一領域として管理します。 */
object ModConfigWindowManager {
    private var activeController: ModConfigController? = null

    /** 同じ機能が開いている場合は、そのworkspace領域へフォーカスします。 */
    fun open(dataService: EditorDataService) {
        val workspace = WorkspaceManager.requireWorkspace()
        if (workspace.focus(WorkspaceFeature.MOD_CONFIG)) return
        val loader = FXMLLoader(javaClass.getResource(AppScreen.MOD_CONFIG.fxml!!))
        val root = loader.load<Parent>()
        val controller = loader.getController<ModConfigController>()
        controller.initData(dataService)
        workspace.show(WorkspaceFeature.MOD_CONFIG, root, close = { completed -> controller.prepareClose(completed) }, dispose = {
            controller.dispose()
            if (activeController === controller) activeController = null
        })
        activeController = controller
    }

    /** 接続終了前に編集中の本文を退避し、失敗時は表示と接続を保持します。 */
    internal fun prepareClose(completed: (Boolean) -> Unit) {
        val controller = activeController
        if (controller == null) completed(true) else controller.prepareClose(completed)
    }

    /** 表示中の機能を、workspaceの終了経路で閉じます。 */
    fun close() { WorkspaceManager.close(WorkspaceFeature.MOD_CONFIG) }
}
