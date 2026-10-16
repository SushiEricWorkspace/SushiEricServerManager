package io.github.sushiericworkspace.sushiericservermanager.communication

import com.hierynomus.sshj.common.KeyDecryptionFailedException
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import io.github.sushiericworkspace.sushiericservermanager.config.FilePath
import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem
import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfile
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.userauth.UserAuthException
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.Security
import java.util.EnumSet
import java.util.UUID

/** 接続済みSSH・SFTPクライアント。所有者が必ず[close]する必要があります。 */
data class ConnectedSsh(
    val client: SSHClient,
    val sftpClient: SFTPClient,
    val compatibility: ManagerCompatibility = ManagerCompatibility.Unchecked
)

class SshConnectionService(
    private val connectTimeoutMillis: Int = 10_000,
    private val operationTimeoutMillis: Int = 15_000,
    private val knownHostsFile: File = FilePath.KNOWN_HOSTS.toFile()
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun open(
        profile: ServerProfile,
        hostKeyApprovalHandler: HostKeyApprovalHandler,
        privateKeyPassphrase: CharArray? = null
    ): SshResult<ConnectedSsh> {
        val remoteOperatingSystem = profile.resolvedRemoteOperatingSystem()
        val keyPath = runCatching { Path.of(profile.key) }.getOrNull()
            ?: return SshResult.Failure(
                SshFailure(
                    code = SshFailureCode.PRIVATE_KEY_NOT_FOUND,
                    remoteOperatingSystem = remoteOperatingSystem
                )
            )
        if (!Files.isRegularFile(keyPath)) {
            return SshResult.Failure(
                SshFailure(
                    code = SshFailureCode.PRIVATE_KEY_NOT_FOUND,
                    remoteOperatingSystem = remoteOperatingSystem
                )
            )
        }

        val verifier = try {
            ManagedKnownHostsVerifier(hostKeyApprovalHandler, knownHostsFile)
        } catch (e: Exception) {
            SafeSshLogger.warn(logger, "known_hosts_initialization_failed", SshFailureCode.HOST_KEY_STORE_FAILED, e)
            return SshResult.Failure(
                SshFailure(
                    code = SshFailureCode.HOST_KEY_STORE_FAILED,
                    remoteOperatingSystem = remoteOperatingSystem
                )
            )
        }

        val client = createClient(verifier)
        var sftp: SFTPClient? = null

        try {
            client.connect(profile.host, profile.port)

            val keyProvider = try {
                if (privateKeyPassphrase == null) {
                    client.loadKeys(profile.key)
                } else {
                    client.loadKeys(profile.key, privateKeyPassphrase)
                }
            } catch (e: Exception) {
                privateKeyPassphrase?.fill('\u0000')
                return failureAndClose(
                    client,
                    sftp,
                    mapPrivateKeyFailure(e, privateKeyPassphrase == null).copy(
                        remoteOperatingSystem = remoteOperatingSystem
                    )
                )
            }

            try {
                client.authPublickey(profile.user, keyProvider)
            } catch (_: UserAuthException) {
                return failureAndClose(
                    client,
                    sftp,
                    SshFailure(
                        code = SshFailureCode.PUBLIC_KEY_AUTHENTICATION_FAILED,
                        remoteOperatingSystem = remoteOperatingSystem
                    )
                )
            } finally {
                // 暗号化鍵のPasswordFinderが認証時に参照できるよう、認証完了後に消去します。
                privateKeyPassphrase?.fill('\u0000')
            }

            val connectedSftp = try {
                client.newSFTPClient()
            } catch (e: Exception) {
                SafeSshLogger.warn(logger, "sftp_start_failed", SshFailureCode.SFTP_UNAVAILABLE, e)
                return failureAndClose(
                    client,
                    sftp,
                    SshFailure(
                        code = SshFailureCode.SFTP_UNAVAILABLE,
                        remoteOperatingSystem = remoteOperatingSystem
                    )
                )
            }
            sftp = connectedSftp

            val compatibility = readManagerCompatibility(connectedSftp, profile.path)
            RemoteDirectoryValidator.validate(connectedSftp, profile.path, compatibility.writable)?.let { failure ->
                return failureAndClose(
                    client,
                    connectedSftp,
                    failure.copy(remoteOperatingSystem = remoteOperatingSystem)
                )
            }

            return SshResult.Success(ConnectedSsh(client, connectedSftp, compatibility))
        } catch (e: Exception) {
            val failure = mapConnectionFailure(e, verifier, remoteOperatingSystem)
            SafeSshLogger.warn(logger, "public_key_connection_failed", failure.code, e)
            return failureAndClose(client, sftp, failure)
        }
    }

    fun test(
        profile: ServerProfile,
        hostKeyApprovalHandler: HostKeyApprovalHandler,
        privateKeyPassphrase: CharArray? = null
    ): SshResult<Unit> {
        return when (val result = open(profile, hostKeyApprovalHandler, privateKeyPassphrase)) {
            is SshResult.Success -> {
                close(result.value)
                SshResult.Success(Unit)
            }

            is SshResult.Failure -> result
        }
    }

    internal fun createPasswordClient(
        host: String,
        port: Int,
        user: String,
        password: CharArray,
        remoteOperatingSystem: RemoteOperatingSystem,
        hostKeyApprovalHandler: HostKeyApprovalHandler
    ): SshResult<ConnectedSsh> {
        val verifier = try {
            ManagedKnownHostsVerifier(hostKeyApprovalHandler, knownHostsFile)
        } catch (e: Exception) {
            password.fill('\u0000')
            SafeSshLogger.warn(logger, "known_hosts_initialization_failed", SshFailureCode.HOST_KEY_STORE_FAILED, e)
            return SshResult.Failure(
                SshFailure(
                    code = SshFailureCode.HOST_KEY_STORE_FAILED,
                    remoteOperatingSystem = remoteOperatingSystem
                )
            )
        }

        val client = createClient(verifier)
        var sftp: SFTPClient? = null

        try {
            client.connect(host, port)

            try {
                client.authPassword(user, password)
            } catch (_: UserAuthException) {
                val allowedMethods = runCatching { client.userAuth.allowedMethods }.getOrDefault(emptyList())
                val passwordAllowed = allowedMethods.any {
                    it == "password" || it == "keyboard-interactive"
                }
                val code = if (allowedMethods.isNotEmpty() && !passwordAllowed) {
                    SshFailureCode.PASSWORD_AUTHENTICATION_DISABLED
                } else {
                    SshFailureCode.PASSWORD_AUTHENTICATION_FAILED
                }
                return failureAndClose(
                    client,
                    sftp,
                    SshFailure(
                        code = code,
                        remoteOperatingSystem = remoteOperatingSystem
                    )
                )
            }

            val connectedSftp = try {
                client.newSFTPClient()
            } catch (e: Exception) {
                SafeSshLogger.warn(logger, "bootstrap_sftp_start_failed", SshFailureCode.SFTP_UNAVAILABLE, e)
                return failureAndClose(
                    client,
                    sftp,
                    SshFailure(
                        code = SshFailureCode.SFTP_UNAVAILABLE,
                        remoteOperatingSystem = remoteOperatingSystem
                    )
                )
            }
            sftp = connectedSftp

            return SshResult.Success(ConnectedSsh(client, connectedSftp))
        } catch (e: Exception) {
            val failure = mapConnectionFailure(e, verifier, remoteOperatingSystem)
            SafeSshLogger.warn(logger, "password_bootstrap_failed", failure.code, e)
            return failureAndClose(client, sftp, failure)
        } finally {
            // SSHJのchar[]版も消去しますが、呼び出し側の失敗経路を含めて二重に消去します。
            password.fill('\u0000')
        }
    }

    internal fun close(connected: ConnectedSsh) {
        runCatching { connected.sftpClient.close() }
        runCatching { connected.client.disconnect() }
        runCatching { connected.client.close() }
    }

    private fun createClient(verifier: ManagedKnownHostsVerifier): SSHClient {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
        return SSHClient().apply {
            addHostKeyVerifier(verifier)
            setConnectTimeout(connectTimeoutMillis)
            setTimeout(operationTimeoutMillis)
        }
    }

    private fun mapPrivateKeyFailure(error: Throwable, passphraseWasMissing: Boolean): SshFailure {
        val decryptionFailure = error.findCause<KeyDecryptionFailedException>() != null
        val likelyPasswordRequirement = passphraseWasMissing && error.causeChain().any { cause ->
            cause::class.simpleName.orEmpty().contains("password", ignoreCase = true) ||
                cause::class.simpleName.orEmpty().contains("passphrase", ignoreCase = true) ||
                cause.message.orEmpty().contains("passphrase", ignoreCase = true)
        }

        return if (decryptionFailure || likelyPasswordRequirement) {
            SshFailure(SshFailureCode.PRIVATE_KEY_PASSPHRASE_REQUIRED)
        } else {
            SshFailure(SshFailureCode.PRIVATE_KEY_FORMAT_UNSUPPORTED)
        }
    }

    private fun mapConnectionFailure(
        error: Throwable,
        verifier: ManagedKnownHostsVerifier,
        remoteOperatingSystem: RemoteOperatingSystem
    ): SshFailure {
        when (verifier.rejectionReason) {
            HostKeyRejectionReason.NOT_APPROVED ->
                return SshFailure(
                    code = SshFailureCode.HOST_KEY_NOT_APPROVED,
                    remoteOperatingSystem = remoteOperatingSystem
                )

            HostKeyRejectionReason.CHANGED -> {
                val current = verifier.lastPrompt?.fingerprint
                val previous = verifier.previousFingerprint
                val detail = buildString {
                    if (previous != null) append("保存済み: $previous")
                    if (current != null) {
                        if (isNotEmpty()) append('\n')
                        append("現在: $current")
                    }
                }.ifBlank { null }
                return SshFailure(
                    code = SshFailureCode.HOST_KEY_CHANGED,
                    detail = detail,
                    remoteOperatingSystem = remoteOperatingSystem
                )
            }

            HostKeyRejectionReason.STORE_FAILED ->
                return SshFailure(
                    code = SshFailureCode.HOST_KEY_STORE_FAILED,
                    remoteOperatingSystem = remoteOperatingSystem
                )

            null -> Unit
        }

        return when {
            error.findCause<UnknownHostException>() != null ->
                SshFailure(
                    code = SshFailureCode.DNS_RESOLUTION_FAILED,
                    remoteOperatingSystem = remoteOperatingSystem
                )

            error.findCause<NoRouteToHostException>() != null ->
                SshFailure(
                    code = SshFailureCode.HOST_UNREACHABLE,
                    remoteOperatingSystem = remoteOperatingSystem
                )

            error.findCause<SocketTimeoutException>() != null ->
                SshFailure(
                    code = SshFailureCode.CONNECTION_TIMEOUT,
                    remoteOperatingSystem = remoteOperatingSystem
                )

            error.findCause<ConnectException>() != null ->
                SshFailure(
                    code = SshFailureCode.CONNECTION_REFUSED,
                    remoteOperatingSystem = remoteOperatingSystem
                )

            else -> SshFailure(
                code = SshFailureCode.UNEXPECTED,
                detail = SecretRedactor.safeThrowableName(error),
                remoteOperatingSystem = remoteOperatingSystem
            )
        }
    }

    private fun failureAndClose(
        client: SSHClient,
        sftp: SFTPClient?,
        failure: SshFailure
    ): SshResult.Failure {
        runCatching { sftp?.close() }
        runCatching { client.disconnect() }
        runCatching { client.close() }
        return SshResult.Failure(failure)
    }
}

