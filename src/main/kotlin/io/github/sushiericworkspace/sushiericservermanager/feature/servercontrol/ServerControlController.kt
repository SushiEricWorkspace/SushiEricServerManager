package io.github.sushiericworkspace.sushiericservermanager.feature.servercontrol

import io.github.sushiericworkspace.sushiericservermanager.communication.SshCommandResult
import io.github.sushiericworkspace.sushiericservermanager.communication.SshCommandRunner
import io.github.sushiericworkspace.sushiericservermanager.config.ServerControlCommandSet
import io.github.sushiericworkspace.sushiericservermanager.config.ServerControlCommandsManager
import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem
import io.github.sushiericworkspace.sushiericservermanager.editor.session.EditorSession
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import javafx.application.Platform
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.BorderPane
import javafx.stage.FileChooser
import javafx.stage.Stage
import java.io.File
import java.net.URL
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.ResourceBundle

/**
 * サーバープロセスの起動、停止、再起動を行う画面です。
 *
 * 操作は利用者が登録したコマンドをSSHで実行します。
 * Management APIはプロセスの制御に使用しません。停止中のサーバーへは接続できず、
 * 起動の手段として使えないためです。
 *
 * コマンドは接続情報とは別のファイルへ保存し、定義だけを受け渡しできます。
 * 取り返しのつかない操作となるため、実行前に内容を確認し、実行中は次の操作を受け付けません。
 */
class ServerControlController : Initializable {

    @FXML private lateinit var rootPane: BorderPane
    @FXML private lateinit var connectionLabel: Label
    @FXML private lateinit var startButton: Button
    @FXML private lateinit var stopButton: Button
    @FXML private lateinit var restartButton: Button
    @FXML private lateinit var statusLabel: Label
    @FXML private lateinit var workingDirectoryField: TextField
    @FXML private lateinit var startCommandField: TextArea
    @FXML private lateinit var stopCommandField: TextArea
    @FXML private lateinit var restartCommandField: TextArea
    @FXML private lateinit var saveButton: Button
    @FXML private lateinit var importButton: Button
    @FXML private lateinit var exportButton: Button
    @FXML private lateinit var outputArea: TextArea

    private val commandRunner = SshCommandRunner()

    /** 保存済みのコマンドです。実行にはこの値を使用します。 */
    private var savedCommands = ServerControlCommandSet.EMPTY

    /** 実行中かを表します。実行が終わるまで次の操作を受け付けません。 */
    private var running = false

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        savedCommands = ServerControlCommandsManager.loadFor(currentProfileName())

