package io.github.sushiericworkspace.sushiericservermanager.editor.component

import javafx.scene.control.ComboBox
import javafx.scene.control.ListCell
import javafx.scene.control.TextField
import javafx.scene.layout.VBox
import javafx.util.Callback
import javafx.util.StringConverter

internal data class SearchableSelection<T>(
    val choices: List<T>,
    val selected: T?,
    val hasMatches: Boolean
)

internal fun <T> resolveSearchableSelection(
    allChoices: List<T>,
    currentValue: T?,
    query: String,
    searchTexts: (T) -> Iterable<String>
): SearchableSelection<T> {
    val normalizedQuery = query.trim()
    val matchingChoices = if (normalizedQuery.isEmpty()) {
        allChoices
    } else {
        allChoices.filter { choice ->
            searchTexts(choice).any { it.contains(normalizedQuery, ignoreCase = true) }
        }
    }
    val displayChoices = matchingChoices.ifEmpty { allChoices }
    val selected = currentValue?.takeIf { it in displayChoices } ?: displayChoices.firstOrNull()
    return SearchableSelection(
        choices = displayChoices,
        selected = selected,
        hasMatches = normalizedQuery.isEmpty() || matchingChoices.isNotEmpty()
    )
}

/**
 * 検索欄と直接入力不可の選択欄を組み合わせた共通コンポーネントです。
 *
 * 現在の選択が検索結果に残る場合は維持し、候補外になった場合は先頭候補を選択します。
 * 一致候補がない場合は全候補を再表示し、選択中の値を維持します。
 */
internal class SearchableComboBox<T>(
    allChoices: List<T>,
    initialValue: T?,
    promptText: String,
    private val displayText: (T) -> String,
    private val searchTexts: (T) -> Iterable<String> = { listOf(displayText(it)) },
    private val onSelected: (T) -> Unit,
    private val onSearchMatchChanged: (Boolean) -> Unit = {}
) : VBox(6.0) {
    val searchField: TextField = TextField().apply {
        this.promptText = promptText
        maxWidth = Double.MAX_VALUE
    }
    val comboBox: ComboBox<T> = ComboBox<T>().apply {
        isEditable = false
        maxWidth = Double.MAX_VALUE
        converter = object : StringConverter<T>() {
            override fun toString(value: T?): String = value?.let(displayText).orEmpty()

            override fun fromString(text: String?): T? =
                allChoices.firstOrNull { displayText(it) == text }
        }
        cellFactory = Callback {
            object : ListCell<T>() {
                override fun updateItem(item: T?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else displayText(item)
                }
            }
        }
    }

    private val choices = allChoices.toList()
    private var updating = false
    private var selectedValue: T?

    init {
        val initialSelection = resolveSearchableSelection(choices, initialValue, "", searchTexts)
        selectedValue = initialSelection.selected
        comboBox.items.setAll(initialSelection.choices)
        comboBox.value = initialSelection.selected

        comboBox.valueProperty().addListener { _, _, selected ->
            if (updating || selected == null) return@addListener
            selectedValue = selected
            onSelected(selected)
        }
        searchField.textProperty().addListener { _, _, query ->
            applySearch(query)
        }
        children.addAll(searchField, comboBox)
        maxWidth = Double.MAX_VALUE
    }

    private fun applySearch(query: String) {
        val result = resolveSearchableSelection(choices, selectedValue, query, searchTexts)
        val selectionChanged = result.selected != selectedValue
        updating = true
        try {
            comboBox.items.setAll(result.choices)
            comboBox.value = result.selected
        } finally {
            updating = false
        }
        selectedValue = result.selected
        if (selectionChanged) result.selected?.let(onSelected)
        onSearchMatchChanged(result.hasMatches)
    }
}
