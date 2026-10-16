package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.update.UpdateCheckResult

sealed interface StartupPreparationResult {
    /** 更新がない。そのまま起動できる。 */
    data object Ready : StartupPreparationResult

    /** 更新がある。自動で更新できる場合と、ダウンロードページを案内する場合がある。 */
    data class UpdateFound(val update: UpdateCheckResult.Update) : StartupPreparationResult

    /** 更新を確認できなかった。起動は続ける。 */
    data class Failure(val cause: Throwable) : StartupPreparationResult
}

/**
 * 起動前処理のモード分岐です。オフラインでは更新確認関数を一切呼びません。
 */
class StartupCoordinator(
    private val updateCheck: () -> UpdateCheckResult
) {
    fun prepare(mode: AppMode): StartupPreparationResult {
        if (mode == AppMode.OFFLINE) return StartupPreparationResult.Ready

        return try {
            when (val result = updateCheck()) {
                UpdateCheckResult.UpToDate -> StartupPreparationResult.Ready
                is UpdateCheckResult.Update -> StartupPreparationResult.UpdateFound(result)
            }
        } catch (e: Exception) {
            StartupPreparationResult.Failure(e)
        }
    }
}
