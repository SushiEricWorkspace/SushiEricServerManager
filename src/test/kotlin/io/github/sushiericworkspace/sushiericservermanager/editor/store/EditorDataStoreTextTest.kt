package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.common.path.SushiEricDataDirectory
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 保存先に依存しない生テキストの読み書きを検証します。 */
class EditorDataStoreTextTest {
    private val configPath = SushiEricDataDirectory.Config().getRawPath()

    @Test
    fun `Mod共通設定のパスは基準ディレクトリ直下のconfig_yml`() {
        assertEquals("config.yml", configPath)
    }

    @Test
    fun `ローカルではUTF-8で内容をそのまま読み書きする`() {
        val root = createTempDirectory("mod-config-store").toFile()

        try {
            val store = LocalEditorDataStore(root)
            val text = "# 設定\nmining:\n  speed: 1.5\n"

            assertIs<StoreResult.Success<Unit>>(store.writeText(configPath, text))

            val file = root.resolve("config.yml")

            assertTrue(file.isFile)
            assertEquals(text, file.readText(Charsets.UTF_8))
            assertEquals(text, assertIs<StoreResult.Success<String>>(store.readText(configPath)).value)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `ローカルでファイルがない場合はFILE_NOT_FOUNDを返す`() {
        val root = createTempDirectory("mod-config-store").toFile()

        try {
            val result = LocalEditorDataStore(root).readText(configPath)

            assertEquals(
                StoreErrorCode.FILE_NOT_FOUND,
                assertIs<StoreResult.Failure>(result).error.code
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `基準ディレクトリの外を指す相対パスは扱わない`() {
        val root = createTempDirectory("mod-config-store").toFile()

        try {
            val store = LocalEditorDataStore(root)

            listOf("../config.yml", "/etc/passwd", "item_data/../../config.yml").forEach { path ->
                assertEquals(
                    StoreErrorCode.INVALID_ID,
                    assertIs<StoreResult.Failure>(store.readText(path)).error.code,
                    path
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `メモリストアでも同じ契約で読み書きできる`() {
        val store = InMemoryEditorDataStore()
        val text = "key: value\n"

        assertEquals(
            StoreErrorCode.FILE_NOT_FOUND,
            assertIs<StoreResult.Failure>(store.readText(configPath)).error.code
        )

        assertIs<StoreResult.Success<Unit>>(store.writeText(configPath, text))
        assertEquals(text, assertIs<StoreResult.Success<String>>(store.readText(configPath)).value)
    }
}
