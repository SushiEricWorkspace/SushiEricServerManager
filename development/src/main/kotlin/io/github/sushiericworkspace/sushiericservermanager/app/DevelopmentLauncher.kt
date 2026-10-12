package io.github.sushiericworkspace.sushiericservermanager.app

/** 管理接続モジュールを組み合わせる開発専用入口です。 */
object DevelopmentLauncher {
    @JvmStatic
    fun main(args: Array<String>) = Launcher.launch(DevelopmentApp::class.java, args)
}
