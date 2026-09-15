package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.common.data.item.model.ItemBaseDataView
import io.github.sushiericworkspace.common.stats.player.StatsType
import kotlin.math.abs

/**
 * アイテムのステータス値の初期値、補正、表示の規則です。
 *
 * ステータス追加時と追加済みステータスの編集で同じ規則を使います。
 * 0は「未設定」を表すため、値として保持しません。
 *
 * 倍率（`stat-multipliers`）はStatsTypeごとに値へ最後に掛ける数で、既定値1.0は保持しません。
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

/** 倍率の既定値です。この値は保存へ含めません。 */
internal const val DEFAULT_ITEM_STAT_MULTIPLIER: Double = ItemBaseDataView.DEFAULT_STAT_MULTIPLIER

/** 入力できる倍率の下限です。Common側の検証（0.0以上）と一致させます。 */
internal const val ITEM_STAT_MULTIPLIER_MIN: Double = 0.0

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

/**
 * 倍率を編集中データへ反映します。
 *
 * 既定値（1.0）は「倍率なし」と同じ意味のため保存へ含めず、Mapから削除します。
 * Common側の`ItemManager.normalize`と同じ規則です。
 */
internal fun applyItemStatMultiplier(
    multipliers: MutableMap<StatsType, Double>,
    type: StatsType,
    value: Double
) {
    val normalized = normalizeItemStatMultiplier(value)

    if (normalized == DEFAULT_ITEM_STAT_MULTIPLIER) {
        multipliers.remove(type)
    } else {
        multipliers[type] = normalized
    }
}
