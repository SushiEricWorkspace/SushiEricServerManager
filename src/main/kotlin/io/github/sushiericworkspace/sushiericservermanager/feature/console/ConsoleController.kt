package io.github.sushiericworkspace.sushiericservermanager.feature.console

import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementCommandSuggestion
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementRequest
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementResponse
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementState
import io.github.sushiericworkspace.sushiericservermanager.config.SettingConfigManager
import io.github.sushiericworkspace.sushiericservermanager.config.replaceServerProfilePreservingOrder
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.ui.AppTooltip
import javafx.animation.AnimationTimer
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.geometry.Bounds
import javafx.geometry.Orientation
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.ScrollBar
import javafx.scene.control.SelectionMode
import javafx.scene.control.Slider
import javafx.scene.control.TextArea
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseDragEvent
import javafx.scene.input.MouseEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Region
import javafx.stage.Popup
import javafx.util.Duration
import java.net.URL
import java.util.ResourceBundle
import java.util.UUID
import kotlin.math.roundToInt

/** Management APIを使用するMinecraftコンソール画面を管理します。 */
class ConsoleController : Initializable {
    @FXML private lateinit var rootPane: BorderPane
    @FXML private lateinit var connectionLabel: Label
    @FXML private lateinit var reconnectButton: Button
    @FXML private lateinit var outputListView: ListView<ConsoleOutputEntry>
    @FXML private lateinit var commandField: TextArea
    @FXML private lateinit var logLevelSlider: Slider
    @FXML private lateinit var logLevelLabel: Label

    private val client = EditorSession.managementClient
    private val commandModel = ConsoleCommandModel()
    private val completionDelay = PauseTransition(Duration.millis(COMPLETION_DELAY_MILLIS))
    private val suggestionPopup = Popup()
    private val suggestionList = ListView<ServerManagementCommandSuggestion>()
    private val pendingCommands = mutableMapOf<String, String>()
    private val incomingLogs = ConsoleLogBuffer(INCOMING_LOG_LIMIT)
    private val autoScrollPolicy = ConsoleAutoScrollPolicy()
    private var pendingCompletion: PendingCompletion? = null
    private var suppressInputListener = false
    private var subscribedToLogs = false
    private var verticalScrollBar: ScrollBar? = null
    private var dragAnchorIndex: Int? = null

    /**
     * 受信したすべての行です。
     *
     * 表示するレベルを下げたときに過去の行も出せるよう、
     * 画面へ出している行とは別に保持します。
     */
    private val allEntries = mutableListOf<ConsoleOutputEntry>()

    /** 画面へ表示するレベルの下限です。 */
    private var minimumLevel = ConsoleLogLevel.DEFAULT_MINIMUM

    private val logDrainTimer = object : AnimationTimer() {
        override fun handle(now: Long) {
            val logs = incomingLogs.drain(LOGS_PER_FRAME)
            if (logs.isEmpty()) return
            appendEntries(
                logs.map { log ->
                    ConsoleOutputEntry(
                        text = log.displayText,
                        styleClass = levelStyleClass(log.normalizedLevel),
                        level = ConsoleLogLevel.from(log.normalizedLevel)
                    )
                }
            )
        }
    }

