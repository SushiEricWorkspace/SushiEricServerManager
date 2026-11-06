package io.github.sushiericworkspace.sushiericservermanager.communication.management

import kotlinx.serialization.Serializable

/** 所有Mod JVMのworld/runを固定します。管理起動の所有情報は既存管理接続から渡します。 */
@Serializable
data class ManagementDatabaseBinding(
    val worldUuid: String,
    val runId: String,
    val instanceId: String? = null,
    val generationId: String? = null,
    val startNonce: String? = null
)
