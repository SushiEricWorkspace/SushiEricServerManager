package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.communication.managed.*
import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.util.Utility
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.scene.control.*
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Modality
import javafx.stage.Stage
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.Properties
import kotlin.concurrent.thread

/** 非秘密プロファイルを再利用し、借用・引継ぎ・返却を明示操作で受け取ります。 */
internal object ManagedConnectionDialog {
    data class Selection(val profile: ManagedProfile, val lease: ManagedLease)
    private var service: ManagedConnectionService? = null
    private val settingsFile get() = FilePath.dataDirectory().resolve("development-connection.properties")
    private val settings = Properties()
    private var settingsLoaded = false
    fun abort() { service?.abort() }

    fun show(ownerStage: Stage): Selection? {
        if (service?.lease?.state == ManagedLease.State.DETACHED) service = ManagedConnectionService(service!!.profile)
        if (!settingsLoaded) {
            if (settingsFile.isFile) settingsFile.inputStream().use { settings.load(it) }
            settingsLoaded = true
        }
        val profileLabel = Label("Git外の形式1プロファイルを選択してください").apply { isWrapText = true }
        val status = Label().apply { isWrapText = true; id = "managed-status" }
        val responsibility = Label().apply { isWrapText = true; id = "managed-responsibility" }
        val instance = ComboBox<JsonObject>().apply { id = "managed-instance" }
        instance.setCellFactory { object : ListCell<JsonObject>() {
            override fun updateItem(item: JsonObject?, empty: Boolean) { super.updateItem(item, empty); text = if (empty || item == null) "" else rowText(item) }
        } }
        instance.buttonCell = object : ListCell<JsonObject>() {
            override fun updateItem(item: JsonObject?, empty: Boolean) { super.updateItem(item, empty); text = if (empty || item == null) "" else rowText(item) }
        }
        val owner = TextField(settings.getProperty("owner", "")).apply { id = "managed-owner" }
        val targetClient = TextField(settings.getProperty("targetClient", "manager")).apply { id = "managed-target-client" }
        val handoff = TextField().apply { id = "managed-handoff" }
        var result: Selection? = null
        val stage = Stage().apply { initOwner(ownerStage); initModality(Modality.APPLICATION_MODAL); title = "管理プロファイルと枠を選択" }
        val select = Button("プロファイルを選択").apply { id = "managed-profile" }
        val refresh = Button("枠状態を更新").apply { id = "managed-refresh" }
        val borrow = Button("借りて接続").apply { id = "managed-borrow" }
        val take = Button("引継ぎを受けて接続").apply { id = "managed-claim" }
        val reconnect = Button("保持中の貸出で接続").apply { id = "managed-connect-held" }
        val release = Button("停止確認済みの貸出を返却").apply { id = "managed-release" }
        val prepare = Button("別操作：世代を準備").apply { id = "managed-prepare" }
        val start = Button("別操作：サーバーを起動").apply { id = "managed-start" }
        val stop = Button("別操作：サーバーを正常停止").apply { id = "managed-stop" }
        val cancel = Button("閉じる").apply { setOnAction { stage.close() } }
        val actions = listOf(select, refresh, borrow, take, reconnect, prepare, start, stop, release, cancel)
        fun saveSettings() {
            settings.setProperty("owner", owner.text); settings.setProperty("targetClient", targetClient.text)
            settingsFile.parentFile.mkdirs()
            settingsFile.outputStream().use { settings.store(it, "development connection (non-secret)") }
        }
        fun display() {
            val current = service
            profileLabel.text = current?.profile?.let { it.description + "\n承認参照：" + it.json.getValue("approval").jsonObject.getValue("reference").jsonPrimitive.content }
                ?: "Git外の形式1プロファイルを選択してください"
            val loan = current?.lease
            responsibility.text = if (current?.uncertain == true) "private記録に保持中・結果不明の操作があります。自動取得はできません。正本を診断してください。" else loan?.let { it.responsibility + "\n貸出状態：${it.state} / 枠：${it.instanceId}" }
                ?: "接続だけでは起動・停止を行いません。復旧は運用者が確認します。停止枠は借用後も停止のままです。"
            select.isDisable = current?.uncertain == true || loan != null
            borrow.isDisable = current == null || current.uncertain || loan != null
            take.isDisable = borrow.isDisable
            reconnect.isDisable = loan?.state != ManagedLease.State.ACTIVE || EditorSession.connection != null
            release.isDisable = loan?.source != ManagedLease.Source.MANAGER || loan.state != ManagedLease.State.ACTIVE || EditorSession.connection != null
            listOf(prepare, start, stop).forEach { it.isDisable = loan?.source != ManagedLease.Source.MANAGER || loan.state != ManagedLease.State.ACTIVE || EditorSession.connection != null }
            refresh.isDisable = current == null
        }
        fun background(action: () -> (() -> Unit)) {
            actions.forEach { it.isDisable = true }
            thread(isDaemon = true, name = "managed-profile-action") {
                try {
                    val complete = action()
                    Platform.runLater { complete(); display() }
                } catch (error: Exception) {
                    Platform.runLater {
                        status.text = "${(error as? SupervisorFailure)?.code ?: "接続条件を確認してください"}：記録・未保存編集を保全し、監督の状態を確認してください。自動再送・復旧は行いません。"
                        display()
                    }
                } finally { Platform.runLater { cancel.isDisable = false } }
            }
        }
        fun refreshRows(): () -> Unit {
            val rows = service!!.instances()
            return { instance.items.setAll(rows); if (rows.isNotEmpty()) instance.selectionModel.selectFirst(); status.text = "枠状態を照合しました" }
        }
        fun connection(loan: ManagedLease): () -> Unit {
            val checked = loan.checkLease()
            if (checked["state"]?.jsonPrimitive?.content != "RUNNING" || checked["generationBound"]?.jsonPrimitive?.boolean != true)
                return { status.text = "貸出を保持しています。世代準備・起動は運用者の別の明示操作が必要です。自動起動しません。" }
            val selected = Selection(loan.connectionProfile(service!!.profile), loan)
            return { result = selected; stage.close() }
        }
        select.setOnAction {
            val chooser = FileChooser().apply { title = "登録済みprofiles/<ID>.jsonを選択"; extensionFilters.add(FileChooser.ExtensionFilter("プロファイル", "*.json")) }
            val file = chooser.showOpenDialog(stage) ?: return@setOnAction
            background {
                val profile = ConnectionProfile.load(file.toPath().toAbsolutePath())
                service = ManagedConnectionService(profile)
                return@background { settings.setProperty("profile", file.absolutePath); if (owner.text.isBlank()) owner.text = profile.operator; saveSettings(); status.text = "プロファイルを検査しました。枠状態を更新してください。" }
            }
        }
        refresh.setOnAction { background { refreshRows() } }
        borrow.setOnAction {
            val selected = instance.value ?: return@setOnAction
            val instanceId = selected.getValue("instanceId").jsonPrimitive.content
            val borrower = owner.text
            saveSettings()
            background { connection(service!!.borrow(instanceId, borrower)) }
        }
        take.setOnAction {
            val id = handoff.text; val borrower = owner.text; val client = targetClient.text
            saveSettings()
            background { connection(service!!.claim(id, borrower, client)) }
        }
        reconnect.setOnAction { background { connection(service!!.lease!!) } }
        prepare.setOnAction {
            val options = generationOptions(stage) ?: return@setOnAction
            background { service!!.lease!!.prepareGeneration(options.first, options.second); refreshRows() }
        }
        start.setOnAction { background { service!!.lease!!.runtime("start"); refreshRows() } }
        stop.setOnAction { background { service!!.lease!!.runtime("stop"); refreshRows() } }
        release.setOnAction { background {
            service!!.lease!!.release()
            service = ManagedConnectionService(service!!.profile)
            return@background { status.text = "停止証明を照合して返却しました。" }
        } }
        val form = VBox(8.0, select, profileLabel, Label("枠 / 起動状態 / 貸出owner / 期限・復旧状態"), instance, refresh,
            Label("貸出owner（借用時は自分の識別、引継ぎ時は発行元と同一）"), owner, borrow,
            Label("発行元から受け取った引継ぎID / 受取client"), handoff, targetClient, take, reconnect, prepare, start, stop, release,
            responsibility, status, cancel).apply { styleClass.add("common-root"); padding = Insets(16.0) }
        stage.scene = Utility.createScene(AppScreen.WIDGETS_ONLY, 720.0, 760.0, customRoot = ScrollPane(form).apply { isFitToWidth = true })
        display()
        if (service == null && settings.getProperty("profile") != null) {
            val remembered = Path.of(settings.getProperty("profile"))
            background {
                service = ManagedConnectionService(ConnectionProfile.load(remembered))
                refreshRows()
            }
        }
        stage.showAndWait()
        return result
    }
    private fun generationOptions(owner: Stage): Pair<String, JsonObject>? {
        val dialog = Dialog<Pair<String, JsonObject>>().apply { initOwner(owner); title = "別操作：停止枠の世代準備" }
        val command = ComboBox<String>().apply { items.setAll("prepare", "reset", "restore"); value = "reset" }
        val artifact = TextField()
        val generation = TextField()
        val source = TextField()
        val eula = CheckBox("Minecraft EULAに同意し、この停止枠の新しい世代を構築する")
        val apply = ButtonType("明示的に準備", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.addAll(apply, ButtonType.CANCEL)
        dialog.dialogPane.content = VBox(8.0, Label("空枠はprepare、既存枠はreset/restore。旧世代は保全します。"), command,
            Label("配置済みartifactId（SHA-256）"), artifact, Label("新しいgenerationId"), generation,
            Label("prepareのseed / resetのtemplateId / restoreのsnapshotId"), source, eula)
        val button = dialog.dialogPane.lookupButton(apply)
        button.isDisable = true
        fun validate() {
            button.isDisable = !eula.isSelected || !Regex("[0-9a-f]{64}").matches(artifact.text)
                || !Regex("[a-z0-9][a-z0-9-]{0,127}").matches(generation.text)
                || if (command.value == "prepare") source.text.toLongOrNull() == null
                else !Regex("[a-z0-9][a-z0-9-]{0,127}").matches(source.text)
        }
        listOf(artifact, generation, source).forEach { it.textProperty().addListener { _, _, _ -> validate() } }
        eula.selectedProperty().addListener { _, _, _ -> validate() }
        command.valueProperty().addListener { _, _, _ -> validate() }
        dialog.setResultConverter { chosen ->
            if (chosen != apply) null else command.value to buildJsonObject {
                put("artifactId", artifact.text); put("generationId", generation.text); put("acceptEula", true)
                when (command.value) { "prepare" -> put("seed", source.text.toLong()); "reset" -> put("templateId", source.text); else -> put("snapshotId", source.text) }
            }
        }
        return dialog.showAndWait().orElse(null)
    }

    private fun rowText(row: JsonObject): String = listOf("instanceId", "state", "owner").joinToString(" / ") {
        row[it]?.jsonPrimitive?.contentOrNull ?: "未貸出"
    } + if (row["leaseExpired"]?.jsonPrimitive?.boolean == true) " / 期限切れ（自動回収なし）" else ""
}
