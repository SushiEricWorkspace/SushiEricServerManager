package io.github.sushiericworkspace.sushiericservermanager.editor.main.ore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OreMiningPreviewTest {
    @Test fun `共通計算の切り上げと最低tickを秒に換算する`() {
        assertEquals("必要時間：15tick / 0.75秒（20TPS換算）", miningTimeText("5", "1", "10", "1"))
        assertTrue(miningTimeText("1", "0", "16", "0").contains("2tick / 0.1秒"))
        assertTrue(miningTimeText("0", "0", "10", "0").contains("1tick / 0.05秒"))
    }

    @Test fun `未入力と無効な編集値を算出しない`() {
        listOf("", "-", "-1", "NaN", "Infinity").forEach {
            assertTrue(miningTimeText(it, "0", "10", "0").startsWith("未算出"))
        }
        listOf("", "NaN", "Infinity").forEach {
            assertTrue(miningTimeText("5", "0", it, "0").startsWith("未算出"))
        }
        assertTrue(miningTimeText("5", "-1", "10", "0").startsWith("未算出"))
        assertTrue(miningTimeText("5", "0", "10", "2147483648").startsWith("未算出"))
    }

    @Test fun `速度不足とTier不足は入力エラーと区別する`() {
        listOf("0", "-1").forEach {
            assertTrue(miningTimeText("5", "0", it, "0").startsWith("採掘不可"))
        }
        assertTrue(miningTimeText("5", "2", "10", "1").startsWith("採掘不可"))
        assertTrue(miningTimeText("5", "2", "10", "2").startsWith("必要時間"))
    }
}
