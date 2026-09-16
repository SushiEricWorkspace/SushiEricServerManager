package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.common.data.item.model.ItemStatMultiplier
import io.github.sushiericworkspace.common.data.item.model.ItemType
import io.github.sushiericworkspace.common.stats.player.StatsPart
import io.github.sushiericworkspace.common.stats.player.StatsPartTarget
import io.github.sushiericworkspace.common.stats.player.StatsType
import kotlin.math.abs

/**
 * アイテムのステータス値の初期値、補正、表示の規則です。
 *
 * ステータス追加時と追加済みステータスの編集で同じ規則を使います。
 * 0は「未設定」を表すため、値として保持しません。
 *
 * 倍率（`stat-multipliers`）はStatsTypeごとの対象Part付きの倍率リストで、既定値1.0の要素は
 * 保存時にCommon側で削除されます。
 */

/**
 * ステータス追加時の初期値を返します。
 *
 * 範囲内で0でなければ[StatsType.default]を使います。既定値が0の場合は、
 * 範囲内の小さな非0値（1、なければ-1）を選びます。
 * 範囲の最小値から算出しないため、`-Double.MAX_VALUE`のような極端な値にはなりません。
 */
internal fun initialItemStatValue(type: StatsType): Double {
    val default = type.default

    return when {
        default != 0.0 && default in type.min..type.max -> default
        1.0 in type.min..type.max -> 1.0
        -1.0 in type.min..type.max -> -1.0
        type.min != 0.0 -> type.min
        else -> type.max
    }
}

/**
 * 入力値を保持できる値へ補正します。
 *
 * 0は未設定を表すため[initialItemStatValue]へ置き換え、それ以外は範囲内へ収めます。
 */
internal fun normalizeItemStatValue(type: StatsType, value: Double): Double {
    if (value.isNaN() || value == 0.0) {
        return initialItemStatValue(type)
    }

    return value.coerceIn(type.min, type.max)
}

/**
 * 入力文字列を値へ変換します。
 *
 * 数値として読めない場合は[fallback]を使い、結果を[normalizeItemStatValue]で補正します。
 */
internal fun parseItemStatValue(type: StatsType, text: String?, fallback: Double): Double {
    val parsed = text?.trim()?.toDoubleOrNull() ?: fallback

    return normalizeItemStatValue(type, parsed)
}

/**
 * 値を表示用の文字列にします。
 *
 * 整数値は小数点なしで表示します。`Int`へ縮小変換しないため、`Int`の範囲を超える値も
 * 桁を失いません。`Long`の範囲も超える整数値と小数は`Double.toString()`の表記
 * （`1.0E20`など）で表示し、そのまま再入力できます。
 */
internal fun formatItemStatValue(value: Double): String {
    if (value.isFinite() && value % 1.0 == 0.0 && abs(value) < Long.MAX_VALUE.toDouble()) {
        return value.toLong().toString()
    }

    return value.toString()
}

/*
 * ─────────────────────────────
 * 倍率
 * ─────────────────────────────
 */

/** 倍率の既定値です。この値の要素は保存時にCommon側で削除されます。 */
internal const val DEFAULT_ITEM_STAT_MULTIPLIER: Double = ItemStatMultiplier.DEFAULT_VALUE

/** 入力できる倍率の下限です。Common側の検証（0.0以上）と一致させます。 */
internal const val ITEM_STAT_MULTIPLIER_MIN: Double = ItemStatMultiplier.MIN_VALUE

/** 入力できる倍率の上限です。 */
internal const val ITEM_STAT_MULTIPLIER_MAX: Double = 1000.0

/** 倍率Spinnerの増減幅です。 */
internal const val ITEM_STAT_MULTIPLIER_STEP: Double = 0.1

/**
 * 入力された倍率を保持できる値へ補正します。
 *
 * NaNと無限は[DEFAULT_ITEM_STAT_MULTIPLIER]へ戻し、それ以外は
 * [ITEM_STAT_MULTIPLIER_MIN]..[ITEM_STAT_MULTIPLIER_MAX]へ収めます。
 */
internal fun normalizeItemStatMultiplier(value: Double): Double {
    if (!value.isFinite()) {
        return DEFAULT_ITEM_STAT_MULTIPLIER
    }

    return value.coerceIn(ITEM_STAT_MULTIPLIER_MIN, ITEM_STAT_MULTIPLIER_MAX)
}

