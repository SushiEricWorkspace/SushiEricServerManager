package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.common.data.core.identity.VanillaItemId
import io.github.sushiericworkspace.common.data.item.model.HeadSkinSource
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableHeadSkinData
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.sushiericservermanager.editor.validation.ValidationRepairResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ItemHeadSkinEditorTest {
    @Test
    fun `player_headだけヘッドスキンを編集できる`() {
        assertTrue(isHeadSkinEditableVanillaId(VanillaItemId("player_head")))
        assertFalse(isHeadSkinEditableVanillaId(VanillaItemId("zombie_head")))
        assertFalse(isHeadSkinEditableVanillaId(VanillaItemId("stone")))
    }

    @Test
    fun `ソースを変更しても入力値を維持できる`() {
        val skin = MutableHeadSkinData(HeadSkinSource.PLAYER_NAME, "SushiEric")

        val updated = applyHeadSkinEditorValue(
            current = skin,
            source = HeadSkinSource.PLAYER_UUID,
            value = "SushiEric"
        )

        assertEquals(HeadSkinSource.PLAYER_UUID, updated?.source)
        assertEquals("SushiEric", updated?.value)
    }

    @Test
    fun `未指定を選択するとヘッドスキンを解除できる`() {
        val skin = MutableHeadSkinData(HeadSkinSource.PLAYER_NAME, "SushiEric")

        assertNull(applyHeadSkinEditorValue(skin, null, skin.value))
    }

    @Test
    fun `テクスチャ入力から改行を除去する`() {
        assertEquals("abcdef", normalizeHeadSkinInput("ab\r\ncd\nef"))
    }

    @Test
    fun `プレイヤー参照方式に応じた警告を表示する`() {
        assertTrue(headSkinWarning(HeadSkinSource.PLAYER_NAME)?.contains("プレイヤー名") == true)
        assertTrue(headSkinWarning(HeadSkinSource.PLAYER_NAME)?.contains("スキンを変更") == true)
        assertTrue(headSkinWarning(HeadSkinSource.PLAYER_UUID)?.contains("スキンを変更") == true)
        assertNull(headSkinWarning(HeadSkinSource.TEXTURE))
        assertNull(headSkinWarning(null))
    }

    @Test
    fun `Commonのヘッドスキン検証結果を利用できる`() {
        val item = MutableItemBaseData().apply {
            itemDetail.mutableHeadSkin = MutableHeadSkinData(HeadSkinSource.PLAYER_NAME, "")
        }

        assertEquals("値を入力してください。", item.itemDetail.validator().validateHeadSkin().single().message)

        item.itemDetail.mutableHeadSkin?.value = "SushiEric"
        val results = item.itemDetail.validator().validateHeadSkin()
        assertEquals(1, results.size)
        assertTrue(results.single().isWarning)
    }

    @Test
    fun `プレイヤー名警告をテクスチャ値へ変換する`() {
        val item = MutableItemBaseData().apply {
            itemDetail.mutableHeadSkin = MutableHeadSkinData(HeadSkinSource.PLAYER_NAME, "SushiEric")
        }
        val warning = item.validate().single { it.isWarning }
        val registry = createItemValidationRepairRegistry { _, _ ->
            HeadSkinTextureLookupResult.Success("texture-value")
        }

        val result = registry.repair(item, warning)

        assertEquals(ValidationRepairResult.Applied("ヘッドスキンをテクスチャ値へ変換"), result)
        assertEquals(HeadSkinSource.TEXTURE, item.itemDetail.mutableHeadSkin?.source)
        assertEquals("texture-value", item.itemDetail.mutableHeadSkin?.value)
    }

    @Test
    fun `プレイヤー名解決失敗時はヘッドスキンを変更しない`() {
        val item = MutableItemBaseData().apply {
            itemDetail.mutableHeadSkin = MutableHeadSkinData(HeadSkinSource.PLAYER_NAME, "Unknown")
        }
        val warning = item.validate().single { it.isWarning }
        val registry = createItemValidationRepairRegistry { _, _ ->
            HeadSkinTextureLookupResult.Failure("プレイヤーが見つかりません。")
        }

        val result = registry.repair(item, warning)

        assertEquals(ValidationRepairResult.Failed("プレイヤーが見つかりません。"), result)
        assertEquals(HeadSkinSource.PLAYER_NAME, item.itemDetail.mutableHeadSkin?.source)
        assertEquals("Unknown", item.itemDetail.mutableHeadSkin?.value)
    }
}
