package io.github.sushiericworkspace.sushiericservermanager.app

import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.layout.VBox
import javafx.stage.Stage

/** 通常のモード選択に開発専用の管理接続を追加します。 */
class DevelopmentModeSelectionController : ModeSelectionController() {
    @FXML private lateinit var rootPane: VBox
    @FXML private lateinit var managedButton: Button

    override fun disableActions() {
        super.disableActions()
        managedButton.isDisable = true
    }

    @FXML
    @Suppress("unused")
    private fun selectManaged() {
        val stage = rootPane.scene.window as Stage
        val profile = ManagedConnectionDialog.show(stage) ?: return
        disableActions()
        ManagedApplicationFlow.prepareManaged(stage, profile)
    }
}
