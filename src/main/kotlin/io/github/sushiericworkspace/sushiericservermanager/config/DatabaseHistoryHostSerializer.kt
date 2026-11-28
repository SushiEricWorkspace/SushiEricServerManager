package io.github.sushiericworkspace.sushiericservermanager.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder

/** DB参照設定だけは、共通設定で許容する未知fieldも受け付けません。 */
internal object DatabaseHistoryHostSerializer : KSerializer<DatabaseHistoryHost> {
    private val delegate = DatabaseHistoryHost.serializer()
    private val json = Json.Default
    override val descriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): DatabaseHistoryHost {
        val input = decoder as? JsonDecoder ?: throw SerializationException("DB参照設定にはJSONが必要です。")
        return try {
            json.decodeFromJsonElement(delegate, input.decodeJsonElement())
        } catch (_: SerializationException) {
            throw SerializationException("DB参照設定の形式が不正です。")
        }
    }

    override fun serialize(encoder: Encoder, value: DatabaseHistoryHost) {
        encoder.encodeSerializableValue(delegate, value)
    }
}
