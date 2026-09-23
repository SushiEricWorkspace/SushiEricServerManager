package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.common.data.core.identity.VanillaItemId
import io.github.sushiericworkspace.common.data.item.model.HeadSkinSource
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableHeadSkinData
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class HeadSkinValidationVisibilityTest {
    private fun itemWithInvalidHeadSkin(vanillaId: String): MutableItemBaseData =
        MutableItemBaseData(id = "test_item").apply {
            itemDetail.vanillaId = VanillaItemId(vanillaId)
            itemDetail.mutableHeadSkin = MutableHeadSkinData(
                source = HeadSkinSource.PLAYER_UUID,
                value = "not-a-uuid"
            )
        }

    @Test
    fun `ヘッドスキンの検証結果を判別する`() {
        val errors = itemWithInvalidHeadSkin("player_head").itemDetail.validator().validate()

        assertTrue(errors.any(::isHeadSkinValidationError))
        assertFalse(
            errors.filterNot(::isHeadSkinValidationError).any { it.property.name == "headSkin" }
        )
    }

    @Test
    fun `頭アイテムだけヘッドスキンを編集できる`() {
        assertTrue(isHeadSkinEditableVanillaId(VanillaItemId("player_head")))
        assertFalse(isHeadSkinEditableVanillaId(VanillaItemId("stone")))
    }
}
