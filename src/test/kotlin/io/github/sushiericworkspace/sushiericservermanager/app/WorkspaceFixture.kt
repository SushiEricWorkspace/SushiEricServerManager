package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.app.workspace.WorkspaceView
import io.github.sushiericworkspace.sushiericservermanager.ui.theme.ThemeManager
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import javafx.stage.Stage

internal fun workspaceStage(identity: String? = null, width: Double = 1300.0): Stage {
    val home = VBox(Label("ホーム"))
    val frame = AppFrame(AppScreen.HOME, home)
    frame.center = WorkspaceView(frame, home, identity, {}, { true }, {})
    return Stage().apply {
        scene = Scene(frame, width, 800.0)
        ThemeManager.apply(scene)
        show()
    }
}
