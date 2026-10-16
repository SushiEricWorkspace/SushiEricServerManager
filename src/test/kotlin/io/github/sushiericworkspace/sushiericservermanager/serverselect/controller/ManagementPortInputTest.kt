package io.github.sushiericworkspace.sushiericservermanager.serverselect.controller

import io.github.sushiericworkspace.sushiericservermanager.config.ServerProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Management APIポートの入力値の解釈を検証する。
 */
class ManagementPortInputTest {

    @Test
    fun `空欄は既定値として扱う`() {
        assertEquals(
            ServerProfile.DEFAULT_MANAGEMENT_PORT,
            ManagementPortInput.parse("")
        )
        assertEquals(
            ServerProfile.DEFAULT_MANAGEMENT_PORT,
            ManagementPortInput.parse("   ")
        )
    }

    @Test
    fun `範囲内の数値をそのまま返す`() {
        assertEquals(25582, ManagementPortInput.parse("25582"))
        assertEquals(25582, ManagementPortInput.parse(" 25582 "))
        assertEquals(
            ServerProfile.MANAGEMENT_PORT_RANGE.first,
            ManagementPortInput.parse(ServerProfile.MANAGEMENT_PORT_RANGE.first.toString())
        )
        assertEquals(
            ServerProfile.MANAGEMENT_PORT_RANGE.last,
            ManagementPortInput.parse(ServerProfile.MANAGEMENT_PORT_RANGE.last.toString())
        )
    }

    @Test
    fun `数値でない場合はnullを返す`() {
        assertNull(ManagementPortInput.parse("abc"))
        assertNull(ManagementPortInput.parse("25582a"))
    }

    @Test
    fun `範囲外の場合はnullを返す`() {
        assertNull(
            ManagementPortInput.parse((ServerProfile.MANAGEMENT_PORT_RANGE.first - 1).toString())
        )
        assertNull(
            ManagementPortInput.parse((ServerProfile.MANAGEMENT_PORT_RANGE.last + 1).toString())
        )
    }
}
