package io.github.sushiericworkspace.sushiericservermanager.editor.component

import io.github.sushiericworkspace.sushiericdatamodel.data.effect.model.MutableLeveledTimedEffectData
import io.github.sushiericworkspace.sushiericdatamodel.data.effect.model.SushiEricEffectType
import io.github.sushiericworkspace.sushiericdatamodel.data.effect.validation.PotionEffectValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PotionEffectEditorDialogTest {

    @Test
    fun `LevelとTimeの4分類に応じた入力項目を返す`() {
        assertEquals(
            setOf(EffectEditorField.LEVEL, EffectEditorField.TIME),
            visibleEffectEditorFields(hasLevel = true, hasTime = true)
        )
        assertEquals(
            setOf(EffectEditorField.LEVEL),
            visibleEffectEditorFields(hasLevel = true, hasTime = false)
        )
        assertEquals(
            setOf(EffectEditorField.TIME),
            visibleEffectEditorFields(hasLevel = false, hasTime = true)
        )
        assertEquals(
            emptySet(),
            visibleEffectEditorFields(hasLevel = false, hasTime = false)
        )
    }

    @Test
    fun `入力項目はSushiEricDataModelのmarker interfaceから決定する`() {
        assertEquals(
            setOf(EffectEditorField.LEVEL, EffectEditorField.TIME),
            visibleEffectEditorFields(SushiEricEffectType.REGENERATION)
        )
    }

    @Test
    fun `EffectTypeの表示にはSushiEricDataModelの表示名を使用する`() {
        SushiEricEffectType.entries.forEach { type ->
            assertEquals(type.display, effectDisplayText(type))
            assertNotEquals(type.name, effectDisplayText(type))
        }
    }

    @Test
    fun `LevelとTimeの入力最小値はSushiEricDataModelの制約に従う`() {
        assertEquals(PotionEffectValidator.MIN_LEVEL, MIN_EDITOR_EFFECT_LEVEL)
        assertEquals(1, MIN_EDITOR_EFFECT_TIME_SECONDS)
        assertEquals(MIN_EDITOR_EFFECT_LEVEL, normalizeEffectLevelForEditor(0))
        assertEquals(MIN_EDITOR_EFFECT_LEVEL, normalizeEffectLevelForEditor(-1))
        assertEquals(
            POTION_EFFECT_TICKS_PER_SECOND,
            normalizeEffectTimeForEditor(0L)
        )
        assertEquals(
            POTION_EFFECT_TICKS_PER_SECOND,
            editorSecondsToEffectTime(MIN_EDITOR_EFFECT_TIME_SECONDS)
        )
    }

    @Test
    fun `旧データの不正値は編集可能な最小値へ補正する`() {
        assertEquals(MIN_EDITOR_EFFECT_LEVEL, normalizeEffectLevelForEditor(Int.MIN_VALUE))
        assertEquals(1, effectTimeToEditorSeconds(Long.MIN_VALUE))
    }

    @Test
    fun `EffectTypeに対応するデータへ必要な入力値を設定する`() {
        val effect = createEditorEffectData(
            type = SushiEricEffectType.REGENERATION,
            level = 3,
            time = 120L
        )

        val leveledTimed = assertIs<MutableLeveledTimedEffectData>(effect)
        assertEquals(SushiEricEffectType.REGENERATION, leveledTimed.type)
        assertEquals(3, leveledTimed.level)
        assertEquals(120L, leveledTimed.time)
    }

    @Test
    fun `入力値の正否はSushiEricDataModel Validatorで判定する`() {
        val invalidEffect = assertIs<MutableLeveledTimedEffectData>(
            createEditorEffectData(
                type = SushiEricEffectType.REGENERATION,
                level = 0,
                time = 0L
            )
        )
        assertEquals(2, PotionEffectValidator(invalidEffect).validate().size)

        invalidEffect.level = MIN_EDITOR_EFFECT_LEVEL
        invalidEffect.time = editorSecondsToEffectTime(MIN_EDITOR_EFFECT_TIME_SECONDS)
        assertTrue(PotionEffectValidator(invalidEffect).validate().isEmpty())
    }
}
