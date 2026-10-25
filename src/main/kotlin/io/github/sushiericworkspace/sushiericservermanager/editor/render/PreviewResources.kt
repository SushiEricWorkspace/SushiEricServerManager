package io.github.sushiericworkspace.sushiericservermanager.editor.render

import java.security.MessageDigest
import java.util.Properties

/** 同梱原本と通知を検査する。フォントのロード・生成・通信は行わない。 */
internal object PreviewResources {
    const val REGULAR = "fonts/NotoSansJP-Regular.otf"
    const val BOLD = "fonts/NotoSansJP-Bold.otf"
    const val COVERAGE = "fonts/noto-sans-jp.codepoints"
    const val ITEM = "images/preview/item.png"
    const val MISSING = "images/preview/missing.png"
    val paths = setOf(REGULAR, BOLD, COVERAGE, ITEM, MISSING,
        "images/preview/item.svg", "images/preview/missing.svg",
        "licenses/preview/OFL.txt", "licenses/preview/NOTICE.txt")

    private val loader = PreviewResources::class.java.classLoader
    private val verified by lazy {
        val manifest = Properties().apply {
            loader.getResourceAsStream("preview-assets.properties")?.use { load(it) }
                ?: error("プレビュー素材の一覧がありません")
        }
        val hashes = manifest.stringPropertyNames().associateWith(manifest::getProperty)
        verify(loader, hashes)
    }

    val codepoints: Set<Int> by lazy {
        ensureVerified()
        loader.getResourceAsStream(COVERAGE)!!.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.flatMap { line ->
                val ends = line.split('-').map { it.toInt(16) }
                (ends.first()..ends.last()).asSequence()
            }.toSet()
        }
    }

    fun ensureVerified() { verified }

    fun verify(loader: ClassLoader, hashes: Map<String, String>) {
        require(hashes.keys == paths) { "プレビュー素材のhash一覧が不足または過剰です" }
        paths.forEach { path ->
            val actual = loader.getResourceAsStream(path)?.use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            } ?: error("同梱プレビュー素材がありません: $path")
            check(actual == hashes.getValue(path)) { "同梱プレビュー素材のhashが一致しません: $path" }
        }
    }
}
