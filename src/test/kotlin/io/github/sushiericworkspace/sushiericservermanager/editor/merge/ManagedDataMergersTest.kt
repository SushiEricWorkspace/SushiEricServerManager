package io.github.sushiericworkspace.sushiericservermanager.editor.merge

import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.data.item.model.HeadSkinSource
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableHeadSkinData
import io.github.sushiericworkspace.common.data.item.model.ItemStatMultiplier
import io.github.sushiericworkspace.common.data.item.model.mutable.detail.MutablePickaxeData
import io.github.sushiericworkspace.common.data.ore.model.mutable.MutableOreBaseData
import io.github.sushiericworkspace.common.stats.player.StatsPartTarget
import io.github.sushiericworkspace.common.stats.player.StatsType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ManagedDataMergersTest {
    @Test
    fun `異なるフィールドの変更は自動マージする`() {
        val base = MutableItemBaseData(id = "sword").apply {
            display.displayName = "Sword"
            stats[StatsType.PHYSICS_DAMAGE] = 10.0
        }
        val local = base.deepCopy().apply {
            stats[StatsType.PHYSICS_DAMAGE] = 15.0
        }
        val remote = base.deepCopy().apply {
            display.displayName = "Long Sword"
        }

        val result = ItemDataMerger.merge(base, local, remote)

        assertTrue(result.conflicts.isEmpty())
        assertEquals(base.internalId, result.merged.internalId)
        assertEquals("Long Sword", result.merged.display.displayName)
        assertEquals(15.0, result.merged.stats[StatsType.PHYSICS_DAMAGE])
    }

    @Test
    fun `ステータス倍率のローカル変更は保存時のマージで維持される`() {
        val base = MutableItemBaseData(id = "sword").apply {
            stats[StatsType.PHYSICS_DAMAGE] = 10.0
        }
        val local = base.deepCopy().apply {
            statMultipliers[StatsType.PHYSICS_DAMAGE] = mutableListOf(
                ItemStatMultiplier(StatsPartTarget.Self, 1.5),
                ItemStatMultiplier(StatsPartTarget.All, 1.1)
            )
        }
        val remote = base.deepCopy()

        val result = ItemDataMerger.merge(base, local, remote)

        assertTrue(result.conflicts.isEmpty())
        assertEquals<List<ItemStatMultiplier>?>(
            listOf(
                ItemStatMultiplier(StatsPartTarget.Self, 1.5),
                ItemStatMultiplier(StatsPartTarget.All, 1.1)
            ),
            result.merged.statMultipliers[StatsType.PHYSICS_DAMAGE]
        )

        /* マージ結果のリストはローカルと共有しない */
        local.statMultipliers.getValue(StatsType.PHYSICS_DAMAGE).clear()
        assertEquals(2, result.merged.statMultipliers.getValue(StatsType.PHYSICS_DAMAGE).size)
    }

    @Test
    fun `ステータス倍率のローカル削除も保存時のマージで維持される`() {
        val base = MutableItemBaseData(id = "sword").apply {
            statMultipliers[StatsType.PHYSICS_DAMAGE] = mutableListOf(ItemStatMultiplier(StatsPartTarget.Self, 1.5))
        }
        val local = base.deepCopy().apply {
            statMultipliers.remove(StatsType.PHYSICS_DAMAGE)
        }
        val remote = base.deepCopy()

        val result = ItemDataMerger.merge(base, local, remote)

        assertTrue(result.conflicts.isEmpty())
        assertTrue(result.merged.statMultipliers.isEmpty())
    }

    @Test
    fun `同じステータス倍率の異なる変更は競合にする`() {
        val base = MutableItemBaseData(id = "sword")
        val local = base.deepCopy().apply {
            statMultipliers[StatsType.PHYSICS_DAMAGE] = mutableListOf(ItemStatMultiplier(StatsPartTarget.Self, 1.5))
        }
        val remote = base.deepCopy().apply {
            statMultipliers[StatsType.PHYSICS_DAMAGE] = mutableListOf(ItemStatMultiplier(StatsPartTarget.All, 2.0))
        }

        val result = ItemDataMerger.merge(base, local, remote)

        assertEquals(1, result.conflicts.size)
        assertEquals(
            DataFields.statMultipliers.key(StatsType.PHYSICS_DAMAGE, StatsType.PHYSICS_DAMAGE.display),
            result.conflicts.single().path
        )
    }

    @Test
    fun `同じフィールドの異なる変更だけを競合にする`() {
        val base = MutableItemBaseData(id = "sword").apply { display.displayName = "Sword" }
        val local = base.deepCopy().apply { display.displayName = "Local Sword" }
        val remote = base.deepCopy().apply { display.displayName = "Remote Sword" }

        val result = ItemDataMerger.merge(base, local, remote)

        assertEquals(listOf(DataFields.displayName), result.conflicts.map { it.path })
        assertEquals("Remote Sword", result.merged.display.displayName)
        assertEquals(
            "Local Sword",
            result.resolveWithLocal(setOf(DataFields.displayName)).display.displayName
        )
    }

    @Test
    fun `Mapの異なるキーは競合しない`() {
        val statTypes = StatsType.entries.take(2)
        val first = statTypes[0]
        val second = statTypes[1]
        val base = MutableItemBaseData(id = "sword").apply {
            stats[first] = first.default
            stats[second] = second.default
        }
        val local = base.deepCopy().apply { stats[first] = first.default + 1.0 }
        val remote = base.deepCopy().apply { stats[second] = second.default + 1.0 }

        val result = ItemDataMerger.merge(base, local, remote)

        assertTrue(result.conflicts.isEmpty())
        assertEquals(local.stats[first], result.merged.stats[first])
        assertEquals(remote.stats[second], result.merged.stats[second])
    }

    @Test
    fun `Listの異なる要素は競合しない`() {
        val base = MutableItemBaseData(id = "sword").apply {
            editorMeta.comment.addAll(listOf("a", "b"))
        }
        val local = base.deepCopy().apply { editorMeta.comment[0] = "local" }
        val remote = base.deepCopy().apply { editorMeta.comment[1] = "remote" }

        val result = ItemDataMerger.merge(base, local, remote)

        assertTrue(result.conflicts.isEmpty())
        assertEquals(listOf("local", "remote"), result.merged.editorMeta.comment)
    }

    @Test
    fun `ローカルだけで変更したヘッドスキンを自動マージする`() {
        val base = MutableItemBaseData(id = "head")
        val local = base.deepCopy().apply {
            itemDetail.mutableHeadSkin = headSkin("local")
        }
        val remote = base.deepCopy()

        val result = ItemDataMerger.merge(base, local, remote)

        assertTrue(result.conflicts.isEmpty())
        assertEquals("local", result.merged.itemDetail.mutableHeadSkin?.value)
    }

    @Test
    fun `ヘッドスキンの同時変更を競合として解決できる`() {
        val base = MutableItemBaseData(id = "head").apply {
            itemDetail.mutableHeadSkin = headSkin("base")
        }
        val local = base.deepCopy().apply {
            itemDetail.mutableHeadSkin = headSkin("local")
        }
        val remote = base.deepCopy().apply {
            itemDetail.mutableHeadSkin = headSkin("remote")
        }

        val result = ItemDataMerger.merge(base, local, remote)

        assertEquals(listOf(DataFields.headSkin), result.conflicts.map { it.path })
        assertEquals("remote", result.merged.itemDetail.mutableHeadSkin?.value)
        assertEquals(
            "local",
            result.resolveWithLocal(setOf(DataFields.headSkin)).itemDetail.mutableHeadSkin?.value
        )
    }

    @Test
    fun `鉱石の要求Tierと別フィールドの変更を自動マージする`() {
        val base = MutableOreBaseData(id = "ore").apply {
            requiredTier = 1
            hardness = 1.0
        }
        val local = base.deepCopy().apply { requiredTier = 2 }
        val remote = base.deepCopy().apply { hardness = 2.0 }

        val result = OreDataMerger.merge(base, local, remote)

        assertTrue(result.conflicts.isEmpty())
        assertEquals(2, result.merged.requiredTier)
        assertEquals(2.0, result.merged.hardness)
    }

    @Test
    fun `鉱石の要求Tierの同時変更を競合として解決できる`() {
        val base = MutableOreBaseData(id = "ore").apply { requiredTier = 1 }
        val local = base.deepCopy().apply { requiredTier = 2 }
        val remote = base.deepCopy().apply { requiredTier = 3 }

        val result = OreDataMerger.merge(base, local, remote)

        assertEquals(listOf(DataFields.requiredTier), result.conflicts.map { it.path })
        assertEquals(3, result.merged.requiredTier)
        assertEquals(
            2,
            result.resolveWithLocal(setOf(DataFields.requiredTier)).requiredTier
        )
    }

    @Test
    fun `PICKAXEのTierの同時変更を種別固有データの競合として解決できる`() {
        val base = MutableItemBaseData(id = "pickaxe").apply {
            itemDetail.content = MutablePickaxeData(tier = 1)
        }
        val local = base.deepCopy().apply {
            assertIs<MutablePickaxeData>(itemDetail.content).tier = 2
        }
        val remote = base.deepCopy().apply {
            assertIs<MutablePickaxeData>(itemDetail.content).tier = 3
        }

        val result = ItemDataMerger.merge(base, local, remote)

        assertEquals(listOf(DataFields.detailContent), result.conflicts.map { it.path })
        assertEquals(3, assertIs<MutablePickaxeData>(result.merged.itemDetail.content).tier)
        assertEquals(
            2,
            assertIs<MutablePickaxeData>(
                result.resolveWithLocal(setOf(DataFields.detailContent)).itemDetail.content
            ).tier
        )
    }

    private fun headSkin(value: String) = MutableHeadSkinData(
        source = HeadSkinSource.PLAYER_NAME,
        value = value
    )
}
