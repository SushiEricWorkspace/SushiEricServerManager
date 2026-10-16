package io.github.sushiericworkspace.sushiericservermanager.app

import io.github.sushiericworkspace.sushiericservermanager.update.AppVersion

/** アプリのバージョンを含むウィンドウ名を生成します。 */
internal fun appWindowTitle(screenName: String): String =
    "SushiEricServerManager v${AppVersion.CURRENT} - $screenName"
