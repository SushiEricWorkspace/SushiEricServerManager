package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementClient
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementRequest
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementResponse
import io.github.sushiericworkspace.sushiericservermanager.config.AppSettingsManager
import io.github.sushiericworkspace.sushiericservermanager.editor.component.SearchableComboBox
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StorePathEntry
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import javafx.application.Platform
import javafx.concurrent.Task
import javafx.fxml.FXML
import javafx.scene.control.ComboBox
import javafx.scene.control.DatePicker
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.Tab
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.ToggleButton
import javafx.scene.control.TextField
import javafx.scene.control.MenuButton
import javafx.scene.control.CheckBox
import javafx.scene.control.CustomMenuItem
import io.github.sushiericworkspace.sushiericservermanager.ui.AppTooltip
import javafx.scene.layout.VBox
import javafx.beans.property.SimpleStringProperty
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** 保存済みの入出金履歴と実績の達成履歴を表示する画面です。入出金タブの読み込みと絞り込み、実績タブの初期化を担当します。 */
class MoneyHistoryController {
    private val logger = LoggerFactory.getLogger(javaClass)

    @FXML private lateinit var achievementTab: Tab
    @FXML private lateinit var shopTab: Tab
    private var shopHistoryView: ShopHistoryView? = null
    @FXML private lateinit var achievementHistoryController: AchievementHistoryController
    @FXML private lateinit var playerSelectorPane: VBox
    private lateinit var playerBox: ComboBox<HistoryPlayer>
    @FXML private lateinit var fromDate: DatePicker
    @FXML private lateinit var toDate: DatePicker
    @FXML private lateinit var fromTime: TextField
    @FXML private lateinit var toTime: TextField
    @FXML private lateinit var typeMenu: MenuButton
    @FXML private lateinit var playerDisplayToggle: ToggleButton
    @FXML private lateinit var statusLabel: Label
    @FXML private lateinit var nameStatusLabel: Label
    @FXML private lateinit var historyTable: TableView<MoneyHistoryRow>
    @FXML private lateinit var timeColumn: TableColumn<MoneyHistoryRow, String>
    @FXML private lateinit var typeColumn: TableColumn<MoneyHistoryRow, String>
    @FXML private lateinit var amountColumn: TableColumn<MoneyHistoryRow, String>
    @FXML private lateinit var balanceColumn: TableColumn<MoneyHistoryRow, String>
    @FXML private lateinit var counterpartyColumn: TableColumn<MoneyHistoryRow, String>
    @FXML private lateinit var executorColumn: TableColumn<MoneyHistoryRow, String>
    @FXML private lateinit var reasonColumn: TableColumn<MoneyHistoryRow, String>

    private lateinit var store: EditorDataStore
    private lateinit var managementClient: ServerManagementClient
    private var allRows: List<MoneyHistoryRow> = emptyList()
    private var disposed = false
    private var loadGeneration = 0
    private var loadingHistory = false
    private var playerSelector: SearchableComboBox<HistoryPlayer>? = null
    private var players: List<HistoryPlayer> = emptyList()
    private var pendingNames = 0
    private var changingFilters = false
    private var playerSearchField: TextField? = null
    private val typeItems = mutableMapOf<MoneyHistoryType, CheckBox>()
    internal var playerNameLookup = PlayerNameLookup()
    private val knownPlayerNames = mutableMapOf<String, String>()

    /** 画面にストアとManagement APIを接続してプレイヤー一覧を読み込みます。 */
    fun initialize(store: EditorDataStore, managementClient: ServerManagementClient) {
        this.store = store
        this.managementClient = managementClient
        initializeTable()
        playerDisplayToggle.isSelected = AppSettingsManager.load().showPlayerUuidInHistory
        updatePlayerDisplay()
        initializeTypeMenu()
        fromDate.valueProperty().addListener { _, _, _ -> applyFilters() }
        toDate.valueProperty().addListener { _, _, _ -> applyFilters() }
        fromTime.textProperty().addListener { _, _, _ -> applyFilters() }
        toTime.textProperty().addListener { _, _, _ -> applyFilters() }
        listOf(fromTime, toTime).forEach { field ->
            field.tooltip = AppTooltip.create("時刻はHH:mmまたはHH:mm:ssで指定します。空欄の場合はその日全体が対象です。")
        }
        achievementHistoryController.playerNameLookup = playerNameLookup
        loadPlayers()
        achievementTab.selectedProperty().addListener { _, _, selected ->
            if (selected) achievementHistoryController.initialize(store, managementClient)
        }
        shopTab.selectedProperty().addListener { _, _, selected ->
            if (selected && shopHistoryView == null) {
                shopHistoryView = ShopHistoryView(store, managementClient)
                shopTab.content = shopHistoryView
            }
        }
    }

