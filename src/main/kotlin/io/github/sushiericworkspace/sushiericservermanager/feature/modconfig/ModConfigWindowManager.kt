package io.github.sushiericworkspace.sushiericservermanager.feature.modconfig

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.editor.service.EditorDataService
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.fxml.FXMLLoader
import javafx.scene.Parent
import javafx.stage.Stage
import javafx.stage.Window

/** Mod共通設定（config.yml）の編集画面を単一ウィンドウとして管理します。 */
object ModConfigWindowManager {
    private var activeStage: Stage? = null

    /**
     * 設定の編集画面を開き、表示済みの場合は前面へ移動します。
     *
     * @param owner 親ウィンドウ。
     * @param dataService 設定ファイルの読み書きに使うデータサービス。
     */
    fun open(owner: Window?, dataService: EditorDataService) {
        activeStage?.takeIf(Stage::isShowing)?.let {
            it.toFront()
            return
        }

        val loader = FXMLLoader(javaClass.getResource(AppScreen.MOD_CONFIG.fxml!!))
        val root = loader.load<Parent>()

        loader.getController<ModConfigController>().initData(dataService)

        val stage = Stage().apply {
            title = "SushiEricServerManager - Mod設定"
            scene = Utility.createScene(AppScreen.MOD_CONFIG, customRoot = root)
            minWidth = 640.0
            minHeight = 480.0
            owner?.let(::initOwner)
            setOnHidden { activeStage = null }
        }

        activeStage = stage
        stage.show()
    }

    /** 表示中の編集画面を閉じます。 */
    fun close() {
        activeStage?.close()
        activeStage = null
    }
}
