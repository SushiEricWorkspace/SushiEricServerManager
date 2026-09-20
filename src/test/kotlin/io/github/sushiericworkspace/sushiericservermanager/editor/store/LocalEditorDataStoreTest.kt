package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.common.data.core.identity.VanillaItemId
import io.github.sushiericworkspace.common.data.core.identity.VanillaBlockId
import io.github.sushiericworkspace.common.data.drop.model.mutable.MutableDropItemData
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.data.item.model.mutable.MutablePlainTextLoreSection
import io.github.sushiericworkspace.common.data.item.model.HeadSkinSource
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableHeadSkinData
import io.github.sushiericworkspace.common.data.ore.model.mutable.MutableOreBaseData
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalEditorDataStoreTest {
    @Test
    fun `初回作成とCRUDを実行する`() {
        val root = createTempDirectory("offline-store").toFile()
        try {
            val store = LocalEditorDataStore(root)
            assertIs<StoreResult.Success<Unit>>(store.ensureDirectories())
            val item = validItem("sword")

            assertIs<StoreResult.Success<Unit>>(store.save(EditorDataDescriptors.item, "sword", item))
            assertTrue(root.resolve("item_data/stats/sword.yml").isFile)
            assertEquals(
                "Sword",
                assertIs<StoreResult.Success<MutableItemBaseData>>(
                    store.load(EditorDataDescriptors.item, "sword")
                ).value.display.displayName
            )
            assertIs<StoreResult.Success<Unit>>(
                store.rename(EditorDataDescriptors.item, "sword", "renamed_sword")
            )
            assertFalse(root.resolve("item_data/stats/sword.yml").exists())
            assertTrue(root.resolve("item_data/stats/renamed_sword.yml").isFile)
            assertIs<StoreResult.Success<Unit>>(
                store.delete(EditorDataDescriptors.item, "renamed_sword")
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `パストラバーサルを拒否する`() {
        val root = createTempDirectory("offline-store-invalid").toFile()
        try {
            val result = LocalEditorDataStore(root).save(
                EditorDataDescriptors.item,
                "../outside",
                validItem("../outside")
            )
            assertEquals(StoreErrorCode.INVALID_ID, assertIs<StoreResult.Failure>(result).error.code)
            assertFalse(root.parentFile.resolve("outside.yml").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `internalIdがない既存Itemを保存すると生成した値を維持する`() {
        val root = createTempDirectory("offline-store-legacy-item").toFile()
        try {
            val store = LocalEditorDataStore(root)
            val descriptor = EditorDataDescriptors.item
            val item = validItem("legacy_sword")
            assertIs<StoreResult.Success<Unit>>(store.save(descriptor, "legacy_sword", item))

            val file = root.resolve("item_data/stats/legacy_sword.yml")
            file.writeText(
                file.readLines()
                    .filterNot { it.trimStart().startsWith("internal-id:") }
                    .joinToString(System.lineSeparator(), postfix = System.lineSeparator())
            )

            val migrated = assertIs<StoreResult.Success<MutableItemBaseData>>(
                store.load(descriptor, "legacy_sword")
            ).value
            assertIs<StoreResult.Success<Unit>>(store.save(descriptor, "legacy_sword", migrated))
            val reloaded = assertIs<StoreResult.Success<MutableItemBaseData>>(
                store.load(descriptor, "legacy_sword")
            ).value

            assertEquals(migrated.internalId, reloaded.internalId)
            assertTrue(file.readText().lineSequence().any { it.startsWith("internal-id:") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `複製したItemのLoreを保存して再読込できる`() {
        val root = createTempDirectory("offline-store-duplicated-item").toFile()
        try {
            val store = LocalEditorDataStore(root)
            val descriptor = EditorDataDescriptors.item
            val source = validItem("source").apply {
                display.mutableLore.add(mutableListOf(MutablePlainTextLoreSection("複製対象のLore")))
            }
            val duplicate = descriptor.duplicateAsNew(source, "duplicate")

            assertIs<StoreResult.Success<Unit>>(store.save(descriptor, "duplicate", duplicate))
            val reloaded = assertIs<StoreResult.Success<MutableItemBaseData>>(
                store.load(descriptor, "duplicate")
            ).value

            assertEquals(
                "複製対象のLore",
                (reloaded.display.mutableLore.single().single() as MutablePlainTextLoreSection).text
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `ヘッドスキンを保存して再読込できる`() {
        val root = createTempDirectory("offline-store-head-skin").toFile()
        try {
            val store = LocalEditorDataStore(root)
            val descriptor = EditorDataDescriptors.item
            val item = validItem("custom_head").apply {
                itemDetail.vanillaId = VanillaItemId("player_head")
                itemDetail.mutableHeadSkin = MutableHeadSkinData(
                    source = HeadSkinSource.PLAYER_UUID,
                    value = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
                )
            }

            assertIs<StoreResult.Success<Unit>>(store.save(descriptor, item.id, item))
            val reloaded = assertIs<StoreResult.Success<MutableItemBaseData>>(
                store.load(descriptor, item.id)
            ).value

            assertEquals(HeadSkinSource.PLAYER_UUID, reloaded.itemDetail.mutableHeadSkin?.source)
            assertEquals(
                "069a79f4-44e9-4726-a5be-fca90e38aaf5",
                reloaded.itemDetail.mutableHeadSkin?.value
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `鉱石のドロップ参照はアイテム内部IDで検証する`() {
        val root = createTempDirectory("offline-store-ore-reference").toFile()
        try {
            val store = LocalEditorDataStore(root)
            val item = validItem("human_readable_id")
            assertIs<StoreResult.Success<Unit>>(
                store.save(EditorDataDescriptors.item, item.id, item)
            )

            val validOre = MutableOreBaseData(
                id = "valid_ore",
                blockId = VanillaBlockId("iron_ore"),
                mutableDropItems = mutableListOf(MutableDropItemData(itemId = item.internalId))
            )
            assertIs<StoreResult.Success<Unit>>(
                store.save(EditorDataDescriptors.ore, validOre.id, validOre)
            )

            val warningOre = MutableOreBaseData(
                id = "warning_ore",
                blockId = VanillaBlockId("iron_ore"),
                mutableDropItems = mutableListOf(
                    MutableDropItemData(itemId = ItemInternalId(item.id))
                )
            )
            assertIs<StoreResult.Success<Unit>>(
                store.save(EditorDataDescriptors.ore, warningOre.id, warningOre)
            )
            assertFalse(warningOre.completed)

            val invalidOre = MutableOreBaseData(
                id = "invalid_ore",
                blockId = VanillaBlockId("iron_ore"),
                mutableDropItems = mutableListOf(
                    MutableDropItemData(itemId = item.internalId, n = 0)
                )
            )
            val result = store.save(EditorDataDescriptors.ore, invalidOre.id, invalidOre)

            assertEquals(
                StoreErrorCode.VALIDATION_FAILED,
                assertIs<StoreResult.Failure>(result).error.code
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun validItem(id: String): MutableItemBaseData = MutableItemBaseData(id = id).apply {
        display.displayName = "Sword"
    }
}
