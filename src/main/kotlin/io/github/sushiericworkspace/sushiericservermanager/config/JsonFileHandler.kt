package io.github.sushiericworkspace.sushiericservermanager.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * アプリケーション全体で一貫したJSONシリアライズ設定を定義します。
 */
private val commonJson = Json {
    prettyPrint = true          // 出力を読みやすく整形する
    ignoreUnknownKeys = true    // 定義されていないキーがJSONにあっても無視する
    encodeDefaults = true       // デフォルト値を持つプロパティもJSONに出力する
}

/**
 * 汎用的なJSONファイル操作ハンドラー。
 * 指定された型 [T] のデータをJSON形式でファイルに保存し、またはファイルから読み込みます。
 *
 * @param T 管理対象となるデータクラスの型
 * @property filePath 操作対象ファイルのパス定義 ([FilePath])
 * @property defaultFactory ファイルが存在しない、または読み込み失敗時にデフォルト値を生成する関数
 * @property serializer 型 [T] に対応する [KSerializer]
 */
open class JsonFileHandler<T>(
    private val filePath: FilePath,
    private val defaultFactory: () -> T,
    private val serializer: KSerializer<T>
) {
    /** 操作対象の [java.io.File] オブジェクトを取得します。 */
    private val file get() = filePath.toFile()

    /**
     * ファイルからデータを読み込み、オブジェクトにデコードします。
     * ファイルが存在しない場合や破損している場合は [defaultFactory] によって生成された値を返します。
     *
     * @return デコードされたオブジェクト、もしくはデフォルト値
     */
    fun load(): T {
        if (!file.exists()) return defaultFactory()
        return try {
            commonJson.decodeFromString(serializer, file.readText())
        } catch (_: Exception) {
            // メモ: 運用時はここに LoggerFactory による警告ログを出力することを推奨
            defaultFactory()
        }
    }

    /**
     * 指定されたデータをJSON文字列に変換し、ファイルへ保存します。
     * 保存先の親ディレクトリが存在しない場合は、自動的に作成を試みます。
     *
     * @param data 保存するオブジェクト
     */
    fun save(data: T) {
        try {
            // 親ディレクトリ（SushiEricServerManagerなど）がない場合に再帰的に作成
            file.parentFile?.mkdirs()
            file.writeText(commonJson.encodeToString(serializer, data))
        } catch (e: Exception) {
            // メモ: 致命的なI/Oエラーが発生した場合のスタックトレース出力
            e.printStackTrace()
        }
    }
}

/**
 * JAR内のリソースからJSONを読み込むためのハンドラー
 */
class JsonResourceHandler<T>(
    private val resourcePath: String, // "/item_list.json"
    private val serializer: KSerializer<T>
) {
    fun load(): T {
        val inputStream = javaClass.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Resource not found: $resourcePath")

        val content = inputStream.bufferedReader().use { it.readText() }
        return commonJson.decodeFromString(serializer, content)
    }
}