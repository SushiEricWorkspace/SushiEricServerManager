package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.app.appWindowTitle
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.stage.Stage
import javafx.stage.Window

/** Stageを持たない画面本体です。機能領域への接続でも同じNodeと終了処理を使えます。 */
class SimulatorView private constructor(val root: Parent, val controller: SimulatorController) {
    companion object {
        fun load(): SimulatorView {
            val loader = FXMLLoader(SimulatorView::class.java.getResource(AppScreen.SIMULATOR.fxml!!))
            val root = loader.load<Parent>()
            return SimulatorView(root, loader.getController())
        }
    }
    fun dispose() = controller.dispose()
}

/** 既存Home経路のシミュレーターを一つだけ保持します。 */
object SimulatorWindowManager {
    private var activeStage: Stage? = null

    fun open(owner: Window?) {
        activeStage?.takeIf(Stage::isShowing)?.let { it.toFront(); return }
        val view = SimulatorView.load()
        val stage = Stage().apply {
            title = appWindowTitle("戦闘シミュレーター")
            scene = Utility.createScene(AppScreen.SIMULATOR, customRoot = view.root)
            minWidth = 850.0
            minHeight = 600.0
            owner?.let(::initOwner)
            setOnHidden { view.dispose(); activeStage = null }
        }
        activeStage = stage
        stage.show()
    }

    fun close() { activeStage?.close() }
}
