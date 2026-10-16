package io.github.sushiericworkspace.sushiericservermanager.editor.offline

import io.github.sushiericworkspace.sushiericservermanager.update.AppVersion
import kotlinx.serialization.Serializable

object OfflineFormatVersion {
    const val CURRENT_WORKSPACE_FORMAT_VERSION = 1
    const val CURRENT_DATA_FORMAT_VERSION = 1
}

@Serializable
data class OfflineWorkspaceManifest(
    val workspaceFormatVersion: Int = OfflineFormatVersion.CURRENT_WORKSPACE_FORMAT_VERSION,
    val dataFormatVersion: Int = OfflineFormatVersion.CURRENT_DATA_FORMAT_VERSION,
    val appVersion: String = AppVersion.CURRENT,
    val files: List<OfflineManifestFile> = emptyList()
)

@Serializable
data class OfflineManifestFile(
    val relativePath: String,
    val dataFormatVersion: Int
)
