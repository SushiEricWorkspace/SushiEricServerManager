package io.github.sushiericworkspace.sushiericservermanager.config

internal enum class ServerProfileDropPosition {
    BEFORE,
    AFTER
}

internal fun reorderServerProfiles(
    profiles: List<ServerProfile>,
    sourceName: String,
    targetName: String,
    position: ServerProfileDropPosition
): List<ServerProfile> {
    val sourceIndex = profiles.indexOfFirst { it.name == sourceName }
    val targetIndex = profiles.indexOfFirst { it.name == targetName }
    if (sourceIndex == -1 || targetIndex == -1 || sourceIndex == targetIndex) {
        return profiles
    }

    val reordered = profiles.toMutableList()
    val source = reordered.removeAt(sourceIndex)
    val remainingTargetIndex = reordered.indexOfFirst { it.name == targetName }
    val insertionIndex = when (position) {
        ServerProfileDropPosition.BEFORE -> remainingTargetIndex
        ServerProfileDropPosition.AFTER -> remainingTargetIndex + 1
    }

    reordered.add(insertionIndex, source)
    return reordered
}

internal fun replaceServerProfilePreservingOrder(
    profiles: List<ServerProfile>,
    originalName: String,
    replacement: ServerProfile
): List<ServerProfile> {
    val targetIndex = profiles.indexOfFirst { it.name == originalName }
    if (targetIndex == -1) {
        return profiles + replacement
    }

    return profiles.toMutableList().apply {
        this[targetIndex] = replacement
    }
}
