package io.github.sushiericworkspace.sushiericservermanager.app

import javafx.fxml.FXML
import javafx.scene.control.Button
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession

open class ModeSelectionController {
    @FXML private lateinit var onlineButton: Button
    @FXML private lateinit var offlineButton: Button

    private var onSelected: ((AppMode) -> Unit)? = null

    fun configure(onSelected: (AppMode) -> Unit) {
        this.onSelected = onSelected
        if (EditorSession.connection != null) disableActions()
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

    protected open fun disableActions() {
        onlineButton.isDisable = true
        offlineButton.isDisable = true
    }

}
