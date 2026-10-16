package io.github.sushiericworkspace.sushiericservermanager.util

import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.TextFormatter

/**
 * 数値入力用の [Spinner] を生成するファクトリです。
 *
 * `TextField` ではなく `Spinner` を使いながら、入力欄に入力できる文字も制限します。
 *
 * このファクトリでは、以下のような数値入力欄を作成できます。
 *
 * - [Double] 用の入力欄
 * - [Int] 用の入力欄
 * - 負数の入力可否
 * - `+` 記号の入力可否
 * - 最小値、最大値、増減幅の指定
 * - getter / setter を使った任意のプロパティやMap値への反映
 *
 * 例:
 *
 * ```kotlin
 * val spinner = NumericSpinnerFactory.doubleSpinner(
 *     getter = { selectData.entityData.stats[type] ?: type.default },
 *     setter = { value -> selectData.entityData.stats[type] = value },
 *     min = type.min,
 *     max = type.max,
 *     step = 1.0,
 *     allowNegative = type.min < 0.0
 * )
 * ```
 */
object NumericSpinnerFactory {

    /**
     * [Double] 用の数値入力 [Spinner] を生成します。
     *
     * 入力欄には数値として成立する可能性のある文字だけを入力できます。
     * 例えば、負数を許可している場合は `-`、小数を許可するために `.` も入力可能です。
     *
     * 入力値が変更されると、[setter] が呼び出されます。
     * そのため、通常のプロパティだけでなく、`MutableMap` の値などにも反映できます。
     *
     * フォーカスが外れた時点で、空欄や `-`、`.` など数値として確定できない入力は、
     * [getter] から取得した現在値へ戻されます。
     *
     * @param getter 現在の値を取得する関数。
     * @param setter 値が変更されたときに呼び出される関数。
     * @param min 入力可能な最小値。
     * @param max 入力可能な最大値。
     * @param step Spinnerの上下ボタンで増減する値。
     * @param allowNegative `true` の場合、`-` の入力を許可します。
     * @param allowPlus `true` の場合、`+` の入力を許可します。
     * @param width Spinnerの推奨幅。
     * @return 入力制限付きの [Spinner<Double>]。
     */
    fun doubleSpinner(
        getter: () -> Double,
        setter: (Double) -> Unit,
        min: Double = -Double.MAX_VALUE,
        max: Double = Double.MAX_VALUE,
        step: Double = 0.1,
        allowNegative: Boolean = true,
        allowPlus: Boolean = false,
        width: Double = 100.0
    ): Spinner<Double> {
        val spinner = Spinner<Double>().apply {
            isEditable = true
            prefWidth = width

            valueFactory = SpinnerValueFactory.DoubleSpinnerValueFactory(
                min,
                max,
                getter().coerceIn(min, max),
                step
            )
        }

        val regex = buildDoubleRegex(
            allowNegative = allowNegative,
            allowPlus = allowPlus
        )

        spinner.editor.textFormatter = TextFormatter<String> { change ->
            val newText = change.controlNewText

            if (newText.matches(regex)) {
                change
            } else {
                null
            }
        }

        spinner.valueProperty().addListener { _, _, newValue ->
            if (newValue != null) {
                setter(newValue.toDouble())
            }
        }

        spinner.editor.focusedProperty().addListener { _, _, focused ->
            if (!focused) {
                val parsed = spinner.editor.text.toDoubleOrNull()

                if (parsed == null) {
                    val currentValue = getter().coerceIn(min, max)
                    spinner.valueFactory.value = currentValue
                    spinner.editor.text = currentValue.toString()
                } else {
                    val value = parsed.coerceIn(min, max)
                    spinner.valueFactory.value = value
                    setter(value)
                }
            }
        }

        return spinner
    }

    /**
     * [Int] 用の数値入力 [Spinner] を生成します。
     *
     * 入力欄には整数として成立する可能性のある文字だけを入力できます。
     * 小数点は入力できません。
     *
     * 入力値が変更されると、[setter] が呼び出されます。
     * そのため、通常のプロパティだけでなく、`MutableMap` の値などにも反映できます。
     *
     * フォーカスが外れた時点で、空欄や `-`、`+` など整数として確定できない入力は、
     * [getter] から取得した現在値へ戻されます。
     *
     * @param getter 現在の値を取得する関数。
     * @param setter 値が変更されたときに呼び出される関数。
     * @param min 入力可能な最小値。
     * @param max 入力可能な最大値。
     * @param step Spinnerの上下ボタンで増減する値。
     * @param allowNegative `true` の場合、`-` の入力を許可します。
     * @param allowPlus `true` の場合、`+` の入力を許可します。
     * @param width Spinnerの推奨幅。
     * @return 入力制限付きの [Spinner<Int>]。
     */
    fun intSpinner(
        getter: () -> Int,
        setter: (Int) -> Unit,
        min: Int = Int.MIN_VALUE,
        max: Int = Int.MAX_VALUE,
        step: Int = 1,
        allowNegative: Boolean = true,
        allowPlus: Boolean = false,
        width: Double = 90.0
    ): Spinner<Int> {
        val spinner = Spinner<Int>().apply {
            isEditable = true
            prefWidth = width

            valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(
                min,
                max,
                getter().coerceIn(min, max),
                step
            )
        }

        val regex = buildIntRegex(
            allowNegative = allowNegative,
            allowPlus = allowPlus
        )

        spinner.editor.textFormatter = TextFormatter<String> { change ->
            val newText = change.controlNewText

            if (newText.matches(regex)) {
                change
            } else {
                null
            }
        }

        spinner.valueProperty().addListener { _, _, newValue ->
            if (newValue != null) {
                setter(newValue.toInt())
            }
        }

        spinner.editor.focusedProperty().addListener { _, _, focused ->
            if (!focused) {
                val parsed = spinner.editor.text.toIntOrNull()

                if (parsed == null) {
                    val currentValue = getter().coerceIn(min, max)
                    spinner.valueFactory.value = currentValue
                    spinner.editor.text = currentValue.toString()
                } else {
                    val value = parsed.coerceIn(min, max)
                    spinner.valueFactory.value = value
                    setter(value)
                }
            }
        }

        return spinner
    }

    /**
     * 整数入力用の正規表現を生成します。
     *
     * @param allowNegative `true` の場合、先頭の `-` を許可します。
     * @param allowPlus `true` の場合、先頭の `+` を許可します。
     * @return 整数入力を判定する正規表現。
     */
    private fun buildIntRegex(
        allowNegative: Boolean,
        allowPlus: Boolean
    ): Regex {
        val sign = when {
            allowNegative && allowPlus -> "[+-]?"
            allowNegative -> "-?"
            allowPlus -> "\\+?"
            else -> ""
        }

        return Regex("$sign\\d*")
    }

    /**
     * 小数入力用の正規表現を生成します。
     *
     * @param allowNegative `true` の場合、先頭の `-` を許可します。
     * @param allowPlus `true` の場合、先頭の `+` を許可します。
     * @return 小数入力を判定する正規表現。
     */
    private fun buildDoubleRegex(
        allowNegative: Boolean,
        allowPlus: Boolean
    ): Regex {
        val sign = when {
            allowNegative && allowPlus -> "[+-]?"
            allowNegative -> "-?"
            allowPlus -> "\\+?"
            else -> ""
        }

        return Regex("$sign\\d*(\\.\\d*)?")
    }
}