package io.github.sushiericworkspace.sushiericservermanager.update

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateResultTest {
    private inline fun withDirectory(block: (File) -> Unit) {
        val directory = createTempDirectory("update-result").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `Updaterが書く形式の結果を読み込む`() = withDirectory { directory ->
        val file = directory.resolve("update-result.json")
        file.writeText(
            """
            {
              "success": false,
              "version": "0.3.0",
              "exitCode": 1603,
              "message": "インストーラーが失敗しました（終了コード 1603）。",
              "timestamp": "2026-10-09T12:00:00.0000000Z"
            }
            """.trimIndent()
        )

        val result = UpdateResultStore(file).read()

        assertEquals(
            UpdateResult(false, "0.3.0", 1603, "インストーラーが失敗しました（終了コード 1603）。", "2026-10-09T12:00:00.0000000Z"),
            result
        )
    }

    @Test
    fun `終了コードとメッセージがnullの結果を読み込む`() = withDirectory { directory ->
        val file = directory.resolve("update-result.json")
        file.writeText("""{"success": true, "version": "0.3.0", "exitCode": 0, "message": null, "timestamp": "t"}""")

        val result = UpdateResultStore(file).read()

        assertEquals(true, result?.success)
        assertNull(result?.message)
    }

    @Test
    fun `BOM付きのファイルも読み込める`() = withDirectory { directory ->
        val file = directory.resolve("update-result.json")
        file.writeText("﻿" + """{"success": true, "version": "0.3.0"}""")

        assertEquals("0.3.0", UpdateResultStore(file).read()?.version)
    }

    @Test
    fun `ファイルがない場合と壊れている場合はnullを返す`() = withDirectory { directory ->
        assertNull(UpdateResultStore(directory.resolve("none.json")).read())

        val broken = directory.resolve("broken.json").apply { writeText("{壊れた") }
        assertNull(UpdateResultStore(broken).read())
    }

    @Test
    fun `結果ファイルを削除する`() = withDirectory { directory ->
        val file = directory.resolve("update-result.json").apply { writeText("""{"success": true, "version": "1.0.0"}""") }

        UpdateResultStore(file).delete()

        assertFalse(file.exists())
        UpdateResultStore(file).delete()
    }

    @Test
    fun `実行中の版以下のダウンロード済み成果物だけを削除する`() = withDirectory { directory ->
        listOf("0.2.1", "0.2.2", "0.3.0", "0.10.0").forEach {
            directory.resolve(it).mkdirs()
            directory.resolve(it).resolve("installer.exe").writeText("x")
        }
        directory.resolve("memo").mkdirs()

        val removed = pruneOldUpdates(directory, "0.2.2")

        assertEquals(2, removed)
        assertEquals(setOf("0.3.0", "0.10.0", "memo"), directory.listFiles()!!.map { it.name }.toSet())
    }

    @Test
    fun `updatesディレクトリがない場合は何もしない`() = withDirectory { directory ->
        assertEquals(0, pruneOldUpdates(directory.resolve("none"), "0.2.2"))
        assertTrue(directory.exists())
    }
}
