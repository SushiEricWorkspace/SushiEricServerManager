package io.github.sushiericworkspace.sushiericservermanager.editor.history

import javafx.scene.input.KeyCode

/**
 * 入力中のテキストを、いつ1件の編集履歴として確定するかの規則です。
 *
 * テキスト入力は1文字ごとに履歴へ積まないため、確定していない変更が残ります。その状態で
 * 別の操作（追加や削除など）を行うと、両方が1件の履歴へまとまってしまいます。
 * そこで、ほかの操作が始まる直前に確定します。
 */

/**
 * マウス操作で入力中のテキストを確定するかどうかを返します。
 *
 * 入力欄の中をクリックしただけの場合は確定しません。ボタンやメニューなど、
 * 入力欄の外を押したときに確定します。
 *
 * @param focusOwnerIsTextInput 現在フォーカスがテキスト入力にあるか。
 * @param targetIsFocusOwner 押した対象がフォーカス中の入力欄自身か。
 */
internal fun shouldCommitHistoryOnMousePress(
    focusOwnerIsTextInput: Boolean,
    targetIsFocusOwner: Boolean
): Boolean = focusOwnerIsTextInput && !targetIsFocusOwner

/**
 * キー操作で入力中のテキストを確定するかどうかを返します。
 *
 * 入力の確定（Enter）、フォーカス移動（Tab）、ショートカット（Ctrl / Cmd との同時押し）で確定します。
 * 通常の文字入力では確定しません。
 *
 * @param focusOwnerIsTextInput 現在フォーカスがテキスト入力にあるか。
 * @param code 押したキー。
 * @param shortcutDown ショートカットの修飾キーを押しているか。
 */
internal fun shouldCommitHistoryOnKeyPress(
    focusOwnerIsTextInput: Boolean,
    code: KeyCode,
    shortcutDown: Boolean
): Boolean {
    if (!focusOwnerIsTextInput) return false

    return shortcutDown || code == KeyCode.ENTER || code == KeyCode.TAB
}
