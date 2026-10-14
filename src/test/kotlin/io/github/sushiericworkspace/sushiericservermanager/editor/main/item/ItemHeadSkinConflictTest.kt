package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.HeadSkinSource
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableHeadSkinData
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.MutableItemDetail
import kotlin.test.Test
import kotlin.test.assertEquals

class ItemHeadSkinConflictTest {
    @Test
    fun `通常詳細だけを選ぶとサーバー側のヘッドスキンを維持する`() {
        val server = detail(enchantAura = false, skinValue = "server")
        val current = detail(enchantAura = true, skinValue = "local")

        val merged = mergeItemDetailForConflict(
            server = server,
            current = current,
            keepLocalDetail = true,
            keepLocalHeadSkin = false
        )

        assertEquals(true, merged.enchantAura)
        assertEquals("server", merged.mutableHeadSkin?.value)
    }

    @Test
    fun `ヘッドスキンだけを選ぶとサーバー側の通常詳細を維持する`() {
        val server = detail(enchantAura = false, skinValue = "server")
        val current = detail(enchantAura = true, skinValue = "local")

        val merged = mergeItemDetailForConflict(
            server = server,
            current = current,
            keepLocalDetail = false,
            keepLocalHeadSkin = true
        )

        assertEquals(false, merged.enchantAura)
        assertEquals("local", merged.mutableHeadSkin?.value)
    }

    private fun detail(enchantAura: Boolean, skinValue: String) = MutableItemDetail(
        enchantAura = enchantAura,
        headSkin = MutableHeadSkinData(HeadSkinSource.PLAYER_NAME, skinValue)
    )
}
