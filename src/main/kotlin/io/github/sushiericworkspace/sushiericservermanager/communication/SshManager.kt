package io.github.sushiericworkspace.sushiericservermanager.communication

import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfile
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import org.slf4j.LoggerFactory

/**
 * 接続済みSSH/SFTPセッションを保持し、既存のファイル操作APIを提供します。
 * 接続確立処理自体は [SshConnectionService] へ委譲します。
 */
class SshManager(
    private val connectionService: SshConnectionService = SshConnectionService()
) {
    private var client: SSHClient? = null
    private var sftpClient: SFTPClient? = null
    private val logger = LoggerFactory.getLogger(javaClass)
    private var profile: ServerProfile? = null

    val currentProfile: ServerProfile? get() = profile

    /**
     * 認証済みのSSHクライアントです。
     *
     * SSH Tunnelの作成で使用します。未接続の場合はnullです。
     * 新しい接続を張らずこのクライアントを共有することで、
     * ホスト鍵の検証結果をそのまま引き継ぎます。
     */
    val sshClient: SSHClient?
        get() = client.takeIf { isConnected }

    val isConnected: Boolean
        get() = client?.isConnected == true && client?.isAuthenticated == true && profile != null

    val isSftpActive: Boolean
        get() = isConnected && sftpClient != null

    fun connect(
        profile: ServerProfile,
        hostKeyApprovalHandler: HostKeyApprovalHandler
    ): SshResult<Unit> {
        disconnect()

        return when (val result = connectionService.open(profile, hostKeyApprovalHandler)) {
            is SshResult.Success -> {
                client = result.value.client
                sftpClient = result.value.sftpClient
                this.profile = profile
                logger.info("SSH接続に成功しました: profile={}", profile.name)
                SshResult.Success(Unit)
            }

            is SshResult.Failure -> result
        }
    }

    /**
     * 互換用です。未知のホスト鍵は自動承認しません。
     */
    @Deprecated("Use connect(profile, hostKeyApprovalHandler)")
    fun connect(profile: ServerProfile): Boolean {
        return connect(profile, HostKeyApprovalHandler.REJECT_UNKNOWN) is SshResult.Success
    }

    fun disconnect() {
        runCatching { sftpClient?.close() }
        runCatching { client?.disconnect() }
        runCatching { client?.close() }

        sftpClient = null
        client = null
        profile = null
    }

    fun listFilesOrThrow(path: String): List<RemoteResourceInfo> {
        return activeSftp().ls(path).filterNotNull()
    }

    fun download(remotePath: String, localPath: String) {
        activeSftp().get(remotePath, localPath)
    }

    fun upload(localPath: String, remotePath: String) {
        val normalizedRemotePath = normalizeRemotePath(remotePath)
        val parentPath = normalizedRemotePath.substringBeforeLast(
            delimiter = "/",
            missingDelimiterValue = ""
        )

        if (parentPath.isNotBlank()) {
            createDirectories(parentPath)
        }

        activeSftp().put(localPath, normalizedRemotePath)
    }

    fun remove(remotePath: String) {
        activeSftp().rm(remotePath)
    }

    fun removeDirectory(remotePath: String) {
        activeSftp().rmdir(remotePath)
    }

    fun rename(oldRemotePath: String, newRemotePath: String) {
        activeSftp().rename(oldRemotePath, newRemotePath)
    }

    fun exists(remotePath: String): Boolean {
        return try {
            activeSftp().stat(remotePath)
            true
        } catch (e: SFTPException) {
            if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) false else throw e
        }
    }

    fun createDirectories(remoteDirPath: String) {
        val sftp = activeSftp()
        val normalizedPath = normalizeRemotePath(remoteDirPath).trimEnd('/')
        if (normalizedPath.isBlank()) return

        val parts = normalizedPath.split('/').filter { it.isNotBlank() }
        var currentPath = if (normalizedPath.startsWith('/')) "/" else ""

        for (part in parts) {
            currentPath = when {
                currentPath == "/" -> "/$part"
                currentPath.isBlank() -> part
                else -> "$currentPath/$part"
            }

            try {
                val attributes = sftp.stat(currentPath)
                if (attributes.type != FileMode.Type.DIRECTORY) {
                    throw IllegalStateException("指定パスはディレクトリではありません: $currentPath")
                }
            } catch (e: SFTPException) {
                if (e.statusCode == Response.StatusCode.NO_SUCH_FILE) {
                    sftp.mkdir(currentPath)
                } else {
                    throw e
                }
            }
        }
    }

    private fun activeSftp(): SFTPClient {
        if (!isSftpActive) {
            throw IllegalStateException("SFTP session is not active")
        }
        return requireNotNull(sftpClient)
    }

    private fun normalizeRemotePath(path: String): String {
        return path
            .replace('\\', '/')
            .replace(Regex("/+"), "/")
    }
}
