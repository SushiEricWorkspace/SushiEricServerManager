package io.github.sushiericworkspace.sushiericservermanager.communication

import io.github.sushiericworkspace.common.path.SushiEricDataDirectory
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import io.github.sushiericworkspace.sushiericservermanager.update.evaluateManagerCompatibility
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.sftp.Response
import org.slf4j.LoggerFactory

/** 接続したSFTPから互換ファイルを読みます。接続時の書き込み検査より先に呼び出します。 */
internal fun readManagerCompatibility(sftp: SFTPClient, serverRoot: String): ManagerCompatibility {
    val path = serverRoot.replace('\\', '/').trimEnd('/') + "/" +
        SushiEricDataDirectory.BASE_ROOT + "/" + SushiEricDataDirectory.ManagerCompat().getRawPath()
    return try {
        val bytes = sftp.open(path).use { file -> file.RemoteFileInputStream().use { it.readNBytes(65_537) } }
        require(bytes.size <= 65_536) { "互換ファイルが大きすぎます。" }
        evaluateManagerCompatibility(bytes.toString(Charsets.UTF_8))
    } catch (e: Exception) {
        if (e is SFTPException && e.statusCode == Response.StatusCode.NO_SUCH_FILE) ManagerCompatibility.Missing
        else {
            LoggerFactory.getLogger("ManagerCompatibilityReader").warn("サーバー互換ファイルを確認できませんでした。書き込みを許可します: {}", path, e)
            ManagerCompatibility.Unreadable
        }
    }
}
