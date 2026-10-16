package io.github.sushiericworkspace.sushiericservermanager.feature.console

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.app.appWindowTitle
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.stage.Stage
import javafx.stage.Window

/** コンソール画面を単一ウィンドウとして管理します。 */
object ConsoleWindowManager {
    private var activeStage: Stage? = null

    /** コンソール画面を開き、表示済みの場合は前面へ移動します。 */
    fun open(owner: Window?) {
        activeStage?.takeIf(Stage::isShowing)?.let {
            it.toFront()
            return
        }

        val loader = FXMLLoader(javaClass.getResource(AppScreen.CONSOLE.fxml!!))
        val root = loader.load<Parent>()
        val controller = loader.getController<ConsoleController>()
        val stage = Stage().apply {
            title = appWindowTitle("Minecraftコンソール")
            scene = Utility.createScene(AppScreen.CONSOLE, customRoot = root)
            minWidth = 640.0
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

    /** 表示中のコンソール画面を閉じます。 */
    fun close() {
        activeStage?.close()
        activeStage = null
    }
}
