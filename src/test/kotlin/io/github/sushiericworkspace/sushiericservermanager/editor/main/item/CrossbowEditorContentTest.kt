package io.github.sushiericworkspace.sushiericservermanager.editor.main.item

import io.github.sushiericworkspace.common.data.item.model.ItemType
import io.github.sushiericworkspace.common.data.item.model.detail.CrossbowData
import io.github.sushiericworkspace.common.data.item.model.mutable.detail.MutableCrossbowData
import io.github.sushiericworkspace.sushiericservermanager.ui.format.ItemDetailContentFormatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** クロスボウの反動設定が編集用データと要約表示へ反映されることを確認します。 */
class CrossbowEditorContentTest {
    @Test
    fun `CROSSBOWは反動の既定値を持つ編集用データを生成する`() {
        val content =
            assertIs<MutableCrossbowData>(
                ItemType.CROSSBOW.createMutableContent()
            )

        assertEquals(
            CrossbowData.DEFAULT_RECOIL_PER_PROJECTILE,
            content.recoilPerProjectile
        )
        assertEquals(
            CrossbowData.DEFAULT_MAX_RECOIL,
            content.maxRecoil
        )
    }

    @Test
    fun `CROSSBOWの反動設定を不変データへ反映する`() {
        val content =
            assertIs<MutableCrossbowData>(
                ItemType.CROSSBOW.createMutableContent()
            ).apply {
                recoilPerProjectile = 0.25
                maxRecoil = 2.5
            }

        val frozen = assertIs<CrossbowData>(content.freeze())

        assertEquals(0.25, frozen.recoilPerProjectile)
        assertEquals(2.5, frozen.maxRecoil)
    }

    @Test
    fun `CROSSBOWの要約へ反動設定を表示する`() {
        val content =
            assertIs<MutableCrossbowData>(
                ItemType.CROSSBOW.createMutableContent()
            ).apply {
                chargeSecond = 1.5
                arrowEfficiency = 0.75
                arrowCount = 3
                diffusionRate = 0.25
                recoilPerProjectile = 0.2
                maxRecoil = 1.5
            }

        assertEquals(
            "クロスボウ chargeSecond=1.5, arrowEfficiency=0.75, " +
                "arrowCount=3, diffusionRate=0.25, " +
                "recoilPerProjectile=0.2, maxRecoil=1.5",
            ItemDetailContentFormatter.format(content.freeze())
        )
    }
}
