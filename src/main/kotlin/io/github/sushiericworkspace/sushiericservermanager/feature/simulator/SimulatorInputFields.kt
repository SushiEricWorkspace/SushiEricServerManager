package io.github.sushiericworkspace.sushiericservermanager.feature.simulator

import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane
import javafx.scene.layout.FlowPane
import javafx.scene.control.Tooltip

internal fun numberField(value: Number = 0): TextField = TextField(value.toString()).apply { prefColumnCount = 9 }
internal fun TextField.number(name: String): Double = text.toDoubleOrNull()?.takeIf(Double::isFinite)
    ?: throw IllegalArgumentException("$name は有限の数値を入力してください。")
internal fun TextField.integer(name: String): Int = text.toIntOrNull()
    ?: throw IllegalArgumentException("$name は整数を入力してください。")
internal fun TextField.longValue(name: String): Long = text.toLongOrNull()
    ?: throw IllegalArgumentException("$name は整数を入力してください。")
internal fun TextField.tick(name: String): Long = longValue(name).also {
    require(it >= 0L) { "$name は0以上のtickを入力してください。" }
}
internal fun TextField.currentValue(name: String): Double = number(name).also {
    require(it >= 0.0 && text.toBigDecimalOrNull()?.stripTrailingZeros()?.scale()?.let { scale -> scale <= 3 } == true) {
        "$name は0以上、小数3桁以内の正規化済み現在値を入力してください。"
    }
}
internal fun fieldRow(name: String, vararg fields: Node) = FlowPane(8.0, 6.0).apply {
    children.addAll(Label(name), *fields)
}
internal fun <T : javafx.scene.control.Control> T.explain(text: String): T = apply { tooltip = Tooltip(text) }

/** UIの文字列をそのまま保持し、計算要求作成時にだけ数値へ変換します。範囲補正はCoreへ委譲します。 */
internal class SimulatorNumberGrid<K>(entries: List<K>, private val label: (K) -> String, initial: (K) -> Number) : GridPane() {
    val fields: Map<K, TextField> = entries.associateWith { numberField(initial(it)) }
    init {
        styleClass.add("simulator-fields")
        entries.forEachIndexed { index, key ->
            add(Label(label(key)), (index % 2) * 2, index / 2)
            add(fields.getValue(key), (index % 2) * 2 + 1, index / 2)
        }
    }
    fun values(): Map<K, Double> = fields.mapValues { (key, field) -> field.number(label(key)) }
}

internal fun <T> simulatorConverter(label: (T) -> String) = object : javafx.util.StringConverter<T>() {
    override fun toString(value: T?): String = value?.let(label).orEmpty()
    override fun fromString(text: String?): T? = null
}
