package io.github.sushiericworkspace.sushiericservermanager.feature.dashboard

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.app.appWindowTitle
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.stage.Stage
import javafx.stage.Window

/** Dashboard画面を単一ウィンドウとして管理します。 */
object DashboardWindowManager {
    private var activeStage: Stage? = null

    /** Dashboard画面を開き、表示済みの場合は前面へ移動します。 */
    fun open(owner: Window?) {
        activeStage?.takeIf(Stage::isShowing)?.let {
            it.toFront()
            return
        }

        val loader = FXMLLoader(javaClass.getResource(AppScreen.DASHBOARD.fxml!!))
        val root = loader.load<Parent>()
        val controller = loader.getController<DashboardController>()

        val stage = Stage().apply {
            title = appWindowTitle("Dashboard")
            scene = Utility.createScene(AppScreen.DASHBOARD, customRoot = root)
            minWidth = 480.0
            minHeight = 420.0
            owner?.let(::initOwner)
            setOnHidden {
                controller.dispose()
                activeStage = null
            }
        }

        activeStage = stage
        stage.show()
    }

    /** 表示中のDashboard画面を閉じます。 */
    fun close() {
        activeStage?.close()
        activeStage = null
    }
}
