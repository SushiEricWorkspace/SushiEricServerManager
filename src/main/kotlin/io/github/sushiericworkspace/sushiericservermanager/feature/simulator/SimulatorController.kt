package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import javafx.fxml.FXML
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.slf4j.LoggerFactory

/** 入力Nodeと実行状態を接続します。装備計算とダメージ計算は既存サービスへ委譲します。 */
class SimulatorController {
    @FXML private lateinit var inputContainer: VBox
    @FXML private lateinit var statusLabel: Label
    @FXML private lateinit var resultLabel: Label
    @FXML private lateinit var cancelButton: Button
    internal lateinit var inputEditor: SimulatorInputEditor
        private set
    internal lateinit var workspace: SimulatorWorkspace
        private set
    private val logger = LoggerFactory.getLogger(javaClass)

    @FXML private fun initialize() {
        val session = EditorSession.dataService
        val mode = EditorSession.mode
        inputEditor = SimulatorInputEditor { showError("入力を確認してください", it) }
        inputContainer.children.setAll(inputEditor)
        workspace = SimulatorWorkspace({ EditorSession.dataService === session && EditorSession.mode == mode }, ::showState)
    }

    @FXML @Suppress("unused")
    private fun calculate() {
        try {
            val input = inputEditor.readInput()
            workspace.start(input, inputEditor.readMode())
        } catch (error: IllegalArgumentException) {
            showError("入力を確認してください", error)
        } catch (error: IllegalStateException) {
            showError("計算を開始できません", error)
        }
    }

    @FXML @Suppress("unused")
    private fun cancel() = workspace.cancel()

    internal fun showState(state: SimulatorRunState) {
        cancelButton.isDisable = state !is SimulatorRunState.Running
        inputEditor.isDisable = state is SimulatorRunState.Running
        when (state) {
            SimulatorRunState.Idle -> { statusLabel.text = "未実行"; resultLabel.text = "" }
            is SimulatorRunState.Running -> {
                statusLabel.text = "計算中（要求 ${state.request.generation}）"
                resultLabel.text = ""
            }
            is SimulatorRunState.Finished -> {
                statusLabel.text = "要求 ${state.request.generation}"
                resultLabel.text = state.result.summary()
            }
            is SimulatorRunState.Failed -> {
                statusLabel.text = "計算失敗（ダメージ値なし）"
                resultLabel.text = ""
                showError("計算に失敗しました", state.error)
            }
        }
    }

    private fun showError(header: String, error: Throwable) {
        logger.warn("シミュレーター: {}", header, error)
        CustomDialog.error().title("戦闘シミュレーター").header(header)
            .content(error.message.orEmpty()).owner(inputContainer.scene?.window as? Stage).show()
    }

    /** 画面を再表示するときは新しいControllerを使います。 */
    fun dispose() = workspace.dispose()
}
