package io.github.sushiericworkspace.sushiericservermanager.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 製品名変更にともなう設定ディレクトリの移行を検証する。
 */
class ConfigDirectoryMigrationTest {

    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `旧ディレクトリの内容を新ディレクトリへコピーする`() {
        val legacy = legacyDirectory()

        File(legacy, "profiles.json").writeText("""{"profiles":[]}""")
        File(legacy, "config.json").writeText("""{"theme":"dark"}""")

        val ssh = File(legacy, "ssh")
        ssh.mkdirs()
        File(ssh, "known_hosts").writeText("example.com ssh-ed25519 AAAA")

        val current = currentDirectory()

        assertTrue(
            ConfigDirectoryMigration.migrateIfNeeded(legacy, current)
        )

        assertEquals(
            """{"profiles":[]}""",
            File(current, "profiles.json").readText()
        )

        assertEquals(
            """{"theme":"dark"}""",
            File(current, "config.json").readText()
        )

        assertEquals(
            "example.com ssh-ed25519 AAAA",
            File(current, "ssh${File.separator}known_hosts").readText()
        )
    }

    @Test
    fun `旧ディレクトリを削除しない`() {
        val legacy = legacyDirectory()
        File(legacy, "profiles.json").writeText("keep")

        ConfigDirectoryMigration.migrateIfNeeded(legacy, currentDirectory())

        assertTrue(legacy.isDirectory)
        assertEquals("keep", File(legacy, "profiles.json").readText())
    }

    @Test
    fun `lockはコピーしない`() {
        val legacy = legacyDirectory()
        File(legacy, "lock").writeText("12345")
        File(legacy, "config.json").writeText("{}")

        val current = currentDirectory()

        assertTrue(
            ConfigDirectoryMigration.migrateIfNeeded(legacy, current)
        )

        assertFalse(
            File(current, "lock").exists(),
            "lockが引き継がれています。"
        )

        assertTrue(File(current, "config.json").isFile)
    }

    @Test
    fun `新ディレクトリが既にある場合は何もしない`() {
        val legacy = legacyDirectory()
        File(legacy, "config.json").writeText("legacy")

        val current = currentDirectory()
        current.mkdirs()
        File(current, "config.json").writeText("current")

        assertFalse(
            ConfigDirectoryMigration.migrateIfNeeded(legacy, current)
        )

        assertEquals(
            "current",
            File(current, "config.json").readText()
        )
    }

    @Test
    fun `旧ディレクトリが無い場合は何もしない`() {
        val current = currentDirectory()

        assertFalse(
            ConfigDirectoryMigration.migrateIfNeeded(
                legacyDirectory(create = false),
                current
            )
        )

        assertFalse(current.exists())
    }

    @Test
    fun `設定ディレクトリ名は製品名と一致する`() {
        assertEquals("SushiEricServerManager", FilePath.DIRECTORY_NAME)
        assertEquals(
            "SushiEricDataEditor2",
            ConfigDirectoryMigration.LEGACY_DIRECTORY_NAME
        )
    }

    private fun legacyDirectory(create: Boolean = true): File {
        val directory =
            File(
                temporaryDirectory.toFile(),
                ConfigDirectoryMigration.LEGACY_DIRECTORY_NAME
            )

        if (create) {
            directory.mkdirs()
        }

        return directory
    }

    private fun currentDirectory(): File =
        File(temporaryDirectory.toFile(), FilePath.DIRECTORY_NAME)
}