object RemoteDirectoryValidator {
    fun validate(sftp: SFTPClient, remoteRootPath: String, checkWrite: Boolean = true): SshFailure? {
        val normalizedPath = normalizeRemotePath(remoteRootPath)

        val attributes = try {
            sftp.stat(normalizedPath)
        } catch (e: SFTPException) {
            return when (e.statusCode) {
                Response.StatusCode.NO_SUCH_FILE -> SshFailure(SshFailureCode.REMOTE_DIRECTORY_NOT_FOUND)
                Response.StatusCode.PERMISSION_DENIED -> SshFailure(SshFailureCode.REMOTE_DIRECTORY_NOT_READABLE)
                else -> SshFailure(SshFailureCode.REMOTE_DIRECTORY_NOT_READABLE)
            }
        } catch (_: IOException) {
            return SshFailure(SshFailureCode.REMOTE_DIRECTORY_NOT_READABLE)
        }

        if (attributes.type != FileMode.Type.DIRECTORY) {
            return SshFailure(SshFailureCode.REMOTE_PATH_NOT_DIRECTORY)
        }

        try {
            sftp.ls(normalizedPath)
        } catch (_: Exception) {
            return SshFailure(SshFailureCode.REMOTE_DIRECTORY_NOT_READABLE)
        }

        if (!checkWrite) return null

        val testPath = if (normalizedPath == "/") {
            "/.sushieric-write-test-${UUID.randomUUID()}"
        } else {
            "$normalizedPath/.sushieric-write-test-${UUID.randomUUID()}"
        }

        var created = false
        return try {
            sftp.open(
                testPath,
                EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)
            ).use { remoteFile ->
                created = true
                remoteFile.RemoteFileOutputStream().use { output ->
                    output.write("SushiEricServerManager write test\n".toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                }
            }
            null
        } catch (_: Exception) {
            SshFailure(SshFailureCode.REMOTE_DIRECTORY_NOT_WRITABLE)
        } finally {
            if (created) runCatching { sftp.rm(testPath) }
        }
    }

    fun normalizeRemotePath(path: String): String {
        val normalized = path.trim().replace('\\', '/')
        if (normalized == "/") return "/"
        return normalized.trimEnd('/')
    }
}

private inline fun <reified T : Throwable> Throwable.findCause(): T? =
    causeChain().filterIsInstance<T>().firstOrNull()

private fun Throwable.causeChain(): Sequence<Throwable> = sequence {
    var current: Throwable? = this@causeChain
    while (current != null) {
        yield(current)
        current = current.cause
    }
}
