package io.github.sushiericworkspace.sushiericservermanager.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {
    private val hash = "a".repeat(64)

    private fun release(
        tag: String = "v0.3.0",
        assets: List<GitHubReleaseAsset> = listOf(
            GitHubReleaseAsset(
                "SushiEricServerManager-0.3.0-Windows-Installer.exe", "https://example.com/win.exe", 100, "sha256:$hash"
            ),
            GitHubReleaseAsset(
                "SushiEricServerManager-0.3.0-macOS-Installer.dmg", "https://example.com/mac.dmg", 200, "sha256:$hash"
            )
        )
    ) = GitHubRelease(tag, "https://example.com/release", "  変更内容  ", assets)

    private fun checker(
        release: GitHubRelease,
        os: String = "Windows 11",
        current: String = "0.2.2"
    ) = UpdateChecker({ release }, current, os)

    @Test
    fun `Windowsでは対応する成果物とSHA-256を選ぶ`() {
        val result = assertIs<UpdateCheckResult.Automatic>(checker(release()).check())

        assertEquals("0.3.0", result.version)
        assertEquals("変更内容", result.notes)
        assertEquals("https://example.com/release", result.releaseUrl)
        assertEquals(UpdateAsset("SushiEricServerManager-0.3.0-Windows-Installer.exe", "https://example.com/win.exe", 100, hash), result.asset)
    }

    @Test
    fun `macOSではdmgを選ぶ`() {
        val result = assertIs<UpdateCheckResult.Automatic>(checker(release(), os = "Mac OS X").check())

        assertEquals("SushiEricServerManager-0.3.0-macOS-Installer.dmg", result.asset.name)
    }

    @Test
    fun `最新版または新しい版の場合は更新なし`() {
        assertEquals(UpdateCheckResult.UpToDate, checker(release(), current = "0.3.0").check())
        assertEquals(UpdateCheckResult.UpToDate, checker(release(), current = "0.3.1").check())
        assertEquals(UpdateCheckResult.UpToDate, checker(release(), current = "1.0.0").check())
    }

    @Test
    fun `対応する成果物がない場合は手動での更新になる`() {
        val result = assertIs<UpdateCheckResult.Manual>(checker(release(assets = emptyList())).check())

        assertEquals("0.3.0", result.version)
        assertTrue("SushiEricServerManager-0.3.0-Windows-Installer.exe" in result.reason)
    }

    @Test
    fun `digestがない場合は手動での更新になる`() {
        val noDigest = release(
            assets = listOf(
                GitHubReleaseAsset("SushiEricServerManager-0.3.0-Windows-Installer.exe", "https://example.com/win.exe", 100, null)
            )
        )

        assertIs<UpdateCheckResult.Manual>(checker(noDigest).check())
    }

    @Test
    fun `digestの形式が不正な場合は手動での更新になる`() {
        listOf("sha1:$hash", "sha256:xyz", "sha256:${"a".repeat(63)}", hash).forEach { digest ->
            val bad = release(
                assets = listOf(
                    GitHubReleaseAsset("SushiEricServerManager-0.3.0-Windows-Installer.exe", "https://example.com/win.exe", 100, digest)
                )
            )
            assertIs<UpdateCheckResult.Manual>(checker(bad).check(), digest)
        }
    }

    @Test
    fun `自動更新に対応しないOSでは手動での更新になる`() {
        assertIs<UpdateCheckResult.Manual>(checker(release(), os = "Linux").check())
    }

    @Test
    fun `タグがvX_Y_Z形式でない場合は例外になる`() {
        assertFailsWith<IllegalArgumentException> { checker(release(tag = "latest")).check() }
        assertFailsWith<IllegalArgumentException> { checker(release(tag = "v1.2")).check() }
    }

    @Test
    fun `取得に失敗した場合は例外をそのまま伝える`() {
        val checker = UpdateChecker({ error("network") }, "0.2.2", "Windows 11")

        assertFailsWith<IllegalStateException> { checker.check() }
    }

    @Test
    fun `版の比較は数値で行う`() {
        assertTrue(isNewerVersion("0.10.0", "0.9.9"))
        assertTrue(isNewerVersion("1.0.0", "0.99.99"))
        assertTrue(!isNewerVersion("0.2.2", "0.2.2"))
        assertTrue(!isNewerVersion("0.2.1", "0.2.2"))
    }

    @Test
    fun `digestから小文字の16進数を取り出す`() {
        assertEquals(hash, sha256Of("sha256:${hash.uppercase()}"))
        assertNull(sha256Of(null))
        assertNull(sha256Of(""))
    }
}
