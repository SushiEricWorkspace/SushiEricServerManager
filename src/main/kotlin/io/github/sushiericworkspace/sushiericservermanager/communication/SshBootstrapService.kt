package io.github.sushiericworkspace.sushiericservermanager.communication

import io.github.sushiericworkspace.sushiericservermanager.config.AuthenticationType
import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem
import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfile
import org.slf4j.LoggerFactory


data class SshSetupRequest(
    val name: String,
    val host: String,
    val port: Int,
    val user: String,
    val remoteRootPath: String,
    val remoteOperatingSystem: RemoteOperatingSystem
)

data class SshSetupSuccess(
    val profile: ServerProfile,
    val publicKeyWasAdded: Boolean
)

class SshBootstrapService(
    private val keyGenerator: SshKeyGenerator = SshKeyGenerator(),
    private val connectionService: SshConnectionService = SshConnectionService(),
    private val authorizedKeysRegistrar: AuthorizedKeysRegistrar = AuthorizedKeysRegistrar()
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun setupGeneratedKey(
        request: SshSetupRequest,
        password: CharArray,
        hostKeyApprovalHandler: HostKeyApprovalHandler
    ): SshResult<SshSetupSuccess> {
        val generatedKey = try {
            keyGenerator.generate()
        } catch (e: Exception) {
            SafeSshLogger.warn(logger, "key_generation_failed", SshFailureCode.KEY_GENERATION_FAILED, e)
            password.fill('\u0000')
            return SshResult.Failure(
                SshFailure(
                    code = if (e.message.orEmpty().contains("permission", ignoreCase = true) ||
                        e.message.orEmpty().contains("ACL", ignoreCase = true)
                    ) {
                        SshFailureCode.PRIVATE_KEY_PERMISSION_FAILED
                    } else {
                        SshFailureCode.KEY_GENERATION_FAILED
                    },
                    detail = SecretRedactor.safeThrowableName(e)
                )
            )
        }

        var publicKeyRegistered = false
        var publicKeyWasAdded = false

        try {
            when (
                val passwordConnection = connectionService.createPasswordClient(
                    host = request.host,
                    port = request.port,
                    user = request.user,
                    password = password,
                    remoteOperatingSystem = request.remoteOperatingSystem,
                    hostKeyApprovalHandler = hostKeyApprovalHandler
                )
            ) {
                is SshResult.Failure -> {
                    val deleted = runCatching { keyGenerator.deleteGeneratedKey(generatedKey) }.isSuccess
                    return if (deleted) {
                        passwordConnection
                    } else {
                        SshResult.Failure(
                            passwordConnection.failure.copy(
                                detail = "初回接続に失敗し、生成した鍵ファイルの自動削除にも失敗しました。",
                                generatedPrivateKeyPath = generatedKey.privateKeyPath.toString(),
                                generatedKeyFormat = generatedKey.keyFormat
                            )
                        )
                    }
                }

                is SshResult.Success -> {
                    try {
                        publicKeyWasAdded = authorizedKeysRegistrar.register(
                            connected = passwordConnection.value,
                            remoteOperatingSystem = request.remoteOperatingSystem,
                            publicKeyLine = generatedKey.publicKeyLine
                        )
                        publicKeyRegistered = true
                    } catch (e: Exception) {
                        val windowsFailure = e as? WindowsAuthorizedKeysRegistrationException
                        val diagnostic = windowsFailure?.safeDiagnostic()
                        SafeSshLogger.warn(
                            logger,
                            "authorized_keys_update_failed",
                            SshFailureCode.AUTHORIZED_KEYS_UPDATE_FAILED,
                            e,
                            diagnostic
                        )
                        return SshResult.Failure(
                            SshFailure(
                                code = SshFailureCode.AUTHORIZED_KEYS_UPDATE_FAILED,
                                detail = diagnostic
                                    ?: "登録処理の途中で失敗したため、公開鍵が追記済みかどうかを確定できません。エラー種別: ${SecretRedactor.safeThrowableName(e)}",
                                publicKeyRegistered = windowsFailure?.publicKeyMayBeRegistered == true,
                                generatedPrivateKeyPath = generatedKey.privateKeyPath.toString(),
                                generatedKeyFormat = generatedKey.keyFormat,
                                remoteOperatingSystem = request.remoteOperatingSystem
                            )
                        )
                    } finally {
                        connectionService.close(passwordConnection.value)
                    }
                }
            }

            val profile = ServerProfile(
                name = request.name,
                host = request.host,
                port = request.port,
                user = request.user,
                path = RemoteDirectoryValidator.normalizeRemotePath(request.remoteRootPath),
                key = generatedKey.privateKeyPath.toString(),
                authenticationType = AuthenticationType.GENERATED_KEY.storedValue,
                generatedKey = true,
                keyFormat = generatedKey.keyFormat,
                remoteOperatingSystem = request.remoteOperatingSystem.storedValue
            )

            return when (val verification = connectionService.test(profile, hostKeyApprovalHandler)) {
                is SshResult.Success -> SshResult.Success(
                    SshSetupSuccess(
                        profile = profile,
                        publicKeyWasAdded = publicKeyWasAdded
                    )
                )

                is SshResult.Failure -> SshResult.Failure(
                    verification.failure.copy(
                        publicKeyRegistered = publicKeyRegistered,
                        generatedPrivateKeyPath = generatedKey.privateKeyPath.toString(),
                        generatedKeyFormat = generatedKey.keyFormat
                    )
                )
            }
        } finally {
            password.fill('\u0000')
        }
    }
}
