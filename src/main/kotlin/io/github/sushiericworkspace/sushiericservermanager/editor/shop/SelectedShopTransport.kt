package io.github.sushiericworkspace.sushiericservermanager.editor.shop

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementClient

/** 最初の要求時に一つの経路へ固定し、失敗やManagement再接続で別writerへ切り替えません。 */
internal class SelectedShopTransport(ssh: SshManager, management: ServerManagementClient) : ShopTransport {
    private val selected = lazy {
        val profile = requireNotNull(ssh.currentProfile)
        check(!management.isConnected || profile.databaseHistory?.postgres == null) {
            "PG Managementの型付き接続契約は未接続です。旧DB契約では実行しません。"
        }
        if (management.isConnected || profile.databaseHistory == null) ManagementShopTransport(management)
        else {
            val client = requireNotNull(ssh.sshClient)
            SshShopTransport({ check(ssh.sshClient === client); client },
                profile.databaseHistory.reference(), profile.resolvedRemoteOperatingSystem())
        }
    }
    override fun worldUuid() = selected.value.worldUuid()
    override fun databaseBinding() = selected.value.databaseBinding()
    override fun execute(request: HostAdapterRequest) = selected.value.execute(request)
    override fun reconcile(request: HostAdapterRequest) = selected.value.reconcile(request)
    override fun close() { if (selected.isInitialized()) selected.value.close() }
}
