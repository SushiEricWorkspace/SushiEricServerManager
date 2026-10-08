package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.common.data.core.identity.VanillaBlockId
import io.github.sushiericworkspace.common.data.ore.model.OreBaseDataView
import io.github.sushiericworkspace.common.data.ore.model.mutable.MutableOreBaseData
import io.github.sushiericworkspace.common.stats.player.SkillType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorDataDescriptorTest {
    @Test
    fun `Itemの初期表示名にはIDが設定される`() {
        val data = EditorDataDescriptors.item.createDefault("new_item")

        assertEquals("new_item", data.display.displayName)
        assertTrue(EditorDataDescriptors.item.validate(data, emptySet()).isEmpty())
    }

    @Test
    fun `Itemの表示名を空にするとバリデーションエラーになる`() {
        val data = EditorDataDescriptors.item.createDefault("new_item").apply {
            display.displayName = " "
        }

        assertTrue(EditorDataDescriptors.item.validate(data, emptySet()).isNotEmpty())
    }

    @Test
    fun `管理対象にはItemとOreとShopが含まれる`() {
        assertEquals(
            listOf(EditorDataDescriptors.item, EditorDataDescriptors.ore, EditorDataDescriptors.shop),
            EditorDataDescriptors.all
        )
    }

    @Test
    fun `鉱石の負の魔力量を検証エラーにする`() {
        val data = MutableOreBaseData(
            id = "ore",
            blockId = VanillaBlockId("minecraft:stone"),
            manaAmount = -0.5
        )

        val errors = EditorDataDescriptors.ore.validate(data, emptySet())

        assertTrue(errors.any {
            it.property.name == OreBaseDataView::manaAmount.name && it.isError
        })
    }

    @Test
    fun `鉱石の魔力量は0を許可する`() {
        val data = MutableOreBaseData(
            id = "ore",
            blockId = VanillaBlockId("minecraft:stone"),
            manaAmount = 0.0
        )

        val errors = EditorDataDescriptors.ore.validate(data, emptySet())

        assertTrue(errors.none { it.property.name == OreBaseDataView::manaAmount.name })
    }

    @Test
    fun `鉱石の負のスキル経験値を検証エラーにする`() {
        val data = MutableOreBaseData(
            id = "ore",
            blockId = VanillaBlockId("minecraft:stone"),
            skillExperienceMap = mutableMapOf(SkillType.MINING to -1.0)
        )

        val errors = EditorDataDescriptors.ore.validate(data, emptySet())

        assertTrue(errors.any {
            it.property.name == OreBaseDataView::skillExperienceMap.name &&
                it.key == SkillType.MINING &&
                it.isError
        })
    }
}
