package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.communication.managed.*
import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import io.github.sushiericworkspace.sushiericservermanager.util.Utility.applyCommonStyle
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.FlowPane
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.util.Duration
import javafx.util.StringConverter
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Properties
import kotlin.concurrent.thread

/** 初回設定を再利用し、日常の接続・起動・停止・リセットを明示ボタンから操作します。 */
internal object ManagedConnectionDialog {
    data class Selection(val profile: ManagedProfile, val lease: ManagedLease)
    private var service: ManagedConnectionService? = null
    private val settingsFile get() = FilePath.dataDirectory().resolve("development-connection.properties")
    private val settings = Properties()
    private var settingsLoaded = false
    @Volatile private var busy = false
    fun abort() { service?.abort() }

    fun show(ownerStage: Stage): Selection? {
        if (!settingsLoaded) {
            if (settingsFile.isFile) settingsFile.inputStream().use { settings.load(it) }
            settingsLoaded = true
        }
        val environments = ComboBox<Path>().apply { id = "managed-environment"; maxWidth = Double.MAX_VALUE; promptText = "環境を選択" }
        environments.converter = object : StringConverter<Path>() {
            override fun toString(path: Path?): String = path?.let { settings.getProperty("environment-name.$it", it.fileName.toString()) } ?: ""
            override fun fromString(text: String): Path = error("登録済み環境から選択してください")
        }
        var selectingEnvironment = false
        fun rememberEnvironment(profile: ConnectionProfile) {
            settings.setProperty("registered-environment.${profile.file}", profile.file.toString())
            settings.setProperty("environment-name.${profile.file}", profile.name)
            selectingEnvironment = true
            environments.items.setAll(settings.stringPropertyNames().filter { it.startsWith("registered-environment.") }
                .map { Path.of(settings.getProperty(it)) }.sortedBy { settings.getProperty("environment-name.$it", it.fileName.toString()) })
            environments.value = profile.file
            selectingEnvironment = false
        }
        val profileLabel = Label("初回設定で環境を登録してください").apply { isWrapText = true }
        val details = Label().apply { isWrapText = true }
        val status = Label().apply { isWrapText = true; id = "managed-status" }
        val responsibility = Label().apply { isWrapText = true; id = "managed-responsibility" }
        val instance = ComboBox<JsonObject>().apply { id = "managed-instance"; maxWidth = Double.MAX_VALUE }
        fun instanceText(row: JsonObject): String {
            val name = service?.profile?.instances?.find { it["instanceId"] == row["instanceId"] }?.get("displayName")?.jsonPrimitive?.content
                ?: row.getValue("instanceId").jsonPrimitive.content
            val state = when (row["state"]?.jsonPrimitive?.content) {
                "AVAILABLE" -> "空き"; "RUNNING" -> "起動中"; "READY" -> "起動準備済み"; "LEASED_STOPPED" -> "停止中"; else -> "診断が必要"
            }
            return "$name / $state" + if (row["leaseExpired"]?.jsonPrimitive?.boolean == true) " / 期限切れ" else ""
        }
        instance.setCellFactory { object : ListCell<JsonObject>() {
            override fun updateItem(item: JsonObject?, empty: Boolean) { super.updateItem(item, empty); text = if (empty || item == null) "" else instanceText(item) }
        } }
        instance.buttonCell = object : ListCell<JsonObject>() {
            override fun updateItem(item: JsonObject?, empty: Boolean) { super.updateItem(item, empty); text = if (empty || item == null) "" else instanceText(item) }
        }
        val handoff = ComboBox<JsonObject>().apply { id = "managed-handoff-choice"; maxWidth = Double.MAX_VALUE; promptText = "受け取る引継ぎを選択" }
        fun handoffText(row: JsonObject): String = "引継ぎ ${row.getValue("handoffId").jsonPrimitive.content.takeLast(8)} / ${row.getValue("owner").jsonPrimitive.content} / " +
            Instant.ofEpochMilli((row.getValue("expiresAt").jsonPrimitive.double * 1000).toLong()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ssまで"))
        handoff.setCellFactory { object : ListCell<JsonObject>() {
            override fun updateItem(item: JsonObject?, empty: Boolean) { super.updateItem(item, empty); text = if (empty || item == null) "" else handoffText(item) }
        } }
        handoff.buttonCell = object : ListCell<JsonObject>() {
            override fun updateItem(item: JsonObject?, empty: Boolean) { super.updateItem(item, empty); text = if (empty || item == null) "" else handoffText(item) }
        }
        var offers = emptyList<JsonObject>()
        var preset: ManagedServerPreset? = null
        val presetOwner = TextField().apply { id = "managed-preset-owner" }
        val artifact = TextField().apply { id = "managed-preset-artifact" }
        val seed = TextField("0").apply { id = "managed-preset-seed" }
        val template = TextField().apply { id = "managed-preset-template" }
        val eula = CheckBox("この環境の起動条件を承認し、Minecraft EULAに同意する")
        val clientPython = TextField(settings.getProperty("clientPython", ""))
        val clientCli = TextField(settings.getProperty("clientCli", ""))
        val knownHosts = TextField(settings.getProperty("knownHosts", ""))
        fun clientTools(): ManagedClientTools? = if (listOf(clientPython, clientCli, knownHosts).all { it.text.isNotBlank() })
            ManagedClientTools(Path.of(clientPython.text), Path.of(clientCli.text), Path.of(knownHosts.text)) else null
        fun prefix(): String = "environment-" + MessageDigest.getInstance("SHA-256")
            .digest(service!!.profile.json.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + "."
        fun saveSettings() {
            settingsFile.parentFile.mkdirs()
            settingsFile.outputStream().use { settings.store(it, "development connection (non-secret)") }
        }
        fun loadPreset() {
            val key = prefix()
            presetOwner.text = settings.getProperty(key + "owner", service!!.profile.operator)
            artifact.text = settings.getProperty(key + "artifact", "")
            seed.text = settings.getProperty(key + "seed", "0")
            template.text = settings.getProperty(key + "template", "")
            eula.isSelected = settings.getProperty(key + "eula") == "true"
            preset = null
            if (eula.isSelected) {
                try { preset = ManagedServerPreset(presetOwner.text, artifact.text, seed.text.toLong(), template.text) }
                catch (_: IllegalArgumentException) { status.text = "保存済みの初回設定を確認してください。" }
            }
        }
        var result: Selection? = null
        val stage = Stage().apply { initOwner(ownerStage); initModality(Modality.APPLICATION_MODAL); title = "開発環境" }
        val select = Button("環境を登録・変更").apply { id = "managed-profile" }
        val save = Button("初回設定を保存").apply { id = "managed-preset-save" }
        val refresh = Button("更新").apply { id = "managed-refresh" }
        val connect = Button("接続").apply { id = "managed-connect" }
        val start = Button("起動").apply { id = "managed-start" }
        val stop = Button("停止").apply { id = "managed-stop" }
        val reset = Button("リセット").apply { id = "managed-reset" }
        val release = Button("返却").apply { id = "managed-release" }
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        var changing = false
        fun cancelPending() {
            cancelled.set(true)
            val current = service
            if (busy && changing) thread(isDaemon = true, name = "managed-cancel") { current?.abort() }
        }
        val cancel = Button("閉じる").apply { setOnAction { cancelPending(); stage.close() } }
        stage.setOnCloseRequest { event -> if (busy && changing) event.consume() else cancelPending() }
        val progress = ProgressBar(0.0).apply { prefWidth = 360.0; id = "managed-progress" }
        val progressText = Label("工程完了率 0%").apply { id = "managed-progress-percent" }
        val progressRow = HBox(12.0, progress, progressText)
        var progressShown = false
        fun updateProgress(completed: Int, total: Int, message: String) {
            Platform.runLater {
                if (!cancelled.get()) {
                    progress.progress = completed.toDouble() / total
                    progressText.text = "工程完了率 ${completed * 100 / total}%（$completed/$total）"
                    status.text = message
                }
            }
        }
        val actions = listOf(select, save, refresh, connect, start, stop, reset, release)
        fun display() {
            val current = service
            profileLabel.text = current?.profile?.let { it.name + " / " + it.json.getValue("host").jsonPrimitive.content } ?: "初回設定で環境を登録してください"
            details.text = current?.profile?.let { it.description + "\n承認参照：" + it.json.getValue("approval").jsonObject.getValue("reference").jsonPrimitive.content } ?: ""
            val row = instance.value
            val state = row?.get("state")?.jsonPrimitive?.content
            val loan = current?.lease
            val held = loan != null && loan.state != ManagedLease.State.DETACHED
            val reconnectable = loan?.state == ManagedLease.State.DETACHED && loan.instanceId == row?.get("instanceId")?.jsonPrimitive?.content
                && row?.get("owner")?.jsonPrimitive?.content == loan.owner && row?.get("epoch") == loan.token["epoch"]
                && row?.get("generation") == loan.token["expectedGeneration"] && row?.get("leaseExpired")?.jsonPrimitive?.boolean == false
            val selectedLoan = loan?.instanceId == row?.get("instanceId")?.jsonPrimitive?.content && loan?.state == ManagedLease.State.ACTIVE
            val usable = current != null && !current.uncertain && loan?.state != ManagedLease.State.UNKNOWN && EditorSession.connection == null
            val owned = usable && selectedLoan && loan?.source == ManagedLease.Source.MANAGER
            responsibility.text = if (current?.uncertain == true || loan?.state == ManagedLease.State.UNKNOWN)
                "結果不明の操作を保全しています。診断が必要です。" else loan?.responsibility ?: when {
                    handoff.value != null -> "起動側から受け取って接続します。停止・返却は起動側が担当します。"
                    state == "RUNNING" -> "この枠の引継ぎが発行されると接続できます。"
                    state == "AVAILABLE" -> "起動時は設定済み条件で借りて新しい世代を準備します。"
                    else -> "枠を選択してください。"
                }
            select.isDisable = current?.uncertain == true || held
            save.isDisable = select.isDisable || current == null
            connect.isDisable = !usable || state != "RUNNING" || !(selectedLoan || reconnectable || !held && handoff.value != null)
            start.text = if (loan == null && state == "AVAILABLE") "借りて初期化・起動" else "起動"
            start.isDisable = !usable || preset == null || !(owned && state in setOf("READY", "LEASED_STOPPED") || loan == null && state == "AVAILABLE"
                && row?.get("owner") == JsonNull && row?.get("leaseExpired")?.jsonPrimitive?.boolean == false
                && (row?.get("generation") == JsonNull || preset!!.templateId.isNotEmpty()))
            stop.isDisable = !owned || state != "RUNNING"
            reset.isDisable = !owned || state != "LEASED_STOPPED" || preset?.templateId.isNullOrEmpty()
            release.isDisable = !owned || state != "LEASED_STOPPED"
            refresh.isDisable = current == null
            environments.isDisable = busy || current?.uncertain == true || held
            instance.isDisable = held
            handoff.isDisable = held
            val multiple = handoff.items.size > 1 && !held
            handoff.isVisible = multiple; handoff.isManaged = multiple
            listOf(presetOwner, artifact, seed, template, clientPython, clientCli, knownHosts).forEach { it.isDisable = busy || select.isDisable }
            eula.isDisable = busy || select.isDisable
            progressRow.isVisible = progressShown; progressRow.isManaged = progressShown
            cancel.isDisable = busy && changing
            if (busy) { actions.forEach { it.isDisable = true }; instance.isDisable = true; handoff.isDisable = true }
        }
        fun updateHandoffs() {
            val selected = handoff.value?.get("handoffId")
            val filtered = offers.filter { it["instanceId"] == instance.value?.get("instanceId") }
            handoff.items.setAll(filtered)
            val previous = filtered.find { it["handoffId"] == selected }
            if (previous != null) handoff.value = previous else if (filtered.size == 1) handoff.selectionModel.selectFirst() else handoff.selectionModel.clearSelection()
        }
        fun background(mutating: Boolean = true, message: String = if (mutating) "操作を実行しています。" else "環境を確認しています。", action: () -> (() -> Unit)) {
            check(!busy) { "前の操作が終了していません。" }
            busy = true; changing = mutating; status.text = message
            progressShown = mutating; progress.progress = 0.0; progressText.text = "工程完了率 0%"
            display()
            thread(isDaemon = true, name = "managed-profile-action") {
                try {
                    val complete = action()
                    Platform.runLater {
                        if (!cancelled.get()) { progress.progress = 1.0; progressText.text = "工程完了率 100%"; complete(); display() }
                        else if (mutating) { val current = service; thread(isDaemon = true) { current?.abort() } }
                    }
                } catch (error: Exception) {
                    Platform.runLater {
                        if (!cancelled.get()) {
                            status.text = if (error is IllegalArgumentException && service?.uncertain == false && service?.lease?.state != ManagedLease.State.UNKNOWN)
                                error.message else "${(error as? SupervisorFailure)?.code ?: "操作を確認してください"}：記録・未保存編集を保全して診断してください。"
                            display()
                        }
                    }
                } finally { Platform.runLater { busy = false; changing = false; if (!cancelled.get()) display() } }
            }
        }
        fun refreshRows(selectedId: String?, loadSettings: Boolean = false): () -> Unit {
            val rows = service!!.instances()
            val received = service!!.handoffs(rows)
            return {
                if (loadSettings) { loadPreset(); rememberEnvironment(service!!.profile) }
                offers = received
                instance.items.setAll(rows)
                val id = service!!.lease?.instanceId ?: selectedId ?: settings.getProperty(prefix() + "instance")
                instance.value = rows.find { it["instanceId"]?.jsonPrimitive?.content == id } ?: rows.firstOrNull()
                updateHandoffs()
                status.text = "環境を確認しました。"
            }
        }
        fun connection(loan: ManagedLease): () -> Unit {
            val selected = Selection(loan.connectionProfile(service!!.profile), loan)
            return { result = selected; stage.close() }
        }
        instance.valueProperty().addListener { _, _, row ->
            updateHandoffs(); display()
            if (row != null && service != null) { settings.setProperty(prefix() + "instance", row.getValue("instanceId").jsonPrimitive.content); saveSettings() }
        }
        handoff.valueProperty().addListener { _, _, _ -> display() }
        environments.valueProperty().addListener { _, _, file ->
            if (!selectingEnvironment && file != null) {
                val tools = clientTools()
                background(false) {
                    val profile = ConnectionProfile.load(file, tools)
                    service?.profile?.closeUnused()
                    service = ManagedConnectionService(profile)
                    val complete = refreshRows(null, true)
                    return@background { settings.setProperty("profile", file.toString()); saveSettings(); complete() }
                }
            }
        }
        select.setOnAction {
            val chooser = FileChooser().apply { title = "登録済み環境を選択"; extensionFilters.add(FileChooser.ExtensionFilter("プロファイル", "*.json")) }
            val file = chooser.showOpenDialog(stage) ?: return@setOnAction
            val tools = clientTools()
            val clientSettings = listOf("clientPython" to clientPython.text, "clientCli" to clientCli.text, "knownHosts" to knownHosts.text)
            background(false) {
                val profile = ConnectionProfile.load(file.toPath().toAbsolutePath(), tools)
                service?.profile?.closeUnused()
                service = ManagedConnectionService(profile)
                val complete = refreshRows(null, true)
                return@background { settings.setProperty("profile", file.absolutePath); clientSettings.forEach { (key, value) -> settings.setProperty(key, value) }; saveSettings(); complete() }
            }
        }
        save.setOnAction {
            try {
                require(eula.isSelected) { "起動条件とEULAへの同意を確認してください。" }
                val configured = ManagedServerPreset(presetOwner.text, artifact.text, seed.text.toLong(), template.text)
                val key = prefix()
                settings.setProperty(key + "owner", configured.owner); settings.setProperty(key + "artifact", configured.artifactId)
                settings.setProperty(key + "seed", configured.seed.toString()); settings.setProperty(key + "template", configured.templateId)
                settings.setProperty(key + "eula", "true"); saveSettings(); preset = configured
                status.text = "初回設定を保存しました。日常操作はボタンから行えます。"; display()
            } catch (error: IllegalArgumentException) { status.text = error.message ?: "初回設定を確認してください。" }
        }
        refresh.setOnAction { val id = instance.value?.get("instanceId")?.jsonPrimitive?.content; background(false) { refreshRows(id) } }
        connect.setOnAction {
            val id = instance.value.getValue("instanceId").jsonPrimitive.content
            val offered = handoff.value
            background(message = "接続しています。") { connection(service!!.connect(id, offered)) }
        }
        fun confirm(text: String): Boolean = Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL)
            .apply { initOwner(stage); headerText = null; applyCommonStyle() }.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK
        start.setOnAction {
            val row = instance.value
            val id = row.getValue("instanceId").jsonPrimitive.content
            val configured = preset!!
            if (service!!.lease == null && !confirm("${instanceText(row)}を借り、設定済み条件で新しい世代を準備して起動します。旧世代は保持します。")) return@setOnAction
            val message = if (service!!.lease == null) "借用・初期化・起動中です。完了まで数分かかります。" else "サーバーを起動しています。"
            background(message = message) { service!!.start(id, configured, ::updateProgress); refreshRows(id) }
        }
        stop.setOnAction { val id = service!!.lease!!.instanceId; background(message = "正常保存してサーバーを停止しています。") { service!!.lease!!.runtime("stop"); refreshRows(id) } }
        reset.setOnAction {
            val id = service!!.lease!!.instanceId; val configured = preset!!
            if (!confirm("選択した枠を${configured.templateId}からリセットします。旧世代は保持し、起動は別のボタンで行います。")) return@setOnAction
            background(message = "テンプレートからリセットしています。完了まで数分かかります。") { service!!.reset(id, configured, ::updateProgress); refreshRows(id) }
        }
        release.setOnAction { background(message = "貸出を返却しています。") {
            service!!.lease!!.release()
            val profile = service!!.profile
            profile.closeUnused()
            service = ManagedConnectionService(ConnectionProfile.load(profile.file, profile.tools))
            refreshRows(null, true)
        } }
        val sshSettings = TitledPane("SSHの初回設定", VBox(6.0, Label("このPCのPython 3.12 / 管理CLI / 確認済みknown_hosts"), clientPython, clientCli, knownHosts)).apply { isExpanded = false }
        val configuration = TitledPane("初回設定・環境情報", VBox(8.0, select, sshSettings, details, Label("借用時のowner"), presetOwner,
            Label("配置済みbundle ID"), artifact, Label("空枠のseed"), seed, Label("リセット元template（使わない場合は空欄）"), template, eula, save)
            .apply { styleClass.add("common-root"); padding = Insets(8.0) }).apply { isExpanded = service == null && settings.getProperty("profile") == null }
        val form = VBox(12.0, environments, profileLabel, instance, handoff, FlowPane(8.0, 8.0, connect, start, stop, reset, release, refresh),
            responsibility, status, progressRow, configuration, cancel).apply { styleClass.add("common-root"); padding = Insets(16.0) }
        stage.scene = Utility.createScene(AppScreen.WIDGETS_ONLY, 740.0, 540.0, customRoot = ScrollPane(form).apply {
            isFitToWidth = true
            styleClass.add("managed-connection")
        }).apply {
            stylesheets.add(requireNotNull(ManagedConnectionDialog::class.java.getResource("/css/managed/connection.css")).toExternalForm())
        }
        display()
        val waiting = PauseTransition(Duration.millis(100.0))
        fun initialRefresh() {
            if (cancelled.get()) return
            if (busy) { waiting.setOnFinished { initialRefresh() }; waiting.play(); return }
            if (service != null || settings.getProperty("profile") != null) {
                val remembered = settings.getProperty("profile")
                val tools = clientTools()
                background(false) {
                    if (service == null) service = ManagedConnectionService(ConnectionProfile.load(Path.of(remembered), tools))
                    refreshRows(null, true)
                }
            }
        }
        initialRefresh()
        stage.showAndWait()
        waiting.stop()
        return result
    }
}
