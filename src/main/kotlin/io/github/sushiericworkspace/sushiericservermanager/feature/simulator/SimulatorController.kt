package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.random.StableDamageRandom
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.StatsPart
import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import java.io.File
import javafx.scene.control.ComboBox
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
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
    @FXML private lateinit var detailsArea: TextArea
    @FXML private lateinit var comparisonLabel: Label
    @FXML private lateinit var previousComparisonButton: Button
    @FXML private lateinit var nextComparisonButton: Button
    @FXML private lateinit var importLabel: Label
    @FXML private lateinit var localRoot: TextField
    @FXML private lateinit var localId: TextField
    @FXML private lateinit var localPart: ComboBox<StatsPart>
    private lateinit var itemLoader: SimulatorItemLoader
    private val comparison = SimulatorComparisonSession()
    private val comparisonVersion = SimulatorComparisonVersion(
        StableDamageRandom::class.java.protectionDomain.codeSource.location.toExternalForm(),
        ItemBaseData::class.java.protectionDomain.codeSource.location.toExternalForm(),
        "manager-simulator-v1")
    private val logger = LoggerFactory.getLogger(javaClass)

    @FXML private fun initialize() {
        val session = EditorSession.dataService
        val mode = EditorSession.mode
        inputEditor = SimulatorInputEditor { showError("入力を確認してください", it) }
        inputContainer.children.setAll(inputEditor)
        workspace = SimulatorWorkspace({ EditorSession.dataService === session && EditorSession.mode == mode }, ::showState)
        localRoot.text = FilePath.OFFLINE_DIR.path
        localPart.items.setAll(StatsPart.entries.filter { it.isEquipment })
        localPart.value = StatsPart.MAIN_HAND
        itemLoader = SimulatorItemLoader({ EditorSession.dataService === session && EditorSession.mode == mode }, { workspace.generation }, { loaded ->
            when (loaded) {
                is StoreResult.Failure -> importLabel.text = "読込失敗: ${loaded.error.code} / ${loaded.error.detail.orEmpty()}（直前の入力・結果を保持）"
                is StoreResult.Success -> try {
                    acceptImportedItem(checkNotNull(requestedPart), loaded.value)
                } catch (error: IllegalArgumentException) { importLabel.text = "変換失敗: ${error.message}（直前の入力・結果を保持）" }
                catch (error: IllegalStateException) { importLabel.text = error.message.orEmpty() }
            }
        })
    }

    @FXML @Suppress("unused")
    private fun calculate() {
        try {
            val input = inputEditor.readInput()
            workspace.start(input, inputEditor.readMode())
            itemLoader.invalidate()
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
        detailPage = 0
        detailsArea.text = (state as? SimulatorRunState.Finished)?.result?.details().orEmpty()
        comparisonLabel.text = ""
        lastComparison = null
        comparisonPage = 0
        updateComparisonNavigation()
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

    @FXML private fun readLocalItem() {
        if (workspace.state is SimulatorRunState.Running) { importLabel.text = "計算中は取り込めません。"; return }
        try {
            val root = File(localRoot.text)
            require(root.isAbsolute) { "OFFLINE_DIRの絶対パスを指定してください。" }
            val part = localPart.value
            require(part != null) { "取込部位を選択してください。" }
            // 選択変更後の通知へ別部位を割り当てないよう、要求中は部位を固定する。
            requestedPart = part
            itemLoader.read(root, localId.text)
            importLabel.text = "明示ローカル取得中（サーバー原本は取得しません）"
        } catch (error: IllegalArgumentException) { importLabel.text = error.message.orEmpty() }
        catch (error: IllegalStateException) { importLabel.text = error.message.orEmpty() }
    }

    private var requestedPart: StatsPart? = null

    internal fun acceptImportedItem(part: StatsPart, value: SimulatorImportedItem) {
        check(workspace.acceptsInput()) { "終了済みの画面・接続には取り込めません。" }
        check(workspace.state !is SimulatorRunState.Running) { "計算中は取り込めません。" }
        require(part.isEquipment) { "装備部位を選択してください。" }
        when (val candidate = inputEditor.readInput(inputEditor.equipmentEditor.withImported(part, value))) {
            is SimulatorMultipleHitInput.Unsupported -> throw IllegalArgumentException("非対応: ${candidate.reason}（直前の装備・結果を保持）")
            is SimulatorMultipleHitInput.Ready -> Unit
        }
        inputEditor.equipmentEditor.slots.getValue(part).importItem(value)
        itemLoader.invalidate()
        importLabel.text = "${value.source} / revision=${value.revision}（固定取得、明示再読込で更新）"
        statusLabel.text = "入力を更新しました。直前の固定要求の結果は再計算まで保持します。"
    }

    private var detailPage = 0
    @FXML private fun previousDetails() = changeDetailPage(-1)
    @FXML private fun nextDetails() = changeDetailPage(1)
    private fun changeDetailPage(delta: Int) {
        val result = (workspace.state as? SimulatorRunState.Finished)?.result ?: return
        detailPage = (detailPage + delta).coerceIn(0, result.detailPages() - 1)
        detailsArea.text = result.details(detailPage)
    }

    private fun currentSnapshot(): SimulatorComparisonSnapshot? = (workspace.state as? SimulatorRunState.Finished)?.let {
        SimulatorComparisonSnapshot.capture(it.request, it.result, comparisonVersion)
    }
    @FXML private fun holdBaseline() {
        currentSnapshot()?.let {
            comparison.setBaseline(it)
            lastComparison = null
            comparisonPage = 0
            updateComparisonNavigation()
            comparisonLabel.text = "固定要求 ${it.generation} を比較基準へ保持しました。"
        }
    }
    @FXML private fun clearBaseline() { comparison.clearBaseline(); lastComparison = null; comparisonPage = 0; updateComparisonNavigation(); comparisonLabel.text = "比較基準を解除しました。" }
    @FXML private fun compare() {
        currentSnapshot()?.let {
            comparisonPage = 0
            lastComparison = comparison.compare(it, workspace.generation)
            comparisonLabel.text = checkNotNull(lastComparison).details()
            updateComparisonNavigation()
        }
    }

    private var lastComparison: SimulatorComparisonResult? = null
    private var comparisonPage = 0
    @FXML private fun previousComparison() = changeComparisonPage(-1)
    @FXML private fun nextComparison() = changeComparisonPage(1)
    private fun changeComparisonPage(delta: Int) {
        val value = lastComparison ?: return
        comparisonPage = (comparisonPage + delta).coerceIn(0, value.detailPages() - 1)
        comparisonLabel.text = value.details(comparisonPage)
        updateComparisonNavigation()
    }

    private fun updateComparisonNavigation() {
        previousComparisonButton.isDisable = lastComparison == null || comparisonPage == 0
        nextComparisonButton.isDisable = lastComparison == null || comparisonPage >= checkNotNull(lastComparison).detailPages() - 1
    }

    /** 画面を再表示するときは新しいControllerを使います。 */
    fun dispose() { itemLoader.dispose(); workspace.dispose() }
}
