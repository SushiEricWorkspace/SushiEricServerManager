package io.github.sushiericworkspace.sushiericservermanager.editor.shop

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericservermanager.communication.database.*
import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem
import net.schmizz.sshj.SSHClient

/** 認証済みの同じSSHへ、固定新版CLIの非秘密frameだけを送ります。再接続でsessionを再生成しません。 */
class SshShopTransport(client: () -> SSHClient, reference: PostgresHostReference,
    os: RemoteOperatingSystem, timeoutMillis: Long = 30_000) : ShopTransport {
    private val fixed by lazy { client() }
    private val transport = PostgresHostTransport(reference, {
        sshHostChannel(fixed, reference, os, it)
    }, current = {
        check(client() === fixed && fixed.isConnected) { "SSH接続が切断・変更されました。" }
    }, readRunState = { sshRunState(fixed, reference) }, timeoutMillis = timeoutMillis)
    override fun worldUuid() = transport.worldUuid()
    override fun databaseBinding() = transport.databaseBinding()
    override fun execute(request: HostAdapterRequest) = transport.execute(request)
    override fun reconcile(request: HostAdapterRequest) = transport.reconcile(request)
    override fun close() = transport.close()
}
