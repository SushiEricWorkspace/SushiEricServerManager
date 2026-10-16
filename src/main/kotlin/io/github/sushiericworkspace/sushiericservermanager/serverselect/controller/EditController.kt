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
import io.github.sushiericworkspace.sushiericservermanager.config.replaceServerProfilePreservingOrder
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

class EditController : Initializable {
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
    @FXML private lateinit var deleteButton: Button
    @FXML private lateinit var cancelButton: Button
    @FXML private lateinit var progressIndicator: ProgressIndicator
    @FXML private lateinit var statusLabel: Label
    @FXML private lateinit var remoteRequirementsLabel: Label

    private val bootstrapService = SshBootstrapService()
    private val connectionService = SshConnectionService()
    private lateinit var originalProfile: ServerProfile
    private var currentKeyFormat: String? = null
    private var currentKeyWasGeneratedByApp = false

    override fun initialize(location: URL?, resources: ResourceBundle?) {
        remoteOperatingSystemField.items.setAll(RemoteOperatingSystem.entries.map { it.displayName })
        remoteOperatingSystemField.valueProperty().addListener { _, _, _ -> updateRemoteOperatingSystemHelp() }

        authenticationTypeField.items.setAll(AuthenticationType.entries.map { it.displayName })
        authenticationTypeField.valueProperty().addListener { _, _, _ -> updateAuthenticationControls() }
    }

    fun initData(profile: ServerProfile) {
        originalProfile = profile
        nameField.text = profile.name
        hostField.text = profile.host
        portField.text = profile.port.toString()
        userField.text = profile.user
        remoteOperatingSystemField.value = profile.resolvedRemoteOperatingSystem().displayName
        folderPathField.text = profile.path
        authenticationTypeField.value = profile.resolvedAuthenticationType().displayName
        keyPathField.text = profile.key
        managementPortField.text = profile.resolvedManagementPort().toString()
        currentKeyFormat = profile.keyFormat
        currentKeyWasGeneratedByApp = profile.generatedKey
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
        fileChooser.showOpenDialog(ownerStage())?.let {
            keyPathField.text = it.absolutePath
            authenticationTypeField.value = AuthenticationType.EXISTING_PRIVATE_KEY.displayName
            currentKeyFormat = null
            currentKeyWasGeneratedByApp = false
            statusLabel.text = "既存の秘密鍵を選択しました。"
        }
    }

    @FXML
    fun handleGenerateAndRegister() {
        val input = validateInput() ?: return
        if (!validateUniqueName(input.name)) return
        val password = SshPasswordDialog.show(ownerStage(), input.remoteOperatingSystem) ?: return

        val request = SshSetupRequest(
            name = input.name,
            host = input.host,
            port = input.port,
            user = input.user,
            remoteRootPath = input.remoteRoot,
            remoteOperatingSystem = input.remoteOperatingSystem
        )

        runBackground(
            message = "鍵を生成し、${input.remoteOperatingSystem.displayName}へ公開鍵を登録しています...",
            operation = { bootstrapService.setupGeneratedKey(request, password, hostKeyApprovalHandler()) }
        ) { result ->
            when (result) {
                is SshResult.Success -> {
                    keyPathField.text = result.value.profile.key
                    currentKeyFormat = result.value.profile.keyFormat
                    currentKeyWasGeneratedByApp = true
                    if (replaceProfile(result.value.profile)) closeWindow()
                    else SshFailureDialog.show(SshFailure(SshFailureCode.PROFILE_SAVE_FAILED), ownerStage())
                }

                is SshResult.Failure -> {
                    result.failure.generatedPrivateKeyPath?.let { keyPathField.text = it }
                    currentKeyFormat = result.failure.generatedKeyFormat
                    currentKeyWasGeneratedByApp = result.failure.generatedPrivateKeyPath != null
                    SshFailureDialog.show(result.failure, ownerStage())
                }
            }
        }
    }

    @FXML
    fun handleConnectionTest() {
        val profile = buildProfile() ?: return
        runBackground(
            message = "SSH接続をテストしています...",
            operation = { connectionService.test(profile, hostKeyApprovalHandler()) }
        ) { result ->
            when (result) {
                is SshResult.Success -> {
                    statusLabel.text = "接続テストに成功しました。"
                    CustomDialog.confirmation()
                        .owner(ownerStage())
                        .title("接続テスト成功")
                        .header("SSH接続に成功しました")
                        .content("接続先ディレクトリの読み取り・書き込みを確認できました。")
                        .okButton("OK")
                        .cancelButton("閉じる")
                        .show()
                }

                is SshResult.Failure -> SshFailureDialog.show(result.failure, ownerStage())
            }
        }
    }

    @FXML
    fun handleSave() {
        val profile = buildProfile() ?: return
        if (!validateUniqueName(profile.name)) return

        runBackground(
            message = "保存前にSSH接続を確認しています...",
            operation = { connectionService.test(profile, hostKeyApprovalHandler()) }
        ) { result ->
            when (result) {
                is SshResult.Success -> {
                    if (replaceProfile(profile)) closeWindow()
                    else SshFailureDialog.show(SshFailure(SshFailureCode.PROFILE_SAVE_FAILED), ownerStage())
                }

                is SshResult.Failure -> SshFailureDialog.show(result.failure, ownerStage())
            }
        }
    }

