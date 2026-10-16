package io.github.sushiericworkspace.sushiericservermanager.serverselect.controller

import io.github.sushiericworkspace.sushiericservermanager.communication.HostKeyApprovalHandler
import io.github.sushiericworkspace.sushiericservermanager.communication.RemoteDirectoryValidator
import io.github.sushiericworkspace.sushiericservermanager.communication.SshBootstrapService
import io.github.sushiericworkspace.sushiericservermanager.communication.SshConnectionService
import io.github.sushiericworkspace.sushiericservermanager.communication.SshFailure
import io.github.sushiericworkspace.sushiericservermanager.communication.SshFailureCode
import io.github.sushiericworkspace.sushiericservermanager.communication.SshResult
import io.github.sushiericworkspace.sushiericservermanager.communication.SshSetupRequest
import io.github.sushiericworkspace.sushiericservermanager.config.AuthenticationType
import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem
import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfile
import io.github.sushiericworkspace.sushiericservermanager.config.SettingConfigManager
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.CustomDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.SshFailureDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.SshHostKeyDialog
import io.github.sushiericworkspace.sushiericservermanager.ui.dialog.SshPasswordDialog
import javafx.concurrent.Task
import javafx.fxml.FXML
import javafx.fxml.Initializable
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.TextField
import javafx.stage.FileChooser
import javafx.stage.Stage
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.ResourceBundle

class CreateController : Initializable {
    @FXML private lateinit var nameField: TextField
    @FXML private lateinit var hostField: TextField
    @FXML private lateinit var portField: TextField
    @FXML private lateinit var userField: TextField
    @FXML private lateinit var remoteOperatingSystemField: ComboBox<String>
    @FXML private lateinit var folderPathField: TextField
    @FXML private lateinit var authenticationTypeField: ComboBox<String>
    @FXML private lateinit var keyPathField: TextField
    @FXML private lateinit var managementPortField: TextField
    @FXML private lateinit var selectKeyButton: Button
    @FXML private lateinit var generateKeyButton: Button
    @FXML private lateinit var testButton: Button
    @FXML private lateinit var saveButton: Button
    @FXML private lateinit var cancelButton: Button
    @FXML private lateinit var progressIndicator: ProgressIndicator
    @FXML private lateinit var statusLabel: Label
    @FXML private lateinit var remoteRequirementsLabel: Label

    private val bootstrapService = SshBootstrapService()
    private val connectionService = SshConnectionService()
    private var pendingGeneratedKeyFormat: String? = null
    private var currentKeyWasGeneratedByApp = false

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        remoteOperatingSystemField.items.setAll(RemoteOperatingSystem.entries.map { it.displayName })
        remoteOperatingSystemField.value = RemoteOperatingSystem.UBUNTU_SERVER.displayName
        remoteOperatingSystemField.valueProperty().addListener { _, _, _ -> updateRemoteOperatingSystemHelp() }

        authenticationTypeField.items.setAll(AuthenticationType.entries.map { it.displayName })
        authenticationTypeField.value = AuthenticationType.GENERATED_KEY.displayName
        authenticationTypeField.valueProperty().addListener { _, _, _ -> updateAuthenticationControls() }

