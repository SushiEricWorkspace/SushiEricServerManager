package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementClient
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementRequest
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementResponse
import io.github.sushiericworkspace.sushiericservermanager.config.AppSettingsManager
import io.github.sushiericworkspace.sushiericservermanager.editor.component.SearchableComboBox
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StorePathEntry
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javafx.concurrent.Task
import javafx.fxml.FXML
import javafx.scene.control.ComboBox
import javafx.scene.control.DatePicker
import javafx.scene.control.Label
import javafx.scene.control.ListCell
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
import javafx.concurrent.WorkerStateEvent
import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** 保存済み入出金履歴を読み取り専用で表示し、期間と種別で絞り込みます。 */
class MoneyHistoryController {
    private val logger = LoggerFactory.getLogger(javaClass)

    @FXML private lateinit var playerSelectorPane: VBox
    private lateinit var playerBox: ComboBox<HistoryPlayer>
    @FXML private lateinit var fromDate: DatePicker
    @FXML private lateinit var toDate: DatePicker
    @FXML private lateinit var fromTime: TextField
    @FXML private lateinit var toTime: TextField
    @FXML private lateinit var typeMenu: MenuButton
    @FXML private lateinit var playerDisplayToggle: ToggleButton
    @FXML private lateinit var statusLabel: Label
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
    private var busy = false
    private var changingFilters = false
    private var playerSearchField: TextField? = null
    private val typeItems = mutableMapOf<MoneyHistoryType, CheckBox>()
    private val playerNameLookup = PlayerNameLookup()
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
        loadPlayers()
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
        historyTable.placeholder = Label("履歴はありません")
        historyTable.columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        timeColumn.setCellValueFactory { SimpleStringProperty(it.value.timeText) }
        typeColumn.setCellValueFactory { SimpleStringProperty(it.value.type.displayName) }
        amountColumn.setCellValueFactory { SimpleStringProperty("%,d".format(it.value.amount)) }
        balanceColumn.setCellValueFactory { SimpleStringProperty("%,d".format(it.value.balanceAfter)) }
        counterpartyColumn.setCellValueFactory { SimpleStringProperty(it.value.counterparty ?: "-") }
        executorColumn.setCellValueFactory { SimpleStringProperty(it.value.executor ?: "-") }
        reasonColumn.setCellValueFactory { SimpleStringProperty(it.value.reason ?: "-") }
        listOf(timeColumn, typeColumn, amountColumn, balanceColumn, counterpartyColumn, executorColumn, reasonColumn)
            .forEach { column ->
                column.isReorderable = false
                column.setCellFactory { HistoryTextCell() }
            }
    }

    private fun loadPlayers() {
        runTask("money-history-players", "プレイヤーと履歴を読み込み中...") {
            val knownNames = readKnownPlayerNames()
            knownPlayerNames.putAll(knownNames)
            val dirs = requireSuccess(store.listPath("player_data"))
            dirs.filter(StorePathEntry::isDirectory)
                .mapNotNull { entry -> runCatching { UUID.fromString(entry.name) }.getOrNull()?.let { entry.name } }
                .map { uuid ->
                    val records = readRecords(uuid)
                    val latest = records.maxByOrNull(MoneyHistoryRow::timestamp)
                    val historyName = latest?.playerName?.takeUnless { it == uuid || it.isBlank() }
                    val name = historyName ?: knownNames[UUID.fromString(uuid).toString()]
                        ?: playerNameLookup.lookup(uuid) ?: uuid
                    if (name != uuid) knownPlayerNames[UUID.fromString(uuid).toString()] = name
                    HistoryPlayer(uuid, name, records)
                }
                .sortedBy(HistoryPlayer::displayName)
        }.also { task ->
            task.setOnSucceeded {
                busy = false
                val players = task.value
                val selector = SearchableComboBox(
                    allChoices = players,
                    initialValue = players.firstOrNull(),
                    promptText = "プレイヤー名・UUIDで検索",
                    displayText = ::playerLabel,
                    searchTexts = { listOf(it.displayName, it.uuid) },
                    onSelected = { loadPlayer(it) }
                )
                playerBox = selector.comboBox
                playerSearchField = selector.searchField
                configurePlayerCells()
                playerSelectorPane.children.setAll(selector)
                if (players.isEmpty()) statusLabel.text = "プレイヤーデータがありません。"
                else loadPlayer(players.first())
            }
        }
    }

    private fun readKnownPlayerNames(): Map<String, String> {
        val text = when (val result = store.readServerText("usercache.json")) {
            is StoreResult.Success -> result.value
            is StoreResult.Failure -> {
                if (result.error.code != StoreErrorCode.FILE_NOT_FOUND) {
                    logger.warn("プレイヤー名キャッシュを読み込めません: {}", result.error.code)
                }
                return emptyMap()
            }
        }
        return runCatching {
            Json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
                runCatching {
                    val entry = element.jsonObject
                    val uuid = UUID.fromString(entry["uuid"]?.jsonPrimitive?.contentOrNull).toString()
                    entry["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                        ?.let { name -> uuid to name }
                }.getOrNull()
            }.toMap()
        }.getOrElse {
            logger.warn("プレイヤー名キャッシュの解析に失敗しました。", it)
            emptyMap()
        }
    }

    private fun loadPlayer(player: HistoryPlayer, forceRefresh: Boolean = false) {
        if (busy) return
        runTask("money-history-load", "${player.displayName}の履歴を読み込み中...") {
            if (managementClient.isConnected) saveOnServer(player.uuid)
            if (managementClient.isConnected || forceRefresh) readRecords(player.uuid) else player.initialRows
        }.also { task ->
            task.setOnSucceeded {
                busy = false
                val selected = playerBox.value
                if (selected != null && selected != player) {
                    loadPlayer(selected)
                    return@setOnSucceeded
                }
                allRows = task.value
                statusLabel.text = if (allRows.isEmpty()) "履歴はありません。" else "${allRows.size}件"
                applyFilters()
            }
            task.addEventHandler(WorkerStateEvent.WORKER_STATE_FAILED) {
                playerBox.value?.takeIf { it != player }?.let { loadPlayer(it) }
            }
        }
    }

    @FXML @Suppress("unused")
    fun reloadSelected() {
        if (::playerBox.isInitialized) playerBox.value?.let { loadPlayer(it, forceRefresh = true) }
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
        }.sortedByDescending(MoneyHistoryRow::timestamp).map { row ->
            row.copy(counterparty = row.counterparty?.let(::formatCounterparty))
        }
    }

    private fun formatCounterparty(value: String): String {
        val uuid = runCatching { UUID.fromString(value).toString() }.getOrNull() ?: return value
        val name = knownPlayerNames[uuid] ?: playerNameLookup.lookup(uuid)
        if (name != null) knownPlayerNames[uuid] = name
        return formatHistoryActor(name, uuid) ?: "-"
    }

    private fun parseRecords(text: String, playerUuid: String, fileName: String): List<MoneyHistoryRow> {
        val yaml = Yaml(SafeConstructor(LoaderOptions()))
        val document = yaml.load<Any?>(text) as? Map<*, *> ?: return emptyList()
        val entries = document["entries"] as? List<*> ?: return emptyList()
        return entries.mapNotNull { raw ->
            val entry = raw as? Map<*, *> ?: return@mapNotNull null
            try {
                val uuid = entry["player-uuid"]?.toString() ?: playerUuid
                require(uuid == playerUuid)
                val timestamp = Instant.parse(entry["timestamp"].toString())
                MoneyHistoryRow(
                    timestamp = timestamp,
                    timeText = TIME_FORMAT.format(timestamp.atZone(ZoneId.systemDefault())),
                    playerName = entry["player-name"]?.toString() ?: playerUuid,
                    type = MoneyHistoryType.valueOf(entry["type"].toString()),
                    amount = entry["amount"].toString().toLong(),
                    balanceAfter = entry["balance-after"].toString().toLong(),
                    counterparty = entry["other-player"]?.toString(),
                    executor = (entry["executor"] as? Map<*, *>)?.let { actor ->
                        formatHistoryActor(actor["name"]?.toString(), actor["uuid"]?.toString())
                    },
                    reason = entry["reason"]?.toString()
                )
            } catch (e: Exception) {
                logger.warn("入出金履歴を読み飛ばしました: file={} error={}", fileName, e.message)
                null
            }
        }
    }

    private fun applyFilters() {
        if (changingFilters) return
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
        historyTable.items.setAll(rows)
        statusLabel.text = "${rows.size}件 / ${allRows.size}件"
    }

    private fun <T> runTask(name: String, message: String, block: () -> T): Task<T> {
        busy = true
        statusLabel.text = message
        val task = object : Task<T>() { override fun call(): T = block() }
        task.addEventHandler(WorkerStateEvent.WORKER_STATE_FAILED) {
            busy = false
            val error = task.exception
            logger.warn("入出金履歴の処理に失敗しました。", error)
            statusLabel.text = "読み込みに失敗しました: ${error?.message ?: "原因不明"}"
        }
        task.addEventHandler(WorkerStateEvent.WORKER_STATE_SUCCEEDED) { busy = false }
        Thread(task, name).apply { isDaemon = true; start() }
        return task
    }

    private fun <T> requireSuccess(result: StoreResult<T>): T = when (result) {
        is StoreResult.Success -> result.value
        is StoreResult.Failure -> error("履歴データを取得できません: ${result.error.code}")
    }

    /** 画面終了時に保有する一時状態を解放します。 */
    fun dispose() { allRows = emptyList() }

    private companion object { val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss") }
}

private data class HistoryPlayer(val uuid: String, val displayName: String, val initialRows: List<MoneyHistoryRow>) {
    override fun toString(): String = "$displayName ($uuid)"
}

private data class MoneyHistoryRow(
    val timestamp: Instant,
    val timeText: String,
    val playerName: String,
    val type: MoneyHistoryType,
    val amount: Long,
    val balanceAfter: Long,
    val counterparty: String?,
    val executor: String?,
    val reason: String?
)

private enum class MoneyHistoryType(val displayName: String) {
    PAY_SENT("送金"), PAY_RECEIVED("受取"), ADMIN_SET("管理者設定"), ADMIN_ADD("管理者加算"), ADMIN_REMOVE("管理者減算")
}
