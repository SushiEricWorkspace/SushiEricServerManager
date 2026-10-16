package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.update.UpdateAsset
import io.github.sushiericworkspace.sushiericservermanager.update.UpdateCheckResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StartupCoordinatorTest {
    private val update = UpdateCheckResult.Automatic(
        version = "0.3.0",
        notes = "変更内容",
        releaseUrl = "https://example.com/release",
        asset = UpdateAsset("installer.exe", "https://example.com/installer.exe", 10, "0".repeat(64))
    )

    @Test
    fun `オフラインでは更新確認を呼ばない`() {
        var updateChecks = 0
        val coordinator = StartupCoordinator {
            updateChecks++
            UpdateCheckResult.UpToDate
        }

        assertIs<StartupPreparationResult.Ready>(coordinator.prepare(AppMode.OFFLINE))
        assertEquals(0, updateChecks)
    }

    @Test
    fun `オンラインでは更新確認後に準備完了する`() {
        var updateChecks = 0
        val coordinator = StartupCoordinator {
            updateChecks++
            UpdateCheckResult.UpToDate
        }

        assertIs<StartupPreparationResult.Ready>(coordinator.prepare(AppMode.ONLINE))
        assertEquals(1, updateChecks)
    }

    @Test
    fun `更新がある場合は更新の内容を返す`() {
        val result = StartupCoordinator { update }.prepare(AppMode.ONLINE)

        assertEquals(update, assertIs<StartupPreparationResult.UpdateFound>(result).update)
    }

    @Test
    fun `自動更新できない更新も更新として返す`() {
        val manual = UpdateCheckResult.Manual("0.3.0", "", "https://example.com/release", "理由")
        val result = StartupCoordinator { manual }.prepare(AppMode.ONLINE)

        assertEquals(manual, assertIs<StartupPreparationResult.UpdateFound>(result).update)
    }

    @Test
    fun `オンライン更新確認失敗を区別する`() {
        val coordinator = StartupCoordinator {
            error("network")
        }

        assertIs<StartupPreparationResult.Failure>(coordinator.prepare(AppMode.ONLINE))
    }
}
