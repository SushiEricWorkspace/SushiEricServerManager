package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceManager
import io.github.sushiericworkspace.sushiericservermanager.config.WorkspaceFeature

/** 履歴閲覧領域（入出金・実績・ショップ）の生成とライフサイクルを管理します。 */
object MoneyHistoryWindowManager {
    /** 同じ機能が開いている場合は、そのworkspace領域へフォーカスします。 */
    fun open(store: EditorDataStore) {
        val workspace = WorkspaceManager.requireWorkspace()
        if (workspace.focus(WorkspaceFeature.MONEY_HISTORY)) return
        val loader = FXMLLoader(javaClass.getResource(AppScreen.MONEY_HISTORY.fxml!!))
        val root = loader.load<Parent>()
        val controller = loader.getController<MoneyHistoryController>()
        controller.initialize(store, EditorSession.managementClient)
        workspace.show(WorkspaceFeature.MONEY_HISTORY, root, dispose = { controller.dispose() })
    }

    /** 表示中の機能を、workspaceの終了経路で閉じます。 */
    fun close() { WorkspaceManager.close(WorkspaceFeature.MONEY_HISTORY) }
}