/**
 * 倍率の入力文字列を値へ変換します。
 *
 * 数値として読めない場合は[fallback]を使い、結果を[normalizeItemStatMultiplier]で補正します。
 */
internal fun parseItemStatMultiplier(text: String?, fallback: Double): Double {
    val parsed = text?.trim()?.toDoubleOrNull() ?: fallback

    return normalizeItemStatMultiplier(parsed)
}

/**
 * 倍率を表示用の文字列にします。表記は[formatItemStatValue]と同じです。
 */
internal fun formatItemStatMultiplier(value: Double): String = formatItemStatValue(value)

/** 倍率を追加したときの初期要素です。装備しているPartだけを対象にする既定値の倍率です。 */
internal fun initialItemStatMultiplier(): ItemStatMultiplier =
    ItemStatMultiplier(StatsPartTarget.Self, DEFAULT_ITEM_STAT_MULTIPLIER)

/**
 * 倍率の対象をUIで選ぶための選択肢です。
 *
 * @property display 選択肢の表示名。
 */
internal enum class ItemStatMultiplierTargetOption(val display: String) {
    SELF("自分"),
    ALL("全体"),
    PARTS("部位");

    override fun toString(): String = display

    companion object {
        /** 倍率の対象に対応する選択肢を返します。 */
        fun of(target: StatsPartTarget<StatsPart>): ItemStatMultiplierTargetOption = when (target) {
            StatsPartTarget.Self -> SELF
            StatsPartTarget.All -> ALL
            is StatsPartTarget.Parts -> PARTS
        }
    }
}

/**
 * 選択肢と選択した部位から倍率の対象を作ります。
 *
 * [ItemStatMultiplierTargetOption.PARTS]で部位が空の場合は対象を作れないため`null`を返します。
 */
internal fun resolveItemStatMultiplierTarget(
    option: ItemStatMultiplierTargetOption,
    parts: Set<StatsPart>
): StatsPartTarget<StatsPart>? = when (option) {
    ItemStatMultiplierTargetOption.SELF -> StatsPartTarget.Self
    ItemStatMultiplierTargetOption.ALL -> StatsPartTarget.All
    ItemStatMultiplierTargetOption.PARTS -> parts.takeIf { it.isNotEmpty() }?.let { StatsPartTarget.Parts(it) }
}

/** 倍率の対象が部位指定なら、その部位を返します。それ以外は空です。 */
internal fun selectedItemStatMultiplierParts(target: StatsPartTarget<StatsPart>): Set<StatsPart> =
    (target as? StatsPartTarget.Parts)?.parts.orEmpty()

/**
 * 部位指定へ切り替えたときに最初に選ぶ部位を返します。
 *
 * 対象を空にできないため、そのアイテムの種類を装備できるPart（無ければメインハンド）を
 * 1つ選びます。
 */
internal fun defaultItemStatMultiplierParts(itemType: ItemType): Set<StatsPart> {
    val part = StatsPart.equipmentParts.firstOrNull { itemType in it.supportType }
        ?: StatsPart.MAIN_HAND

    return setOf(part)
}

/** 倍率の対象の選択肢に表示する説明です。 */
internal const val ITEM_STAT_MULTIPLIER_TARGET_TOOLTIP: String =
    "倍率を掛ける対象の部位です。\n" +
            "自分: このアイテムを装備している部位のステータスだけに掛けます。\n" +
            "全体: 基礎値・全装備・状態異常・効果を含む全部位に掛けます。\n" +
            "部位: 選んだ部位のステータスにだけ掛けます。\n" +
            "\n" +
            "計算: 部位ごとに固定値を合計し、その部位に届く倍率を掛けてから全部位を合計します。\n" +
            "同じ部位に複数の倍率が届く場合は 1 + Σ(倍率 − 1) で合成します（1.5と1.1なら1.6）。"

/** 倍率の値の入力欄に表示する説明です。 */
internal const val ITEM_STAT_MULTIPLIER_VALUE_TOOLTIP: String =
    "対象部位の固定値合計に掛ける倍率です。1 は倍率なしで、保存時に省略されます。\n" +
            "0 以上の値を指定できます（0.5 で半減、2 で2倍）。"
