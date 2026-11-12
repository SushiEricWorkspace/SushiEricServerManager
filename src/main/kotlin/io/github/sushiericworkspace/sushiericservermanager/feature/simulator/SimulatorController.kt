package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import io.github.sushiericworkspace.combatcore.combat.random.StableDamageRandom
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.ItemBaseData
import io.github.sushiericworkspace.sushiericdatamodel.stats.player.StatsPart
import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import java.io.File
import io.github.sushiericworkspace.sushiericservermanager.editor.component.SearchableComboBox
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
    @FXML private lateinit var formulaHelpArea: TextArea
    @FXML private lateinit var formulaRequestArea: TextArea
    @FXML private lateinit var comparisonLabel: Label
    @FXML private lateinit var previousComparisonButton: Button
    @FXML private lateinit var nextComparisonButton: Button
    @FXML private lateinit var importLabel: Label
    @FXML private lateinit var localRoot: TextField
    @FXML private lateinit var localChoicesBox: VBox
    internal lateinit var localChoices: SearchableComboBox<SimulatorItemChoice>
        private set
    private var catalogRoot: File? = null
    private var searchHasMatches = true
    @FXML private lateinit var localPart: ComboBox<StatsPart>
    private lateinit var itemLoader: SimulatorItemLoader
    private val comparison = SimulatorComparisonSession()
    private val comparisonVersion = SimulatorComparisonVersion(
        StableDamageRandom::class.java.protectionDomain.codeSource.location.toExternalForm(),
        ItemBaseData::class.java.protectionDomain.codeSource.location.toExternalForm(),
        "manager-simulator-v1")
    private val logger = LoggerFactory.getLogger(javaClass)

    @FXML private fun initialize() {
        formulaHelpArea.text = simulatorFormulaHelp
        val session = EditorSession.dataService
        val mode = EditorSession.mode
        inputEditor = SimulatorInputEditor { showError("入力を確認してください", it) }
        inputContainer.children.setAll(inputEditor)
        workspace = SimulatorWorkspace({ EditorSession.dataService === session && EditorSession.mode == mode }, ::showState)
        localRoot.text = FilePath.OFFLINE_DIR.path
        localPart.items.setAll(StatsPart.entries.filter { it.isEquipment })
        localPart.value = StatsPart.MAIN_HAND
        localPart.converter = simulatorConverter { it.display }
        localChoices = SearchableComboBox(listOf(SimulatorItemChoice.None), SimulatorItemChoice.None,
            "装備名・公開IDで検索", { choice -> when (choice) {
                SimulatorItemChoice.None -> "なし（この部位を未装備にする）"
                is SimulatorItemChoice.Saved -> choice.name?.let { "$it / ${choice.id}" } ?: choice.id
            } }, { choice -> when (choice) {
                SimulatorItemChoice.None -> listOf("なし", "未装備")
                is SimulatorItemChoice.Saved -> listOf(choice.id, choice.name.orEmpty())
            } }, { itemLoader.invalidate() }, { matches ->
                searchHasMatches = matches
                if (!matches) importLabel.text = "一致する装備がありません。全候補を表示しています。"
            })
        localChoices.comboBox.id = "localId"
        localChoicesBox.children.setAll(localChoices)
        itemLoader = SimulatorItemLoader({ EditorSession.dataService === session && EditorSession.mode == mode }, { workspace.generation }, { loaded ->
            when (loaded) {
                is StoreResult.Failure -> importLabel.text = "読込失敗: 装備が見つからないか、内容を読み込めませんでした。${loaded.error.detail.orEmpty()}（直前の入力・結果を保持）"
                is StoreResult.Success -> try {
                    acceptImportedItem(checkNotNull(requestedPart), loaded.value)
                } catch (error: IllegalArgumentException) { importLabel.text = "変換失敗: ${error.message}（直前の入力・結果を保持）" }
                catch (error: IllegalStateException) { importLabel.text = error.message.orEmpty() }
            }
        }, onChoices = { root, result ->
            when (result) {
                is StoreResult.Failure -> importLabel.text = "候補の取得に失敗しました。直前の装備・結果は変更していません。${result.error.detail.orEmpty()}"
                is StoreResult.Success -> {
                    catalogRoot = root
                    val selectedId = (localChoices.comboBox.value as? SimulatorItemChoice.Saved)?.id
                    localChoices.updateChoices(listOf(SimulatorItemChoice.None) + result.value)
                    result.value.firstOrNull { it.id == selectedId }?.let { localChoices.comboBox.value = it }
                    importLabel.text = "装備候補 ${result.value.size}件。選択後に「選択を適用・再読込」を押してください。"
                }
            }
        })
        localRoot.textProperty().addListener { _, _, _ -> catalogRoot = null; itemLoader.invalidate() }
        localPart.valueProperty().addListener { _, _, _ -> itemLoader.invalidate() }
        inputEditor.equipmentEditor.onSelectionChange = { itemLoader.invalidate() }
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
        formulaRequestArea.text = (state as? SimulatorRunState.Finished)?.formulaExplanation() ?: "まだ計算していません。"
        comparisonLabel.text = ""
        lastComparison = null
        comparisonPage = 0
        updateComparisonNavigation()
        when (state) {
            SimulatorRunState.Idle -> { statusLabel.text = "未実行"; resultLabel.text = "" }
            is SimulatorRunState.Running -> {
                statusLabel.text = "計算中"
                resultLabel.text = ""
            }
            is SimulatorRunState.Finished -> {
                statusLabel.text = "計算結果 #${state.request.generation}"
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
            require(searchHasMatches) { "一致する装備がありません。検索欄を空にして、装備または「なし」を選んでください。" }
            val root = File(localRoot.text)
            val part = localPart.value
            require(part != null) { "取込部位を選択してください。" }
            // 選択変更後の通知へ別部位を割り当てないよう、要求中は部位を固定する。
            requestedPart = part
            when (val choice = localChoices.comboBox.value) {
                SimulatorItemChoice.None -> unequip(part)
                is SimulatorItemChoice.Saved -> {
                    require(root.isAbsolute) { "ローカル保存先の絶対パスを指定してください。" }
                    require(root == catalogRoot) { "保存先を変更した場合は装備候補を更新してください。" }
                    require(choice in localChoices.comboBox.items) { "候補から装備を選んでください。" }
                    itemLoader.read(root, choice.id)
                    importLabel.text = "ローカル装備を読み込んでいます（サーバーには接続しません）"
                }
                null -> throw IllegalArgumentException("装備または「なし」を選んでください。")
            }
        } catch (error: IllegalArgumentException) { importLabel.text = error.message.orEmpty() }
        catch (error: IllegalStateException) { importLabel.text = error.message.orEmpty() }
    }

    @FXML private fun refreshLocalItems() {
        if (workspace.state is SimulatorRunState.Running) { importLabel.text = "計算中は候補を更新できません。"; return }
        try {
            val root = File(localRoot.text)
            require(root.isAbsolute) { "ローカル保存先の絶対パスを指定してください。" }
            itemLoader.readChoices(root)
            importLabel.text = "装備候補を取得しています。装備・結果は変更しません。"
        } catch (error: IllegalArgumentException) { importLabel.text = error.message.orEmpty() }
        catch (error: IllegalStateException) { importLabel.text = error.message.orEmpty() }
    }

    internal fun unequip(part: StatsPart) {
        check(workspace.acceptsInput() && workspace.state !is SimulatorRunState.Running) { "計算中または終了済みの画面では装備を変更できません。" }
        when (val candidate = inputEditor.readInput(inputEditor.equipmentEditor.without(part))) {
            is SimulatorMultipleHitInput.Unsupported -> throw IllegalArgumentException("変更できません: ${candidate.reason}（直前の装備・結果を保持）")
            is SimulatorMultipleHitInput.Ready -> Unit
        }
        itemLoader.invalidate()
        inputEditor.equipmentEditor.slots.getValue(part).unequip()
        importLabel.text = "${part.display}を未装備にしました。"
        statusLabel.text = "入力を更新しました。表示中の結果は変更前のものです。再計算してください。"
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
        importLabel.text = "${value.source} / 取得版=${value.revision}（再読込するまで、この装備内容で固定）"
        statusLabel.text = "入力を更新しました。表示中の結果は変更前のものです。再計算してください。"
    }

    private var detailPage = 0
    @FXML private fun previousDetails() = changeDetailPage(-1)
    @FXML private fun nextDetails() = changeDetailPage(1)
    private fun changeDetailPage(delta: Int) {
        val finished = workspace.state as? SimulatorRunState.Finished ?: return
        val result = finished.result
        detailPage = (detailPage + delta).coerceIn(0, result.detailPages() - 1)
        detailsArea.text = result.details(detailPage)
        formulaRequestArea.text = finished.formulaExplanation(detailPage)
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
            comparisonLabel.text = "表示中の結果を比較基準にしました（計算 #${it.generation}）。"
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
