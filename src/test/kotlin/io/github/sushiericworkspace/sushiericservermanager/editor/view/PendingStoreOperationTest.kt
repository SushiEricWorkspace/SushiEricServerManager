package io.github.sushiericworkspace.sushiericservermanager.editor.view

import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.data.ore.model.mutable.MutableOreBaseData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import io.github.sushiericworkspace.sushiericservermanager.editor.service.PendingStoreOperationKind

class PendingStoreOperationTest {
    @Test
    fun `追加と内容変更を保存確認へ表示する`() {
        assertEquals(
            listOf("追加: new_item", "内容変更: new_item"),
            saveChangeSummary(
                dataId = "new_item",
                operation = PendingStoreOperation.Create("new_item"),
                contentChanged = true
            )
        )
    }

    @Test
    fun `ID変更元と変更先を保存確認へ表示する`() {
        assertEquals(
            listOf("ID変更: old_item → renamed_item"),
            saveChangeSummary(
                dataId = "renamed_item",
                operation = PendingStoreOperation.Rename("old_item", "renamed_item"),
                contentChanged = false
            )
        )
    }

    @Test
    fun `削除対象は変更前のIDを保存確認へ表示する`() {
        assertEquals(
            listOf("削除: old_item"),
            saveChangeSummary(
                dataId = "renamed_item",
                operation = PendingStoreOperation.Delete("renamed_item", "old_item"),
                contentChanged = true
            )
        )
    }

    @Test
    fun `削除保留を永続化用レコードと相互変換できる`() {
        val operation = PendingStoreOperation.Delete("renamed_item", "old_item")

        val record = operation.toRecord()

        assertEquals(PendingStoreOperationKind.DELETE, record.kind)
        assertEquals(operation, record.toPendingOperation())
    }

    @Test
    fun `アイテムの保存確認へ項目ごとの変更前後を表示する`() {
        val original = MutableItemBaseData(id = "test_item")
        val current = original.deepCopy().apply {
            display.displayName = "変更後の表示名"
            itemDetail.maxStackSize = 32
        }

        val details = saveChangeDetails("test_item", null, original, current)

        assertTrue("・表示名" in details)
        assertTrue("  変更後: 変更後の表示名" in details)
        assertTrue("・最大スタック数" in details)
        assertTrue("  変更後: 32" in details)
    }

    @Test
    fun `鉱石の保存確認へ追加データの値を表示する`() {
        val ore = MutableOreBaseData(id = "new_ore", hardness = 8.0, requiredTier = 3)

        val details = saveChangeDetails(
            "new_ore",
            PendingStoreOperation.Create("new_ore"),
            ore.deepCopy(),
            ore
        )

        assertTrue("・追加: new_ore" in details)
        assertTrue("・硬度" in details)
        assertTrue("  値: 8.0" in details)
        assertTrue("・要求Tier" in details)
    }
}
