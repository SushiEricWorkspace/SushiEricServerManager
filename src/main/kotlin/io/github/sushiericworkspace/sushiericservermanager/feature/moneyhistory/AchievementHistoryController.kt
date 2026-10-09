package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.common.stats.player.AchievementCategory
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementClient
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementRequest
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementResponse
import io.github.sushiericworkspace.sushiericservermanager.config.AppSettingsManager
import io.github.sushiericworkspace.sushiericservermanager.editor.component.SearchableComboBox
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StorePathEntry
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import io.github.sushiericworkspace.sushiericservermanager.ui.AppTooltip
import javafx.beans.property.SimpleStringProperty
import javafx.concurrent.Task
import javafx.concurrent.WorkerStateEvent
import javafx.fxml.FXML
import javafx.scene.control.CheckBox
import javafx.scene.control.CustomMenuItem
import javafx.scene.control.DatePicker
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.MenuButton
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.layout.VBox
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 保存済みの実績達成履歴を読み取り専用で表示し、達成時刻と分類で絞り込みます。
 *
 * プレイヤーを選択してから、そのプレイヤーの`achievements.yml`を読み込みます。
 * サーバーのManagement APIへ接続している場合は、読み込む前に実績データの保存を要求します。
 */
class AchievementHistoryController {
    private val logger = LoggerFactory.getLogger(javaClass)

    @FXML private lateinit var playerSelectorPane: VBox
    @FXML private lateinit var playerDisplayToggle: ToggleButton
    @FXML private lateinit var fromDate: DatePicker
    @FXML private lateinit var toDate: DatePicker
    @FXML private lateinit var fromTime: TextField
    @FXML private lateinit var toTime: TextField
    @FXML private lateinit var categoryMenu: MenuButton
    @FXML private lateinit var statusLabel: Label
    @FXML private lateinit var historyTable: TableView<AchievementHistoryRow>
    @FXML private lateinit var timeColumn: TableColumn<AchievementHistoryRow, String>
    @FXML private lateinit var nameColumn: TableColumn<AchievementHistoryRow, String>
    @FXML private lateinit var categoryColumn: TableColumn<AchievementHistoryRow, String>

    private lateinit var store: EditorDataStore
    private lateinit var managementClient: ServerManagementClient
    private var initialized = false
    private var allEntries: List<AchievementHistoryEntry> = emptyList()
    private var saveWarning: String? = null
    private var changingFilters = false
    private var loadGeneration = 0
    private var selectedPlayer: AchievementHistoryPlayer? = null
    private var playerSearchField: TextField? = null
    private var playerSelector: SearchableComboBox<AchievementHistoryPlayer>? = null
    private val categoryItems = mutableMapOf<AchievementCategory, CheckBox>()
    private val playerNameLookup = PlayerNameLookup()

    /** 画面にストアとManagement APIを接続してプレイヤー一覧を読み込みます。2回目以降の呼び出しは何もしません。 */
    fun initialize(store: EditorDataStore, managementClient: ServerManagementClient) {
        if (initialized) return
        initialized = true
        this.store = store
        this.managementClient = managementClient
        initializeTable()
        playerDisplayToggle.isSelected = AppSettingsManager.load().showPlayerUuidInHistory
        updatePlayerDisplay()
        initializeCategoryMenu()
        fromDate.valueProperty().addListener { _, _, _ -> applyFilters() }
        toDate.valueProperty().addListener { _, _, _ -> applyFilters() }
        fromTime.textProperty().addListener { _, _, _ -> applyFilters() }
        toTime.textProperty().addListener { _, _, _ -> applyFilters() }
        listOf(fromTime, toTime).forEach { field ->
            field.tooltip = AppTooltip.create("時刻はHH:mmまたはHH:mm:ssで指定します。空欄の場合はその日全体が対象です。")
        }
        loadPlayers()
    }

