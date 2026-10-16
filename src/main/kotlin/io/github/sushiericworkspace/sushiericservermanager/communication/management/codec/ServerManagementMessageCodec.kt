package io.github.sushiericworkspace.sushiericservermanager.communication.management.codec

import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementRequest
import io.github.sushiericworkspace.sushiericservermanager.communication.management.ServerManagementResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

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

    /**
     * 送信するメッセージをJSONへ変換します。
     *
     * @param request 送信するメッセージ。
     * @return `type`を含むJSON文字列。
     */
    fun encode(
        request: ServerManagementRequest
    ): String =
        json.encodeToString(request)

    /**
     * 受信したJSONをメッセージへ変換します。
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
                    detail = summarize(exception)
                )
            }

        if (element !is JsonObject) {
            return ServerManagementDecodeResult.Failure(
                reason = "メッセージがJSONオブジェクトではありません。",
                detail = null
            )
        }

        return try {
            ServerManagementDecodeResult.Success(
                json.decodeFromJsonElement(
                    ServerManagementResponse.serializer(),
                    element
                )
            )
        } catch (exception: SerializationException) {
            ServerManagementDecodeResult.Failure(
                reason = "未対応の種別です。",
                detail = summarize(exception)
            )
        } catch (exception: IllegalArgumentException) {
            ServerManagementDecodeResult.Failure(
                reason = "未対応の種別です。",
                detail = summarize(exception)
            )
        }
    }

    /**
     * 例外メッセージをログへ載せられる長さへ整えます。
     *
     * ライブラリの例外は受信内容そのものを含む複数行のことがあるため、先頭行だけを使います。
     */
    private fun summarize(
        exception: Exception
    ): String? =
        exception.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
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
