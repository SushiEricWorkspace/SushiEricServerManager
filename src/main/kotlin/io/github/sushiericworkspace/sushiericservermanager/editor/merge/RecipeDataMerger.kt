package io.github.sushiericworkspace.sushiericservermanager.editor.merge

import io.github.sushiericworkspace.common.data.recipe.model.mutable.MutableRecipeData

/** 盤サイズと材料は一体として扱い、完成品と解放条件は独立にマージします。 */
object RecipeDataMerger : DataMerger<MutableRecipeData> {
    override fun merge(base: MutableRecipeData, local: MutableRecipeData, remote: MutableRecipeData): ThreeWayMergeResult<MutableRecipeData> {
        val accumulator = MergeAccumulator(remote.deepCopy(), MutableRecipeData::deepCopy)
        accumulator.mergeValue(DataFieldPath.property("layout", "盤サイズ・材料配置"),
            base.size to base.ingredients, local.size to local.ingredients, remote.size to remote.ingredients) { data, value ->
            data.size = value.first
            data.ingredients = value.second
        }
        accumulator.mergeValue(DataFieldPath.property("result", "完成品"), base.resultItemInternalId, local.resultItemInternalId, remote.resultItemInternalId) { data, value -> data.resultItemInternalId = value }
        accumulator.mergeValue(DataFieldPath.property("result-count", "完成数"), base.resultCount, local.resultCount, remote.resultCount) { data, value -> data.resultCount = value }
        accumulator.mergeMap(DataFieldPath.property("skills", "要求スキルレベル"), base.skillLevelRequirements, local.skillLevelRequirements, remote.skillLevelRequirements,
            keyDisplay = { it.display }, targetMap = { it.skillLevelRequirements })
        accumulator.mergeValue(DataFieldPath.property("achievements", "要求実績"), base.achievementRequirements.toSet(), local.achievementRequirements.toSet(), remote.achievementRequirements.toSet()) { data, value ->
            data.achievementRequirements = value.toMutableSet()
        }
        return accumulator.result()
    }
}
