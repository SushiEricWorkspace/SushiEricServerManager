package io.github.sushiericworkspace.sushiericservermanager.editor.validation

import io.github.sushiericworkspace.common.data.core.validation.SushiEricValidationError

/** バリデーション警告へ登録済みの修正を適用した結果です。 */
sealed interface ValidationRepairResult {
    /** データを変更した結果です。 */
    data class Applied(val description: String) : ValidationRepairResult

    /** 修正を試みたものの、データを変更せず終了した結果です。 */
    data class Failed(val reason: String) : ValidationRepairResult

    /** 対応する修正アクションが登録されていない結果です。 */
    data object Unsupported : ValidationRepairResult
}

/**
 * データ種別固有の警告修正を、Commonのプロパティとkeyを条件に登録します。
 *
 * 共通エディター基盤は具体的なデータ型を判定せず、このRegistryだけを実行します。
 */
class ValidationRepairRegistry<T> {
    private data class Entry<T>(
        val matches: (SushiEricValidationError) -> Boolean,
        val description: (SushiEricValidationError) -> String,
        val repair: (T, SushiEricValidationError) -> ValidationRepairResult
    )

    private val entries = mutableListOf<Entry<T>>()

    /** 条件に一致する警告の修正アクションを登録します。 */
    fun register(
        matches: (SushiEricValidationError) -> Boolean,
        description: (SushiEricValidationError) -> String,
        repair: (T, SushiEricValidationError) -> ValidationRepairResult
    ) {
        entries += Entry(matches, description, repair)
    }

    /** 対応する修正アクションがあるか返します。 */
    fun supports(error: SushiEricValidationError): Boolean =
        entries.any { it.matches(error) }

    /** 確認画面へ表示する変更内容を返します。 */
    fun description(error: SushiEricValidationError): String? =
        entries.firstOrNull { it.matches(error) }?.description?.invoke(error)

    /** 最初に一致した修正アクションを実行します。 */
    fun repair(data: T, error: SushiEricValidationError): ValidationRepairResult =
        entries.firstOrNull { it.matches(error) }
            ?.repair
            ?.invoke(data, error)
            ?: ValidationRepairResult.Unsupported
}
