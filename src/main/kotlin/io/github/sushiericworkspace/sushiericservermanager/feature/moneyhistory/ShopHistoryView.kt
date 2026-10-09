package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementClient
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementRequest
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementResponse
import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import javafx.beans.property.SimpleStringProperty
import javafx.concurrent.Task
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.CheckBox
import javafx.scene.control.CustomMenuItem
import javafx.scene.control.DatePicker
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.control.ListCell
import io.github.sushiericworkspace.sushiericservermanager.editor.component.SearchableComboBox
import io.github.sushiericworkspace.sushiericservermanager.config.AppSettingsManager
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.LocalDateTime
import javafx.animation.PauseTransition
import javafx.util.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** 条件未指定では全日付、指定時は対象日付のみ取得する読み取り専用のショップ履歴タブです。 */
internal class ShopHistoryView(private val store: EditorDataStore, private val client: ServerManagementClient) : VBox(10.0) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val from = DatePicker().apply { promptText = "YYYY/MM/DD"; prefWidth = 180.0 }
    private val to = DatePicker().apply { promptText = "YYYY/MM/DD"; prefWidth = 180.0 }
    private val fromTime = TextField().apply { promptText = "HH:mm:ss"; prefWidth = 105.0; maxWidth = 105.0 }
    private val toTime = TextField().apply { promptText = "HH:mm:ss"; prefWidth = 105.0; maxWidth = 105.0 }
    private var changingFilters = false
    private val reloadDelay = PauseTransition(Duration.millis(300.0)).apply { setOnFinished { load() } }
    private val product = TextField().apply { promptText = "商品IDで絞り込み" }
    private val playerPane = VBox().apply { minWidth = 220.0; prefWidth = 340.0; maxWidth = Double.MAX_VALUE }
    private val playerToggle = ToggleButton().apply {
        minWidth = 110.0
        isSelected = AppSettingsManager.load().showPlayerUuidInHistory
    }
    private var playerSelector: SearchableComboBox<ShopHistoryPlayer>? = null
    private var selectedPlayerUuid: UUID? = null
    private val modes = ShopHistoryMode.entries.associateWith { CheckBox(it.displayName).apply { isSelected = true } }
    private val status = Label("ショップ売買履歴を読み込み中...")
    private val table = TableView<ShopHistoryEntry>()
    private val loadButton = Button("更新")
    private var entries = emptyList<ShopHistoryEntry>()
    private var loadedRange: Pair<LocalDate?, LocalDate?>? = null
    private var warning: String? = null
    private var generation = 0
    private var disposed = false

    init {
        styleClass.add("money-history-content")
        fun row(vararg nodes: javafx.scene.Node) = HBox(8.0, *nodes).apply { alignment = Pos.CENTER_LEFT }
        fun label(text: String) = Label(text).apply { minWidth = 95.0; styleClass.add("history-filter-label") }
        val modeMenu = MenuButton("すべて").apply {
            minWidth = 140.0
            modes.values.forEach { items.add(CustomMenuItem(it, false)) }
        }
        fun dateRow(title: String, date: DatePicker, time: TextField) = row(
            label(title), date, time,
            Button("時刻を選択").apply { setOnAction { showHistoryTimePicker(time) } },
            Button("現在時刻").apply { setOnAction { changeFilters {
                val now = LocalDateTime.now(); date.value = now.toLocalDate()
                time.text = now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            } } },
            Button("今日").apply { setOnAction { date.value = LocalDate.now() } },
            Button("リセット").apply { setOnAction { changeFilters { date.value = null; time.clear() } } }
        )
        children.addAll(
            row(label("プレイヤー"), playerPane, playerToggle, loadButton).apply { HBox.setHgrow(playerPane, Priority.ALWAYS) },
            dateRow("開始", from, fromTime),
            dateRow("終了", to, toTime),
            row(label("商品ID"), product).apply {
                HBox.setHgrow(product, Priority.ALWAYS)
            },
            row(label("種別"), modeMenu, Button("条件をリセット").apply { setOnAction { changeFilters {
                product.clear(); selectedPlayerUuid = null; playerSelector?.searchField?.clear()
                playerSelector?.comboBox?.value = ShopHistoryPlayer(null, "すべて")
                modes.values.forEach { it.isSelected = true }
                from.value = null; to.value = null
                fromTime.clear(); toTime.clear()
            } } }), status, table
        )
        VBox.setVgrow(table, Priority.ALWAYS)
        table.selectionModel = null
        table.isEditable = false
        table.placeholder = Label("ショップ売買履歴はありません")
        table.columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN
        column("時刻", 170.0) { TIME_FORMAT.format(it.timestamp.atZone(ZoneId.systemDefault())) }
        column("商品ID", 190.0) { it.productId }
        column("種別", 70.0) { it.mode.displayName }
        column("プレイヤー", 140.0) { if (playerToggle.isSelected) it.playerUuid.toString() else it.playerName }
        column("数量", 70.0) { it.count.toString() }
        column("単価", 100.0) { it.unitPrice.toString() }
        column("合計額", 110.0) { it.total.toString() }
        product.textProperty().addListener { _, _, _ -> applyFilters() }
        playerToggle.setOnAction {
            AppSettingsManager.save(AppSettingsManager.load().copy(showPlayerUuidInHistory = playerToggle.isSelected))
            updatePlayerDisplay()
            table.refresh()
        }
        rebuildPlayerSelector()
        modes.values.forEach { it.selectedProperty().addListener { _, _, _ ->
            val selected = modes.filterValues { it.isSelected }.keys
            modeMenu.text = when (selected.size) {
                modes.size -> "すべて"
                0 -> "未選択"
                else -> selected.first().displayName
            }
            applyFilters()
        } }
        listOf(from, to).forEach { picker -> picker.valueProperty().addListener { _, _, _ ->
            if (!changingFilters) scheduleReload()
        } }
        listOf(fromTime, toTime).forEach { it.textProperty().addListener { _, _, _ -> applyFilters() } }
        loadButton.setOnAction { load() }
        load()
    }

    private fun changeFilters(block: () -> Unit) {
        val previousDates = from.value to to.value
        changingFilters = true
        try { block() } finally { changingFilters = false }
        if (previousDates == (from.value to to.value)) applyFilters() else scheduleReload()
    }

    private fun scheduleReload() {
        generation++
        // 新しい範囲の取得が完了するまで、表示済みの行を保持します。
        status.text = "ショップ売買履歴を更新中..."
        reloadDelay.playFromStart()
    }

    private fun column(title: String, width: Double, value: (ShopHistoryEntry) -> String) {
        table.columns.add(TableColumn<ShopHistoryEntry, String>(title).apply {
            prefWidth = width; isReorderable = false
            setCellValueFactory { SimpleStringProperty(value(it.value)) }
            setCellFactory { HistoryTextCell() }
        })
    }

    private fun load() {
        val first = from.value
        val last = to.value
        runCatching { historyTimeRange(first, fromTime.text, last, toTime.text) }.getOrElse {
            status.text = it.message; table.items.clear(); return
        }
        val current = ++generation
        loadButton.isDisable = true
        status.text = "ショップ売買履歴を読み込み中..."
        val task = object : Task<Pair<String?, List<ShopHistoryEntry>>>() {
            override fun call(): Pair<String?, List<ShopHistoryEntry>> {
                val saveWarning = if (client.isConnected) requestSave() else null
                return saveWarning to readShopHistory(store, first, last) { logger.warn("ショップ履歴: {}", it) }
            }
        }
        task.setOnSucceeded {
            loadButton.isDisable = false
            if (disposed || current != generation) return@setOnSucceeded
            warning = task.value.first; entries = task.value.second; loadedRange = first to last
            rebuildPlayerSelector()
            applyFilters()
        }
        task.setOnFailed {
            loadButton.isDisable = false
            if (disposed || current != generation) return@setOnFailed
            entries = emptyList(); loadedRange = null; table.items.clear()
            logger.warn("ショップ履歴の読み込みに失敗しました。", task.exception)
            status.text = "読み込みに失敗しました: ${task.exception?.message}"
        }
        Thread(task, "shop-history-load").apply { isDaemon = true; start() }
    }

    private fun applyFilters() {
        if (disposed) return
        if (changingFilters) return
        val range = runCatching { historyTimeRange(from.value, fromTime.text, to.value, toTime.text) }.getOrElse {
            status.text = it.message; table.items.clear(); return
        }
        val rows = filterShopHistory(entries, product.text, "", modes.filterValues { it.isSelected }.keys,
            range, playerUuid = selectedPlayerUuid)
        table.items.setAll(rows)
        status.text = if (loadedRange == null) "ショップ売買履歴を読み込み中..."
            else listOfNotNull("${rows.size}件 / ${entries.size}件", warning).joinToString(" ")
    }

    private fun playerLabel(player: ShopHistoryPlayer): String =
        if (player.uuid == null) "すべて" else if (playerToggle.isSelected) player.uuid.toString() else player.name

    private fun rebuildPlayerSelector() {
        val all = ShopHistoryPlayer(null, "すべて")
        val choices = listOf(all) + entries.distinctBy { it.playerUuid }
            .map { ShopHistoryPlayer(it.playerUuid, it.playerName) }.sortedBy { it.name }
        val query = playerSelector?.searchField?.text.orEmpty()
        val selected = choices.firstOrNull { it.uuid == selectedPlayerUuid } ?: all
        selectedPlayerUuid = selected.uuid
        val selector = SearchableComboBox(choices, selected, "プレイヤー名・UUIDで検索",
            ::playerLabel, { listOf(it.name, it.uuid?.toString().orEmpty()) }, {
                selectedPlayerUuid = it.uuid; applyFilters()
            })
        playerSelector = selector
        selector.searchField.text = query
        playerPane.children.setAll(selector)
        updatePlayerDisplay()
    }

    private fun updatePlayerDisplay() {
        playerToggle.text = if (playerToggle.isSelected) "UUID表示" else "名前表示"
        val combo = playerSelector?.comboBox ?: return
        fun cell() = object : ListCell<ShopHistoryPlayer>() {
            override fun updateItem(item: ShopHistoryPlayer?, empty: Boolean) {
                super.updateItem(item, empty)
                text = if (empty || item == null) null else playerLabel(item)
            }
        }
        combo.setCellFactory { cell() }
        combo.buttonCell = cell()
    }

    /** 全プレイヤーの履歴保存が完了してからファイルを取得します。失敗時は保存済み履歴へフォールバックします。 */
    private fun requestSave(): String? {
        val nonce = UUID.randomUUID().toString()
        val response = CompletableFuture<ServerManagementResponse.MoneyHistorySaveResult>()
        val listener: (ServerManagementResponse) -> Unit = { message ->
            if (message is ServerManagementResponse.MoneyHistorySaveResult && message.nonce == nonce) response.complete(message)
        }
        client.addMessageListener(listener)
        return try {
            check(client.send(ServerManagementRequest.MoneyHistorySave(nonce = nonce))) { "保存要求を送信できませんでした。" }
            val saved = response.get(20, TimeUnit.SECONDS)
            check(saved.success) { saved.detail ?: "履歴を保存できませんでした。" }
            null
        } catch (error: Exception) {
            logger.warn("ショップ履歴保存に失敗しました。", error)
            "サーバー保存に失敗したため、保存済みの履歴を表示しています。"
        } finally { client.removeMessageListener(listener) }
    }

    /** 終了後の非同期結果が画面へ反映されることを防ぎます。 */
    fun dispose() { disposed = true; generation++; reloadDelay.stop(); entries = emptyList() }

    private companion object { val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss") }
}

private data class ShopHistoryPlayer(val uuid: UUID?, val name: String)