    private fun initializeTable() {
        historyTable.selectionModel = null
        HistoryTableSupport.install(historyTable)
        historyTable.placeholder = Label("達成履歴はありません")
        historyTable.columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        timeColumn.setCellValueFactory { SimpleStringProperty(it.value.timeText) }
        nameColumn.setCellValueFactory { SimpleStringProperty(it.value.name) }
        categoryColumn.setCellValueFactory { SimpleStringProperty(it.value.category) }
        timeColumn.apply {
            isReorderable = false
            setCellFactory {
                HistoryTextCell(
                    onSetFromTime = { setFilterBoundary(fromDate, fromTime, it) },
                    onSetToTime = { setFilterBoundary(toDate, toTime, it) }
                )
            }
        }
        listOf(nameColumn, categoryColumn).forEach { column ->
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

    private fun initializeCategoryMenu() {
        AchievementCategory.entries.forEach { category ->
            val item = CheckBox(category.displayName).apply {
                isSelected = true
                maxWidth = Double.MAX_VALUE
            }
            item.selectedProperty().addListener { _, _, _ ->
                updateCategoryMenuText()
                applyFilters()
            }
            categoryItems[category] = item
            categoryMenu.items.add(CustomMenuItem(item, false))
        }
        updateCategoryMenuText()
    }

    private fun updateCategoryMenuText() {
        val selected = categoryItems.filterValues { it.isSelected }.keys
        categoryMenu.text = when (selected.size) {
            categoryItems.size -> "すべて"
            0 -> "未選択"
            1 -> selected.first().displayName
            else -> "${selected.size}種類を選択"
        }
    }

    private fun loadPlayers() {
        runTask("achievement-history-players", "プレイヤーを読み込み中...") {
            val knownNames = readKnownPlayerNames(store, logger)
            val directories = requireSuccess(store.listPath("player_data"))
            directories.filter(StorePathEntry::isDirectory)
                .mapNotNull { entry -> runCatching { UUID.fromString(entry.name) }.getOrNull()?.let { entry.name } }
                .map { uuid ->
                    val name = knownNames[UUID.fromString(uuid).toString()] ?: playerNameLookup.lookup(uuid) ?: uuid
                    AchievementHistoryPlayer(uuid, name)
                }
                .sortedBy(AchievementHistoryPlayer::displayName)
        }.also { task ->
            task.setOnSucceeded {
                val players = task.value
                val selector = SearchableComboBox(
                    allChoices = players,
                    initialValue = players.firstOrNull(),
                    promptText = "プレイヤー名・UUIDで検索",
                    displayText = ::playerLabel,
                    searchTexts = { listOf(it.displayName, it.uuid) },
                    onSelected = { loadPlayer(it) }
                )
                playerSelector = selector
                playerSearchField = selector.searchField
                configurePlayerCells()
                playerSelectorPane.children.setAll(selector)
                if (players.isEmpty()) statusLabel.text = "プレイヤーデータがありません。"
                else loadPlayer(players.first())
            }
        }
    }

    private fun configurePlayerCells() {
        val comboBox = playerSelector?.comboBox ?: return
        comboBox.setCellFactory { PlayerCell() }
        comboBox.buttonCell = PlayerCell()
    }

    private inner class PlayerCell : ListCell<AchievementHistoryPlayer>() {
        override fun updateItem(item: AchievementHistoryPlayer?, empty: Boolean) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else playerLabel(item)
        }
    }

    private fun loadPlayer(player: AchievementHistoryPlayer) {
        selectedPlayer = player
        val generation = ++loadGeneration
        runTask("achievement-history-load", "${player.displayName}の達成履歴を読み込み中...") {
            val warning = if (managementClient.isConnected) requestSave(player.uuid) else null
            warning to readHistory(player.uuid)
        }.also { task ->
            task.setOnSucceeded {
                if (generation != loadGeneration) return@setOnSucceeded
                saveWarning = task.value.first
                allEntries = task.value.second
                applyFilters()
            }
        }
    }

    /** サーバーへ保存を要求します。失敗した場合は、保存済みファイルを表示する旨の警告文を返します。 */
    private fun requestSave(uuid: String): String? {
        val nonce = UUID.randomUUID().toString()
        val result = CompletableFuture<ServerManagementResponse.AchievementSaveResult>()
        val listener: (ServerManagementResponse) -> Unit = { message ->
            when {
                message is ServerManagementResponse.AchievementSaveResult && message.nonce == nonce -> result.complete(message)
                message is ServerManagementResponse.Error ->
                    result.completeExceptionally(IllegalStateException(message.detail ?: message.reason))
            }
        }
        managementClient.addMessageListener(listener)
        return try {
            check(managementClient.send(ServerManagementRequest.AchievementSave(uuid, nonce))) {
                "実績保存要求を送信できませんでした。"
            }
            val response = result.get(SAVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            check(response.success) { response.detail ?: "サーバーが実績データを保存できませんでした。" }
            null
        } catch (e: Exception) {
            logger.warn("サーバーでの実績データの保存に失敗しました。保存済みのファイルを表示します。", e)
            "サーバーでの保存に失敗したため、保存済みのファイルを表示しています。"
        } finally {
            managementClient.removeMessageListener(listener)
        }
    }

    private fun readHistory(uuid: String): List<AchievementHistoryEntry> {
        val path = "player_data/$uuid/achievements.yml"
        return when (val text = store.readText(path)) {
            is StoreResult.Success -> parseAchievementHistory(text.value) { logger.warn("実績履歴: {} file={}", it, path) }
            is StoreResult.Failure ->
                if (text.error.code == StoreErrorCode.FILE_NOT_FOUND) emptyList()
                else error("${path}を読み込めません: ${text.error.code}")
        }
    }

    @FXML @Suppress("unused")
    fun reloadSelected() { selectedPlayer?.let(::loadPlayer) }

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

    @FXML @Suppress("unused")
    fun resetFilters() {
        changeFilters {
            fromDate.value = null
            toDate.value = null
            fromTime.clear()
            toTime.clear()
            categoryItems.values.forEach { it.isSelected = true }
            playerSearchField?.clear()
        }
    }

    @FXML @Suppress("unused")
    fun togglePlayerDisplay() {
        AppSettingsManager.save(AppSettingsManager.load().copy(
            showPlayerUuidInHistory = playerDisplayToggle.isSelected
        ))
        updatePlayerDisplay()
        configurePlayerCells()
        playerSelector?.comboBox?.let { it.buttonCell?.text = it.value?.let(::playerLabel) }
    }

    private fun updatePlayerDisplay() {
        playerDisplayToggle.text = if (playerDisplayToggle.isSelected) "UUID表示" else "名前表示"
    }

    private fun playerLabel(player: AchievementHistoryPlayer): String =
        if (playerDisplayToggle.isSelected) player.uuid else player.displayName

    private fun setCurrentDateTime(date: DatePicker, time: TextField) {
        val now = LocalDateTime.now()
        changeFilters {
            date.value = now.toLocalDate()
            time.text = now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        }
    }

    private fun changeFilters(block: () -> Unit) {
        changingFilters = true
        try { block() } finally { changingFilters = false }
        applyFilters()
    }

    private fun applyFilters() {
        if (changingFilters) return
        val range = runCatching { historyTimeRange(fromDate.value, fromTime.text, toDate.value, toTime.text) }
            .getOrElse {
                statusLabel.text = it.message ?: "日時の指定が正しくありません。"
                historyTable.items.clear()
                return
            }
        val selectedCategories = categoryItems.filterValues { it.isSelected }.keys
        val zone = ZoneId.systemDefault()
        val rows = filterAchievementHistory(allEntries, range, selectedCategories, zone).map { entry ->
            AchievementHistoryRow(
                timeText = TIME_FORMAT.format(entry.achievedAt.atZone(zone)),
                name = entry.type.display,
                category = entry.type.category.displayName
            )
        }
        historyTable.items.setAll(rows)
        val count = if (allEntries.isEmpty()) "達成履歴はありません。" else "${rows.size}件 / ${allEntries.size}件"
        statusLabel.text = listOfNotNull(count, saveWarning).joinToString(" ")
    }

    private fun <T> runTask(name: String, message: String, block: () -> T): Task<T> {
        statusLabel.text = message
        val task = object : Task<T>() { override fun call(): T = block() }
        task.addEventHandler(WorkerStateEvent.WORKER_STATE_FAILED) {
            val error = task.exception
            logger.warn("実績履歴の処理に失敗しました。", error)
            statusLabel.text = "読み込みに失敗しました: ${error?.message ?: "原因不明"}"
        }
        Thread(task, name).apply { isDaemon = true; start() }
        return task
    }

    private fun <T> requireSuccess(result: StoreResult<T>): T = when (result) {
        is StoreResult.Success -> result.value
        is StoreResult.Failure -> error("履歴データを取得できません: ${result.error.code}")
    }

    /** 画面終了時に保有する一時状態を解放します。 */
    fun dispose() {
        loadGeneration++
        allEntries = emptyList()
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        const val SAVE_TIMEOUT_SECONDS = 20L
    }
}

private data class AchievementHistoryPlayer(val uuid: String, val displayName: String) {
    override fun toString(): String = "$displayName ($uuid)"
}

/** 表に表示する達成履歴1行です。 */
internal data class AchievementHistoryRow(val timeText: String, val name: String, val category: String)
