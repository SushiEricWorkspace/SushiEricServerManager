package io.github.sushiericworkspace.sushiericservermanager.editor.shop

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import io.github.sushiericworkspace.sushiericservermanager.communication.database.*

/** 固定Host helperだけが保護設定を読みます。Manager JVMはPG接続や秘密読取を行いません。 */
class LocalShopTransport(reference: PostgresHostReference, timeoutMillis: Long = 30_000) : ShopTransport {
    private val transport = PostgresHostTransport(reference, { localHostChannel(reference, it) },
        readRunState = { localRunState(reference) }, timeoutMillis = timeoutMillis)
    override fun worldUuid() = transport.worldUuid()
    override fun databaseBinding() = transport.databaseBinding()
    override fun execute(request: HostAdapterRequest) = transport.execute(request)
    override fun reconcile(request: HostAdapterRequest) = transport.reconcile(request)
    override fun close() = transport.close()
}
