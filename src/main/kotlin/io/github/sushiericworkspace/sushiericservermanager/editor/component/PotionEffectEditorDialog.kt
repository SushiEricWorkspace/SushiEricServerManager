package io.github.sushiericworkspace.sushiericservermanager.editor.component

import io.github.sushiericworkspace.sushiericservermanager.app.WindowSizing
import io.github.sushiericworkspace.sushiericdatamodel.data.effect.model.EffectLevelSupport
import io.github.sushiericworkspace.sushiericdatamodel.data.effect.model.EffectTimeSupport
import io.github.sushiericworkspace.sushiericdatamodel.data.effect.model.MutableLevelLessInstantEffectData
import io.github.sushiericworkspace.sushiericdatamodel.data.effect.model.MutableLevelLessTimedEffectData
import io.github.sushiericworkspace.sushiericdatamodel.data.effect.model.MutableLeveledInstantEffectData
import io.github.sushiericworkspace.sushiericdatamodel.data.effect.model.MutableLeveledTimedEffectData
import io.github.sushiericworkspace.sushiericdatamodel.data.effect.model.MutablePotionEffectData
import io.github.sushiericworkspace.sushiericdatamodel.data.effect.model.SushiEricEffectType
import io.github.sushiericworkspace.sushiericdatamodel.data.effect.model.createMutableEffectData
import io.github.sushiericworkspace.sushiericdatamodel.data.effect.validation.PotionEffectValidator
import io.github.sushiericworkspace.sushiericdatamodel.data.item.model.mutable.detail.MutablePotionData
import io.github.sushiericworkspace.sushiericservermanager.app.AppScreen
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.ListCell
import javafx.scene.control.ScrollPane
import javafx.scene.control.Spinner
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.util.Callback

internal enum class EffectEditorField {
    LEVEL,
    TIME
}

internal const val POTION_EFFECT_TICKS_PER_SECOND = 20L
internal const val MIN_EDITOR_EFFECT_LEVEL = PotionEffectValidator.MIN_LEVEL
internal val MIN_EDITOR_EFFECT_TIME_SECONDS =
    ((PotionEffectValidator.MIN_TIME + POTION_EFFECT_TICKS_PER_SECOND - 1L) /
        POTION_EFFECT_TICKS_PER_SECOND).toInt()

internal fun effectDisplayText(type: SushiEricEffectType): String = type.display

internal fun normalizeEffectLevelForEditor(level: Int): Int =
    level.coerceAtLeast(MIN_EDITOR_EFFECT_LEVEL)

internal fun normalizeEffectTimeForEditor(time: Long): Long =
    if (time >= PotionEffectValidator.MIN_TIME) {
        time
    } else {
        MIN_EDITOR_EFFECT_TIME_SECONDS * POTION_EFFECT_TICKS_PER_SECOND
    }

internal fun effectTimeToEditorSeconds(time: Long): Int =
    (normalizeEffectTimeForEditor(time) / POTION_EFFECT_TICKS_PER_SECOND)
        .toInt()
        .coerceAtLeast(MIN_EDITOR_EFFECT_TIME_SECONDS)

internal fun editorSecondsToEffectTime(seconds: Int): Long =
    seconds.toLong() * POTION_EFFECT_TICKS_PER_SECOND

internal fun visibleEffectEditorFields(
    hasLevel: Boolean,
    hasTime: Boolean
): Set<EffectEditorField> = buildSet {
    if (hasLevel) add(EffectEditorField.LEVEL)
    if (hasTime) add(EffectEditorField.TIME)
}

internal fun visibleEffectEditorFields(
    type: SushiEricEffectType
): Set<EffectEditorField> = visibleEffectEditorFields(
    hasLevel = type is EffectLevelSupport,
    hasTime = type is EffectTimeSupport
)

internal fun createEditorEffectData(
    type: SushiEricEffectType,
    level: Int,
    time: Long
): MutablePotionEffectData = when (val effect = createMutableEffectData(type)) {
    is MutableLeveledTimedEffectData -> effect.apply {
        this.level = level
        this.time = time
    }

    is MutableLeveledInstantEffectData -> effect.apply {
        this.level = level
    }

    is MutableLevelLessTimedEffectData -> effect.apply {
        this.time = time
    }

    is MutableLevelLessInstantEffectData -> effect
}

object PotionEffectEditorDialog {

