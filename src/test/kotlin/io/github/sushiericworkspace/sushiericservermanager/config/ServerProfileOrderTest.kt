package io.github.sushiericworkspace.sushiericservermanager.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ServerProfileOrderTest {

    @Test
    fun `先頭のプロファイルを末尾へ移動する`() {
        val profiles = profiles("a", "b", "c")

        val reordered = reorderServerProfiles(
            profiles,
            sourceName = "a",
            targetName = "c",
            position = ServerProfileDropPosition.AFTER
        )

        assertEquals(listOf("b", "c", "a"), reordered.map(ServerProfile::name))
    }

    @Test
    fun `末尾のプロファイルを先頭へ移動する`() {
        val profiles = profiles("a", "b", "c")

        val reordered = reorderServerProfiles(
            profiles,
            sourceName = "c",
            targetName = "a",
            position = ServerProfileDropPosition.BEFORE
        )

        assertEquals(listOf("c", "a", "b"), reordered.map(ServerProfile::name))
    }

    @Test
    fun `隣接移動を連続して適用する`() {
        val firstMove = reorderServerProfiles(
            profiles("a", "b", "c"),
            sourceName = "a",
            targetName = "b",
            position = ServerProfileDropPosition.AFTER
        )
        val secondMove = reorderServerProfiles(
            firstMove,
            sourceName = "c",
            targetName = "a",
            position = ServerProfileDropPosition.BEFORE
        )

        assertEquals(listOf("b", "c", "a"), secondMove.map(ServerProfile::name))
    }

    @Test
    fun `存在しないプロファイルや同じ位置への移動では元の一覧を返す`() {
        val profiles = profiles("a", "b", "c")

        assertSame(
            profiles,
            reorderServerProfiles(
                profiles,
                sourceName = "missing",
                targetName = "a",
                position = ServerProfileDropPosition.BEFORE
            )
        )
        assertSame(
            profiles,
            reorderServerProfiles(
                profiles,
                sourceName = "a",
                targetName = "a",
                position = ServerProfileDropPosition.AFTER
            )
        )
    }

    @Test
    fun `編集したプロファイルを元の位置で置き換える`() {
        val profiles = profiles("a", "b", "c")
        val replacement = profile("renamed").copy(host = "updated.example.com")

        val replaced = replaceServerProfilePreservingOrder(
            profiles,
            originalName = "b",
            replacement = replacement
        )

        assertEquals(listOf("a", "renamed", "c"), replaced.map(ServerProfile::name))
        assertEquals("updated.example.com", replaced[1].host)
    }

    private fun profiles(vararg names: String): List<ServerProfile> =
        names.map(::profile)

    private fun profile(name: String): ServerProfile =
        ServerProfile(
            name = name,
            host = "$name.example.com",
            port = 22,
            user = "user",
            path = "/server",
            key = "/key"
        )
}
