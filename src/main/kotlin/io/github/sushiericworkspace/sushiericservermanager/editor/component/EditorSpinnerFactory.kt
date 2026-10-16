package io.github.sushiericworkspace.sushiericservermanager.editor.component

import javafx.scene.control.Spinner
import javafx.scene.control.SpinnerValueFactory
import javafx.scene.control.TextFormatter
import javafx.scene.input.KeyCode
import javafx.util.StringConverter
import kotlin.math.pow
import kotlin.math.roundToInt

internal fun normalizeLongSpinnerValue(text: String, min: Long, max: Long): Long =
    text.toLongOrNull()?.coerceIn(min, max) ?: min

internal fun incrementLongSpinnerValue(
    current: Long,
    steps: Int,
    step: Long,
    max: Long
): Long {
    require(step > 0L) { "stepは正数である必要があります" }
    if (steps <= 0 || current >= max) return current.coerceAtMost(max)
    val delta = try {
        Math.multiplyExact(step, steps.toLong())
    } catch (_: ArithmeticException) {
        return max
    }
    return try {
        Math.addExact(current, delta).coerceAtMost(max)
    } catch (_: ArithmeticException) {
        max
    }
}

internal fun decrementLongSpinnerValue(
    current: Long,
    steps: Int,
    step: Long,
    min: Long
): Long {
    require(step > 0L) { "stepは正数である必要があります" }
    if (steps <= 0 || current <= min) return current.coerceAtLeast(min)
    val delta = try {
        Math.multiplyExact(step, steps.toLong())
    } catch (_: ArithmeticException) {
        return min
    }
    return try {
        Math.subtractExact(current, delta).coerceAtLeast(min)
    } catch (_: ArithmeticException) {
        min
    }
}

object EditorSpinnerFactory {

    fun intSpinner(
        initialValue: Int,
        min: Int = 0,
        max: Int = 999,
        step: Int = 1,
        prefWidth: Double? = null,
        onChanged: (Int) -> Unit
    ): Spinner<Int> {
        return Spinner<Int>().apply {
            isEditable = true

            valueFactory = SpinnerValueFactory.IntegerSpinnerValueFactory(
                min,
                max,
                initialValue.coerceIn(min, max),
                step
            )

            prefWidth?.let {
                this.prefWidth = it
            }

            editor.textFormatter = TextFormatter<String> { change ->
                val newText = change.controlNewText

                if (newText.matches(Regex("\\d*"))) {
                    change
                } else {
                    null
                }
            }

            fun commitEditorValue() {
                val fixedValue = editor.text
                    .toIntOrNull()
                    ?.coerceIn(min, max)
                    ?: min

                if (valueFactory.value != fixedValue) {
                    valueFactory.value = fixedValue
                }

                editor.text = fixedValue.toString()
                onChanged(fixedValue)
            }

            editor.focusedProperty().addListener { _, _, focused ->
                if (!focused) {
                    commitEditorValue()
                }
            }

            editor.setOnKeyPressed { event ->
                if (event.code == KeyCode.ENTER) {
                    commitEditorValue()
                    event.consume()
                }
            }

            valueProperty().addListener { _, _, newValue ->
                val fixedValue = (newValue ?: min).coerceIn(min, max)

                if (valueFactory.value != fixedValue) {
                    valueFactory.value = fixedValue
                }

                editor.text = fixedValue.toString()
                onChanged(fixedValue)
            }
        }
    }

