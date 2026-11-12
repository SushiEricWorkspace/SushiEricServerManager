package io.github.sushiericworkspace.sushiericservermanager.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProfileIdentityTest {
    private fun profile(name: String) = ServerProfile(name, "host", 22, "user", "/root", "/key")

    @Test
    fun missingIdentitiesAreAssignedOnceWithoutChangingProfileOrder() {
        val config = ServerConfig(listOf(profile("first"), profile("second")))
        val identified = assignProfileIds(config)
        assertEquals(listOf("first", "second"), identified.list.map { it.name })
        assertTrue(identified.list.all { it.profileId.isNotEmpty() })
        assertEquals(2, identified.list.map { it.profileId }.toSet().size)
        assertEquals(identified, assignProfileIds(identified))
    }

    @Test
    fun renamePreservesIdentityAndRecreationGetsAnotherIdentity() {
        val first = assignProfileIds(ServerConfig(listOf(profile("same")))).list.single()
        assertEquals(first.profileId, first.copy(name = "renamed").profileId)
        val recreated = assignProfileIds(ServerConfig(listOf(profile("same")))).list.single()
        assertNotEquals(first.profileId, recreated.profileId)
    }

    @Test
    fun duplicatePersistedIdentitiesAreNotSilentlyReassigned() {
        val first = profile("first").copy(profileId = "duplicate")
        assertThrows(IllegalArgumentException::class.java) {
            assignProfileIds(ServerConfig(listOf(first, first.copy(name = "second"))))
        }
    }
}
