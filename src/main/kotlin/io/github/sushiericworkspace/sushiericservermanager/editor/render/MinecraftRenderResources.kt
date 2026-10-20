package io.github.sushiericworkspace.sushiericservermanager.editor.render

import java.security.MessageDigest

/** MinecraftFontの初期化前に、同梱フォントの存在と固定hashを検査する。生成処理は起動しない。 */
internal object MinecraftRenderResources {
    val fontPaths: Set<String> = setOf(
        "fonts/Minecraft-Regular.otf",
        "fonts/Minecraft-Bold.otf",
        "fonts/Minecraft-Italic.otf",
        "fonts/Minecraft-BoldItalic.otf",
        "fonts/Minecraft-Galactic.otf",
        "fonts/Minecraft-Illageralt.otf"
    )

    fun verify(loader: ClassLoader, hashes: Map<String, String>) {
        require(hashes.keys == fontPaths) { "描画フォントのhash一覧が不足または過剰です" }
        fontPaths.forEach { path ->
            val actual = loader.getResourceAsStream(path)?.use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } ?: error("同梱描画フォントがありません: $path")
            check(actual == hashes.getValue(path)) { "同梱描画フォントのhashが一致しません: $path" }
        }
    }
}