    @FXML
    fun handleDelete() {
        val confirmed = CustomDialog.confirmation()
            .owner(ownerStage())
            .title("サーバー設定の削除")
            .header("'${originalProfile.name}' を削除しますか？")
            .content("接続プロファイルだけを削除します。生成済み秘密鍵は誤削除を防ぐため自動削除しません。")
            .okButton("削除")
            .show()
        if (!confirmed) return

        val current = SettingConfigManager.load()
        val saved = SettingConfigManager.saveAndVerify(
            current.copy(list = current.list.filterNot { it.name == originalProfile.name })
        )
        if (saved) closeWindow()
        else SshFailureDialog.show(SshFailure(SshFailureCode.PROFILE_SAVE_FAILED), ownerStage())
    }

    @FXML
    fun handleCancel() = closeWindow()

    private fun buildProfile(): ServerProfile? {
        val input = validateInput() ?: return null
        val keyPath = keyPathField.text.trim()
        if (!isExistingPrivateKey(keyPath)) {
            showInputError("指定された秘密鍵ファイルが存在しません。")
            return null
        }

        val type = AuthenticationType.fromDisplayName(authenticationTypeField.value)
        if (type == AuthenticationType.GENERATED_KEY && !currentKeyWasGeneratedByApp) {
            showInputError("新しい生成鍵方式へ変更する場合は「新しいSSH鍵を生成して登録」を実行してください。")
            return null
        }

        return ServerProfile(
            name = input.name,
            host = input.host,
            port = input.port,
            user = input.user,
            path = RemoteDirectoryValidator.normalizeRemotePath(input.remoteRoot),
            key = keyPath,
            authenticationType = type.storedValue,
            generatedKey = type == AuthenticationType.GENERATED_KEY,
            keyFormat = if (type == AuthenticationType.GENERATED_KEY) currentKeyFormat else null,
            remoteOperatingSystem = input.remoteOperatingSystem.storedValue,
            managementPort = input.managementPort
        )
    }

    private fun validateInput(): FormInput? {
        val port = portField.text.trim().toIntOrNull()
        val input = FormInput(
            name = nameField.text.trim(),
            host = hostField.text.trim(),
            port = port ?: -1,
            user = userField.text.trim(),
            remoteRoot = folderPathField.text.trim(),
            remoteOperatingSystem = RemoteOperatingSystem.fromDisplayName(remoteOperatingSystemField.value),
            managementPort = ManagementPortInput.parse(managementPortField.text) ?: -1
        )
        if (input.name.isBlank() || input.host.isBlank() || input.user.isBlank() || input.remoteRoot.isBlank()) {
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
        val duplicate = SettingConfigManager.load().list.any {
            it.name == name && it.name != originalProfile.name
        }
        if (duplicate) showInputError("接続名 '$name' は既に使用されています。")
        return !duplicate
    }

    private fun replaceProfile(profile: ServerProfile): Boolean {
        val current = SettingConfigManager.load()
        return SettingConfigManager.saveAndVerify(
            current.copy(
                list = replaceServerProfilePreservingOrder(
                    profiles = current.list,
                    originalName = originalProfile.name,
                    replacement = profile
                )
            )
        )
    }

    private fun updateAuthenticationControls() {
        val generated = AuthenticationType.fromDisplayName(authenticationTypeField.value) == AuthenticationType.GENERATED_KEY
        generateKeyButton.isDisable = !generated
        selectKeyButton.isDisable = generated
        if (generated && !currentKeyWasGeneratedByApp) {
            keyPathField.clear()
            currentKeyFormat = null
        }
    }

    private fun updateRemoteOperatingSystemHelp() {
        val remoteOs = RemoteOperatingSystem.fromDisplayName(remoteOperatingSystemField.value)
        folderPathField.promptText = when (remoteOs) {
            RemoteOperatingSystem.WINDOWS -> "C:/Users/user/server"
            RemoteOperatingSystem.MACOS -> "/Users/user/server"
            RemoteOperatingSystem.UBUNTU_DESKTOP,
            RemoteOperatingSystem.UBUNTU_SERVER -> "/home/user/server"
        }
        remoteRequirementsLabel.text = when (remoteOs) {
            RemoteOperatingSystem.WINDOWS ->
                "Windows OpenSSH Server、初回パスワード認証、PowerShell実行権限が必要です。管理者アカウントはProgramData側へ登録します。"
            RemoteOperatingSystem.MACOS ->
                "macOSのリモートログイン（SSH）と、初回だけパスワード認証が必要です。"
            RemoteOperatingSystem.UBUNTU_DESKTOP,
            RemoteOperatingSystem.UBUNTU_SERVER ->
                "OpenSSH Serverと、初回だけパスワード認証が必要です。"
        }
    }

    private fun hostKeyApprovalHandler(): HostKeyApprovalHandler {
        val owner = ownerStage()
        return HostKeyApprovalHandler { SshHostKeyDialog.confirm(it, owner) }
    }

    private fun <T : Any> runBackground(message: String, operation: () -> T, onSuccess: (T) -> Unit) {
        setBusy(true, message)
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
        Thread(task, "ssh-profile-edit").apply { isDaemon = true; start() }
    }

    private fun setBusy(busy: Boolean, message: String) {
        listOf(nameField, hostField, portField, userField, folderPathField, keyPathField).forEach { it.isDisable = busy }
        remoteOperatingSystemField.isDisable = busy
        authenticationTypeField.isDisable = busy
        generateKeyButton.isDisable = busy || AuthenticationType.fromDisplayName(authenticationTypeField.value) != AuthenticationType.GENERATED_KEY
        selectKeyButton.isDisable = busy || AuthenticationType.fromDisplayName(authenticationTypeField.value) == AuthenticationType.GENERATED_KEY
        testButton.isDisable = busy
        saveButton.isDisable = busy
        deleteButton.isDisable = busy
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
        val remoteRoot: String,
        val remoteOperatingSystem: RemoteOperatingSystem,
        val managementPort: Int
    )
}
