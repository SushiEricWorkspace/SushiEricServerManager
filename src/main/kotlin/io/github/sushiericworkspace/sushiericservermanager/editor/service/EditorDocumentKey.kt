package io.github.sushiericworkspace.sushiericservermanager.editor.service

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/** 保存元を接続表示名や現在のsessionから独立して識別します。認証情報は含めません。 */
@Serializable
sealed class EditorDocumentSource {
    @Serializable @SerialName("local")
    data object Local : EditorDocumentSource()

    /** profileの名称変更は同じ保存元、接続先またはrootの変更は別の保存元です。 */
    @Serializable @SerialName("server")
    data class Server(val profileId: String, val endpoint: String) : EditorDocumentSource()

    internal val directory: String
        get() = when (this) {
            Local -> "local"
            is Server -> "server-" + documentHash("$profileId\u0000$endpoint")
        }
}

/** 同じ公開IDでも保存元・データ種別の異なる文書は共有しません。 */
@Serializable
data class EditorDocumentKey(
    val source: EditorDocumentSource,
    val category: String,
    val id: String
)

internal fun documentHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
