package io.github.sushiericworkspace.sushiericservermanager.communication.managed

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

class ManagedBackupRootsTest {
    @TempDir lateinit var root: File

    @Test
    fun onlyKnownRunAutosavesAndCommonAutosaveAreSelected() {
        val home = root.resolve("home").apply { mkdirs() }
        val first = home.resolve("managed/run-one").apply { mkdirs() }
        val second = home.resolve("managed/run-two").apply { mkdirs() }
        home.resolve("managed/binding.json").writeText("この内容は候補走査で解析しません")
        home.resolve("unrelated/autosave/old").mkdirs()
        assertEquals(setOf(first.resolve("autosave"), second.resolve("autosave"), home.resolve("autosave")),
            legacyManagedBackupRoots(home).toSet())
    }

    @Test
    fun aManagedLinkToAnotherHomeIsNotFollowed() {
        val home = root.resolve("home").apply { mkdirs() }
        val outside = root.resolve("outside").apply { mkdirs() }
        outside.resolve("old/autosave").mkdirs()
        link(home.resolve("managed"), outside)
        assertEquals(listOf(home.resolve("autosave")), legacyManagedBackupRoots(home))
    }

    @Test
    fun aRunLinkToAnotherHomeIsNotSelected() {
        val home = root.resolve("home").apply { mkdirs() }
        val managed = home.resolve("managed").apply { mkdirs() }
        val known = managed.resolve("known").apply { mkdirs() }
        val outside = root.resolve("outside").apply { mkdirs() }
        outside.resolve("autosave/old").mkdirs()
        link(managed.resolve("external"), outside)
        assertEquals(setOf(known.resolve("autosave"), home.resolve("autosave")),
            legacyManagedBackupRoots(home).toSet())
    }

    @Test
    fun autosaveLinksOutsideTheHomeAreNotSelected() {
        val home = root.resolve("home").apply { mkdirs() }
        val run = home.resolve("managed/run").apply { mkdirs() }
        val outside = root.resolve("outside").apply { mkdirs() }
        link(home.resolve("autosave"), outside)
        link(run.resolve("autosave"), outside)
        assertEquals(emptyList(), legacyManagedBackupRoots(home))
    }

    private fun link(link: File, target: File) {
        val created = runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }.isSuccess
        assumeTrue(created, "この環境ではシンボリックリンクを作成できません。")
    }
}
