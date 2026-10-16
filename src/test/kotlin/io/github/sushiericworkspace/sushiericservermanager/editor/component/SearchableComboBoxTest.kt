package io.github.sushiericworkspace.sushiericservermanager.editor.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchableComboBoxTest {

    private val choices = listOf(
        Choice("iron_sword", "internal-sword"),
        Choice("golden_apple", "internal-apple"),
        Choice("iron_ore", "internal-ore")
    )

    @Test
    fun `表示文字列と追加の検索対象文字列で大文字小文字を区別せず絞り込む`() {
        val byDisplayText = resolve("SWORD", choices[0])
        val byInternalId = resolve("INTERNAL-APPLE", choices[0])

        assertEquals(listOf(choices[0]), byDisplayText.choices)
        assertEquals(listOf(choices[1]), byInternalId.choices)
        assertTrue(byDisplayText.hasMatches)
        assertTrue(byInternalId.hasMatches)
    }

    @Test
    fun `現在値が候補に残る場合は選択を維持する`() {
        val result = resolve("iron", choices[2])

        assertEquals(listOf(choices[0], choices[2]), result.choices)
        assertEquals(choices[2], result.selected)
    }

    @Test
    fun `現在値が候補外なら先頭候補を選択する`() {
        val result = resolve("iron", choices[1])

        assertEquals(choices[0], result.selected)
    }

    @Test
    fun `一致候補がなければ全候補へ戻して現在値を維持する`() {
        val result = resolve("missing", choices[1])

        assertEquals(choices, result.choices)
        assertEquals(choices[1], result.selected)
        assertFalse(result.hasMatches)
    }

    @Test
    fun `空の検索文字列では全候補を表示する`() {
        val result = resolve("  ", choices[0])

        assertEquals(choices, result.choices)
        assertEquals(choices[0], result.selected)
        assertTrue(result.hasMatches)
    }

    private fun resolve(query: String, current: Choice?): SearchableSelection<Choice> =
        resolveSearchableSelection(
            allChoices = choices,
            currentValue = current,
            query = query,
            searchTexts = { listOf(it.displayText, it.internalId) }
        )

    private data class Choice(val displayText: String, val internalId: String)
}
