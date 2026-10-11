package io.github.sushiericworkspace.sushiericservermanager.app

import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.layout.VBox
import javafx.stage.Stage
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession

class ModeSelectionController {
    @FXML private lateinit var rootPane: VBox
    @FXML private lateinit var onlineButton: Button
    @FXML private lateinit var offlineButton: Button
    @FXML private lateinit var managedButton: Button

    private var onSelected: ((AppMode) -> Unit)? = null

    fun configure(onSelected: (AppMode) -> Unit) {
        this.onSelected = onSelected
        if (EditorSession.managedSession != null) disableActions()
    }

    @FXML
    @Suppress("unused")
    private fun selectOnline() {
        disableActions()
        onSelected?.invoke(AppMode.ONLINE)
    }

    @FXML
    @Suppress("unused")
    private fun selectOffline() {
        disableActions()
        onSelected?.invoke(AppMode.OFFLINE)
    }

    private fun disableActions() {
        onlineButton.isDisable = true
        offlineButton.isDisable = true
        managedButton.isDisable = true
    }

    @FXML
    @Suppress("unused")
    private fun selectManaged() {
        val stage = rootPane.scene.window as Stage
        val profile = ManagedConnectionDialog.show(stage) ?: return
        disableActions()
        ApplicationFlow.prepareManaged(stage, profile)
    }
}
