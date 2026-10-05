package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

/** 相手と実行者を同じ形式に揃え、欠損した情報や同じ値を重複表示しません。 */
internal fun formatHistoryActor(name: String?, uuid: String?): String? {
    val displayName = name?.trim()?.takeIf(String::isNotEmpty)
    val displayUuid = uuid?.trim()?.takeIf(String::isNotEmpty)
    return when {
        displayName != null && displayUuid != null && displayName != displayUuid -> "$displayName ($displayUuid)"
        else -> displayName ?: displayUuid
    }
}
