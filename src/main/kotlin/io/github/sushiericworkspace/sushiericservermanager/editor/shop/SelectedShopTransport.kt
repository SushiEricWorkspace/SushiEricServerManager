package io.github.sushiericworkspace.sushiericservermanager.editor.shop

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericservermanager.communication.SshManager
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementClient

/** 最初の要求時に一つの経路へ固定し、失敗やManagement再接続で別writerへ切り替えません。 */
internal class SelectedShopTransport(ssh: SshManager, management: ServerManagementClient) : ShopTransport {
    private val selected = lazy {
        val profile = requireNotNull(ssh.currentProfile)
        if (management.isConnected) ManagementShopTransport(management,
            reference = requireNotNull(profile.databaseHistory) { "Managementには承認済みv2 DB参照が必要です。" }.reference())
        else {
            val client = requireNotNull(ssh.sshClient)
            SshShopTransport({ check(ssh.sshClient === client); client },
                requireNotNull(profile.databaseHistory).reference(), profile.resolvedRemoteOperatingSystem())
        }
    }
    override fun worldUuid() = selected.value.worldUuid()
    override fun databaseBinding() = selected.value.databaseBinding()
    override fun execute(request: HostAdapterRequest) = selected.value.execute(request)
    override fun reconcile(request: HostAdapterRequest) = selected.value.reconcile(request)
    override fun close() { if (selected.isInitialized()) selected.value.close() }
}
