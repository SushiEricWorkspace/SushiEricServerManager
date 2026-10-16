package io.github.sushiericworkspace.sushiericservermanager.ui

import javafx.util.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class AppTooltipTest {
    @Test
    fun `ツールチップの表示時間は無期限である`() {
        assertEquals(Duration.INDEFINITE, AppTooltip.displayDuration)
    }
}
