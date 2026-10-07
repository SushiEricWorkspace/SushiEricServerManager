package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import io.github.sushiericworkspace.sushiericservermanager.editor.store.EditorDataStore
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.Logger
import java.util.UUID

/**
 * サーバーの`usercache.json`から、正規化したUUIDをキーにしたプレイヤー名の対応を読み込みます。
 *
 * ファイルがない、または解析できない場合は空のMapを返し、解析失敗だけを警告として記録します。
 */
internal fun readKnownPlayerNames(store: EditorDataStore, logger: Logger): Map<String, String> {
    val text = when (val result = store.readServerText("usercache.json")) {
        is StoreResult.Success -> result.value
        is StoreResult.Failure -> {
            if (result.error.code != StoreErrorCode.FILE_NOT_FOUND) {
                logger.warn("プレイヤー名キャッシュを読み込めません: {}", result.error.code)
            }
            return emptyMap()
        }
    }
    return runCatching {
        Json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
            runCatching {
                val entry = element.jsonObject
                val uuid = UUID.fromString(entry["uuid"]?.jsonPrimitive?.contentOrNull).toString()
                entry["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                    ?.let { name -> uuid to name }
            }.getOrNull()
        }.toMap()
    }.getOrElse {
        logger.warn("プレイヤー名キャッシュの解析に失敗しました。", it)
        emptyMap()
    }
}