        applyToFields(savedCommands)
        refreshControls()
    }

    @FXML
    @Suppress("unused")
    fun handleStart() = execute(ServerControlCommand.START)

    @FXML
    @Suppress("unused")
    fun handleStop() = execute(ServerControlCommand.STOP)

    @FXML
    @Suppress("unused")
    fun handleRestart() = execute(ServerControlCommand.RESTART)

    /** 入力したコマンドを保存します。 */
    @FXML
    @Suppress("unused")
    fun handleSave() {
        val profileName = currentProfileName()

        if (profileName.isNullOrBlank()) {
            showInformation(
                "保存先のプロファイルがありません。",
                "サーバーへ接続してから保存してください。"
            )
            return
        }

        savedCommands = fieldsAsCommandSet()
        ServerControlCommandsManager.saveFor(profileName, savedCommands)

        applyToFields(savedCommands)
        statusLabel.text = "コマンドを保存しました。"
        refreshControls()
    }

    /** 受け渡し用のファイルへコマンドを書き出します。 */
    @FXML
    @Suppress("unused")
    fun handleExport() {
        val commandSet = fieldsAsCommandSet()

        if (!commandSet.hasAnyCommand) {
            showInformation(
                "書き出すコマンドがありません。",
                "起動、停止、再起動のいずれかを入力してください。"
            )
            return
        }

        val file =
            FileChooser().apply {
                title = "サーバー操作コマンドの書き出し"
                initialFileName = DEFAULT_EXPORT_FILE_NAME
                extensionFilters.add(JSON_FILTER)
            }.showSaveDialog(ownerStage())
                ?: return

        if (ServerControlCommandsIo.export(file, commandSet)) {
            statusLabel.text = "コマンドを書き出しました: ${file.name}"
        } else {
            showInformation(
                "コマンドを書き出せませんでした。",
                "書き込み先の場所と権限を確認してください。"
            )
        }
    }

    /**
     * 受け渡し用のファイルからコマンドを読み込みます。
     *
     * 受け取った定義をそのまま使わせないよう、内容を確認してから反映します。
     */
    @FXML
    @Suppress("unused")
    fun handleImport() {
        val file =
            FileChooser().apply {
                title = "サーバー操作コマンドの読み込み"
                extensionFilters.add(JSON_FILTER)
            }.showOpenDialog(ownerStage())
                ?: return

        val imported = ServerControlCommandsIo.import(file)

        if (imported == null) {
            showInformation(
                "コマンドを読み込めませんでした。",
                "対応していない形式か、内容が壊れている可能性があります。"
            )
            return
        }

        val confirmed =
            CustomDialog.confirmation()
                .title("コマンドの読み込み")
                .header("読み込んだコマンドを反映しますか？")
                .content(
                    listOf(
                        "現在の入力内容を置き換えます。",
                        "",
                        "作業ディレクトリ: ${describe(imported.workingDirectory)}",
                        "起動: ${describe(imported.startCommand)}",
                        "停止: ${describe(imported.stopCommand)}",
                        "再起動: ${describe(imported.restartCommand)}"
                    )
                )
                .owner(ownerStage())
                .show()

        if (!confirmed) {
            return
        }

        applyToFields(imported)
        statusLabel.text = "コマンドを読み込みました。保存すると次回から使用します。"
        refreshControls()
    }

    /**
     * 操作を確認したうえで実行します。
     *
     * 実行はSSHの応答を待つため、画面を止めないよう別スレッドで行います。
     */
    private fun execute(control: ServerControlCommand) {
        if (running) {
            return
        }

        val command = control.commandOf(savedCommands)
        if (command == null) {
            showInformation(
                "${control.displayName}のコマンドが未設定です。",
                "コマンドを入力して保存してください。"
            )
            return
        }

        val commandLine =
            ServerControlCommandLine.build(
                command = command,
                workingDirectory = savedCommands.workingDirectory,
                operatingSystem = currentRemoteOperatingSystem()
            ) ?: return

        val client = EditorSession.sshManager.sshClient
        if (client == null) {
            showInformation(
                "SSHへ接続していません。",
                "サーバーへ接続してから操作してください。"
            )
            return
        }

        val confirmed =
            CustomDialog.confirmation()
                .title("${control.displayName}の確認")
                .header("サーバーを${control.displayName}しますか？")
                .content(
                    listOf(
                        "次のコマンドをSSHで実行します。",
                        "",
                        commandLine
                    )
                )
                .owner(ownerStage())
                .show()

        if (!confirmed) {
            return
        }

        setRunning(true, "${control.displayName}を実行しています...")
        appendOutput("> $commandLine")

        Thread({
            val result = commandRunner.run(client, commandLine)

            Platform.runLater {
                showResult(control, result)
                setRunning(false, statusLabel.text)
            }
        }, "server-control-${control.name.lowercase()}").apply {
            isDaemon = true
            start()
        }
    }

    private fun showResult(
        control: ServerControlCommand,
        result: SshCommandResult?
    ) {
        if (result == null) {
            statusLabel.text = "${control.displayName}のコマンドを実行できませんでした。"
            appendOutput("コマンドを実行できませんでした。接続の状態を確認してください。")
            return
        }

        statusLabel.text =
            if (result.isSuccess) {
                "${control.displayName}のコマンドが正常に終了しました。"
            } else {
                "${control.displayName}のコマンドが終了コード ${result.exitStatus} で終了しました。"
            }

        appendOutput("終了コード: ${result.exitStatus ?: "不明"}")

        result.output.trim().takeIf { it.isNotEmpty() }?.let(::appendOutput)
        result.errorOutput.trim().takeIf { it.isNotEmpty() }?.let { error ->
            appendOutput("[エラー出力] $error")
        }
    }

    /**
     * ボタンの状態を、実行中かどうかと登録の有無から決めます。
     */
    private fun refreshControls() {
        val connected = EditorSession.sshManager.isConnected

        connectionLabel.text =
            if (connected) {
                "SSH：接続済み（${currentProfileName().orEmpty()}）"
            } else {
                "SSH：未接続"
            }

        applyButton(startButton, ServerControlCommand.START, connected)
        applyButton(stopButton, ServerControlCommand.STOP, connected)
        applyButton(restartButton, ServerControlCommand.RESTART, connected)

        saveButton.isDisable = running
        importButton.isDisable = running
        exportButton.isDisable = running
        workingDirectoryField.isDisable = running
        startCommandField.isDisable = running
        stopCommandField.isDisable = running
        restartCommandField.isDisable = running
    }

    private fun applyButton(
        button: Button,
        control: ServerControlCommand,
        connected: Boolean
    ) {
        val configured = control.isConfigured(savedCommands)

        button.isDisable = running || !connected || !configured
        button.text =
            if (configured) {
                control.displayName
            } else {
                "${control.displayName}（未設定）"
            }
    }

    private fun setRunning(next: Boolean, message: String) {
        running = next
        statusLabel.text = message
        refreshControls()
    }

    private fun applyToFields(commandSet: ServerControlCommandSet) {
        workingDirectoryField.text = commandSet.workingDirectory
        startCommandField.text = commandSet.startCommand
        stopCommandField.text = commandSet.stopCommand
        restartCommandField.text = commandSet.restartCommand
    }

    private fun fieldsAsCommandSet(): ServerControlCommandSet =
        ServerControlCommandSet(
            startCommand = startCommandField.text.orEmpty(),
            stopCommand = stopCommandField.text.orEmpty(),
            restartCommand = restartCommandField.text.orEmpty(),
            workingDirectory = workingDirectoryField.text.orEmpty()
        ).normalized()

    private fun describe(command: String): String =
        command.trim().takeIf { it.isNotEmpty() } ?: "（未設定）"

    private fun appendOutput(text: String) {
        val time = LocalTime.now().format(TIME_FORMAT)

        if (outputArea.text.isNotEmpty()) {
            outputArea.appendText(System.lineSeparator())
        }

        outputArea.appendText("[$time] $text")
        outputArea.positionCaret(outputArea.text.length)
    }

    private fun showInformation(header: String, content: String) {
        CustomDialog.information()
            .title("Server Control")
            .header(header)
            .content(content)
            .owner(ownerStage())
            .show()
    }

    private fun currentProfileName(): String? =
        EditorSession.sshManager.currentProfile?.name

    private fun currentRemoteOperatingSystem(): RemoteOperatingSystem =
        EditorSession.sshManager.currentProfile?.resolvedRemoteOperatingSystem()
            ?: RemoteOperatingSystem.UBUNTU_SERVER

    private fun ownerStage(): Stage? =
        rootPane.scene?.window as? Stage

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

        const val DEFAULT_EXPORT_FILE_NAME = "server-control.json"

        val JSON_FILTER =
            FileChooser.ExtensionFilter("サーバー操作コマンド (*.json)", "*.json")
    }
}
