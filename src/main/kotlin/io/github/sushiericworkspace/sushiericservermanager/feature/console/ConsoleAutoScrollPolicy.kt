package io.github.sushiericworkspace.sushiericservermanager.feature.console

/** コンソールのスクロール位置から自動スクロールの有効状態を管理します。 */
class ConsoleAutoScrollPolicy {
    /** 末尾への自動スクロールが有効かを返します。 */
    var isEnabled: Boolean = true
        private set

    /** 利用者が操作した現在位置を反映します。 */
    fun update(current: Double, maximum: Double, scrollBarVisible: Boolean) {
        isEnabled = !scrollBarVisible || current >= maximum - SCROLL_EPSILON
    }

    private companion object {
        const val SCROLL_EPSILON = 0.001
    }
}
