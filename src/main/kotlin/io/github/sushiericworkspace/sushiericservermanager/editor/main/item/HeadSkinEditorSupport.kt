package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.common.data.core.identity.VanillaItemId
import io.github.sushiericworkspace.common.data.item.model.HeadSkinSource
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableHeadSkinData

internal val PLAYER_HEAD_VANILLA_ID = VanillaItemId("player_head")

internal fun isHeadSkinEditableVanillaId(vanillaId: VanillaItemId): Boolean =
    vanillaId == PLAYER_HEAD_VANILLA_ID

internal fun normalizeHeadSkinInput(value: String): String =
    value.replace("\r", "").replace("\n", "")

internal fun headSkinWarning(source: HeadSkinSource?): String? = when (source) {
    HeadSkinSource.PLAYER_NAME ->
        "注意: プレイヤー名は変更される可能性があります。また、プレイヤーがスキンを変更すると表示も変わります。"
    HeadSkinSource.PLAYER_UUID ->
        "注意: プレイヤーがスキンを変更すると表示も変わります。"
    else -> null
}

internal fun applyHeadSkinEditorValue(
    current: MutableHeadSkinData?,
    source: HeadSkinSource?,
    value: String
): MutableHeadSkinData? {
    if (source == null) return null

    return (current ?: MutableHeadSkinData()).apply {
        this.source = source
        this.value = value
    }
}
