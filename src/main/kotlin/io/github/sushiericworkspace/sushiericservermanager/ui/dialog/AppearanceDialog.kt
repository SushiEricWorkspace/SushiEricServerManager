package io.github.sushiericworkspace.sushiericservermanager.ui.dialog

import io.github.sushiericworkspace.sushiericservermanager.config.AppTheme
import io.github.sushiericworkspace.sushiericservermanager.ui.theme.ThemeManager
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.VBox
import javafx.stage.Stage

/** 選択と同時に全ウィンドウの配色を変更し、次の起動にも保存します。 */
object AppearanceDialog {
    fun show(owner: Stage?) {
        val group = ToggleGroup()
        val choices = AppTheme.entries.map { theme ->
            RadioButton(if (theme == AppTheme.LIGHT) "ライト" else "ダーク").apply {
                id = "appearance-${theme.name.lowercase()}"
                toggleGroup = group
                isSelected = theme == ThemeManager.current
                setOnAction {
                    if (!ThemeManager.select(theme)) {
                        group.toggles.first { it.userData == ThemeManager.current }.isSelected = true
                        CustomDialog.error().owner(owner).title("外観設定").header("設定を保存できませんでした")
                            .content("保存先を確認してください。配色は変更していません。").show()
                    }
                }
                userData = theme
            }
        }
        Dialog<Void>().apply {
            title = "外観"
            if (owner != null) initOwner(owner)
            dialogPane.content = VBox(12.0, Label("配色を選択すると、すべてのウィンドウへ反映して保存します。"), *choices.toTypedArray()).apply {
                styleClass.add("appearance-settings")
            }
            dialogPane.buttonTypes.add(ButtonType.CLOSE)
            dialogPane.scene?.let(ThemeManager::apply)
            showAndWait()
        }
    }
}
