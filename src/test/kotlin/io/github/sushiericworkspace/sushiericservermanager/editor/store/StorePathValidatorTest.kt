package io.github.sushiericworkspace.sushiericservermanager.editor.store

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorePathValidatorTest {
    @Test
    fun `完全IDの各セグメントを検証する`() {
        assertTrue(StorePathValidator.isValidId("test"))
        assertTrue(StorePathValidator.isValidId("combat.sword.test_sword"))
        assertFalse(StorePathValidator.isValidId("combat..test"))
        assertFalse(StorePathValidator.isValidId("combat/Test"))
        assertFalse(StorePathValidator.isValidId("../outside"))
    }

    @Test
    fun `ルートを含むディレクトリを検証する`() {
        assertTrue(StorePathValidator.isValidDirectory(""))
        assertTrue(StorePathValidator.isValidDirectory("combat.sword"))
        assertFalse(StorePathValidator.isValidDirectory("", allowRoot = false))
        assertFalse(StorePathValidator.isValidDirectory("combat..sword"))
    }
}
