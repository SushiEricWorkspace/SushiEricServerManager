package io.github.sushiericworkspace.sushiericservermanager.ui.format

import io.github.sushiericworkspace.common.data.item.model.detail.ArmorContent
import io.github.sushiericworkspace.common.data.item.model.detail.AxeData
import io.github.sushiericworkspace.common.data.item.model.detail.BowContent
import io.github.sushiericworkspace.common.data.item.model.detail.CrossbowData
import io.github.sushiericworkspace.common.data.item.model.detail.ItemDetailContent
import io.github.sushiericworkspace.common.data.item.model.detail.LongSwordData
import io.github.sushiericworkspace.common.data.item.model.detail.OtherData
import io.github.sushiericworkspace.common.data.item.model.detail.PotionData
import io.github.sushiericworkspace.common.data.item.model.detail.ShieldData
import io.github.sushiericworkspace.common.data.item.model.detail.ShortBowData
import io.github.sushiericworkspace.common.data.item.model.detail.ShortSwordData
import io.github.sushiericworkspace.common.data.item.model.detail.OtherWeaponData
import io.github.sushiericworkspace.common.data.item.model.detail.PickaxeData
import io.github.sushiericworkspace.common.data.item.model.detail.SwordData

/**
 * アイテムの種別固有データを、エディター画面へ表示するための文字列へ整形します。
 *
 * #### 仕様:
 * - 種別名を先頭に置き、その種別が持つ値を続けます。
 * - [SwordData] などプロパティを持たない種別は種別名だけを返します。
 *   これらは通常クラスのため既定の `toString()` がオブジェクト参照になり、そのままでは表示に使えません。
 * - Common側のデータモデルへ表示用の情報を追加しないため、整形はエディター側のこのクラスへ集約します。
 *
 * 差分表示と競合解決ダイアログの双方から使用します。
 */
object ItemDetailContentFormatter {

    /**
     * 種別固有データを表示用の文字列へ整形します。
     *
     * @param content 整形対象の種別固有データ。
     * @return 種別名と値を含む1行の文字列。
     */
    fun format(content: ItemDetailContent): String {
        return when (content) {
            is SwordData -> "剣"
            is ShortSwordData -> "短剣"
            is LongSwordData -> "長剣 cooldown=${content.cooldown}"
            is AxeData -> "斧"
            is PickaxeData -> "ツルハシ tier=${content.tier}"

            is BowContent -> {
                val base = "弓 multi=${content.multi}, angle=${content.angle}, pierce=${content.pierce}"

                if (content is ShortBowData) {
                    base + ", shortInterval=${content.shortInterval}"
                } else {
                    base
                }
            }

            is CrossbowData -> {
                "クロスボウ chargeSecond=${content.chargeSecond}, " +
                    "arrowEfficiency=${content.arrowEfficiency}, arrowCount=${content.arrowCount}, " +
                    "diffusionRate=${content.diffusionRate}, " +
                    "recoilPerProjectile=${content.recoilPerProjectile}, " +
                    "maxRecoil=${content.maxRecoil}"
            }

            is OtherWeaponData -> "その他の武器"

            is PotionData -> {
                "ポーション color=${content.color}, effects=${content.effects.size}件"
            }

            is ShieldData -> {
                "盾 blockCooldown=${content.blockCooldown}, parryCooldown=${content.parryCooldown}"
            }

            is ArmorContent -> {
                "防具 color=${content.color ?: "なし"}, trim=${content.trimData ?: "なし"}"
            }

            is OtherData -> "その他"
        }
    }
}
