package io.github.sushiericworkspace.sushiericservermanager.update

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateDownloaderTest {
    private val content = ByteArray(200_000) { (it % 251).toByte() }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun update(sha256: String = sha256(content)) = UpdateCheckResult.Automatic(
        version = "0.3.0",
        notes = "",
        releaseUrl = "https://example.com/release",
        asset = UpdateAsset("installer.exe", "https://example.com/installer.exe", content.size.toLong(), sha256)
    )

    private inline fun withDirectory(block: (File) -> Unit) {
        val directory = createTempDirectory("update-download").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `ダウンロードしてSHA-256を検証し成果物を保存する`() = withDirectory { directory ->
        val progress = mutableListOf<Pair<Long, Long>>()

        val file = UpdateDownloader(directory, { ByteArrayInputStream(content) })
            .download(update(), onProgress = { received, total -> progress += received to total })

        assertEquals(directory.resolve("0.3.0").resolve("installer.exe"), file)
        assertContentEquals(content, file.readBytes())
        assertFalse(file.parentFile.resolve("installer.exe.part").exists())
        assertEquals(content.size.toLong() to content.size.toLong(), progress.last())
        assertTrue(progress.all { (received, total) -> received in 1..total })
        assertEquals(progress.map { it.first }.sorted(), progress.map { it.first })
    }

    @Test
    fun `SHA-256が一致しない場合は例外にして保存しない`() = withDirectory { directory ->
        val wrong = "0".repeat(64)

        val error = assertFailsWith<ChecksumMismatchException> {
            UpdateDownloader(directory, { ByteArrayInputStream(content) }).download(update(wrong))
        }

        assertEquals(wrong, error.expected)
        assertEquals(sha256(content), error.actual)
        val version = directory.resolve("0.3.0")
        assertFalse(version.resolve("installer.exe").exists())
        assertFalse(version.resolve("installer.exe.part").exists())
    }

    @Test
    fun `既存の成果物が壊れている場合は再ダウンロードして置き換える`() = withDirectory { directory ->
        val target = directory.resolve("0.3.0").resolve("installer.exe").apply {
            parentFile.mkdirs()
            writeText("壊れた古いファイル")
        }

        val file = UpdateDownloader(directory, { ByteArrayInputStream(content) }).download(update())

        assertEquals(target, file)
        assertContentEquals(content, file.readBytes())
    }

    @Test
    fun `検証済みの成果物が既にある場合は再ダウンロードしない`() = withDirectory { directory ->
        var opened = 0
        val downloader = UpdateDownloader(directory, {
            opened++
            ByteArrayInputStream(content)
        })

        downloader.download(update())
        downloader.download(update())

        assertEquals(1, opened)
    }

    @Test
    fun `中止すると一時ファイルを残さない`() = withDirectory { directory ->
        var reads = 0
        val downloader = UpdateDownloader(directory, { ByteArrayInputStream(content) })

        assertFailsWith<CancellationException> {
            downloader.download(update(), isCancelled = { ++reads > 1 })
        }

        val version = directory.resolve("0.3.0")
        assertFalse(version.resolve("installer.exe").exists())
        assertFalse(version.resolve("installer.exe.part").exists())
    }

    @Test
    fun `通信が途中で失敗した場合は例外にして一時ファイルを残さない`() = withDirectory { directory ->
        val broken = object : InputStream() {
            private var sent = 0
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (sent >= 1000) throw IOException("切断")
                sent += 1000
                return 1000
            }
        }

        assertFailsWith<IOException> { UpdateDownloader(directory, { broken }).download(update()) }

        assertFalse(directory.resolve("0.3.0").resolve("installer.exe.part").exists())
    }
}