    fun show(content: MutablePotionData, onConfirmed: () -> Unit) {
        val stage = Stage().apply {
            title = "ポーション効果編集"
            initModality(Modality.APPLICATION_MODAL)
        }
        val editingEffects = content.effects
            .map { it.deepCopy() }
            .toMutableList()
        val effectListBox = VBox(6.0).apply {
            styleClass.addAll("editor-row-vbox", "potion-effect-list-box")
            maxWidth = Double.MAX_VALUE
        }
        val effectTypeComboBox = createEffectTypeComboBox()

        fun refreshEffectTypeItems() {
            val usedTypes = editingEffects.map { it.type }.toSet()
            val availableTypes = SushiEricEffectType.entries.filter { it !in usedTypes }
            effectTypeComboBox.items.setAll(availableTypes)
            effectTypeComboBox.value = availableTypes.firstOrNull()
        }

        fun rebuildEffectList() {
            effectListBox.children.clear()
            if (editingEffects.isEmpty()) {
                effectListBox.children.add(
                    Label("効果がありません").apply {
                        styleClass.add("editor-label")
                    }
                )
                return
            }

            editingEffects.forEach { effect ->
                effectListBox.children.add(
                    createEffectRow(
                        effect = effect,
                        onDelete = {
                            editingEffects.remove(effect)
                            rebuildEffectList()
                            refreshEffectTypeItems()
                        }
                    )
                )
            }
        }

        val levelSpinner = EditorSpinnerFactory.intSpinner(
            initialValue = MIN_EDITOR_EFFECT_LEVEL,
            min = MIN_EDITOR_EFFECT_LEVEL,
            max = 999,
            step = 1
        ) {}
        val timeSpinner = EditorSpinnerFactory.intSpinner(
            initialValue = MIN_EDITOR_EFFECT_TIME_SECONDS,
            min = MIN_EDITOR_EFFECT_TIME_SECONDS,
            max = 999999,
            step = 1
        ) {}
        val addButton = Button("追加").apply {
            isFocusTraversable = false
            styleClass.add("btn-success")
            setOnAction {
                val selectedType = effectTypeComboBox.value ?: return@setOnAction
                if (editingEffects.any { it.type == selectedType }) return@setOnAction

                editingEffects.add(
                    createEditorEffectData(
                        type = selectedType,
                        level = levelSpinner.value ?: MIN_EDITOR_EFFECT_LEVEL,
                        time = editorSecondsToEffectTime(
                            timeSpinner.value ?: MIN_EDITOR_EFFECT_TIME_SECONDS
                        )
                    )
                )
                refreshEffectTypeItems()
                rebuildEffectList()
            }
        }
        val contentRoot = VBox(12.0).apply {
            padding = Insets(15.0)
            prefWidth = 680.0
            prefHeight = 460.0
            maxWidth = Double.MAX_VALUE
            maxHeight = Double.MAX_VALUE
            styleClass.addAll("editor-row-vbox", "potion-effect-dialog-root")
            children.addAll(
                Label("現在の効果:").apply {
                    styleClass.add("editor-label-highlight")
                },
                ScrollPane(effectListBox).apply {
                    styleClass.add("potion-effect-list-scroll")
                    isFitToWidth = true
                    prefHeight = 220.0
                    maxWidth = Double.MAX_VALUE
                },
                createAddEffectForm(effectTypeComboBox, levelSpinner, timeSpinner, addButton),
                HBox(
                    Button("キャンセル").apply {
                        isFocusTraversable = false
                        styleClass.add("btn-cancel")
                        setOnAction { stage.close() }
                    },
                    Button("OK").apply {
                        isFocusTraversable = false
                        styleClass.add("btn-primary")
                        setOnAction {
                            content.effects = editingEffects
                                .map { it.deepCopy() }
                                .toMutableList()
                            onConfirmed()
                            stage.close()
                        }
                    }
                ).apply {
                    alignment = Pos.CENTER_RIGHT
                    styleClass.addAll("editor-row-hbox", "dialog-actions")
                }
            )
        }

        rebuildEffectList()
        refreshEffectTypeItems()
        stage.scene = Scene(
            StackPane(contentRoot).apply {
                styleClass.add("potion-effect-scene-root")
                padding = Insets(0.0)
            },
            680.0,
            500.0
        ).apply {
            fill = Color.web("#18191A")
            stylesheets.add(
                PotionEffectEditorDialog::class.java
                    .getResource(AppScreen.WIDGETS_ONLY.css)!!
                    .toExternalForm()
            )
        }
        WindowSizing.install(stage)
        stage.showAndWait()
    }

    private fun createEffectTypeComboBox(): ComboBox<SushiEricEffectType> {
        return ComboBox<SushiEricEffectType>().apply {
            items.addAll(SushiEricEffectType.entries)
            prefWidth = 220.0
            cellFactory = Callback {
                object : ListCell<SushiEricEffectType>() {
                    override fun updateItem(item: SushiEricEffectType?, empty: Boolean) {
                        super.updateItem(item, empty)
                        text = if (empty || item == null) null else effectDisplayText(item)
                    }
                }
            }
            buttonCell = object : ListCell<SushiEricEffectType>() {
                override fun updateItem(item: SushiEricEffectType?, empty: Boolean) {
                    super.updateItem(item, empty)
                    text = if (empty || item == null) null else effectDisplayText(item)
                }
            }
        }
    }

