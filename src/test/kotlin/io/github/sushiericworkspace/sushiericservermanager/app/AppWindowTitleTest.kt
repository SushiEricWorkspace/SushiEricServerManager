package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.update.AppVersion
import kotlin.test.Test
import kotlin.test.assertEquals

class AppWindowTitleTest {
    @Test
    fun `現在のアプリバージョンと画面名を含むウィンドウ名を生成する`() {
        assertEquals(
            "SushiEricServerManager v${AppVersion.CURRENT} - アイテムエディタ",
            appWindowTitle("アイテムエディタ")
        )
    }
}
