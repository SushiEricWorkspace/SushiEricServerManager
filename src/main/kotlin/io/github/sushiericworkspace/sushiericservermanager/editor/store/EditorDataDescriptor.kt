package io.github.sushiericworkspace.sushiericservermanager.editor.store

import io.github.sushiericworkspace.common.data.core.SushiEricDataType
import io.github.sushiericworkspace.common.data.core.ManagedData
import io.github.sushiericworkspace.common.data.core.validation.SushiEricValidationError
import io.github.sushiericworkspace.common.data.item.ItemManager
import io.github.sushiericworkspace.common.data.item.model.ItemInternalId
import io.github.sushiericworkspace.common.data.item.model.mutable.MutableItemBaseData
import io.github.sushiericworkspace.common.data.ore.OreManager
import io.github.sushiericworkspace.common.data.ore.model.mutable.MutableOreBaseData
import io.github.sushiericworkspace.common.data.ore.model.OreBaseDataView
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.DataMerger
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.ItemDataMerger
import io.github.sushiericworkspace.sushiericservermanager.editor.merge.OreDataMerger
import java.io.File

/** Commonの検証結果を優先し、未提供の版ではスキル経験値の範囲検証だけを補います。 */
internal fun validateOreDataForEditor(
    data: MutableOreBaseData,
    itemIds: Set<ItemInternalId>
): List<SushiEricValidationError> {
    val commonErrors = data.validate(itemIds)
    if (commonErrors.any { it.property.name == OreBaseDataView::skillExperienceMap.name }) {
        return commonErrors
    }

    return commonErrors + data.skillExperienceMap.mapNotNull { (skill, value) ->
        if (value < 0.0 || !value.isFinite()) {
            SushiEricValidationError(
                property = data::skillExperienceMap,
                message = "${skill.display}のスキル経験値は0.0以上の有限値を設定してください。",
                key = skill
            )
        } else {
            null
        }
    }
}

class EditorDataDescriptor<T : ManagedData<T, *>>(
    val dataType: SushiEricDataType<T>,
    val load: (File, File) -> T?,
    val save: (File, T, Set<ItemInternalId>?) -> Unit,
    val validate: (T, Set<ItemInternalId>) -> List<SushiEricValidationError>,
    val merger: DataMerger<T>,
    private val duplicateForNewEntry: (T) -> T = { it.deepCopy() }
) {
    val displayName: String
        get() = dataType.displayName

    val relativeDirectory: String
        get() = dataType.dir.getRawPath()

    fun createDefault(id: String): T = dataType.createDefault(id)

    fun deepCopy(data: T): T = data.deepCopy()

    /**
     * 既存データを別データとして複製し、新しい公開IDを設定します。
     *
     * データ種別固有の永続識別子がある場合は、[duplicateForNewEntry]側で再生成します。
     */
    fun duplicateAsNew(data: T, newId: String): T = duplicateForNewEntry(data).apply {
        id = newId
    }
}

object EditorDataDescriptors {
    val item = EditorDataDescriptor(
        dataType = SushiEricDataType.Item,
        load = ItemManager::loadMutable,
        save = { file, data, _ -> ItemManager.saveMutable(file, data) },
        validate = { data, _ -> data.validate() },
        merger = ItemDataMerger,
        duplicateForNewEntry = MutableItemBaseData::duplicateAsNew
    )

    val ore = EditorDataDescriptor(
        dataType = SushiEricDataType.Ore,
        load = OreManager::loadMutable,
        save = { file, data, _ -> OreManager.saveMutable(file, data) },
        validate = ::validateOreDataForEditor,
        merger = OreDataMerger,
        duplicateForNewEntry = MutableOreBaseData::duplicateAsNew
    )

    val all: List<EditorDataDescriptor<out ManagedData<*, *>>> = listOf(item, ore)

    @Suppress("UNCHECKED_CAST")
    fun <T : ManagedData<T, *>> of(dataType: SushiEricDataType<T>): EditorDataDescriptor<T> {
        return when (dataType) {
            SushiEricDataType.Item -> item
            SushiEricDataType.Ore -> ore
        } as EditorDataDescriptor<T>
    }
}
