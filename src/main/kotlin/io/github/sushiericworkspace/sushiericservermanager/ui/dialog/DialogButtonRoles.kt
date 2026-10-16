package io.github.sushiericworkspace.sushiericservermanager.ui.dialog

import javafx.scene.control.Button

/**
 * OK / Cancel型ダイアログの標準キーボード操作を設定します。
 *
 * JavaFXのdefault buttonとcancel buttonを使用するため、入力コントロールにフォーカスがある場合も
 * Enterで肯定操作、Escでキャンセル操作を実行できます。
 */
internal object DialogButtonRoles {
    fun apply(confirmButton: Button, cancelButton: Button) {
        applyRoles(
            setDefaultButton = { confirmButton.isDefaultButton = it },
            setCancelButton = { cancelButton.isCancelButton = it }
        )
    }

    internal fun applyRoles(
        setDefaultButton: (Boolean) -> Unit,
        setCancelButton: (Boolean) -> Unit
    ) {
        setDefaultButton(true)
        setCancelButton(true)
    }
}
