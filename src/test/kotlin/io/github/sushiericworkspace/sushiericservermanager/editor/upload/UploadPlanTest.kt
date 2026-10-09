package io.github.sushiericworkspace.sushiericservermanager.editor.upload

import io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadPlanTest {
    private fun key(id: String) = UploadKey(UploadDataCategory.ITEM, id)

    private fun scan(vararg localIds: String, remote: Set<String> = emptySet()) = UploadScanResult.Success(
        entries = localIds.map { LocalUploadEntry(key(it), requiresFormatUpdate = false) },
        remoteIds = mapOf(UploadDataCategory.ITEM to remote)
    )

    @Test
    fun `宛先が空の場合はローカルのディレクトリを使わず名前だけのIDになる`() {
        assertEquals("sword", uploadTargetId("weapons.sword", ""))
        assertEquals("sword", uploadTargetId("sword", ""))
    }

    @Test
    fun `宛先を指定するとローカルのディレクトリを置き換える`() {
        assertEquals("event.sword", uploadTargetId("weapons.sword", "event"))
        assertEquals("event.weapons.sword", uploadTargetId("weapons.sword", "event.weapons"))
        assertEquals("a.b.c.sword", uploadTargetId("sword", "a.b.c"))
    }

    @Test
    fun `宛先ディレクトリの指定は空とPublicIdの形式だけが有効`() {
        assertTrue(isValidUploadDestination(""))
        assertTrue(isValidUploadDestination("event"))
        assertTrue(isValidUploadDestination("event.weapons"))
        assertFalse(isValidUploadDestination("event..weapons"))
        assertFalse(isValidUploadDestination("Event"))
        assertFalse(isValidUploadDestination("../event"))
        assertFalse(isValidUploadDestination("event/weapons"))
    }

    @Test
    fun `サーバーに同じIDがあれば上書き、なければ新規になる`() {
        val plan = planUpload(scan("weapons.sword", "weapons.axe", remote = setOf("event.sword")), "event")

        val byKey = plan.associateBy { it.key.id }
        assertEquals(UploadCandidateState.OVERWRITE, byKey.getValue("weapons.sword").state)
        assertEquals("event.sword", byKey.getValue("weapons.sword").targetId)
        assertEquals(UploadCandidateState.NEW, byKey.getValue("weapons.axe").state)
        assertEquals("event.axe", byKey.getValue("weapons.axe").targetId)
        assertTrue(plan.all { it.selectable })
    }

    @Test
    fun `別のローカルデータが同じ宛先IDになる場合はどちらも選択できない`() {
        val plan = planUpload(scan("weapons.sword", "tools.sword", "weapons.axe"), "event")

        val byKey = plan.associateBy { it.key.id }
        assertEquals(UploadCandidateState.UNAVAILABLE, byKey.getValue("weapons.sword").state)
        assertEquals(UploadCandidateState.UNAVAILABLE, byKey.getValue("tools.sword").state)
        assertEquals(StoreErrorCode.ALREADY_EXISTS, byKey.getValue("tools.sword").error?.code)
        assertEquals(UploadCandidateState.NEW, byKey.getValue("weapons.axe").state)
    }

    @Test
    fun `宛先が違えば同じ名前でも衝突しない`() {
        val plan = planUpload(scan("weapons.sword", "tools.sword"), "")

        // 宛先が空だとどちらもルート直下のswordになる。
        assertTrue(plan.none { it.selectable })
        assertTrue(planUpload(scan("weapons.sword"), "").single().selectable)
    }

    @Test
    fun `宛先の指定が不正な場合はすべて選択できない`() {
        val plan = planUpload(scan("weapons.sword", "weapons.axe"), "Event..x")

        assertTrue(plan.none { it.selectable })
        assertEquals(StoreErrorCode.INVALID_ID, plan.first().error?.code)
    }

    @Test
    fun `ローカルのデータを読めない場合は理由を保って選択できない`() {
        val error = io.github.sushiericworkspace.sushiericservermanager.editor.store.StoreError(
            StoreErrorCode.IO_ERROR
        )
        val broken = UploadScanResult.Success(
            entries = listOf(LocalUploadEntry(key("weapons.sword"), requiresFormatUpdate = false, loadError = error)),
            remoteIds = mapOf(UploadDataCategory.ITEM to emptySet())
        )

        val candidate = planUpload(broken, "event").single()
        assertEquals(UploadCandidateState.UNAVAILABLE, candidate.state)
        assertEquals(error, candidate.error)
    }

    @Test
    fun `候補は種別とローカルIDの順に並ぶ`() {
        val plan = planUpload(scan("b.item", "a.item", "c.item"), "")
        assertEquals(listOf("a.item", "b.item", "c.item"), plan.map { it.key.id })
    }

    @Test
    fun `問題のない候補にはエラーがない`() {
        assertNull(planUpload(scan("weapons.sword"), "event").single().error)
        assertNotNull(planUpload(scan("weapons.sword"), "Bad").single().error)
    }
}
