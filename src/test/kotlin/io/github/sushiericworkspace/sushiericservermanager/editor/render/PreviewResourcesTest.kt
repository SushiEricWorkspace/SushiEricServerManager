package io.github.sushiericworkspace.sushiericservermanager.editor.render

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PreviewResourcesTest {
    @TempDir lateinit var root: Path

    private fun fixture(): Map<String, String> = PreviewResources.paths.associateWith { path ->
        val bytes = "検査用:$path".toByteArray(Charsets.UTF_8)
        root.resolve(path).also { it.parent.createDirectories() }.writeBytes(bytes)
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test
    fun verifiesBundledOriginalsNoticesAndCoverageWithoutLoadingJavafx() {
        PreviewResources.ensureVerified()
        assertTrue("日本語§".codePoints().allMatch { it in PreviewResources.codepoints })
        assertTrue(0x301 in PreviewResources.codepoints)
        assertFalse(0x1f600 in PreviewResources.codepoints)
        assertEquals(16732, PreviewResources.codepoints.size)
    }

    @Test
    fun rejectsMissingOrModifiedResources() {
        val hashes = fixture()
        URLClassLoader(arrayOf(root.toUri().toURL()), null).use { loader ->
            PreviewResources.verify(loader, hashes)
            val path = root.resolve(PreviewResources.REGULAR)
            path.writeBytes(byteArrayOf(1))
            assertFailsWith<IllegalStateException> { PreviewResources.verify(loader, hashes) }
            path.toFile().delete()
            assertFailsWith<IllegalStateException> { PreviewResources.verify(loader, hashes) }
        }
    }

    @Test
    fun rejectsIncompleteManifest() {
        URLClassLoader(emptyArray(), null).use {
            assertFailsWith<IllegalArgumentException> { PreviewResources.verify(it, emptyMap()) }
        }
    }
}
