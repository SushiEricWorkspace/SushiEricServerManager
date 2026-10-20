package io.github.sushiericworkspace.sushiericservermanager.editor.render

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.assertFailsWith

class MinecraftRenderResourcesTest {
    @TempDir lateinit var root: Path

    private fun fixture(): Map<String, String> = MinecraftRenderResources.fontPaths.associateWith { path ->
        val bytes = "検査用:$path".toByteArray(Charsets.UTF_8)
        root.resolve(path).also { it.parent.createDirectories() }.writeBytes(bytes)
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun verifiesEveryResourceBeforeAnyFontInitialization() {
        val hashes = fixture()
        URLClassLoader(arrayOf(root.toUri().toURL()), null).use { MinecraftRenderResources.verify(it, hashes) }
    }

    @Test
    fun rejectsMissingResource() {
        val hashes = fixture()
        root.resolve(MinecraftRenderResources.fontPaths.first()).toFile().delete()
        URLClassLoader(arrayOf(root.toUri().toURL()), null).use {
            assertFailsWith<IllegalStateException> { MinecraftRenderResources.verify(it, hashes) }
        }
    }

    @Test
    fun rejectsModifiedResource() {
        val hashes = fixture()
        root.resolve(MinecraftRenderResources.fontPaths.first()).writeBytes(byteArrayOf(1))
        URLClassLoader(arrayOf(root.toUri().toURL()), null).use {
            assertFailsWith<IllegalStateException> { MinecraftRenderResources.verify(it, hashes) }
        }
    }

    @Test
    fun rejectsIncompleteManifest() {
        URLClassLoader(emptyArray(), null).use {
            assertFailsWith<IllegalArgumentException> { MinecraftRenderResources.verify(it, emptyMap()) }
        }
    }
}
