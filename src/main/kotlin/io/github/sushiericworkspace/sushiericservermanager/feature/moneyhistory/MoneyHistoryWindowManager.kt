package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.stage.Stage
import javafx.stage.Window

/** 履歴閲覧ウィンドウ（入出金・実績・ショップ）の生成とライフサイクルを管理します。 */
object MoneyHistoryWindowManager {
    private var activeStage: Stage? = null

    /** 履歴閲覧画面を開き、表示中ならその画面を前面へ移動します。 */
    fun open(owner: Window?, store: EditorDataStore) {
        activeStage?.takeIf(Stage::isShowing)?.let { it.toFront(); return }
        val loader = FXMLLoader(javaClass.getResource(AppScreen.MONEY_HISTORY.fxml!!))
        val root = loader.load<Parent>()
        val controller = loader.getController<MoneyHistoryController>()
        controller.initialize(store, EditorSession.managementClient)
        val stage = Stage().apply {
            title = "SushiEricServerManager - 履歴"
            scene = Utility.createScene(AppScreen.MONEY_HISTORY, customRoot = root)
            minWidth = 900.0
            minHeight = 560.0
            owner?.let(::initOwner)
            setOnHidden { controller.dispose(); activeStage = null }
        }
        activeStage = stage
        stage.show()
    }

    /** 表示中の履歴閲覧画面を閉じます。 */
    fun close() { activeStage?.close(); activeStage = null }
}