    private fun configurePlayerCells() {
        playerBox.setCellFactory {
            object : ListCell<HistoryPlayer>() {
                override fun updateItem(item: HistoryPlayer?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else playerLabel(item)
                }
            }
        }
        playerBox.buttonCell = object : ListCell<HistoryPlayer>() {
            override fun updateItem(item: HistoryPlayer?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null else playerLabel(item)
            }
        }
    }

    private fun initializeTable() {
        historyTable.selectionModel = null
        HistoryTableSupport.install(historyTable)
        historyTable.placeholder = Label("履歴はありません")
        historyTable.columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        timeColumn.setCellValueFactory { SimpleStringProperty(it.value.timeText) }
        typeColumn.setCellValueFactory { SimpleStringProperty(it.value.type.displayName) }
        amountColumn.setCellValueFactory { SimpleStringProperty("%,d".format(it.value.amount)) }
        balanceColumn.setCellValueFactory { SimpleStringProperty("%,d".format(it.value.balanceAfter)) }
        counterpartyColumn.setCellValueFactory { SimpleStringProperty(it.value.counterparty ?: "-") }
        executorColumn.setCellValueFactory { SimpleStringProperty(it.value.executor ?: "-") }
        reasonColumn.setCellValueFactory { SimpleStringProperty(it.value.reason ?: "-") }
        timeColumn.apply {
            isReorderable = false
            setCellFactory {
                HistoryTextCell(
                    onSetFromTime = { setFilterBoundary(fromDate, fromTime, it) },
                    onSetToTime = { setFilterBoundary(toDate, toTime, it) }
                )
            }
        }
        listOf(typeColumn, amountColumn, balanceColumn, counterpartyColumn, executorColumn, reasonColumn)
            .forEach { column ->
                column.isReorderable = false
                column.setCellFactory { HistoryTextCell() }
            }
    }

    private fun setFilterBoundary(date: DatePicker, time: TextField, value: LocalDateTime) {
        changeFilters {
            date.value = value.toLocalDate()
            time.text = value.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        }
    }

    private fun loadPlayers() {
        runTask("money-history-players", "プレイヤー一覧を読み込み中...", block = {
            readHistoryPlayers(store, logger)
        }) { loaded ->
            players = loaded
            players.filter { it.displayName != it.uuid }.forEach { knownPlayerNames[it.uuid] = it.displayName }
            val selector = SearchableComboBox(
                allChoices = players,
                initialValue = players.firstOrNull(),
                promptText = "プレイヤー名・UUIDで検索",
                displayText = ::playerLabel,
                searchTexts = { listOf(it.displayName, it.uuid) },
                onSelected = { loadPlayer(it) }
            )
            playerSelector = selector
            playerBox = selector.comboBox
            playerSearchField = selector.searchField
            configurePlayerCells()
            playerSelectorPane.children.setAll(selector)
            if (players.isEmpty()) statusLabel.text = "プレイヤーデータがありません。"
            else loadPlayer(players.first())
            players.filter { it.displayName == it.uuid }.forEach { resolveName(it.uuid) }
        }
    }

    private fun resolveName(uuid: String) {
        pendingNames++
        nameStatusLabel.text = "プレイヤー名を補完中...（${pendingNames}件）"
        playerNameLookup.lookupAsync(uuid).thenAccept { name ->
            Platform.runLater {
                if (disposed) return@runLater
                pendingNames--
                if (name != null && uuid !in knownPlayerNames) {
                    knownPlayerNames[uuid] = name
                    players.firstOrNull { it.uuid == uuid }?.displayName = name
                    refreshPlayers()
                    applyFilters()
                }
                nameStatusLabel.text = if (pendingNames == 0) "" else "プレイヤー名を補完中...（${pendingNames}件）"
            }
        }
    }

    private fun refreshPlayers() {
        playerSelector?.updateChoices(players.sortedBy(HistoryPlayer::displayName))
        configurePlayerCells()
        playerBox.buttonCell?.text = playerBox.value?.let(::playerLabel)
    }

