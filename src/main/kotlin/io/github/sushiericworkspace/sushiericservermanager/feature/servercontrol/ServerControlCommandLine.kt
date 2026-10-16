package io.github.sushiericworkspace.sushiericservermanager.feature.servercontrol

import io.github.sushiericworkspace.sushiericservermanager.config.RemoteOperatingSystem

/** Server Controlの入力からSSHへ渡す1つのコマンド行を組み立てます。 */
internal object ServerControlCommandLine {

    /** 空行を除いた各行を、前の行が成功した場合だけ続く形へ連結します。 */
    fun combine(command: String): String? =
        command
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(COMMAND_SEPARATOR)
            .takeIf(String::isNotEmpty)

    /** 必要であれば作業ディレクトリへの移動を先頭へ追加します。 */
    fun build(
        command: String,
        workingDirectory: String,
        operatingSystem: RemoteOperatingSystem
    ): String? {
        val combinedCommand = combine(command) ?: return null
        val directory = workingDirectory.trim()

        if (directory.isEmpty()) {
            return combinedCommand
        }

        val changeDirectory =
            when (operatingSystem.family) {
                RemoteOperatingSystem.Family.WINDOWS ->
                    "cd /d \"${directory.replace("\"", "\"\"")}\""

                RemoteOperatingSystem.Family.UNIX_LIKE ->
                    "cd -- '${directory.replace("'", "'\"'\"'")}'"
            }

        return "$changeDirectory$COMMAND_SEPARATOR$combinedCommand"
    }

    private const val COMMAND_SEPARATOR = " && "
}
