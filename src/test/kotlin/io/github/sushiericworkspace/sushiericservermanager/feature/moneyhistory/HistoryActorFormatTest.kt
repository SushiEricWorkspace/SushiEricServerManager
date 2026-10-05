package io.github.sushiericworkspace.sushiericservermanager.feature.moneyhistory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryActorFormatTest {
    @Test
    fun `相手と実行者は名前とUUIDを括弧付きで表示する`() {
        assertEquals("SushiEric (uuid)", formatHistoryActor("SushiEric", "uuid"))
    }

    @Test
    fun `片方だけの場合は存在する値を表示する`() {
        assertEquals("SushiEric", formatHistoryActor("SushiEric", null))
        assertEquals("uuid", formatHistoryActor(null, "uuid"))
        assertEquals("uuid", formatHistoryActor("uuid", "uuid"))
    }

    @Test
    fun `空白しかない値は欠損として扱う`() {
        assertNull(formatHistoryActor(" ", null))
        assertNull(formatHistoryActor(null, " "))
        assertEquals("SushiEric", formatHistoryActor(" SushiEric ", ""))
    }
}