    private fun loadPlayer(player: HistoryPlayer) {
        val generation = ++loadGeneration
        loadingHistory = true
        allRows = emptyList()
        historyTable.items.clear()
        val connected = managementClient.isConnected
        runTask("money-history-load", "${player.displayName}の${if (connected) "サーバー保存を待機中" else "履歴を読み込み中"}...",
            isCurrent = { generation == loadGeneration }, block = {
                if (connected) {
                    saveOnServer(player.uuid)
                    Platform.runLater {
                        if (!disposed && generation == loadGeneration) statusLabel.text = "${player.displayName}の履歴を読み込み中..."
                    }
                }
                readRecords(player.uuid)
            }) { rows ->
                loadingHistory = false
                allRows = rows
                val historyName = rows.maxByOrNull(MoneyHistoryRow::timestamp)?.playerName
                    ?.takeUnless { it == player.uuid || it.isBlank() }
                if (historyName != null) {
                    knownPlayerNames[player.uuid] = historyName
                    player.displayName = historyName
                    refreshPlayers()
                }
                applyFilters()
                rows.mapNotNull { it.counterparty }.mapNotNull { value ->
                    runCatching { UUID.fromString(value).toString() }.getOrNull()
                }.distinct().filter { it !in knownPlayerNames }.forEach(::resolveName)
            }
    }

    @FXML @Suppress("unused")
    fun reloadSelected() {
        if (::playerBox.isInitialized) playerBox.value?.let { loadPlayer(it) }
    }

    @FXML @Suppress("unused")
    fun selectFromTime() { showHistoryTimePicker(fromTime) }

    @FXML @Suppress("unused")
    fun selectToTime() { showHistoryTimePicker(toTime) }

    @FXML @Suppress("unused")
    fun setFromNow() { setCurrentDateTime(fromDate, fromTime) }

    @FXML @Suppress("unused")
    fun setToNow() { setCurrentDateTime(toDate, toTime) }

    @FXML @Suppress("unused")
    fun setFromToday() { fromDate.value = LocalDate.now() }

    @FXML @Suppress("unused")
    fun setToToday() { toDate.value = LocalDate.now() }

    @FXML @Suppress("unused")
    fun resetFrom() { changeFilters { fromDate.value = null; fromTime.clear() } }

    @FXML @Suppress("unused")
    fun resetTo() { changeFilters { toDate.value = null; toTime.clear() } }

    private fun initializeTypeMenu() {
        MoneyHistoryType.entries.forEach { type ->
            val item = CheckBox(type.displayName).apply {
                isSelected = true
                maxWidth = Double.MAX_VALUE
            }
            item.selectedProperty().addListener { _, _, _ ->
                updateTypeMenuText()
                applyFilters()
            }
            typeItems[type] = item
            typeMenu.items.add(CustomMenuItem(item, false))
        }
        updateTypeMenuText()
    }

    private fun updateTypeMenuText() {
        val selected = typeItems.filterValues { it.isSelected }.keys
        typeMenu.text = when (selected.size) {
            typeItems.size -> "すべて"
            0 -> "未選択"
            1 -> selected.first().displayName
            else -> "${selected.size}種類を選択"
        }
    }

    private fun setCurrentDateTime(date: DatePicker, time: TextField) {
        val now = LocalDateTime.now()
        changeFilters {
            date.value = now.toLocalDate()
            time.text = now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        }
    }

    @FXML @Suppress("unused")
    fun resetFilters() {
        changeFilters {
            fromDate.value = null
            toDate.value = null
            fromTime.clear()
            toTime.clear()
            typeItems.values.forEach { it.isSelected = true }
            playerSearchField?.clear()
        }
    }

    private fun changeFilters(block: () -> Unit) {
        changingFilters = true
        try { block() } finally { changingFilters = false }
        applyFilters()
    }

    @FXML @Suppress("unused")
    fun togglePlayerDisplay() {
        AppSettingsManager.save(AppSettingsManager.load().copy(
            showPlayerUuidInHistory = playerDisplayToggle.isSelected
        ))
        updatePlayerDisplay()
        if (::playerBox.isInitialized) {
            configurePlayerCells()
            playerBox.buttonCell?.text = playerBox.value?.let(::playerLabel)
        }
    }

    private fun updatePlayerDisplay() {
        playerDisplayToggle.text = if (playerDisplayToggle.isSelected) "UUID表示" else "名前表示"
    }

