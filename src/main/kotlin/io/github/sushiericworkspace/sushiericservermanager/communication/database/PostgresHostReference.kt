package io.github.sushiericworkspace.sushiericservermanager.communication.database

import io.github.sushiericworkspace.sushiericdatamodel.database.run.*
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** 非秘密の承認済みrun観測と固定helperの配置。shopはhelper設定と同じ承認実体を指定し、秘密fileは読みません。 */
@Serializable
data class PostgresHostReference(
    @Required val storageContractVersion: Int = 2,
    val runState: String,
    val shop: PostgresRunShopBinding,
    val javaExecutable: String,
    val runtimeDirectory: String,
    val settingsFile: String
) {
    val status: PostgresRunStatus
        get() {
            val bytes = runState.encodeToByteArray(throwOnInvalidSequence = true)
            return PostgresRunStatus(MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }, PostgresRunCodec.decode(bytes))
        }

    fun validate() {
        require(storageContractVersion == 2)
        PostgresRunCodec.encode(status.state)
        require(shop.identity.isNotBlank() && (shop.root.startsWith("/") ||
            Regex("^[A-Za-z]:[/\\\\].+").matches(shop.root))) { "承認済みshopの絶対パスと実体識別が必要です。" }
        require(shop.root.none { it == '\u0000' || it == '\n' || it == '\r' })
        checkShop(status.state)
        require(listOf(javaExecutable, runtimeDirectory, settingsFile).all {
            it.isNotBlank() && it.none { c -> c == '\u0000' || c == '\n' || c == '\r' }
        })
    }

    internal fun observe(bytes: ByteArray): PostgresRunStatus {
        val state = PostgresRunCodec.decode(bytes)
        require(state.binding == status.state.binding) { "DB/world/generationの参照が変更されました。" }
        checkShop(state)
        return PostgresRunStatus(MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }, state)
    }

    private fun checkShop(state: PostgresRunState) {
        val observedShop = when (val phase = state.phase) {
            is PostgresRunPhase.OfflinePending -> phase.request.shop
            is PostgresRunPhase.OfflineEdited -> phase.edit.request.shop
            else -> null
        }
        require(observedShop == null || observedShop == shop) { "承認済みshopがHostの編集記録と一致しません。" }
    }

    internal fun runStatePath() = status.state.binding.worldRoot.trimEnd('/', '\\') +
        "/sushieric/run-state-v2/current.json"

    internal fun arguments(lookup: Boolean = false): List<String> {
        validate()
        return listOf(javaExecutable, "-cp", runtimeDirectory.trimEnd('/', '\\') + "/lib/*",
            "io.github.sushiericworkspace.sushiericdatamodel.database.host." +
                if (lookup) "PostgresOfflineLookupCli" else "PostgresWriterCli", "--settings", settingsFile)
    }
}