        updateRemoteOperatingSystemHelp()
        updateAuthenticationControls()
    }

    @FXML
    fun handleSelectKeyFile() {
        val fileChooser = FileChooser().apply {
            title = "秘密鍵を選択"
            initialDirectory = File(System.getProperty("user.home"), ".ssh").let {
                if (it.isDirectory) it else File(System.getProperty("user.home"))
            }
        }

        fileChooser.showOpenDialog(ownerStage())?.let { selectedFile ->
            keyPathField.text = selectedFile.absolutePath
            authenticationTypeField.value = AuthenticationType.EXISTING_PRIVATE_KEY.displayName
            pendingGeneratedKeyFormat = null
            currentKeyWasGeneratedByApp = false
            statusLabel.text = "既存の秘密鍵を選択しました。接続テスト後に保存できます。"
        }
    }

    @FXML
    fun handleGenerateAndRegister() {
        val input = validateBaseInput() ?: return
        if (!validateUniqueName(input.name)) return

        val password = SshPasswordDialog.show(ownerStage(), input.remoteOperatingSystem) ?: return
        val request = SshSetupRequest(
            name = input.name,
            host = input.host,
            port = input.port,
            user = input.user,
            remoteRootPath = input.remoteRootPath,
            remoteOperatingSystem = input.remoteOperatingSystem
        )

        runBackground(
            runningMessage = "鍵を生成し、${input.remoteOperatingSystem.displayName}へ公開鍵を登録しています...",
            operation = {
                bootstrapService.setupGeneratedKey(
                    request = request,
                    password = password,
                    hostKeyApprovalHandler = hostKeyApprovalHandler()
                )
            },
            onSuccess = { result ->
                when (result) {
                    is SshResult.Success -> {
                        keyPathField.text = result.value.profile.key
                        pendingGeneratedKeyFormat = result.value.profile.keyFormat
                        currentKeyWasGeneratedByApp = true
                        if (saveNewProfile(result.value.profile)) {
                            statusLabel.text = "公開鍵認証を確認し、接続設定を保存しました。"
                            closeWindow()
                        } else {
                            statusLabel.text = "公開鍵認証には成功しましたが、設定を保存できませんでした。"
                            SshFailureDialog.show(
                                SshFailure(
                                    code = SshFailureCode.PROFILE_SAVE_FAILED,
                                    publicKeyRegistered = true,
                                    generatedPrivateKeyPath = result.value.profile.key
                                ),
                                ownerStage()
                            )
                        }
                    }

                    is SshResult.Failure -> {
                        result.failure.generatedPrivateKeyPath?.let { keyPathField.text = it }
                        pendingGeneratedKeyFormat = result.failure.generatedKeyFormat
                        currentKeyWasGeneratedByApp = result.failure.generatedPrivateKeyPath != null
                        statusLabel.text = "鍵登録または公開鍵認証の確認に失敗しました。"
                        SshFailureDialog.show(result.failure, ownerStage())
                    }
                }
            }
        )
    }

    @FXML
    fun handleConnectionTest() {
        val input = validateBaseInput() ?: return
        val keyPath = keyPathField.text.trim()
        if (!isExistingPrivateKey(keyPath)) {
            showInputError("指定された秘密鍵ファイルが存在しません。")
            return
        }

        val authenticationType = selectedAuthenticationType()
        val profile = input.toProfile(
            keyPath = keyPath,
            authenticationType = authenticationType,
            generatedKey = authenticationType == AuthenticationType.GENERATED_KEY,
            keyFormat = if (authenticationType == AuthenticationType.GENERATED_KEY) pendingGeneratedKeyFormat else null
        )

        runBackground(
            runningMessage = "SSH接続をテストしています...",
            operation = { connectionService.test(profile, hostKeyApprovalHandler()) },
            onSuccess = { result ->
                when (result) {
                    is SshResult.Success -> {
                        statusLabel.text = "接続テストに成功しました。"
                        CustomDialog.confirmation()
                            .owner(ownerStage())
                            .title("接続テスト成功")
                            .header("秘密鍵によるSSH接続に成功しました")
                            .content("接続先ディレクトリの読み取り・書き込みも確認できました。")
                            .okButton("OK")
                            .cancelButton("閉じる")
                            .show()
                    }

                    is SshResult.Failure -> {
                        statusLabel.text = "接続テストに失敗しました。"
                        SshFailureDialog.show(result.failure, ownerStage())
                    }
                }
            }
        )
    }

    @FXML
    fun handleSave() {
        val input = validateBaseInput() ?: return
        if (!validateUniqueName(input.name)) return

        val authenticationType = selectedAuthenticationType()
        if (authenticationType == AuthenticationType.GENERATED_KEY &&
            (!currentKeyWasGeneratedByApp || keyPathField.text.isBlank())
        ) {
            showInputError("「SSH鍵を生成して登録」を実行してください。既存鍵を生成鍵として保存することはできません。")
            return
        }

        val keyPath = keyPathField.text.trim()
        if (!isExistingPrivateKey(keyPath)) {
            showInputError("指定された秘密鍵ファイルが存在しません。")
            return
        }

        val profile = input.toProfile(
            keyPath = keyPath,
            authenticationType = authenticationType,
            generatedKey = authenticationType == AuthenticationType.GENERATED_KEY,
            keyFormat = if (authenticationType == AuthenticationType.GENERATED_KEY) pendingGeneratedKeyFormat else null
        )

        runBackground(
            runningMessage = "保存前にSSH接続を確認しています...",
            operation = { connectionService.test(profile, hostKeyApprovalHandler()) },
            onSuccess = { result ->
                when (result) {
                    is SshResult.Success -> {
                        if (saveNewProfile(profile)) closeWindow()
                        else SshFailureDialog.show(SshFailure(SshFailureCode.PROFILE_SAVE_FAILED), ownerStage())
                    }

                    is SshResult.Failure -> SshFailureDialog.show(result.failure, ownerStage())
                }
            }
        )
    }

    @FXML
    fun handleCancel() = closeWindow()

    private fun validateBaseInput(): FormInput? {
        val port = portField.text.trim().toIntOrNull()
        val input = FormInput(
            name = nameField.text.trim(),
            host = hostField.text.trim(),
            port = port ?: -1,
            user = userField.text.trim(),
            remoteRootPath = folderPathField.text.trim(),
            remoteOperatingSystem = selectedRemoteOperatingSystem(),
            managementPort = ManagementPortInput.parse(managementPortField.text) ?: -1
        )

        if (input.name.isBlank() || input.host.isBlank() || input.user.isBlank() || input.remoteRootPath.isBlank()) {
            showInputError("全ての接続項目を入力してください。")
            return null
        }
        if (input.port !in 1..65535) {
            showInputError("ポートには1～65535の数値を入力してください。")
            return null
        }
        if (input.managementPort !in ServerProfile.MANAGEMENT_PORT_RANGE) {
            showInputError(
                "Management APIポートには" +
                        "${ServerProfile.MANAGEMENT_PORT_RANGE.first}～" +
                        "${ServerProfile.MANAGEMENT_PORT_RANGE.last}の数値を入力してください。"
            )
            return null
        }
        return input
    }

    private fun validateUniqueName(name: String): Boolean {
        val duplicate = SettingConfigManager.load().list.any { it.name == name }
        if (duplicate) showInputError("接続名 '$name' は既に使用されています。")
        return !duplicate
    }

    private fun saveNewProfile(profile: ServerProfile): Boolean {
        val current = SettingConfigManager.load()
        if (current.list.any { it.name == profile.name }) return false
        return SettingConfigManager.saveAndVerify(current.copy(list = current.list + profile))
    }

    private fun selectedAuthenticationType(): AuthenticationType =
        AuthenticationType.fromDisplayName(authenticationTypeField.value)

    private fun selectedRemoteOperatingSystem(): RemoteOperatingSystem =
        RemoteOperatingSystem.fromDisplayName(remoteOperatingSystemField.value)

    private fun updateAuthenticationControls() {
        val generated = selectedAuthenticationType() == AuthenticationType.GENERATED_KEY
        generateKeyButton.isDisable = !generated
        selectKeyButton.isDisable = generated
        if (generated && !currentKeyWasGeneratedByApp) {
            keyPathField.clear()
            pendingGeneratedKeyFormat = null
        }
    }

    private fun updateRemoteOperatingSystemHelp() {
        val remoteOs = selectedRemoteOperatingSystem()
        folderPathField.promptText = when (remoteOs) {
            RemoteOperatingSystem.WINDOWS -> "C:/Users/user/server"
            RemoteOperatingSystem.MACOS -> "/Users/user/server"
            RemoteOperatingSystem.UBUNTU_DESKTOP,
            RemoteOperatingSystem.UBUNTU_SERVER -> "/home/user/server"
        }
        remoteRequirementsLabel.text = when (remoteOs) {
            RemoteOperatingSystem.WINDOWS ->
                "Windows OpenSSH Serverが起動し、初回だけパスワード認証とPowerShellの実行が許可されている必要があります。管理者アカウントはProgramData側へ登録します。"
            RemoteOperatingSystem.MACOS ->
                "macOSのリモートログイン（SSH）が有効で、初回だけ対象ユーザーのパスワード認証が利用できる必要があります。"
            RemoteOperatingSystem.UBUNTU_DESKTOP,
            RemoteOperatingSystem.UBUNTU_SERVER ->
                "OpenSSH Serverが起動し、初回だけ対象ユーザーのパスワード認証が利用できる必要があります。"
        }
    }

    private fun hostKeyApprovalHandler(): HostKeyApprovalHandler {
        val owner = ownerStage()
        return HostKeyApprovalHandler { SshHostKeyDialog.confirm(it, owner) }
    }

    private fun <T : Any> runBackground(
        runningMessage: String,
        operation: () -> T,
        onSuccess: (T) -> Unit
    ) {
        setBusy(true, runningMessage)
        val task = object : Task<T>() {
            override fun call(): T = operation()
        }
        task.setOnSucceeded {
            setBusy(false, "")
            onSuccess(requireNotNull(task.value))
        }
        task.setOnFailed {
            setBusy(false, "")
            SshFailureDialog.show(
                SshFailure(SshFailureCode.UNEXPECTED, task.exception?.let { it::class.simpleName }),
                ownerStage()
            )
        }
        Thread(task, "ssh-profile-create").apply { isDaemon = true; start() }
    }

    private fun setBusy(busy: Boolean, message: String) {
        listOf(nameField, hostField, portField, userField, folderPathField, keyPathField).forEach { it.isDisable = busy }
        remoteOperatingSystemField.isDisable = busy
        authenticationTypeField.isDisable = busy
        generateKeyButton.isDisable = busy || selectedAuthenticationType() != AuthenticationType.GENERATED_KEY
        selectKeyButton.isDisable = busy || selectedAuthenticationType() == AuthenticationType.GENERATED_KEY
        testButton.isDisable = busy
        saveButton.isDisable = busy
        cancelButton.isDisable = busy
        progressIndicator.isManaged = busy
        progressIndicator.isVisible = busy
        if (message.isNotBlank()) statusLabel.text = message
    }

    private fun isExistingPrivateKey(pathText: String): Boolean {
        if (pathText.isBlank()) return false
        return runCatching { Files.isRegularFile(Path.of(pathText)) }.getOrDefault(false)
    }

    private fun showInputError(message: String) {
        CustomDialog.error()
            .owner(ownerStage())
            .title("入力エラー")
            .header("接続設定を確認してください")
            .content(message)
            .show()
    }

    private fun ownerStage(): Stage? = nameField.scene?.window as? Stage
    private fun closeWindow() = ownerStage()?.close()

    private data class FormInput(
        val name: String,
        val host: String,
        val port: Int,
        val user: String,
        val remoteRootPath: String,
        val remoteOperatingSystem: RemoteOperatingSystem,
        val managementPort: Int
    ) {
        fun toProfile(
            keyPath: String,
            authenticationType: AuthenticationType,
            generatedKey: Boolean,
            keyFormat: String?
        ): ServerProfile = ServerProfile(
            name = name,
            host = host,
            port = port,
            user = user,
            path = RemoteDirectoryValidator.normalizeRemotePath(remoteRootPath),
            key = keyPath,
            authenticationType = authenticationType.storedValue,
            generatedKey = generatedKey,
            keyFormat = keyFormat,
            remoteOperatingSystem = remoteOperatingSystem.storedValue,
            managementPort = managementPort
        )
    }
}
