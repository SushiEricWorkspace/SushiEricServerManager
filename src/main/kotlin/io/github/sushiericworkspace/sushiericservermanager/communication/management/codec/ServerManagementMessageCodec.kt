package io.github.sushiericworkspace.sushiericservermanager.communication.management.codec

import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementRequest
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementResponse
import io.github.sushiericworkspace.sushiericdatamodel.database.host.HostAdapterCodec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadFeature

/**
 * Management APIのメッセージとJSONを相互変換します。
 *
 * 種別の判別子は`type`で、SushiEricServerMod側の定義と一致させます。
 */
object ServerManagementMessageCodec {

    /** 種別を表すJSONのキーです。 */
    const val TYPE_KEY: String = "type"

    private val json =
        Json {
            classDiscriminator = TYPE_KEY

            /*
             * サーバー側が新しいフィールドを追加しても、
             * 既知のフィールドだけで処理を継続できるようにする。
             */
            ignoreUnknownKeys = true
        }

    private val databaseJson = Json { classDiscriminator = TYPE_KEY }
    private val wireJson = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
    private val databaseTypes = setOf("database_context_request", "database_product_request", "database_history_start",
        "database_history_page", "database_history_end", "money_history_save", "database_context",
        "database_product_result", "database_history", "money_history_save_result")

    private fun isDatabase(element: JsonObject) = (element[TYPE_KEY] as? JsonPrimitive)?.content in databaseTypes

    private fun validateDatabase(text: String, element: JsonObject) {
        require(text.encodeToByteArray(throwOnInvalidSequence = true).size <= 60 * 1024)
        val version = element["storageContractVersion"] as? JsonPrimitive
        require(version != null && !version.isString && version.content == "2")
        wireJson.createParser(text).use { parser ->
            require(parser.nextToken() == JsonToken.START_OBJECT)
            val unicode = Charsets.UTF_8.newEncoder()
            var depth = 1
            while (depth > 0) {
                val token = requireNotNull(parser.nextToken())
                if (token == JsonToken.FIELD_NAME || token == JsonToken.VALUE_STRING)
                    require(unicode.canEncode(parser.text))
                if (token != JsonToken.FIELD_NAME && parser.currentName() in setOf("success", "rejectedBeforeEffect", "hasMore"))
                    require(token == JsonToken.VALUE_TRUE || token == JsonToken.VALUE_FALSE)
                if (token != JsonToken.FIELD_NAME && parser.currentName() == "storageContractVersion")
                    require(token == JsonToken.VALUE_NUMBER_INT && parser.text == "2")
                if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) depth++
                if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--
            }
            require(parser.nextToken() == null)
        }
    }

    /**
     * 送信するメッセージをJSONへ変換します。
     *
     * @param request 送信するメッセージ。
     * @return `type`を含むJSON文字列。
     * @throws IllegalArgumentException DB保存契約又は要求サイズ・Unicodeが不正な場合。
     */
    fun encode(
        request: ServerManagementRequest
    ): String {
        val text = json.encodeToString(request)
        val element = json.parseToJsonElement(text) as JsonObject
        if (isDatabase(element)) {
            validateDatabase(text, element)
            if (request is ServerManagementRequest.DatabaseProductRequest)
                require(HostAdapterCodec.encodeRequest(request.request).size <= 56 * 1024)
        }
        return text
    }

    /**
     * 受信したJSONをメッセージへ変換します。DB応答はstorage v2を必須とし、
     * 未知・重複fieldと不正Unicodeを拒否します。
     *
     * 例外を呼び出し側へ伝播させず、結果で表します。
     * サーバー側が新しい種別を送ってきても接続を落とさないためです。
     *
     * @param text 受信したテキスト。
     * @return 復号結果。
     */
    fun decode(
        text: String
    ): ServerManagementDecodeResult {
        val element =
            try {
                json.parseToJsonElement(text)
            } catch (exception: SerializationException) {
                return ServerManagementDecodeResult.Failure(
                    reason = "JSONとして解釈できません。",
                    detail = null
                )
            }

        if (element !is JsonObject) {
            return ServerManagementDecodeResult.Failure(
                reason = "メッセージがJSONオブジェクトではありません。",
                detail = null
            )
        }

        return try {
            val decoder = if (isDatabase(element)) {
                validateDatabase(text, element)
                databaseJson
            } else json
            ServerManagementDecodeResult.Success(
                decoder.decodeFromJsonElement(
                    ServerManagementResponse.serializer(),
                    element
                )
            )
        } catch (exception: SerializationException) {
            ServerManagementDecodeResult.Failure(
                reason = "未対応の種別です。",
                detail = null
            )
        } catch (exception: java.io.IOException) {
            ServerManagementDecodeResult.Failure("DB応答のJSONが不正です。", null)
        } catch (exception: IllegalArgumentException) {
            ServerManagementDecodeResult.Failure(
                reason = "未対応の種別です。",
                detail = null
            )
        }
    }
}

/**
 * 受信メッセージの復号結果です。
 */
sealed interface ServerManagementDecodeResult {

    /**
     * 復号できた場合の結果です。
     *
     * @property message 復号したメッセージ。
     */
    data class Success(
        val message: ServerManagementResponse
    ) : ServerManagementDecodeResult

    /**
     * 復号できなかった場合の結果です。
     *
     * @property reason 復号できなかった理由。
     * @property detail 原因の補足。
     */
    data class Failure(
        val reason: String,
        val detail: String?
    ) : ServerManagementDecodeResult
}
