package io.github.sushiericworkspace.sushiericservermanager.editor.merge

import io.github.sushiericworkspace.common.data.core.identity.VanillaBlockId
import io.github.sushiericworkspace.common.data.core.identity.VanillaItemId
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.data.item.model.HeadSkinSource
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableHeadSkinData
import io.github.sushiericworkspace.common.data.item.model.mutable.MutablePlainTextLoreSection
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableCustomComponentLoreSection
import io.github.sushiericworkspace.common.data.item.model.mutable.detail.MutableAxeData
import io.github.sushiericworkspace.common.data.item.model.mutable.detail.MutableShortSwordData
import io.github.sushiericworkspace.common.data.item.model.mutable.detail.MutableSwordData
import io.github.sushiericworkspace.common.stats.player.StatsType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConflictValueFormatterTest {
    @Test
    fun `種別固有データはどの種別かが分かる文字列にする`() {
        val base = MutableItemBaseData(id = "sword").apply { itemDetail.content = MutableSwordData() }
        val local = base.deepCopy().apply { itemDetail.content = MutableAxeData() }
        val remote = base.deepCopy().apply { itemDetail.content = MutableShortSwordData() }

        val conflict = ItemDataMerger.merge(base, local, remote)
            .conflicts
            .single { it.path == DataFields.detailContent }

        assertEquals("剣", ConflictValueFormatter.format(conflict.baseValue))
        assertEquals("斧", ConflictValueFormatter.format(conflict.localValue))
        assertEquals("短剣", ConflictValueFormatter.format(conflict.remoteValue))
    }

    @Test
    fun `Mapから削除された競合値はなしと表示する`() {
        val stat = StatsType.PHYSICS_DAMAGE
        val base = MutableItemBaseData(id = "sword").apply { stats[stat] = 10.0 }
        val local = base.deepCopy().apply { stats.remove(stat) }
        val remote = base.deepCopy().apply { stats[stat] = 20.0 }

        val conflict = ItemDataMerger.merge(base, local, remote).conflicts.single()

        assertEquals("10.0", ConflictValueFormatter.format(conflict.baseValue))
        assertEquals("なし", ConflictValueFormatter.format(conflict.localValue))
        assertEquals("20.0", ConflictValueFormatter.format(conflict.remoteValue))
    }

    @Test
    fun `Lore行はセクションの表示内容を含む文字列にする`() {
        val base = MutableItemBaseData(id = "sword").apply {
            display.mutableLore.add(mutableListOf(MutablePlainTextLoreSection(text = "base")))
        }
        val local = base.deepCopy().apply {
            display.mutableLore[0] = mutableListOf(MutablePlainTextLoreSection(text = "local"))
        }
        val remote = base.deepCopy().apply {
            display.mutableLore[0] = mutableListOf(MutablePlainTextLoreSection(text = "remote"))
        }

        val conflict = ItemDataMerger.merge(base, local, remote).conflicts.single()

        assertTrue(ConflictValueFormatter.format(conflict.baseValue).contains("base"))
        assertTrue(ConflictValueFormatter.format(conflict.localValue).contains("local"))
        assertTrue(ConflictValueFormatter.format(conflict.remoteValue).contains("remote"))
        assertEquals("Lore / 1行目", conflict.displayName)
    }

    @Test
    fun `Loreの装飾タグを除いて表示する`() {
        val section = MutableCustomComponentLoreSection(
            component = Component.text("赤い剣", NamedTextColor.RED)
        )

        assertEquals("赤い剣", ConflictValueFormatter.format(section))
    }

    @Test
    fun `値なしと空文字を区別して表示する`() {
        assertEquals("なし", ConflictValueFormatter.format(null))
        assertEquals("（空）", ConflictValueFormatter.format(""))
        assertEquals("（空）", ConflictValueFormatter.format(emptyList<String>()))
        assertEquals("Sword", ConflictValueFormatter.format("Sword"))
    }

    @Test
    fun `ヘッドスキンはソースと値を表示する`() {
        val headSkin = MutableHeadSkinData(
            source = HeadSkinSource.PLAYER_NAME,
            value = "SushiEric"
        )

        assertEquals("PLAYER_NAME: SushiEric", ConflictValueFormatter.format(headSkin))
    }

    @Test
    fun `用途別IDはUI表示用の値だけを表示する`() {
        assertEquals("minecraft:stone", ConflictValueFormatter.format(VanillaItemId("minecraft:stone")))
        assertEquals("minecraft:iron_ore", ConflictValueFormatter.format(VanillaBlockId("minecraft:iron_ore")))
        assertEquals("item-internal-id", ConflictValueFormatter.format(ItemInternalId("item-internal-id")))
    }
}
