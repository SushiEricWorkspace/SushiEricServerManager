package io.github.sushiericworkspace.sushiericservermanager.editor.shop

import io.github.sushiericworkspace.sushiericdatamodel.database.host.*
import javafx.application.Platform
import java.nio.file.Path

/** 同一ホストの非管理worldへD3で接続します。path・seal・run-stateの検証はDataModelへ委譲します。 */
class LocalShopTransport(
    private val world: Path,
    private val shop: Path,
    private val worldId: String
) : ShopTransport {
    override fun worldUuid() = worldId

    override fun execute(request: HostAdapterRequest): HostAdapterResponse {
        check(!Platform.isFxApplicationThread()) { "商品DB操作はI/O workerで実行してください。" }
        require(request.worldUuid == worldId)
        return HostDatabaseAdapter(world, shop).execute(request)
    }
}
