package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericservermanager.communication.database.PostgresHostReference
import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem
import io.github.sushiericworkspace.sushiericservermanager.editor.shop.SshShopTransport
import io.github.sushiericworkspace.sushiericdatamodel.database.host.HostHistoryFilter
import net.schmizz.sshj.SSHClient

/** 検索ごとに同じHを保持し、検索終了でClose/EOFとSSH channelの終端を確認します。 */
class SshHistorySource(private val client: () -> SSHClient, private val reference: PostgresHostReference,
    private val os: RemoteOperatingSystem, private val timeoutMillis: Long = 30_000) : HistorySource {
    private val fixed by lazy { client() }
    override fun open(filter: HostHistoryFilter): HistorySearch = transportHistory(
        SshShopTransport({ check(client() === fixed); fixed }, reference, os, timeoutMillis), filter)
}
