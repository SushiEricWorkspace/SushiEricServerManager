package io.github.sushiericworkspace.sushiericservermanager.communication.management

import io.github.sushiericworkspace.sushiericdatamodel.database.run.PostgresRunBinding
import java.util.UUID

internal fun managementBinding(world: String = UUID.randomUUID().toString(), run: String = UUID.randomUUID().toString(),
    instanceId: String? = null, generationId: String? = null) = ManagementDatabaseBinding(
    world, run, database = PostgresRunBinding("fixture", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
        world, UUID.randomUUID().toString(), "/world", "world", "/ownership", "ownership"),
    instanceId = instanceId, generationId = generationId)
