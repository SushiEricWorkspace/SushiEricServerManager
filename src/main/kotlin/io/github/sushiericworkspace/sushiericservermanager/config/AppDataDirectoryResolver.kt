package io.github.sushiericworkspace.sushiericservermanager.config

import java.nio.file.Path

object AppDataDirectoryResolver {
    fun resolveBaseDirectory(
        osName: String,
        userHome: Path,
        environment: Map<String, String> = System.getenv()
    ): Path {
        val normalizedOsName = osName.lowercase()

        return when {
            normalizedOsName.contains("win") -> {
                environment["LOCALAPPDATA"]
                    ?.takeIf { it.isNotBlank() }
                    ?.let(Path::of)
                    ?: environment["APPDATA"]
                        ?.takeIf { it.isNotBlank() }
                        ?.let(Path::of)
                    ?: userHome.resolve("AppData").resolve("Local")
            }

            normalizedOsName.contains("mac") -> {
                userHome.resolve("Library").resolve("Application Support")
            }

            else -> {
                environment["XDG_CONFIG_HOME"]
                    ?.takeIf { it.isNotBlank() }
                    ?.let(Path::of)
                    ?: userHome.resolve(".config")
            }
        }
    }
}
