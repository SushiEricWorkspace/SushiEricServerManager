package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.fxml.FXMLLoader
import javafx.scene.Parent

/** 開発用画面を選び、正式版への自動更新を無効にして通常編集機能を利用します。 */
class DevelopmentApp : MainApp() {
    override fun configureStartup() {
        ApplicationFlow.updatesEnabled = false
        ApplicationFlow.modeSelection = { stage ->
            val loader = FXMLLoader(javaClass.getResource("/fxml/development/mode-select.fxml"))
            val root = loader.load<Parent>()
            loader.getController<DevelopmentModeSelectionController>().configure { mode ->
                ApplicationFlow.prepareMode(stage, mode)
            }
            stage.title = "SushiEricServerManager 開発用"
            stage.scene = Utility.createScene(AppScreen.MODE_SELECT, customRoot = root)
            stage.isResizable = false
            stage.show()
        }
    }
}
