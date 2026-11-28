package io.github.sushiericworkspace.sushiericservermanager.communication.management

import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import io.github.sushiericworkspace.sushiericdatamodel.database.run.PostgresRunBinding
import io.github.sushiericworkspace.sushiericdatamodel.database.run.PostgresRunPhase
import io.github.sushiericworkspace.sushiericservermanager.communication.database.PostgresHostReference
import java.util.UUID

/** 所有Mod JVMのworld/runを固定します。管理起動の所有情報は既存管理接続から渡します。 */
@Serializable
data class ManagementDatabaseBinding(
    val worldUuid: String,
    val runId: String,
    @Required val storageContractVersion: Int = 2,
    val database: PostgresRunBinding,
    val instanceId: String? = null,
    val generationId: String? = null,
    val startNonce: String? = null
) {
    init {
        require(storageContractVersion == 2 && worldUuid == database.worldUuid)
        require(UUID.fromString(worldUuid).toString() == worldUuid && UUID.fromString(runId).toString() == runId)
    }

    /** profileの承認済み参照と照合し、管理起動の所有情報を推測しません。 */
    internal fun validate(reference: PostgresHostReference?) {
        require(instanceId == null && generationId == null && startNonce == null) {
            "管理worldは専用の所有接続が必要です。"
        }
        reference?.let {
            it.validate()
            val state = it.status.state
            require(database == state.binding) { "ManagementのDB/world/generationがprofileと一致しません。" }
            val phase = state.phase
            require(phase !is PostgresRunPhase.GameDirty || runId == phase.runUuid) { "Managementのrunがprofileと一致しません。" }
        }
    }
}