    /**
     * [Long]の整数値を編集するスピナーを作成します。
     *
     * JavaFXにはLong専用の標準ValueFactoryがないため、増減時のオーバーフローを防ぐ
     * ValueFactoryと数字だけを許可する入力フィルターを設定します。
     */
    fun longSpinner(
        initialValue: Long,
        min: Long = 0L,
        max: Long = Long.MAX_VALUE,
        step: Long = 1L,
        prefWidth: Double? = null,
        onChanged: (Long) -> Unit
    ): Spinner<Long> {
        require(min <= max) { "minはmax以下である必要があります" }
        require(step > 0L) { "stepは正数である必要があります" }

        return Spinner<Long>().apply {
            isEditable = true

            valueFactory = object : SpinnerValueFactory<Long>() {
                init {
                    converter = object : StringConverter<Long>() {
                        override fun toString(value: Long?): String = (value ?: min).toString()

                        override fun fromString(text: String?): Long =
                            normalizeLongSpinnerValue(text.orEmpty(), min, max)
                    }
                    value = initialValue.coerceIn(min, max)
                }

                override fun decrement(steps: Int) {
                    value = decrementLongSpinnerValue(value ?: min, steps, step, min)
                }

                override fun increment(steps: Int) {
                    value = incrementLongSpinnerValue(value ?: min, steps, step, max)
                }
            }

            prefWidth?.let {
                this.prefWidth = it
            }

            editor.textFormatter = TextFormatter<String> { change ->
                if (change.controlNewText.matches(Regex("\\d*"))) change else null
            }

            fun commitEditorValue() {
                val fixedValue = normalizeLongSpinnerValue(editor.text, min, max)
                if (valueFactory.value != fixedValue) {
                    valueFactory.value = fixedValue
                }
                editor.text = fixedValue.toString()
                onChanged(fixedValue)
            }

            editor.focusedProperty().addListener { _, _, focused ->
                if (!focused) commitEditorValue()
            }

            editor.setOnKeyPressed { event ->
                if (event.code == KeyCode.ENTER) {
                    commitEditorValue()
                    event.consume()
                }
            }

            valueProperty().addListener { _, _, newValue ->
                val fixedValue = (newValue ?: min).coerceIn(min, max)
                if (valueFactory.value != fixedValue) {
                    valueFactory.value = fixedValue
                }
                editor.text = fixedValue.toString()
                onChanged(fixedValue)
            }
        }
    }

    fun doubleSpinner(
        initialValue: Double,
        min: Double = 0.0,
        max: Double = 999.0,
        step: Double = 0.1,
        prefWidth: Double? = null,
        decimalPlaces: Int = 1,
        onChanged: (Double) -> Unit
    ): Spinner<Double> {
        return Spinner<Double>().apply {
            isEditable = true

            valueFactory = SpinnerValueFactory.DoubleSpinnerValueFactory(
                min,
                max,
                initialValue.coerceIn(min, max),
                step
            )

            prefWidth?.let {
                this.prefWidth = it
            }

            editor.textFormatter = TextFormatter<String> { change ->
                val newText = change.controlNewText

                if (newText.matches(Regex("\\d*(\\.\\d*)?"))) {
                    change
                } else {
                    null
                }
            }

            fun fix(value: Double): Double {
                val coerced = value.coerceIn(min, max)
                val scale = 10.0.pow(decimalPlaces.toDouble())

                return (coerced * scale).roundToInt() / scale
            }

            fun format(value: Double): String {
                return "%.${decimalPlaces}f".format(value)
            }

            fun commitEditorValue() {
                val fixedValue = editor.text
                    .toDoubleOrNull()
                    ?.let { fix(it) }
                    ?: min

                if (valueFactory.value != fixedValue) {
                    valueFactory.value = fixedValue
                }

                editor.text = format(fixedValue)
                onChanged(fixedValue)
            }

            editor.focusedProperty().addListener { _, _, focused ->
                if (!focused) {
                    commitEditorValue()
                }
            }

            editor.setOnKeyPressed { event ->
                if (event.code == KeyCode.ENTER) {
                    commitEditorValue()
                    event.consume()
                }
            }

            valueProperty().addListener { _, _, newValue ->
                val fixedValue = fix(newValue ?: min)

                if (valueFactory.value != fixedValue) {
                    valueFactory.value = fixedValue
                }

                editor.text = format(fixedValue)
                onChanged(fixedValue)
            }
        }
    }
}
