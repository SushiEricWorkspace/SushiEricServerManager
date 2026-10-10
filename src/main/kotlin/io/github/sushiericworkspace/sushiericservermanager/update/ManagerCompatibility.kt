package io.github.sushiericworkspace.sushiericservermanager.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** サーバーの互換ファイルを評価した結果。未確認の接続からの書き込みは許可しません。 */
sealed interface ManagerCompatibility {
    val message: String
    val writable: Boolean

    data object Unchecked : ManagerCompatibility {
        override val message = "サーバー互換性は未確認です。"
        override val writable = false
    }
    data class Compatible(val minimumVersion: String) : ManagerCompatibility {
        override val message = "サーバー互換性：確認済み（最低版 $minimumVersion）"
        override val writable = true
    }
    data class UpdateRequired(val minimumVersion: String) : ManagerCompatibility {
        override val message = "更新が必要です（最低版 $minimumVersion）。読み取り専用：保存・削除・名前変更・アップロードはできません。"
        override val writable = false
    }
    data object Missing : ManagerCompatibility {
        override val message = "サーバー互換ファイルがありません。書き込みは許可されています。"
        override val writable = true
    }
    data object Unreadable : ManagerCompatibility {
        override val message = "サーバー互換ファイルを確認できませんでした。書き込みは許可されています。"
        override val writable = true
    }
}

/** 厳密な数値版だけを評価します。不正なファイルは呼び出し側で警告付きの許可状態へ変換します。 */
internal fun evaluateManagerCompatibility(text: String, currentVersion: String = AppVersion.CURRENT): ManagerCompatibility {
    val root = Json.parseToJsonElement(text).jsonObject
    val minimumValue = requireNotNull(root["minManagerVersion"]).jsonPrimitive
    val modValue = requireNotNull(root["modVersion"]).jsonPrimitive
    require(minimumValue.isString && modValue.isString) { "互換ファイルの版は文字列で指定してください。" }
    val minimum = minimumValue.content
    val mod = modValue.content
    val versionPattern = Regex("\\d+\\.\\d+\\.\\d+")
    require(versionPattern.matches(minimum) && minimum.split('.').all { it.toIntOrNull() != null } && mod.isNotBlank()) { "互換ファイルの版が不正です。" }
    return if (isNewerVersion(minimum, currentVersion)) ManagerCompatibility.UpdateRequired(minimum)
    else ManagerCompatibility.Compatible(minimum)
}
