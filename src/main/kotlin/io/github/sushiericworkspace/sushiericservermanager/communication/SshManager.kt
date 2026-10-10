package io.github.sushiericworkspace.sushiericservermanager.communication

import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfile
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import java.io.IOException
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

    /** 接続時に評価したサーバーの互換性。切断・再接続でリセットします。 */
    var compatibility: ManagerCompatibility = ManagerCompatibility.Unchecked
        private set

    /** SFTPの書き込み直前に必ず確認します。読み取り専用の接続では副作用を開始しません。 */
    internal fun requireWritable() {
        if (!compatibility.writable) throw IOException(compatibility.message)
    }

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
                compatibility = result.value.compatibility
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
        compatibility = ManagerCompatibility.Unchecked
    }

    fun listFilesOrThrow(path: String): List<RemoteResourceInfo> {
        return activeSftp().ls(path).filterNotNull()
    }

    fun download(remotePath: String, localPath: String) {
        activeSftp().get(remotePath, localPath)
    }

    fun upload(localPath: String, remotePath: String) {
        requireWritable()
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
        requireWritable()
        activeSftp().rm(remotePath)
    }

    fun removeDirectory(remotePath: String) {
        requireWritable()
        activeSftp().rmdir(remotePath)
    }

    fun rename(oldRemotePath: String, newRemotePath: String) {
        requireWritable()
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
        requireWritable()
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
