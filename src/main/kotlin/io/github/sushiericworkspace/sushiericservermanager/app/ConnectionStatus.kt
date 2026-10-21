package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementState
import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfile
import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem
import io.github.sushiericworkspace.sushiericservermanager.update.ManagerCompatibility
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 通信を開始せず、観測済みの接続情報だけを共通バー用に整形します。 */
internal data class ConnectionStatus(
    val serverName: String,
    val ssh: String,
    val api: String,
    val minecraft: String,
    val tps: String,
    val access: String
) {
    val summary: String
        get() = "$serverName  |  SSH: $ssh  |  API: $api  |  MC: $minecraft  |  TPS: $tps  |  $access"

    companion object {
        fun from(
            mode: AppMode?, serverName: String?, sshConnected: Boolean, managed: Boolean,
            apiState: ServerManagementState, ticksPerSecond: Double?, compatibility: ManagerCompatibility
        ): ConnectionStatus {
            val online = mode == AppMode.ONLINE
            val connected = online && (sshConnected || managed)
            val apiConnected = connected && apiState is ServerManagementState.Connected
            return ConnectionStatus(
                serverName ?: if (mode == AppMode.OFFLINE) "オフライン" else "サーバー未選択",
                when { mode == null -> "不明"; !online || managed -> "対象外"; sshConnected -> "接続中"; else -> "未接続" },
                when {
                    mode == null -> "不明"
                    !online -> "対象外"
                    !connected -> "未接続"
                    apiState is ServerManagementState.Connected -> "接続中"
                    apiState is ServerManagementState.Connecting -> "接続処理中"
                    apiState is ServerManagementState.Failed -> "接続失敗"
                    else -> "未接続"
                },
                if (apiConnected && ticksPerSecond?.isFinite() == true) "稼働中" else "不明",
                if (apiConnected && ticksPerSecond?.isFinite() == true) String.format(Locale.ROOT, "%.2f", ticksPerSecond) else "不明",
                when { mode == AppMode.OFFLINE -> "ローカル書込可"; !connected -> "書込状態不明";
                    compatibility.writable -> "書込可"; else -> "サーバー読取専用（ローカル保存可）" }
            )
        }
    }
}

/** サーバー一覧に表示する、この起動中に観測した前回状態です。自動接続は行いません。 */
internal object ServerStatusHistory {
    private data class Observation(val status: ConnectionStatus, val checkedAt: Instant)
    private data class Identity(
        val name: String, val host: String, val port: Int, val user: String, val path: String,
        val os: RemoteOperatingSystem, val managementPort: Int
    )
    private fun identity(profile: ServerProfile) = Identity(
        profile.name, profile.host, profile.port, profile.user, profile.path,
        profile.resolvedRemoteOperatingSystem(), profile.resolvedManagementPort()
    )
    private val observations = mutableMapOf<Identity, Observation>()
    private val format = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneId.systemDefault())

    fun record(profile: ServerProfile, status: ConnectionStatus, checkedAt: Instant = Instant.now()) {
        observations[identity(profile)] = Observation(status, checkedAt)
    }

    fun forget(profile: ServerProfile) {
        observations.keys.removeAll { it.name == profile.name }
    }

    fun describe(profile: ServerProfile): String {
        val previous = observations[identity(profile)] ?: return "前回状態: 未確認  |  確認時刻: 未確認"
        return "前回状態: SSH ${previous.status.ssh} / API ${previous.status.api} / MC ${previous.status.minecraft}" +
            "  |  確認時刻: ${format.format(previous.checkedAt)}（今回の起動中）"
    }
}