    private val stateListener: (ServerManagementState) -> Unit = { state ->
        runOnFxThread { applyConnectionState(state) }
    }
    private val messageListener: (ServerManagementResponse) -> Unit = { message ->
        if (message is ServerManagementResponse.ConsoleLog) {
            incomingLogs.offer(ConsoleLogEntry.from(message))
        } else {
            runOnFxThread { receive(message) }
        }
    }

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        configureOutputList()
        configureLogLevelSlider()
        configureSuggestionPopup()
        configureCommandInput()
        client.addStateListener(stateListener)
        client.addMessageListener(messageListener)
        applyConnectionState(client.state)
        logDrainTimer.start()
    }

    private fun configureOutputList() {
        /*
         * コマンドプロンプトと同じ感覚で範囲を選んでコピーできるようにする。
         * ドラッグとShift+クリックで複数行を選び、ショートカットでコピーする。
         */
        outputListView.selectionModel.selectionMode = SelectionMode.MULTIPLE
        outputListView.addEventFilter(KeyEvent.KEY_PRESSED, ::handleOutputKeyPressed)

        outputListView.setCellFactory {
            val cell = object : ListCell<ConsoleOutputEntry>() {
                override fun updateItem(item: ConsoleOutputEntry?, empty: Boolean) {
                    super.updateItem(item, empty)
                    styleClass.removeAll(DISPLAY_STYLE_CLASSES)
                    text = if (empty || item == null) null else item.text
                    item?.styleClass?.let(styleClass::add)
                }
            }

            /*
             * ListViewはドラッグでの範囲選択を持たない。
             * 押した位置を基点として記録し、通過したセルまでを選択範囲にする。
             *
             * ドラッグの開始はDRAG_DETECTEDで宣言する。
             * 押下時点で開始すると、通常のクリックによる選択が働かなくなる。
             */
            cell.addEventHandler(MouseEvent.MOUSE_PRESSED) { event ->
                if (event.button == MouseButton.PRIMARY && !cell.isEmpty) {
                    dragAnchorIndex = cell.index
                }
            }

            cell.addEventHandler(MouseEvent.DRAG_DETECTED) { event ->
                if (event.button == MouseButton.PRIMARY && !cell.isEmpty) {
                    cell.startFullDrag()
                }
            }

            cell.addEventHandler(MouseDragEvent.MOUSE_DRAG_ENTERED) {
                if (!cell.isEmpty) selectOutputRange(cell.index)
            }

            cell
        }
        Platform.runLater {
            verticalScrollBar = outputListView.lookupAll(".scroll-bar")
                .filterIsInstance<ScrollBar>()
                .firstOrNull { it.orientation == Orientation.VERTICAL }
                ?.also { scrollBar ->
                    scrollBar.valueProperty().addListener { _, _, value ->
                        autoScrollPolicy.update(
                            current = value.toDouble(),
                            maximum = scrollBar.max,
                            scrollBarVisible = scrollBar.isVisible
                        )
                    }
                    scrollBar.visibleProperty().addListener { _, _, visible ->
                        autoScrollPolicy.update(
                            current = scrollBar.value,
                            maximum = scrollBar.max,
                            scrollBarVisible = visible
                        )
                    }
                }
        }
    }

    /**
     * ドラッグの基点から[index]までを選択範囲にします。
     *
     * 上下どちらの向きへドラッグしても同じ範囲になるよう、行番号の小さい方から選択します。
     */
    private fun selectOutputRange(index: Int) {
        val anchor = dragAnchorIndex ?: return
        val from = minOf(anchor, index)
        val to = maxOf(anchor, index)

        outputListView.selectionModel.clearSelection()
        outputListView.selectionModel.selectRange(from, to + 1)
    }

    private fun handleOutputKeyPressed(event: KeyEvent) {
        when {
            COPY_SHORTCUT.match(event) -> {
                copySelectedOutput()
                event.consume()
            }

            SELECT_ALL_SHORTCUT.match(event) -> {
                outputListView.selectionModel.selectAll()
                event.consume()
            }
        }
    }

    /**
     * 選択している行を、画面へ表示している文字列のままクリップボードへ入れます。
     *
     * 選択が飛び飛びの場合も表示順を保つため、行番号の順で並べ替えてから連結します。
     */
    private fun copySelectedOutput() {
        val lines = outputListView.selectionModel.selectedIndices
            .sorted()
            .mapNotNull { index -> outputListView.items.getOrNull(index)?.text }

        if (lines.isEmpty()) return

        Clipboard.getSystemClipboard().setContent(
            ClipboardContent().apply { putString(joinConsoleLines(lines)) }
        )
    }

    /**
     * 表示レベルのスライダーを、選べるレベルの並びへ対応付けます。
     *
     * 目盛りはレベル1つ分とし、つまみが段階の位置以外で止まらないようにします。
     */
    private fun configureLogLevelSlider() {
        val levels = ConsoleLogLevel.SELECTABLE

        logLevelSlider.min = 0.0
        logLevelSlider.max = (levels.size - 1).toDouble()
        logLevelSlider.majorTickUnit = 1.0
        logLevelSlider.minorTickCount = 0
        logLevelSlider.isSnapToTicks = true
        logLevelSlider.blockIncrement = 1.0
        val initial = loadStoredMinimumLevel()
        logLevelSlider.value = levels.indexOf(initial).toDouble()

        logLevelSlider.valueProperty().addListener { _, _, value ->
            val index = value.toDouble().roundToInt().coerceIn(levels.indices)
            val selected = levels[index]

            if (selected != minimumLevel) {
                applyMinimumLevel(selected)
                saveMinimumLevel(selected)
            }
        }

        applyMinimumLevel(initial)
    }

    /**
     * 接続中のプロファイルへ保存された表示レベルを読み込みます。
     *
     * プロファイルが無い場合と、保存された値を選べるレベルとして解釈できない場合は既定値を使用します。
     */
    private fun loadStoredMinimumLevel(): ConsoleLogLevel {
        val profileName = EditorSession.sshManager.currentProfile?.name
            ?: return ConsoleLogLevel.DEFAULT_MINIMUM

        val stored = SettingConfigManager.load().list
            .firstOrNull { it.name == profileName }
            ?.consoleLogLevel
            ?: return ConsoleLogLevel.DEFAULT_MINIMUM

        return ConsoleLogLevel.from(stored)
            ?.takeIf { it in ConsoleLogLevel.SELECTABLE }
            ?: ConsoleLogLevel.DEFAULT_MINIMUM
    }

    /**
     * 選んだ表示レベルを、接続中のプロファイルへ保存します。
     *
     * 他のプロファイルと並び順を変えないよう、対象のプロファイルだけを差し替えます。
     */
    private fun saveMinimumLevel(level: ConsoleLogLevel) {
        val profileName = EditorSession.sshManager.currentProfile?.name
            ?: return

        val config = SettingConfigManager.load()

        val target = config.list.firstOrNull { it.name == profileName }
            ?: return

        if (target.consoleLogLevel == level.name) {
            return
        }

        val updated = config.copy(
            list = replaceServerProfilePreservingOrder(
                profiles = config.list,
                originalName = profileName,
                replacement = target.copy(consoleLogLevel = level.name)
            )
        )

        if (!SettingConfigManager.saveAndVerify(updated)) {
            appendOutput("表示レベルの設定を保存できませんでした。", COMMAND_ERROR_STYLE)
        }
    }

    private fun configureSuggestionPopup() {
        suggestionList.styleClass.add("console-suggestion-list")
        suggestionList.stylesheets.add(
            requireNotNull(javaClass.getResource(AppScreen.CONSOLE.css)).toExternalForm()
        )
        suggestionList.setCellFactory {
            object : ListCell<ServerManagementCommandSuggestion>() {
                override fun updateItem(item: ServerManagementCommandSuggestion?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else item.text
                    tooltip = item?.tooltip?.takeIf(String::isNotBlank)?.let(AppTooltip::create)
                }
            }
        }
        suggestionList.setOnMouseClicked { event ->
            if (event.button == MouseButton.PRIMARY && !suggestionList.selectionModel.isEmpty) {
                applySelectedSuggestion()
            }
        }
        suggestionPopup.isAutoHide = true
        suggestionPopup.isHideOnEscape = true
        suggestionPopup.content.add(suggestionList)
        suggestionPopup.scene.addEventFilter(KeyEvent.KEY_PRESSED, ::handlePopupKeyPressed)
    }

    private fun configureCommandInput() {
        completionDelay.setOnFinished { requestCompletion() }
        commandField.textProperty().addListener { _, _, text ->
            updateCommandFieldHeight(text)
            if (suppressInputListener) return@addListener
            commandModel.resetHistoryNavigation()
            scheduleCompletion(text)
        }
        commandField.addEventFilter(KeyEvent.KEY_PRESSED, ::handleKeyPressed)
        commandField.maxHeight = Region.USE_PREF_SIZE
        updateCommandFieldHeight(commandField.text)
    }

    private fun handleKeyPressed(event: KeyEvent) {
        when (event.code) {
            KeyCode.ENTER -> {
                if (event.isShiftDown) {
                    insertLineBreak()
                } else {
                    executeCommands()
                }
            }
            KeyCode.TAB -> {
                if (suggestionPopup.isShowing && !suggestionList.items.isEmpty()) {
                    applySelectedSuggestion()
                } else {
                    completionDelay.stop()
                    requestCompletion()
                }
            }
            KeyCode.UP -> {
                if (suggestionPopup.isShowing) {
                    moveSuggestionSelection(-1)
                } else if (!shouldNavigateHistory(KeyCode.UP)) {
                    return
                } else {
                    commandModel.previous(commandField.text)?.let(::replaceCommandText)
                }
            }
            KeyCode.DOWN -> {
                if (suggestionPopup.isShowing) {
                    moveSuggestionSelection(1)
                } else if (!shouldNavigateHistory(KeyCode.DOWN)) {
                    return
                } else {
                    commandModel.next()?.let(::replaceCommandText)
                }
            }
            KeyCode.ESCAPE -> cancelCompletion()
            KeyCode.LEFT,
            KeyCode.RIGHT,
            KeyCode.HOME,
            KeyCode.END -> {
                cancelCompletion()
                return
            }
            else -> return
        }
        event.consume()
    }

    private fun handlePopupKeyPressed(event: KeyEvent) {
        when (event.code) {
            KeyCode.ENTER -> {
                if (event.isShiftDown) {
                    insertLineBreak()
                } else {
                    executeCommands()
                }
            }
            KeyCode.TAB -> applySelectedSuggestion()
            KeyCode.UP -> moveSuggestionSelection(-1)
            KeyCode.DOWN -> moveSuggestionSelection(1)
            KeyCode.ESCAPE -> cancelCompletion()
            KeyCode.LEFT,
            KeyCode.RIGHT,
            KeyCode.HOME,
            KeyCode.END -> moveCaretFromPopup(event.code)
            else -> return
        }
        event.consume()
    }

    private fun moveCaretFromPopup(keyCode: KeyCode) {
        cancelCompletion()
        commandField.requestFocus()
        val current = commandField.caretPosition
        val currentLine = commandModel.currentLine(commandField.text, current)
        val destination = when (keyCode) {
            KeyCode.LEFT -> current - 1
            KeyCode.RIGHT -> current + 1
            KeyCode.HOME -> currentLine.start
            KeyCode.END -> currentLine.end
            else -> current
        }
        commandField.positionCaret(destination.coerceIn(0, commandField.text.length))
    }

    private fun executeCommands() {
        hideSuggestions()
        completionDelay.stop()
        val commands = commandModel.executableCommands(commandField.text)
        if (commands.isEmpty()) return
        if (!client.isConnected) {
            appendOutput("Management APIへ接続していないため実行できません。", COMMAND_ERROR_STYLE)
            applyConnectionState(client.state)
            return
        }

        commands.forEachIndexed { index, command ->
            val nonce = UUID.randomUUID().toString()
            if (!client.send(ServerManagementRequest.CommandExecute(command, nonce))) {
                appendOutput("コマンドを送信できませんでした。", COMMAND_ERROR_STYLE)
                replaceCommandText(commands.drop(index).joinToString("\n"))
                applyConnectionState(client.state)
                return
            }

            pendingCommands[nonce] = command
            appendOutput("> $command", COMMAND_STYLE)
            commandModel.record(command)
        }
        replaceCommandText("")
    }

    private fun scheduleCompletion(text: String) {
        pendingCompletion = null
        if (text.isBlank() || !client.isConnected) {
            completionDelay.stop()
            hideSuggestions()
            return
        }
        completionDelay.playFromStart()
    }

    private fun requestCompletion() {
        val fullText = commandField.text
        val line = commandModel.currentLine(fullText, commandField.caretPosition)
        if (line.text.isBlank() || !client.isConnected) {
            hideSuggestions()
            return
        }

        val nonce = UUID.randomUUID().toString()
        val request = PendingCompletion(nonce, fullText, commandField.caretPosition)
        pendingCompletion = request
        if (!client.send(ServerManagementRequest.CommandComplete(line.text, line.caretPosition, nonce))) {
            pendingCompletion = null
            hideSuggestions()
            applyConnectionState(client.state)
        }
    }

    private fun receive(message: ServerManagementResponse) {
        when (message) {
            is ServerManagementResponse.CommandResult -> showCommandResult(message)
            is ServerManagementResponse.CommandCompleteResult -> showCompletionResult(message)
            is ServerManagementResponse.ConsoleLog -> Unit
            is ServerManagementResponse.MonitorSubscription -> Unit
            is ServerManagementResponse.MonitorUpdate -> Unit
            is ServerManagementResponse.Error -> {
                appendOutput(
                    "Management APIエラー: ${message.reason}${message.detail?.let { " ($it)" }.orEmpty()}",
                    COMMAND_ERROR_STYLE
                )
                hideSuggestions()
            }
            is ServerManagementResponse.Pong -> Unit
        }
    }

    private fun showCommandResult(result: ServerManagementResponse.CommandResult) {
        val nonce = result.nonce ?: return
        if (pendingCommands.remove(nonce) == null) return

        if (result.output.isEmpty()) {
            val status = if (result.success) "成功" else "失敗"
            val returnValue = result.returnValue?.let { "（戻り値: $it）" }.orEmpty()
            appendOutput(
                "$status$returnValue",
                if (result.success) COMMAND_SUCCESS_STYLE else COMMAND_ERROR_STYLE
            )
        } else {
            val styleClass = if (result.success) COMMAND_OUTPUT_STYLE else COMMAND_ERROR_STYLE
            result.output.forEach { appendOutput(it, styleClass) }
        }
    }

    private fun showCompletionResult(result: ServerManagementResponse.CommandCompleteResult) {
        val request = pendingCompletion ?: return
        if (result.nonce != request.nonce) return
        pendingCompletion = null
        if (commandField.text != request.command || commandField.caretPosition != request.cursor) return

        suggestionList.items.setAll(result.suggestions)
        if (result.suggestions.isEmpty()) {
            hideSuggestions()
            return
        }
        suggestionList.selectionModel.selectFirst()
        showSuggestions()
    }

    private fun showSuggestions() {
        val bounds: Bounds = commandField.localToScreen(commandField.boundsInLocal) ?: return
        suggestionList.prefWidth = commandField.width.coerceAtLeast(MINIMUM_POPUP_WIDTH)
        suggestionList.prefHeight =
            (suggestionList.items.size.coerceAtMost(MAXIMUM_VISIBLE_SUGGESTIONS) * SUGGESTION_ROW_HEIGHT)
                .coerceAtLeast(SUGGESTION_ROW_HEIGHT)
        if (suggestionPopup.isShowing) {
            suggestionPopup.x = bounds.minX
            suggestionPopup.y = bounds.maxY
        } else {
            suggestionPopup.show(commandField, bounds.minX, bounds.maxY)
        }
        if (suggestionList.selectionModel.isEmpty) {
            suggestionList.selectionModel.selectFirst()
        }
    }

    private fun hideSuggestions() {
        suggestionPopup.hide()
        suggestionList.items.clear()
    }

    private fun cancelCompletion() {
        completionDelay.stop()
        pendingCompletion = null
        hideSuggestions()
    }

    private fun moveSuggestionSelection(delta: Int) {
        val size = suggestionList.items.size
        if (size == 0) return
        val current = suggestionList.selectionModel.selectedIndex.coerceAtLeast(0)
        suggestionList.selectionModel.select((current + delta).coerceIn(0, size - 1))
        suggestionList.scrollTo(suggestionList.selectionModel.selectedIndex)
    }

    private fun applySelectedSuggestion() {
        val suggestion = commandModel.selectSuggestion(
            suggestionList.items,
            suggestionList.selectionModel.selectedIndex
        ) ?: return
        val applied = commandModel.applySuggestionToCurrentLine(
            commandField.text,
            commandField.caretPosition,
            suggestion
        ) ?: return
        hideSuggestions()
        replaceCommandText(applied.text, applied.caretPosition)
        commandField.requestFocus()
        scheduleCompletion(applied.text)
    }

    private fun replaceCommandText(text: String, caretPosition: Int = text.length) {
        suppressInputListener = true
        try {
            commandField.text = text
            commandField.positionCaret(caretPosition.coerceIn(0, text.length))
        } finally {
            suppressInputListener = false
        }
    }

    private fun shouldNavigateHistory(keyCode: KeyCode): Boolean {
        val text = commandField.text
        if ('\n' !in text) return true
        return when (keyCode) {
            KeyCode.UP -> commandField.caretPosition == 0
            KeyCode.DOWN -> commandField.caretPosition == text.length
            else -> false
        }
    }

    private fun insertLineBreak() {
        cancelCompletion()
        commandField.requestFocus()
        val insertionPosition = commandField.selection.start
        commandField.replaceSelection("\n")
        commandField.positionCaret(insertionPosition + 1)
    }

    private fun updateCommandFieldHeight(text: String) {
        val lineCount = text.count { it == '\n' } + 1
        commandField.prefRowCount = lineCount.coerceIn(1, MAXIMUM_INPUT_ROWS)
    }

    private fun appendOutput(text: String, styleClass: String = COMMAND_OUTPUT_STYLE) {
        appendEntries(listOf(ConsoleOutputEntry(text, styleClass)))
    }

    private fun appendEntries(entries: Collection<ConsoleOutputEntry>) {
        val shouldScroll = autoScrollPolicy.isEnabled

        appendConsoleLogs(allEntries, entries, MAXIMUM_OUTPUT_LINES)

        val visible = entries.filter { isVisibleAt(it.level, minimumLevel) }
        if (visible.isNotEmpty()) {
            appendConsoleLogs(outputListView.items, visible, MAXIMUM_OUTPUT_LINES)
        }

        if (shouldScroll && outputListView.items.isNotEmpty()) {
            outputListView.scrollTo(outputListView.items.lastIndex)
        }
    }

    /**
     * 表示するレベルの下限を変更し、表示中の行を作り直します。
     *
     * 受信済みの行から作り直すため、下限を下げると過去の行も表示されます。
     */
    private fun applyMinimumLevel(level: ConsoleLogLevel) {
        minimumLevel = level
        logLevelLabel.text = "表示レベル：${level.name}以上"

        val shouldScroll = autoScrollPolicy.isEnabled

        outputListView.selectionModel.clearSelection()
        outputListView.items.setAll(
            allEntries.filter { isVisibleAt(it.level, minimumLevel) }
        )

        if (shouldScroll && outputListView.items.isNotEmpty()) {
            outputListView.scrollTo(outputListView.items.lastIndex)
        }
    }

    private fun applyConnectionState(state: ServerManagementState) {
        val connected = state is ServerManagementState.Connected
        commandField.isDisable = !connected
        commandField.promptText = if (connected) "コマンドを入力" else "Management APIへ接続していません"

        /*
         * 接続済みと接続処理中は、重ねて接続を要求できないようにする。
         */
        reconnectButton.isDisable = connected || state is ServerManagementState.Connecting
        connectionLabel.text = when (state) {
            ServerManagementState.Disconnected -> "Management API：未接続"
            ServerManagementState.Connecting -> "Management API：接続中..."
            is ServerManagementState.Connected -> "Management API：接続済み"
            is ServerManagementState.Failed -> "Management API：未接続（${state.reason}）"
        }
        if (connected) {
            subscribeToLogs()
        } else {
            subscribedToLogs = false
            incomingLogs.clear()
            pendingCommands.clear()
            pendingCompletion = null
            completionDelay.stop()
            hideSuggestions()
        }
    }

    /**
     * Management APIへ接続し直します。
     *
     * 自動の再試行を打ち切ったあとでも、この操作で改めて接続を試せます。
     */
    @FXML
    @Suppress("unused")
    fun handleReconnect() {
        appendOutput("Management APIへ再接続しています...")
        EditorSession.reconnectManagementApi()
    }

    private fun subscribeToLogs() {
        if (!subscribedToLogs && client.send(ServerManagementRequest.ConsoleSubscribe)) {
            subscribedToLogs = true
        }
    }

    /** 画面が閉じられたとき、登録したリスナーとPopupを解放します。 */
    fun dispose() {
        if (subscribedToLogs && client.isConnected) {
            client.send(ServerManagementRequest.ConsoleUnsubscribe)
        }
        subscribedToLogs = false
        logDrainTimer.stop()
        incomingLogs.clear()
        completionDelay.stop()
        hideSuggestions()
        client.removeStateListener(stateListener)
        client.removeMessageListener(messageListener)
        pendingCommands.clear()
        pendingCompletion = null
    }

    private fun runOnFxThread(action: () -> Unit) {
        if (Platform.isFxApplicationThread()) action() else Platform.runLater(action)
    }

    private data class PendingCompletion(
        val nonce: String,
        val command: String,
        val cursor: Int
    )

    /**
     * 画面へ表示する1行です。
     *
     * @property level ログの重大度。コマンドの入力と実行結果、
     *                 および判別できないレベルの場合は`null`となり、常に表示します。
     */
    private data class ConsoleOutputEntry(
        val text: String,
        val styleClass: String,
        val level: ConsoleLogLevel? = null
    )

    companion object {
        /** 選択した行をコピーするショートカットです。macOSではCommandキーになります。 */
        private val COPY_SHORTCUT =
            KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN)

        /** 出力すべてを選択するショートカットです。 */
        private val SELECT_ALL_SHORTCUT =
            KeyCodeCombination(KeyCode.A, KeyCombination.SHORTCUT_DOWN)

        private const val COMPLETION_DELAY_MILLIS = 150.0
        private const val MINIMUM_POPUP_WIDTH = 320.0
        private const val MAXIMUM_VISIBLE_SUGGESTIONS = 8
        private const val MAXIMUM_INPUT_ROWS = 6
        private const val SUGGESTION_ROW_HEIGHT = 28.0
        private const val MAXIMUM_OUTPUT_LINES = 1_000
        private const val INCOMING_LOG_LIMIT = 2_000
        private const val LOGS_PER_FRAME = 200

        private const val COMMAND_STYLE = "console-line-command"
        private const val COMMAND_OUTPUT_STYLE = "console-line-output"
        private const val COMMAND_SUCCESS_STYLE = "console-line-success"
        private const val COMMAND_ERROR_STYLE = "console-line-error"
        private val DISPLAY_STYLE_CLASSES = setOf(
            COMMAND_STYLE,
            COMMAND_OUTPUT_STYLE,
            COMMAND_SUCCESS_STYLE,
            COMMAND_ERROR_STYLE,
            "console-log-trace",
            "console-log-debug",
            "console-log-info",
            "console-log-warn",
            "console-log-error"
        )

        /** ログレベルに対応するCSSクラスを返します。 */
        fun levelStyleClass(level: String): String = when (level.uppercase()) {
            "TRACE" -> "console-log-trace"
            "DEBUG" -> "console-log-debug"
            "WARN" -> "console-log-warn"
            "ERROR", "FATAL" -> "console-log-error"
            else -> "console-log-info"
        }
    }
}
