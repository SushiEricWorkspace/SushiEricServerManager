package io.github.sushiericworkspace.sushiericservermanager.feature.servercontrol

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.app.appWindowTitle
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.stage.Stage
import javafx.stage.Window

/** Server Control画面を単一ウィンドウとして管理します。 */
object ServerControlWindowManager {
    private var activeStage: Stage? = null

    /** Server Control画面を開き、表示済みの場合は前面へ移動します。 */
    fun open(owner: Window?) {
        activeStage?.takeIf(Stage::isShowing)?.let {
            it.toFront()
            return
        }

        val loader = FXMLLoader(javaClass.getResource(AppScreen.SERVER_CONTROL.fxml!!))
        val root = loader.load<Parent>()

        val stage = Stage().apply {
            title = appWindowTitle("Server Control")
            scene = Utility.createScene(AppScreen.SERVER_CONTROL, customRoot = root)
            minWidth = 560.0
            minHeight = 480.0
            owner?.let(::initOwner)
            setOnHidden { activeStage = null }
        }

        activeStage = stage
        stage.show()
    }

    /** 表示中のServer Control画面を閉じます。 */
    fun close() {
        activeStage?.close()
        activeStage = null
    }
}