    private fun createEffectRow(effect: MutablePotionEffectData, onDelete: () -> Unit): GridPane {
        return GridPane().apply {
            styleClass.add("potion-effect-row-grid")
            hgap = 8.0
            vgap = 4.0
            alignment = Pos.CENTER_LEFT
            maxWidth = Double.MAX_VALUE
            add(
                Label(effectDisplayText(effect.type)).apply {
                    styleClass.add("editor-label-highlight")
                    minWidth = 170.0
                    prefWidth = 170.0
                    maxWidth = 170.0
                    isWrapText = false
                },
                0,
                0
            )

            var nextColumn = 1

            fun addLevelEditor(initialValue: Int, onChanged: (Int) -> Unit) {
                val editorValue = normalizeEffectLevelForEditor(initialValue)
                onChanged(editorValue)
                add(Label("Lv.").fixedLabel(), nextColumn++, 0)
                add(
                    EditorSpinnerFactory.intSpinner(
                        initialValue = editorValue,
                        min = MIN_EDITOR_EFFECT_LEVEL,
                        max = 999,
                        step = 1,
                        prefWidth = 90.0,
                        onChanged = onChanged
                    ).fixedWidth(90.0),
                    nextColumn++,
                    0
                )
            }

            fun addTimeEditor(initialValue: Long, onChanged: (Long) -> Unit) {
                val editorTime = normalizeEffectTimeForEditor(initialValue)
                onChanged(editorTime)
                add(Label("秒").fixedLabel(), nextColumn++, 0)
                add(
                    EditorSpinnerFactory.intSpinner(
                        initialValue = effectTimeToEditorSeconds(editorTime),
                        min = MIN_EDITOR_EFFECT_TIME_SECONDS,
                        max = 999999,
                        step = 1,
                        prefWidth = 100.0
                    ) { seconds ->
                        onChanged(editorSecondsToEffectTime(seconds))
                    }.fixedWidth(100.0),
                    nextColumn++,
                    0
                )
            }

            when (effect) {
                is MutableLeveledTimedEffectData -> {
                    addLevelEditor(effect.level) { effect.level = it }
                    addTimeEditor(effect.time) { effect.time = it }
                }

                is MutableLeveledInstantEffectData ->
                    addLevelEditor(effect.level) { effect.level = it }

                is MutableLevelLessTimedEffectData ->
                    addTimeEditor(effect.time) { effect.time = it }

                is MutableLevelLessInstantEffectData -> Unit
            }

            add(
                Button("削除").apply {
                    isFocusTraversable = false
                    styleClass.add("btn-danger")
                    minWidth = 70.0
                    prefWidth = 70.0
                    maxWidth = 70.0
                    setOnAction { onDelete() }
                },
                nextColumn,
                0
            )
        }
    }

    private fun createAddEffectForm(
        effectType: ComboBox<SushiEricEffectType>,
        levelSpinner: Spinner<Int>,
        timeSpinner: Spinner<Int>,
        addButton: Button
    ): GridPane {
        val levelLabel = Label("レベル:").editorLabel()
        val timeLabel = Label("時間(秒):").editorLabel()

        fun updateInputFields(type: SushiEricEffectType?) {
            val visibleFields = type
                ?.let(::visibleEffectEditorFields)
                .orEmpty()
            val showLevel = EffectEditorField.LEVEL in visibleFields
            val showTime = EffectEditorField.TIME in visibleFields

            levelLabel.isManaged = showLevel
            levelLabel.isVisible = showLevel
            levelSpinner.isManaged = showLevel
            levelSpinner.isVisible = showLevel
            timeLabel.isManaged = showTime
            timeLabel.isVisible = showTime
            timeSpinner.isManaged = showTime
            timeSpinner.isVisible = showTime
        }

        effectType.valueProperty().addListener { _, oldType, newType ->
            if (oldType != newType) {
                levelSpinner.valueFactory.value = MIN_EDITOR_EFFECT_LEVEL
                timeSpinner.valueFactory.value = MIN_EDITOR_EFFECT_TIME_SECONDS
            }
            updateInputFields(newType)
        }
        updateInputFields(effectType.value)

        return GridPane().apply {
            styleClass.add("potion-effect-form-grid")
            hgap = 10.0
            vgap = 8.0
            add(Label("効果:").editorLabel(), 0, 0)
            add(effectType, 1, 0)
            add(levelLabel, 0, 1)
            add(levelSpinner, 1, 1)
            add(timeLabel, 0, 2)
            add(timeSpinner, 1, 2)
            add(addButton, 1, 3)
        }
    }

    private fun Label.editorLabel(): Label = apply {
        styleClass.add("editor-label")
    }

    private fun Label.fixedLabel(): Label = editorLabel().apply {
        minWidth = Region.USE_PREF_SIZE
        prefWidth = Region.USE_COMPUTED_SIZE
    }

    private fun <T> Spinner<T>.fixedWidth(width: Double): Spinner<T> = apply {
        minWidth = width
        prefWidth = width
        maxWidth = width
    }
}