    private fun playerLabel(player: HistoryPlayer): String =
        if (playerDisplayToggle.isSelected) player.uuid else player.displayName

    private fun saveOnServer(uuid: String) {
        val nonce = UUID.randomUUID().toString()
        val result = CompletableFuture<ServerManagementResponse.MoneyHistorySaveResult>()
        val listener: (ServerManagementResponse) -> Unit = { message ->
            if (message is ServerManagementResponse.MoneyHistorySaveResult && message.nonce == nonce) {
                result.complete(message)
            }
        }
        managementClient.addMessageListener(listener)
        try {
            check(managementClient.send(ServerManagementRequest.MoneyHistorySave(uuid, nonce))) {
                "履歴保存要求を送信できませんでした。"
            }
            val response = result.get(20, TimeUnit.SECONDS)
            check(response.success) { response.detail ?: "サーバーが入出金履歴を保存できませんでした。" }
        } finally {
            managementClient.removeMessageListener(listener)
        }
    }

    private fun readRecords(uuid: String): List<MoneyHistoryRow> {
        val directory = "player_data/$uuid/money_history"
        val files = requireSuccess(store.listPath(directory)).filterNot(StorePathEntry::isDirectory)
            .filter { it.name.endsWith(".yml", true) }
        return files.flatMap { file ->
            when (val text = store.readText("$directory/${file.name}")) {
                is StoreResult.Failure -> throw IllegalStateException("${file.name}を読み込めません: ${text.error.code}")
                is StoreResult.Success -> runCatching { parseRecords(text.value, uuid, file.name) }
                    .onFailure { logger.warn("入出金履歴ファイルを読み飛ばしました: file={}", file.name, it) }
                    .getOrDefault(emptyList())
            }
        }.sortedByDescending(MoneyHistoryRow::timestamp)
    }

    private fun formatCounterparty(value: String): String {
        val uuid = runCatching { UUID.fromString(value).toString() }.getOrNull() ?: return value
        val name = knownPlayerNames[uuid]
        return formatHistoryActor(name, uuid) ?: "-"
    }

    private fun parseRecords(text: String, playerUuid: String, fileName: String): List<MoneyHistoryRow> =
        parseMoneyHistory(text, playerUuid, { reason ->
            logger.warn("入出金履歴を読み飛ばしました: file={} error={}", fileName, reason)
        })

    private fun applyFilters() {
        if (changingFilters || loadingHistory || disposed) return
        val range = runCatching { historyTimeRange(fromDate.value, fromTime.text, toDate.value, toTime.text) }
            .getOrElse {
                statusLabel.text = it.message ?: "日時の指定が正しくありません。"
                historyTable.items.clear()
                return
            }
        val selectedTypes = typeItems.filterValues { it.isSelected }.keys
        val zone = ZoneId.systemDefault()
        val rows = allRows.filter { row ->
            range.contains(row.timestamp.atZone(zone).toLocalDateTime()) &&
                row.type in selectedTypes
        }
        historyTable.items.setAll(rows.map { row ->
            row.copy(counterparty = row.counterparty?.let(::formatCounterparty))
        })
        statusLabel.text = "${rows.size}件 / ${allRows.size}件"
    }

    private fun <T> runTask(
        name: String,
        message: String,
        isCurrent: () -> Boolean = { true },
        block: () -> T,
        onSucceeded: (T) -> Unit
    ) {
        statusLabel.text = message
        val task = object : Task<T>() { override fun call(): T = block() }
        task.setOnFailed {
            if (disposed || !isCurrent()) return@setOnFailed
            loadingHistory = false
            logger.warn("入出金履歴の処理に失敗しました。", task.exception)
            statusLabel.text = "読み込みに失敗しました: ${task.exception?.message ?: "原因不明"}"
        }
        task.setOnSucceeded { if (!disposed && isCurrent()) onSucceeded(task.value) }
        Thread(task, name).apply { isDaemon = true; start() }
    }

    private fun <T> requireSuccess(result: StoreResult<T>): T = when (result) {
        is StoreResult.Success -> result.value
        is StoreResult.Failure -> error("履歴データを取得できません: ${result.error.code}")
    }

    /** 画面終了時に保有する一時状態を解放します。 */
    fun dispose() {
        disposed = true
        playerNameLookup.close()
        loadGeneration++
        allRows = emptyList()
        achievementHistoryController.dispose()
        shopHistoryView?.dispose()
    }

}
